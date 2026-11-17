package app.relayview;

import android.app.Activity;
import android.os.Bundle;
import android.view.*;
import android.widget.*;

public final class ViewerActivity extends Activity {
    private CameraTile tile;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        try {
            Recorder d = new Vault(this).read();
            int n = getIntent().getIntExtra("channel",0);
            if (d == null || n < 1 || n > d.channels.size()) { finish(); return; }
            FrameLayout root=new FrameLayout(this);
            tile=new CameraTile(this,d,n,true);
            root.addView(tile,new FrameLayout.LayoutParams(-1,-1));
            TextView title=Ui.text(this,d.channels.get(n-1)+"   ·   Main stream",14,Ui.TEXT,true);
            Ui.pad(title,14,8); title.setBackground(Ui.shape(this,0xcb17211e,0,14));
            FrameLayout.LayoutParams badge=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.START);
            badge.setMargins(Ui.dp(this,24),Ui.dp(this,16),0,0); root.addView(title,badge);
            tile.setOnClickListener(v -> finish()); title.setOnClickListener(v -> finish());
            setContentView(root);
        } catch (Exception failure) {
            Toast.makeText(this,"Pair the recorder again to open this channel.",Toast.LENGTH_LONG).show();
            finish();
        }
    }
    @Override protected void onStart() { super.onStart(); if (tile!=null) tile.active(true); }
    @Override protected void onStop() { if(tile!=null) tile.active(false); super.onStop(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if(focus) immersive(); }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
}
