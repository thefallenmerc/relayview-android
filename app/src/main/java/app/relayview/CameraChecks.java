package app.relayview;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Each channel schedules its successor after completion, so late checks never pile up. */
final class CameraChecks implements AutoCloseable {
    enum Health { PENDING, OK, UNREACHABLE, DENIED }

    interface Check { int run(int channel) throws Exception; }
    interface Listener {
        void report(int channel, Health health, int entered);
        void detectorFailed();
    }

    private final ScheduledThreadPoolExecutor pool;
    private final Check check;
    private final Listener listener;
    private final int channels;
    private final long periodMs;

    CameraChecks(int channels, long periodMs, Check check, Listener listener, Runnable cleanup) {
        this.channels = channels;
        this.periodMs = periodMs;
        this.check = check;
        this.listener = listener;
        pool = new ScheduledThreadPoolExecutor(Math.min(8, channels)) {
            @Override protected void terminated() { cleanup.run(); }
        };
        pool.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    void start() {
        for (int i = 0; i < channels; i++) {
            schedule(new Channel(i + 1), i * periodMs / channels);
        }
    }

    private void schedule(Runnable task, long delay) {
        try { pool.schedule(task, delay, TimeUnit.MILLISECONDS); }
        catch (RejectedExecutionException stopped) { if (!pool.isShutdown()) throw stopped; }
    }

    private final class Channel implements Runnable {
        final int number;
        final Presence presence = new Presence();
        Health previous = Health.PENDING;

        Channel(int number) { this.number = number; }

        @Override public void run() {
            if (pool.isShutdown()) return;
            long start = System.nanoTime();
            Health health;
            int entered = 0;
            long next = periodMs;
            try {
                int seen = check.run(number);
                health = Health.OK;
                entered = presence.entered(seen);
            } catch (Snapshots.Denied error) {
                health = Health.DENIED;
                next = 60_000; // Avoid repeatedly submitting credentials rejected by the DVR.
            } catch (ai.onnxruntime.OrtException error) {
                if (!pool.isShutdown()) listener.detectorFailed();
                return;
            } catch (Exception error) {
                health = Health.UNREACHABLE;
            }
            if (pool.isShutdown()) return;
            if (health != previous || entered != 0) listener.report(number, health, entered);
            previous = health;
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            schedule(this, Math.max(0, next - elapsed));
        }
    }

    @Override public void close() { pool.shutdownNow(); }
}
