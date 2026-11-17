package app.relayview;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.SystemClock;

import java.io.StringReader;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

final class Discovery {
    static final class Device {
        final String host, model;
        final int port;
        Device(String host, String model, int port) { this.host = host; this.model = model; this.port = port; }
    }

    static Device parse(String xml, String sender) throws Exception {
        if (!Recorder.validHost(sender) || xml.contains("<!DOCTYPE")) return null;
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document doc = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        NodeList matches = doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/ws/2005/04/discovery", "ProbeMatch");
        if (matches.getLength() == 0) return null;
        Element match = (Element) matches.item(0);
        String scopes = field(match,"Scopes");
        String decoded = URLDecoder.decode(scopes, "UTF-8");
        String identity = decoded.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "");
        if (!identity.contains("cpplus") && !identity.contains("hardwarecp-")) return null;
        String model = "CP Plus recorder";
        for (String scope : scopes.trim().split("\\s+")) {
            int pos = scope.indexOf("/hardware/");
            if (pos >= 0) {
                String label = URLDecoder.decode(scope.substring(pos + 10), "UTF-8").trim();
                if (!label.isEmpty()) model = label.substring(0, Math.min(80, label.length()));
            }
        }
        int port = 80;
        for (String address : field(match,"XAddrs").trim().split("\\s+")) {
            try {
                URI uri = URI.create(address);
                if ("http".equalsIgnoreCase(uri.getScheme()) && sender.equals(uri.getHost()) && uri.getPort() > 0 && uri.getPort() <= 65535) {
                    port = uri.getPort(); break;
                }
            } catch (IllegalArgumentException ignored) { }
        }
        // Some DVRs advertise an empty host. The unicast reply identifies the device.
        return new Device(sender, model, port);
    }

    private static String field(Element element, String name) {
        NodeList nodes = element.getElementsByTagNameNS("*", name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    static List<Device> scan(Context context) throws Exception {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        Network lan = null;
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) { lan = network; break; }
        }
        if (lan == null) throw new java.io.IOException("Connect to the recorder’s Wi-Fi or Ethernet and scan again.");
        LinkProperties links = cm.getLinkProperties(lan);
        NetworkInterface iface = links == null ? null : NetworkInterface.getByName(links.getInterfaceName());
        if (iface == null) throw new java.io.IOException("Local network interface unavailable.");
        WifiManager wifi = context.getApplicationContext().getSystemService(WifiManager.class);
        WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock("RelayView-discovery");
        Map<String,Device> devices = new TreeMap<>();
        try (MulticastSocket socket = new MulticastSocket(null)) {
            if (lock != null) lock.acquire();
            lan.bindSocket(socket);
            socket.bind(new InetSocketAddress(0));
            socket.setNetworkInterface(iface);
            socket.setTimeToLive(1);
            socket.setSoTimeout(350);
            String probe = "<s:Envelope xmlns:s='http://www.w3.org/2003/05/soap-envelope' xmlns:a='http://schemas.xmlsoap.org/ws/2004/08/addressing' xmlns:d='http://schemas.xmlsoap.org/ws/2005/04/discovery' xmlns:n='http://www.onvif.org/ver10/network/wsdl'>"
                    + "<s:Header><a:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</a:Action><a:MessageID>urn:uuid:" + UUID.randomUUID()
                    + "</a:MessageID><a:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</a:To></s:Header><s:Body><d:Probe><d:Types>n:NetworkVideoTransmitter</d:Types></d:Probe></s:Body></s:Envelope>";
            byte[] bytes = probe.getBytes(StandardCharsets.UTF_8);
            DatagramPacket request = new DatagramPacket(bytes, bytes.length, InetAddress.getByName("239.255.255.250"), 3702);
            byte[] buffer = new byte[32768];
            long end = SystemClock.elapsedRealtime() + 3600;
            long nextSend = 0;
            while (SystemClock.elapsedRealtime() < end && !Thread.currentThread().isInterrupted()) {
                if (SystemClock.elapsedRealtime() >= nextSend) {
                    socket.send(request);
                    nextSend = SystemClock.elapsedRealtime() + 1300;
                }
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(response); } catch (SocketTimeoutException timeout) { continue; }
                try {
                    Device result = parse(new String(buffer, 0, response.getLength(), StandardCharsets.UTF_8), response.getAddress().getHostAddress());
                    if (result != null) devices.put(result.host, result);
                } catch (Exception malformed) { /* Ignore unrelated or malformed multicast traffic. */ }
            }
        } finally { if (lock != null && lock.isHeld()) lock.release(); }
        return new ArrayList<>(devices.values());
    }
}
