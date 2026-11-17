package app.relayview;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends ComponentActivity {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
    private static final int NUM = InputType.TYPE_CLASS_NUMBER;
    private enum Screen { DISCOVER, PAIR, GRID }
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final List<CameraTile> tiles=new ArrayList<>();
    private final List<Discovery.Device> devices=new ArrayList<>();
    private Vault vault;
    private Recorder recorder;
    private Screen screen;
    private LinearLayout body;
    private ScrollView scroll;
    private boolean visible;
    private int generation;
    private Future<?> task;
    private Button monitorButton;
    private TextView monitorStatus;
    private ActivityResultLauncher<String> notificationPermission;
    private final BroadcastReceiver monitorReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { refreshMonitor(); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        MonitorService.channels(this);
        notificationPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (screen != Screen.GRID || recorder == null) return;
            if (granted && MonitorService.alertsEnabled(this)) startMonitor();
            else notificationSettings();
        });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { goBack(); }
        });
        vault=new Vault(this);
        try { recorder=vault.read(); }
        catch (Exception unavailable) { toast("Saved pairing could not be opened. Pair the recorder again."); }
        if (recorder == null) discover(true);
        else grid(state == null ? 0 : state.getInt("scroll",0));
    }

    private void layout(Screen next) {
        monitorButton = null;
        monitorStatus = null;
        generation++;
        if (task!=null) task.cancel(true);
        for (CameraTile t:tiles) t.active(false);
        tiles.clear();
        screen=next;
        if (next==Screen.PAIR) getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
        if (next==Screen.GRID) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Ui.BG);
        scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        body=Ui.column(this); Ui.pad(body,20,22);
        root.addView(scroll); scroll.addView(body); setContentView(root); Ui.insets(root);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(this); icon.setImageResource(R.drawable.ic_relay);
        bar.addView(icon,dp(30),dp(30));
        TextView brand=Ui.text(this,"RelayView",17,Ui.TEXT,true); Ui.pad(brand,9,0);
        bar.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        if (next==Screen.GRID) bar.addView(Ui.button(this,"Recorder",false,v -> recorderMenu()));
        else if (recorder!=null || next==Screen.PAIR) bar.addView(Ui.button(this,"Back",false,v -> goBack()));
        body.addView(bar); Ui.space(body,27);
    }

    private int dp(int n) { return Ui.dp(this,n); }
    private void heading(String title,String description) {
        body.addView(Ui.text(this,title,32,Ui.TEXT,true));
        Ui.space(body,7); body.addView(Ui.text(this,description,15,Ui.MUTED,false)); Ui.space(body,23);
    }

    private void discover(boolean scan) {
        layout(Screen.DISCOVER);
        heading("Find a recorder","Connect to the same local network as your CP Plus DVR.");
        LinearLayout results=Ui.column(this); body.addView(results);
        TextView status=Ui.text(this,"",14,Ui.MUTED,false); body.addView(status); Ui.space(body,12);
        Button search=Ui.button(this,"Scan network",true,v -> discover(true)); body.addView(search,-1,-2);
        Ui.space(body,8); body.addView(Ui.button(this,"Enter address manually",false,v -> pair(null)),-1,-2);
        if (!scan) { showDevices(results,status); return; }
        status.setText("Searching the local network…"); search.setEnabled(false);
        int ticket=generation;
        task=worker.submit(() -> {
            try {
                List<Discovery.Device> found=Discovery.scan(getApplicationContext());
                runOnUiThread(() -> {
                    if(ticket!=generation || isDestroyed()) return;
                    devices.clear(); devices.addAll(found); search.setEnabled(true);
                    showDevices(results,status);
                });
            } catch(Exception error) {
                runOnUiThread(() -> {
                    if(ticket!=generation || isDestroyed()) return;
                    devices.clear(); search.setEnabled(true);
                    status.setText("Network scan unavailable. Check Wi-Fi or enter the recorder address.");
                });
            }
        });
    }

    private void showDevices(LinearLayout list,TextView status) {
        list.removeAllViews();
        status.setText(devices.isEmpty() ? "No CP Plus recorders found." : "");
        for(Discovery.Device d:devices) {
            LinearLayout card=Ui.column(this); card.setBackground(Ui.shape(this,Ui.PANEL,Ui.BORDER,16));
            Ui.pad(card,16,15);
            card.addView(Ui.text(this,d.model,18,Ui.TEXT,true)); Ui.space(card,4);
            card.addView(Ui.text(this,d.host,14,Ui.MUTED,false));
            card.setOnClickListener(v -> pair(d)); card.setFocusable(true);
            card.setContentDescription("Pair " + d.model + " at " + d.host);
            list.addView(card,-1,-2); Ui.space(list,10);
        }
    }

    private void pair(Discovery.Device found) {
        layout(Screen.PAIR);
        heading("Pair recorder",found==null ? "Enter the recorder’s local IPv4 address and viewing credentials." : found.model);
        EditText host=Ui.field(body,"Recorder address",found==null ? "" : found.host,TEXT);
        EditText user=Ui.field(body,"Username","",TEXT);
        EditText pass=Ui.field(body,"Password","",InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout ports=Ui.column(this);
        EditText http=Ui.field(ports,"HTTP port",String.valueOf(found==null ? 80 : found.port),NUM);
        EditText rtsp=Ui.field(ports,"RTSP port","554",NUM);
        ports.setVisibility(View.GONE);
        Button advanced=Ui.button(this,"Connection settings",false,v -> ports.setVisibility(ports.getVisibility()==View.VISIBLE ? View.GONE : View.VISIBLE));
        body.addView(advanced); Ui.space(body,10); body.addView(ports);
        TextView status=Ui.text(this,"",14,Ui.MUTED,false); body.addView(status); Ui.space(body,8);
        Button connect=Ui.button(this,"Pair and view cameras",true,null); body.addView(connect,-1,-2);
        connect.setOnClickListener(v -> {
            String address=host.getText().toString().trim();
            if(!Recorder.validHost(address)) {host.setError("Enter an IPv4 address");return;}
            if(user.length()==0) {user.setError("Enter your DVR username");return;}
            if(pass.length()==0) {pass.setError("Enter your DVR password");return;}
            int hp=port(http), rp=port(rtsp);
            if(hp==0 || rp==0) {ports.setVisibility(View.VISIBLE);return;}
            Recorder candidate=new Recorder(address,found==null ? "CP Plus recorder" : found.model,hp,rp,
                    user.getText().toString(),pass.getText().toString(),Collections.emptyList());
            connect.setEnabled(false); status.setTextColor(Ui.MUTED); status.setText("Checking login and reading channels…");
            for(EditText field:Arrays.asList(host,user,pass,http,rtsp)) field.setEnabled(false);
            int ticket=generation;
            task=worker.submit(() -> {
                List<String> names=Collections.emptyList();
                String message=null;
                try { names=CpPlus.pair(candidate); }
                catch(SocketTimeoutException timeout) { message="Recorder timed out. Check Wi-Fi and the RTSP port."; }
                catch(IOException error) { message=error.getMessage()==null ? "Recorder unavailable." : error.getMessage(); }
                catch(Exception error) { message="Could not pair. Check the recorder address and credentials."; }
                List<String> channels=names; String failure=message;
                runOnUiThread(() -> {
                    if(ticket!=generation || isDestroyed()) return;
                    connect.setEnabled(true);
                    for(EditText field:Arrays.asList(host,user,pass,http,rtsp)) field.setEnabled(true);
                    if(failure!=null) {status.setTextColor(Ui.ERROR);status.setText(failure);return;}
                    status.setText("Login verified.");
                    if(channels.isEmpty()) askCount(candidate);
                    else save(candidate.withChannels(channels));
                });
            });
        });
    }

    private int port(EditText field) {
        try { int n=Integer.parseInt(field.getText().toString()); if(n>=1 && n<=65535) return n; }
        catch(NumberFormatException ignored) { }
        field.setError("Use a port from 1 to 65535"); return 0;
    }

    private void askCount(Recorder device) {
        LinearLayout content=Ui.column(this); Ui.pad(content,20,8);
        EditText count=Ui.field(content,"Number of channels","",NUM);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Channel count")
                .setMessage("The recorder accepted your login but did not share its channel list. Enter its channel count to build the grid.")
                .setView(content).setNegativeButton("Cancel",null).setPositiveButton("Open grid",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            int n=0;
            try { n=Integer.parseInt(count.getText().toString()); } catch(NumberFormatException ignored) { }
            if(n<1 || n>CpPlus.MAX_CHANNELS) { count.setError("Enter 1–"+CpPlus.MAX_CHANNELS);return; }
            dialog.dismiss(); save(device.withChannels(CpPlus.names(n)));
        }));
        dialog.show();
    }

    private void save(Recorder saved) {
        try { vault.save(saved); stopService(new Intent(this, MonitorService.class)); recorder=saved; grid(0); }
        catch(Exception failure) { toast("Android could not save this pairing securely. Try again."); }
    }

    private void grid(int scrollY) {
        layout(Screen.GRID);
        heading("Cameras",recorder.model+"   ·   "+recorder.channels.size()+" channels");
        monitorButton = Ui.button(this, "Start monitoring", false, v -> toggleMonitor());
        monitorStatus = Ui.text(this, "", 14, Ui.MUTED, false);
        body.addView(monitorButton, -1, -2);
        Ui.space(body, 6);
        body.addView(monitorStatus);
        Ui.space(body, 16);
        refreshMonitor();
        int columns=getResources().getConfiguration().screenWidthDp>=640 ? 3 : 2;
        int width=getResources().getDisplayMetrics().widthPixels-dp(40+10*(columns-1));
        int height=Math.max(dp(80),width/columns*9/16);
        LinearLayout row=null;
        for(int i=0; i<recorder.channels.size(); i++) {
            if(i%columns==0) { row=new LinearLayout(this); body.addView(row,-1,-2); }
            int number=i+1;
            LinearLayout card=Ui.column(this);
            card.setBackground(Ui.shape(this,Ui.PANEL,Ui.BORDER,13)); card.setClipToOutline(true);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);
            if(i%columns!=0) lp.leftMargin=dp(10);
            row.addView(card,lp);
            CameraTile tile=new CameraTile(this,recorder,number,false);
            card.addView(tile,-1,height); tiles.add(tile);
            TextView label=Ui.text(this,recorder.channels.get(i),14,Ui.TEXT,true);
            label.setSingleLine();label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Ui.pad(label,10,10); card.addView(label);
            View.OnClickListener open=v -> startActivity(new Intent(this,ViewerActivity.class).putExtra("channel",number));
            card.setOnClickListener(open); tile.setOnClickListener(open);
            if(i%columns==columns-1 || number==recorder.channels.size()) Ui.space(body,12);
        }
        int rest=recorder.channels.size()%columns;
        if(rest>0 && row!=null) for(int i=rest;i<columns;i++) {
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,1,1);
            lp.leftMargin=dp(10); row.addView(new Space(this),lp);
        }
        scroll.setOnScrollChangeListener((View v,int x,int y,int ox,int oy) -> syncTiles());
        ScrollView current=scroll;
        scroll.post(() -> { if(current==scroll) {scroll.scrollTo(0,scrollY);syncTiles();} });
    }

    private void syncTiles() {
        Rect area=new Rect();
        for (CameraTile t:tiles) t.active(visible && t.getLocalVisibleRect(area) && area.height()>0);
    }

    private void refreshMonitor() {
        if (monitorButton == null) return;
        monitorButton.setText(MonitorService.running ? "Stop monitoring" : "Start monitoring");
        String detail = MonitorService.detail;
        monitorStatus.setText(detail.isEmpty() ? "Get alerts for people and vehicles, even with the screen off." : detail);
    }

    private void toggleMonitor() {
        if (MonitorService.running) {
            stopService(new Intent(this, MonitorService.class));
        } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        } else if (!MonitorService.alertsEnabled(this)) {
            notificationSettings();
        } else {
            startMonitor();
        }
    }

    private void startMonitor() {
        startForegroundService(new Intent(this, MonitorService.class).setAction(MonitorService.START));
    }

    private void notificationSettings() {
        new AlertDialog.Builder(this).setTitle("Enable camera alerts")
                .setMessage("Allow RelayView notifications and enable Camera detections to start monitoring.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Settings", (d, w) -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))).show();
    }

    private void recorderMenu() {
        new AlertDialog.Builder(this).setTitle(recorder.model).setMessage(recorder.host)
                .setPositiveButton("Done",null).setNeutralButton("Change", (d,w) -> discover(true))
                .setNegativeButton("Forget", (d,w) -> new AlertDialog.Builder(this)
                        .setTitle("Forget this recorder?").setMessage("Remove its saved login from this phone.")
                        .setNegativeButton("Cancel",null).setPositiveButton("Forget",(dd,ww) -> {
                            try {vault.forget();stopService(new Intent(this, MonitorService.class));recorder=null;discover(true);}
                            catch(Exception error) { toast("Could not remove saved pairing."); }
                        }).show()).show();
    }

    private void toast(String text) {Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    private void goBack() {
        if(screen==Screen.PAIR) discover(false);
        else if(screen==Screen.DISCOVER && recorder!=null) grid(0);
        else finish();
    }
    @Override protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(this, monitorReceiver, new IntentFilter(MonitorService.CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED);
        refreshMonitor();
        visible=true;if(scroll!=null) scroll.post(this::syncTiles);
    }
    @Override protected void onStop() { unregisterReceiver(monitorReceiver); super.onStop(); }
    @Override protected void onPause() {visible=false;syncTiles();super.onPause();}
    @Override protected void onResume() {super.onResume();visible=true;if(scroll!=null) scroll.post(this::syncTiles);}
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state); if(screen==Screen.GRID) state.putInt("scroll",scroll.getScrollY());
    }
    @Override protected void onDestroy() {
        generation++;visible=false;syncTiles();worker.shutdownNow();super.onDestroy();
    }
}
