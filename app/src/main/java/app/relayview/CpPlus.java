package app.relayview;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CpPlus {
    static final int MAX_CHANNELS = 128;
    private static final Pattern TITLE = Pattern.compile("(?m)^(?:table\\.)?ChannelTitle\\[(\\d{1,3})]\\.Name=(.*)$");
    private static final String CONFIG = "/cgi-bin/configManager.cgi?action=getConfig&name=ChannelTitle";

    static String stream(Recorder recorder, int channel, boolean main) {
        return "rtsp://" + recorder.host + ":" + recorder.rtspPort + "/cam/realmonitor?channel=" + channel + "&subtype=" + (main ? 0 : 1);
    }

    static List<String> names(int count) {
        List<String> names = new ArrayList<>();
        for (int i = 1; i <= count; i++) names.add("Camera " + i);
        return names;
    }

    static List<String> parseTitles(String body) {
        Matcher matcher = TITLE.matcher(body);
        Map<Integer,String> titles = new TreeMap<>();
        int count = 0;
        while (matcher.find()) {
            int index = Integer.parseInt(matcher.group(1));
            if (index >= MAX_CHANNELS) continue;
            String value = matcher.group(2).trim();
            if (!value.isEmpty()) titles.put(index, value.substring(0, Math.min(90, value.length())));
            count = Math.max(count, index + 1);
        }
        List<String> result = names(count);
        titles.forEach(result::set);
        return result;
    }

    static List<String> pair(Recorder device) throws Exception {
        // A protected channel directory also works when the first physical input is offline.
        List<String> names = channels(device);
        if (names.isEmpty()) verify(device);
        return names;
    }

    static void verify(Recorder device) throws Exception {
        String url = stream(device, 1, false);
        Reply reply = describe(device, url, null, 1);
        if (reply.status == 401) {
            String auth = Digest.answer(reply.challenge, "DESCRIBE", url, device.user, device.password, UUID.randomUUID().toString());
            reply = describe(device, url, auth, 2);
        }
        if (reply.status == 401 || reply.status == 403) throw new IOException("Login rejected. Check your credentials and channel 1 viewing permission.");
        if (reply.status != 200 || !reply.video) throw new IOException("Channel 1 substream unavailable. Check the RTSP port and enable its substream.");
    }

    static List<String> channels(Recorder device) {
        try {
            URL url = new URL("http", device.host, device.httpPort, CONFIG);
            String auth = null;
            for (int attempt = 0; attempt < 2; attempt++) {
                HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
                try {
                    connection.setConnectTimeout(2500);
                    connection.setReadTimeout(3000);
                    connection.setInstanceFollowRedirects(false);
                    connection.setUseCaches(false);
                    if (auth != null) connection.setRequestProperty("Authorization", auth);
                    int status = connection.getResponseCode();
                    if (status == 401 && attempt == 0) {
                        auth = Digest.answer(connection.getHeaderField("WWW-Authenticate"), "GET", CONFIG, device.user, device.password, UUID.randomUUID().toString());
                        continue;
                    }
                    if (status != 200) break;
                    try (InputStream input = connection.getInputStream()) {
                        ByteArrayOutputStream body = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = input.read(buf)) != -1) {
                            if (body.size() + n > 131072) throw new IOException("Channel list too large");
                            body.write(buf, 0, n);
                        }
                        List<String> names = parseTitles(body.toString("UTF-8"));
                        if (auth == null && !names.isEmpty()) verify(device);
                        return names;
                    }
                } finally { connection.disconnect(); }
            }
        } catch (Exception unavailable) { /* Viewer-only accounts can enter the DVR channel count. */ }
        return Collections.emptyList();
    }

    private static final class Reply {
        int status;
        String challenge;
        boolean video;
    }

    private static Reply describe(Recorder device, String url, String auth, int seq) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(device.host, device.rtspPort), 3500);
            socket.setSoTimeout(4000);
            String request = "DESCRIBE " + url + " RTSP/1.0\r\nCSeq: " + seq + "\r\nAccept: application/sdp\r\n"
                    + (auth == null ? "" : "Authorization: " + auth + "\r\n") + "\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            int tail = 0;
            while (tail != 0x0d0a0d0a) {
                int next = input.read();
                if (next < 0 || headers.size() >= 16384) throw new IOException("Invalid RTSP reply.");
                headers.write(next);
                tail = (tail << 8) | next;
            }
            String[] lines = headers.toString("ISO-8859-1").split("\r\n");
            if (!lines[0].matches("RTSP/1\\.0 [0-9]{3}.*")) throw new IOException("The selected port did not answer RTSP.");
            Reply reply = new Reply();
            reply.status = Integer.parseInt(lines[0].substring(9, 12));
            int length = 0;
            for (String line : lines) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon), value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("WWW-Authenticate") && value.regionMatches(true, 0, "Digest ", 0, 7)) reply.challenge = value;
                if (name.equalsIgnoreCase("Content-Length")) {
                    if (!value.matches("[0-9]{1,6}")) throw new IOException("Invalid RTSP content length.");
                    length = Integer.parseInt(value);
                }
            }
            if (reply.status == 200 && length > 0 && length <= 131072) {
                byte[] sdp = new byte[length];
                new DataInputStream(input).readFully(sdp);
                reply.video = Pattern.compile("(?m)^m=video ").matcher(new String(sdp, StandardCharsets.UTF_8)).find();
            }
            return reply;
        }
    }
}
