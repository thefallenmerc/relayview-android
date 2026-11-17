package app.relayview;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class RecorderTest {
    private static Recorder recorder(int http, int rtsp) {
        return new Recorder("127.0.0.1", "Test DVR", http, rtsp, "viewer", "synthetic@pw:%", Collections.emptyList());
    }

    @Test public void digestMatchesPublishedMd5Vector() throws Exception {
        String challenge = "Digest realm=\"testrealm@host.com\", qop=\"auth,auth-int\", nonce=\"dcd98b7102dd2f0e8b11d0f600bfb0c093\"";
        String answer = Digest.answer(challenge,"GET","/dir/index.html","Mufasa","Circle Of Life","0a4f113b");
        assertTrue(answer.contains("response=\"6629fae49393a05397450978507c4ef1\""));
    }

    @Test public void urlsCarryChannelAndQualityOnly() {
        Recorder d = recorder(80,8554);
        assertEquals("rtsp://127.0.0.1:8554/cam/realmonitor?channel=3&subtype=1",CpPlus.stream(d,3,false));
        assertTrue(CpPlus.stream(d,3,true).endsWith("&subtype=0"));
        assertFalse(CpPlus.stream(d,3,false).contains(d.user));
        assertFalse(Recorder.validHost("127.0.0.1@example.invalid"));
    }

    @Test public void preservesMissingOrUnnamedChannelPositions() {
        assertEquals(Arrays.asList("Front","Camera 2","Camera 3","Gate"), CpPlus.parseTitles(
                "table.ChannelTitle[3].Name=Gate\r\ntable.ChannelTitle[0].Name=Front\r\ntable.ChannelTitle[2].Name=\r\n"));
        assertTrue(CpPlus.parseTitles("Error\nInvalid Authority!").isEmpty());
    }

    @Test public void matchesCpPlusWithBrokenAdvertisedAddress() throws Exception {
        String xml = "<d:ProbeMatches xmlns:d='http://schemas.xmlsoap.org/ws/2005/04/discovery'><d:ProbeMatch>"
                +"<d:Scopes>onvif://www.onvif.org/name/CPPLUS onvif://www.onvif.org/hardware/CP-TEST</d:Scopes>"
                +"<d:XAddrs>http:///onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches>";
        Discovery.Device match=Discovery.parse(xml,"192.0.2.7");
        assertEquals("192.0.2.7",match.host);
        assertEquals("CP-TEST",match.model);
        assertEquals(80,match.port);
        assertNull(Discovery.parse(xml.replace("CPPLUS","Generic").replace("CP-TEST","Test"),"192.0.2.7"));
        assertNull(Discovery.parse("<!DOCTYPE x>"+xml,"192.0.2.7"));
    }

    @Test public void pairsOverDigestWithOnlyRtspViewingPermission() throws Exception {
        try (ServerSocket rtsp=new ServerSocket(0,4,InetAddress.getLoopbackAddress());
             ServerSocket http=new ServerSocket(0,4,InetAddress.getLoopbackAddress())) {
            ExecutorService task=Executors.newFixedThreadPool(2);
            try {
                Future<?> denied=task.submit(() -> { exchange(http, "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n"); return null; });
                Future<?> handshake=task.submit(() -> {
                    exchange(rtsp,"RTSP/1.0 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"test\", nonce=\"abc\"\r\n\r\n");
                    String sdp="v=0\r\nm=video 0 RTP/AVP 96\r\n";
                    String headers=exchange(rtsp,"RTSP/1.0 200 OK\r\nContent-Length: "+sdp.length()+"\r\n\r\n"+sdp);
                    String url=CpPlus.stream(recorder(80,rtsp.getLocalPort()),1,false);
                    assertTrue(headers.contains(Digest.answer("Digest realm=\"test\", nonce=\"abc\"","DESCRIBE",url,"viewer","synthetic@pw:%","unused")));
                    assertFalse(headers.contains("synthetic@pw"));
                    return null;
                });
                assertTrue(CpPlus.pair(recorder(http.getLocalPort(),rtsp.getLocalPort())).isEmpty());
                denied.get(5,TimeUnit.SECONDS); handshake.get(5,TimeUnit.SECONDS);
            } finally {task.shutdownNow();}
        }
    }

    @Test public void rejectedLoginIsNotTreatedAsSuccessfulPairing() throws Exception {
        try (ServerSocket rtsp=new ServerSocket(0,4,InetAddress.getLoopbackAddress())) {
            ExecutorService task=Executors.newSingleThreadExecutor();
            try {
                Future<?> reject=task.submit(() -> {
                    String response="RTSP/1.0 401 Unauthorized\r\nWWW-Authenticate: Digest realm=\"test\", nonce=\"abc\"\r\n\r\n";
                    exchange(rtsp,response); exchange(rtsp,response); return null;
                });
                assertThrows(IOException.class,() -> CpPlus.verify(recorder(80,rtsp.getLocalPort())));
                reject.get(5,TimeUnit.SECONDS);
            } finally {task.shutdownNow();}
        }
    }

    private static String exchange(ServerSocket listener, String response) throws IOException {
        listener.setSoTimeout(5000);
        try(Socket client=listener.accept()) {
            client.setSoTimeout(3000);
            BufferedReader lines=new BufferedReader(new InputStreamReader(client.getInputStream(),StandardCharsets.UTF_8));
            StringBuilder request=new StringBuilder(); String line;
            while((line=lines.readLine())!=null && !line.isEmpty()) request.append(line).append('\n');
            client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
            return request.toString();
        }
    }
}
