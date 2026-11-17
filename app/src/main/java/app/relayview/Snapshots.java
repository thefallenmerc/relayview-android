package app.relayview;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.UUID;

/** Fetches one in-memory JPEG directly from the paired recorder. */
final class Snapshots {
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    static final class Denied extends IOException { }

    static byte[] get(Recorder recorder, int channel) throws Exception {
        String path = "/cgi-bin/snapshot.cgi?channel=" + channel;
        URL url = new URL("http", recorder.host, recorder.httpPort, path);
        String auth = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            try {
                conn.setConnectTimeout(1500);
                conn.setReadTimeout(1800);
                conn.setInstanceFollowRedirects(false);
                conn.setUseCaches(false);
                conn.setRequestProperty("Cache-Control", "no-cache");
                if (auth != null) conn.setRequestProperty("Authorization", auth);
                int code = conn.getResponseCode();
                if (code == 401 && attempt == 0) {
                    auth = Digest.answer(conn.getHeaderField("WWW-Authenticate"), "GET", path,
                            recorder.user, recorder.password, UUID.randomUUID().toString());
                    continue;
                }
                if (code == 401 || code == 403) throw new Denied();
                if (code != 200 || conn.getContentLengthLong() > MAX_BYTES) throw new IOException("Snapshot unavailable");
                try (InputStream in = conn.getInputStream()) {
                    return readJpeg(in);
                }
            } finally {
                conn.disconnect();
            }
        }
        throw new Denied();
    }

    static byte[] readJpeg(InputStream in) throws IOException {
        if (in.read() != 0xff || in.read() != 0xd8) throw new IOException("Expected a JPEG snapshot");
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        out.write(0xff);
        out.write(0xd8);
        byte[] buffer = new byte[16384];
        int count;
        long deadline = System.nanoTime() + 3_000_000_000L;
        while ((count = in.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) throw new InterruptedIOException();
            if (out.size() + count > MAX_BYTES) throw new IOException("Snapshot too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
