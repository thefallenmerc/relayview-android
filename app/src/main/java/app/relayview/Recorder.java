package app.relayview;

import java.util.List;

final class Recorder {
    final String host, model, user, password;
    final int httpPort, rtspPort;
    final List<String> channels;

    Recorder(String host, String model, int httpPort, int rtspPort, String user, String password, List<String> channels) {
        this.host = host;
        this.model = model;
        this.httpPort = httpPort;
        this.rtspPort = rtspPort;
        this.user = user;
        this.password = password;
        this.channels = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(channels));
    }

    Recorder withChannels(List<String> names) {
        return new Recorder(host, model, httpPort, rtspPort, user, password, names);
    }

    static boolean validHost(String host) {
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] octets = host.split("\\.");
        for (String part : octets) {
            if (Integer.parseInt(part) > 255 || (part.length() > 1 && part.startsWith("0"))) return false;
        }
        int first = Integer.parseInt(octets[0]);
        return first > 0 && first < 224;
    }
}
