package app.relayview;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class MonitoringTest {
    @Test public void onlyWinningVehicleAndPersonClassesAboveThresholdTrigger() {
        FloatBuffer output = FloatBuffer.allocate(85 * 3);
        output.put(4, .9f).put(5, .8f);
        output.put(85 + 4, .8f).put(85 + 5 + 5, .6f); // bus
        output.put(170 + 4, .3f).put(170 + 5 + 2, .99f);
        assertEquals(Presence.PERSON | Presence.VEHICLE, Presence.classify(output));
        output.put(85 + 5 + 15, .95f); // a higher-scoring non-vehicle class wins this row
        assertEquals(Presence.PERSON, Presence.classify(output));
        output.put(5, .42f);
        assertEquals(0, Presence.classify(output));
    }

    @Test public void categoriesRearmIndependentlyAfterThreeClearFrames() {
        Presence gate = new Presence();
        assertEquals(1, gate.entered(1));
        assertEquals(2, gate.entered(3));
        assertEquals(0, gate.entered(3));
        assertEquals(0, gate.entered(2));
        assertEquals(0, gate.entered(2));
        assertEquals(0, gate.entered(3)); // two misses did not re-arm
        for (int i = 0; i < 3; i++) assertEquals(0, gate.entered(2));
        assertEquals(1, gate.entered(3)); // vehicle remained present throughout
    }

    @Test public void slowChannelDoesNotBlockOthersOrBuildBacklog() throws Exception {
        CountDownLatch busy = new CountDownLatch(1), releaseSlow = new CountDownLatch(1);
        CountDownLatch healthy = new CountDownLatch(3), cleaned = new CountDownLatch(1);
        AtomicInteger slowAttempts = new AtomicInteger(), goodAttempts = new AtomicInteger(), alerts = new AtomicInteger();
        CameraChecks checks = new CameraChecks(2, 30, channel -> {
            if (channel == 1) { slowAttempts.incrementAndGet(); busy.countDown(); releaseSlow.await(); }
            else { goodAttempts.incrementAndGet(); healthy.countDown(); }
            return Presence.PERSON;
        }, new CameraChecks.Listener() {
            public void report(int channel, CameraChecks.Health health, int entered) {
                if (channel == 2 && entered != 0) alerts.incrementAndGet();
            }
            public void detectorFailed() { fail(); }
        }, cleaned::countDown);
        try {
            checks.start();
            assertTrue(busy.await(2, TimeUnit.SECONDS));
            assertTrue(healthy.await(2, TimeUnit.SECONDS));
            assertEquals(1, slowAttempts.get());
            assertEquals(1, alerts.get());
        } finally { checks.close(); releaseSlow.countDown(); }
        assertTrue(cleaned.await(2, TimeUnit.SECONDS));
        int afterStop = goodAttempts.get();
        Thread.sleep(65);
        assertEquals(afterStop, goodAttempts.get());
    }

    @Test public void transientFailureIsReportedAndDoesNotRearmAnAlert() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch recovered = new CountDownLatch(3);
        List<String> changes = Collections.synchronizedList(new ArrayList<>());
        CameraChecks checks = new CameraChecks(1, 15, channel -> {
            int n = attempts.incrementAndGet();
            if (n >= 2 && n <= 4) throw new IOException("offline");
            return Presence.VEHICLE;
        }, new CameraChecks.Listener() {
            public void report(int channel, CameraChecks.Health h, int entered) {
                changes.add(h + "/" + entered); recovered.countDown();
            }
            public void detectorFailed() { fail(); }
        }, () -> {});
        try {
            checks.start();
            assertTrue(recovered.await(2, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("OK/2","UNREACHABLE/0","OK/0"), changes);
        } finally { checks.close(); }
    }

    @Test public void digestSnapshotRequestUsesTheRightChannelAndPreservesJpegBytes() throws Exception {
        byte[] image = {(byte)255, (byte)216, 0, (byte)255, (byte)217};
        try (ServerSocket server = new ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))) {
            ExecutorService io = Executors.newSingleThreadExecutor();
            server.setSoTimeout(4000);
            Recorder d = new Recorder("127.0.0.1", "Synthetic DVR", server.getLocalPort(), 554, "viewer", "example@pass", CpPlus.names(3));
            String path = "/cgi-bin/snapshot.cgi?channel=3";
            String challenge = "Digest realm=\"dvr\", nonce=\"xyz\"";
            String auth = Digest.answer(challenge,"GET",path,d.user,d.password,"unused");
            Future<?> fixture = io.submit(() -> {
                for (int i = 0; i < 2; i++) try (Socket s = server.accept()) {
                    s.setSoTimeout(3000);
                    BufferedReader lines = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    assertEquals("GET " + path + " HTTP/1.1", lines.readLine());
                    String line, authorization = null;
                    while ((line=lines.readLine()) != null && !line.isEmpty()) {
                        if (line.startsWith("Authorization: ")) authorization = line.substring(15);
                        assertFalse(line.contains(d.password));
                    }
                    if (i == 0) {
                        assertNull(authorization);
                        s.getOutputStream().write(("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: " + challenge +
                                "\r\nConnection: close\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    } else {
                        assertEquals(auth, authorization);
                        s.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + image.length +
                                "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        s.getOutputStream().write(image);
                    }
                }
                return null;
            });
            try { assertArrayEquals(image, Snapshots.get(d, 3)); fixture.get(4,TimeUnit.SECONDS); }
            finally { io.shutdownNow(); }
        }
    }

    @Test public void rejectsHtmlAndOversizedSnapshots() {
        assertThrows(IOException.class, () -> Snapshots.readJpeg(new ByteArrayInputStream("<html>".getBytes())));
        byte[] tooLarge = new byte[4*1024*1024+1];
        tooLarge[0] = (byte)255; tooLarge[1] = (byte)216;
        assertThrows(IOException.class, () -> Snapshots.readJpeg(new ByteArrayInputStream(tooLarge)));
    }
}
