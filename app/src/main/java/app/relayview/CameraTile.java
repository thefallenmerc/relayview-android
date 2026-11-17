package app.relayview;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.*;
import android.net.Uri;
import org.videolan.libvlc.*;
import org.videolan.libvlc.util.VLCVideoLayout;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

final class CameraTile extends FrameLayout {
    private static LibVLC engine;
    private static final Executor CLEANUP = Executors.newFixedThreadPool(2);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Recorder device;
    private final int number;
    private final boolean main;
    private final VLCVideoLayout video;
    private final TextView state;
    private MediaPlayer player;
    private boolean active;
    private int failures;
    private final Runnable retry = this::start;
    private final Runnable timeout = this::failed;

    CameraTile(Context c, Recorder device, int channel, boolean main) {
        super(c);
        this.device=device; this.number=channel; this.main=main;
        setBackgroundColor(0xff0a100f);
        video = new VLCVideoLayout(c); addView(video,new LayoutParams(-1,-1));
        state=Ui.text(c,"Connecting…",13,Ui.MUTED,false);
        state.setGravity(Gravity.CENTER);
        addView(state,new LayoutParams(-1,-2,Gravity.CENTER));
        setContentDescription(device.channels.get(channel-1) + (main ? ", tap to return to cameras" : ", open full resolution"));
        setFocusable(true);
    }

    void active(boolean wanted) {
        if (active == wanted) return;
        active=wanted;
        if (active) { failures=0; start(); } else { stop(); }
    }

    private void start() {
        if (!active || player != null) return;
        state.setText("Connecting…"); state.setVisibility(VISIBLE);
        try {
            if (engine == null) engine = new LibVLC(getContext().getApplicationContext(),
                    new java.util.ArrayList<>(Arrays.asList("--quiet","--keystore=memory","--no-audio")));
            MediaPlayer current = new MediaPlayer(engine);
            player=current;
            current.attachViews(video,null,false,true);
            current.setEventListener(event -> {
                if (player != current || !active) return;
                if (event.type == MediaPlayer.Event.Vout && event.getVoutCount() > 0) {
                    ui.removeCallbacks(timeout);
                    failures=0;
                    state.setVisibility(GONE);
                } else if (event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached) {
                    failed();
                }
            });
            Media media = new Media(engine, Uri.parse(CpPlus.stream(device,number,main)));
            try {
                media.setHWDecoderEnabled(main,false);
                media.addOption(":rtsp-tcp");
                media.addOption(":network-caching=350");
                media.addOption(":no-audio");
                media.addOption(":rtsp-user=" + device.user);
                media.addOption(":rtsp-pwd=" + device.password);
                current.setMedia(media);
            } finally { media.release(); }
            ui.postDelayed(timeout,15000);
            current.play();
        } catch (RuntimeException error) { failed(); }
    }

    private void failed() {
        stop();
        if (!active) return;
        state.setText("Stream unavailable · retrying"); state.setVisibility(VISIBLE);
        ui.postDelayed(retry, Math.min(30000, 2000L << Math.min(4,failures++)));
    }

    private void stop() {
        ui.removeCallbacks(retry); ui.removeCallbacks(timeout);
        MediaPlayer old = player; player=null;
        if (old != null) {
            old.setEventListener(null);
            old.detachViews();
            CLEANUP.execute(() -> { try { old.stop(); } finally { old.release(); } });
        }
    }

    @Override protected void onDetachedFromWindow() { active(false); super.onDetachedFromWindow(); }
}
