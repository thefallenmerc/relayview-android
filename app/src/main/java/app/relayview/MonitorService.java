package app.relayview;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MonitorService extends Service {
    static final String START = "app.relayview.monitor.START";
    static final String CHANGED = "app.relayview.monitor.CHANGED";
    private static final String STOP = "app.relayview.monitor.STOP";
    private static final String SERVICE_CHANNEL = "monitoring";
    static final String ALERT_CHANNEL = "camera_detections";
    private static final int SERVICE_ID = 1, ERROR_ID = 2;
    private static final String ALERT_TAG = "camera";
    // The UI and service live in the same process. A sticky restart reloads the encrypted pairing.
    static volatile boolean running;
    static volatile String detail = "";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Runnable refreshStatus = this::updateStatus;
    private final Runnable renewWake = this::renewWakeLock;
    private PowerManager.WakeLock wake;
    private CameraChecks checks;
    private CameraChecks.Health[] health;
    private Recorder recorder;
    private boolean active;
    private boolean statusQueued;
    private int generation;

    static void channels(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(SERVICE_CHANNEL, "Background monitoring", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL, "Camera detections", NotificationManager.IMPORTANCE_HIGH));
    }

    static boolean alertsEnabled(Context context) {
        NotificationManager nm = context.getSystemService(NotificationManager.class);
        NotificationChannel channel = nm.getNotificationChannel(ALERT_CHANNEL);
        return nm.areNotificationsEnabled() && channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && STOP.equals(intent.getAction())) {
            end("");
            return START_NOT_STICKY;
        }
        if (active) return START_STICKY;
        channels(this);
        active = true;
        int ticket = ++generation;
        String starting = "Starting on-device detection…";
        Notification notification = ongoing(starting);
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(SERVICE_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(SERVICE_ID, notification);
        setState(true, starting);
        if (!alertsEnabled(this)) {
            end("Enable camera notifications to start monitoring.");
            return START_NOT_STICKY;
        }
        loader.execute(() -> {
            try {
                Recorder paired = new Vault(this).read();
                if (paired == null || paired.channels.isEmpty()) throw new IllegalStateException("Pair a recorder first");
                SnapshotDetector detector = new SnapshotDetector(this);
                main.post(() -> {
                    if (!active || generation != ticket) { detector.close(); return; }
                    begin(paired, detector, ticket);
                });
            } catch (Exception | LinkageError error) {
                main.post(() -> { if (active && generation == ticket) failure("Could not open the saved recorder or on-device model."); });
            }
        });
        return START_STICKY;
    }

    private void begin(Recorder paired, SnapshotDetector detector, int ticket) {
        recorder = paired;
        health = new CameraChecks.Health[paired.channels.size()];
        Arrays.fill(health, CameraChecks.Health.PENDING);
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RelayView:Detection");
        wake.setReferenceCounted(false);
        renewWakeLock();
        checks = new CameraChecks(health.length, 1000,
                channel -> detector.detect(Snapshots.get(paired, channel)),
                new CameraChecks.Listener() {
                    @Override public void report(int channel, CameraChecks.Health result, int entered) {
                        main.post(() -> {
                            if (!active || generation != ticket) return;
                            if (health[channel - 1] != result) {
                                health[channel - 1] = result;
                                if (!statusQueued) {
                                    statusQueued = true;
                                    main.postDelayed(refreshStatus, 350);
                                }
                            }
                            if (entered != 0) alert(channel, entered);
                        });
                    }
                    @Override public void detectorFailed() {
                        main.post(() -> { if (active && generation == ticket) failure("The on-device detector stopped."); });
                    }
                }, detector::close);
        checks.start();
        updateStatus();
    }

    private void renewWakeLock() {
        if (!active || wake == null) return;
        wake.acquire(10 * 60_000L);
        main.postDelayed(renewWake, 5 * 60_000L);
    }

    private void updateStatus() {
        statusQueued = false;
        if (!active || health == null) return;
        int ok = 0, denied = 0, unavailable = 0;
        for (CameraChecks.Health h : health) {
            if (h == CameraChecks.Health.OK) ok++;
            if (h == CameraChecks.Health.DENIED) denied++;
            if (h == CameraChecks.Health.UNREACHABLE) unavailable++;
        }
        String summary = "Checking " + ok + " of " + health.length + " cameras";
        if (unavailable > 0) summary += " · " + unavailable + " unavailable";
        if (denied > 0) summary += " · access denied on " + denied;
        setState(true, summary);
        getSystemService(NotificationManager.class).notify(SERVICE_ID, ongoing(summary));
    }

    private void alert(int channel, int entered) {
        if (!alertsEnabled(this)) { end("Camera notifications were disabled."); return; }
        String label = entered == Presence.PERSON ? "Person detected" : entered == Presence.VEHICLE ?
                "Vehicle detected" : "Person and vehicle detected";
        Notification notice = new Notification.Builder(this, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_detection).setContentTitle(recorder.channels.get(channel - 1))
                .setContentText(label).setCategory(Notification.CATEGORY_EVENT).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setAutoCancel(true).setContentIntent(openApp()).build();
        getSystemService(NotificationManager.class).notify(ALERT_TAG, channel, notice);
    }

    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification ongoing(String summary) {
        Intent stop = new Intent(this, MonitorService.class).setAction(STOP);
        PendingIntent action = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, SERVICE_CHANNEL).setSmallIcon(R.drawable.ic_detection)
                .setContentTitle("RelayView monitoring").setContentText(summary).setContentIntent(openApp())
                .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
                .addAction(new Notification.Action.Builder(null, "Stop", action).build()).build();
    }

    private void failure(String reason) {
        if (alertsEnabled(this)) {
            Notification notice = new Notification.Builder(this, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_detection)
                    .setContentTitle("Monitoring stopped").setContentText(reason).setAutoCancel(true).setContentIntent(openApp()).build();
            getSystemService(NotificationManager.class).notify(ERROR_ID, notice);
        }
        end(reason);
    }

    private void setState(boolean enabled, String text) {
        running = enabled;
        detail = text;
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }

    private void release() {
        active = false;
        generation++;
        main.removeCallbacks(refreshStatus);
        main.removeCallbacks(renewWake);
        statusQueued = false;
        if (checks != null) { checks.close(); checks = null; }
        if (wake != null && wake.isHeld()) wake.release();
        wake = null;
        health = null;
        recorder = null;
    }

    private void end(String reason) {
        release();
        setState(false, reason);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        if (active) { release(); setState(false, ""); }
        loader.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
