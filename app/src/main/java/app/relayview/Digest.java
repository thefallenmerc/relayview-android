package app.relayview;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Single-use authorization for a fresh HTTP or RTSP Digest challenge. */
final class Digest {
    private static final Pattern PARAM = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"\\\\])*)\"|([^,\\s]+))");

    static String answer(String challenge, String method, String uri, String user, String password, String cnonce) throws Exception {
        if (challenge == null || !challenge.regionMatches(true, 0, "Digest ", 0, 7))
            throw new IOException("This recorder must offer Digest authentication.");
        Map<String,String> fields = new HashMap<>();
        Matcher matcher = PARAM.matcher(challenge.substring(7));
        while (matcher.find()) {
            String value = matcher.group(2) == null ? matcher.group(3) : matcher.group(2).replaceAll("\\\\(.)", "$1");
            fields.put(matcher.group(1).toLowerCase(Locale.ROOT), value);
        }
        String realm = fields.get("realm"), nonce = fields.get("nonce");
        if (realm == null || nonce == null) throw new IOException("Incomplete authentication challenge.");
        String algorithm = fields.getOrDefault("algorithm", "MD5").toUpperCase(Locale.ROOT);
        String hash = algorithm.replaceFirst("-SESS$", "");
        if (!hash.equals("MD5") && !hash.equals("SHA-256")) throw new IOException("Unsupported Digest algorithm.");
        String ha1 = hex(hash, user + ":" + realm + ":" + password);
        if (algorithm.endsWith("-SESS")) ha1 = hex(hash, ha1 + ":" + nonce + ":" + cnonce);
        String ha2 = hex(hash, method + ":" + uri);
        boolean qop = fields.containsKey("qop");
        if (qop && !Arrays.asList(fields.get("qop").toLowerCase(Locale.ROOT).split("\\s*,\\s*")).contains("auth"))
            throw new IOException("Unsupported Digest protection mode.");
        String response = hex(hash, ha1 + ":" + nonce + ":" + (qop ? "00000001:" + cnonce + ":auth:" : "") + ha2);
        String header = "Digest username=" + quote(user) + ", realm=" + quote(realm) + ", nonce=" + quote(nonce)
                + ", uri=" + quote(uri) + ", response=" + quote(response) + ", algorithm=" + algorithm;
        if (qop) header += ", qop=auth, nc=00000001";
        if (qop || algorithm.endsWith("-SESS")) header += ", cnonce=" + quote(cnonce);
        if (fields.containsKey("opaque")) header += ", opaque=" + quote(fields.get("opaque"));
        return header;
    }

    private static String quote(String value) throws IOException {
        for (int i = 0; i < value.length(); i++)
            if (value.charAt(i) < 32 || value.charAt(i) == 127) throw new IOException("Invalid authentication field.");
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String hex(String algorithm, String text) throws Exception {
        byte[] digest = MessageDigest.getInstance(algorithm).digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        return out.toString();
    }
}
