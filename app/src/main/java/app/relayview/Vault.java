package app.relayview;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Vault {
    private static final String ALIAS = "relayview.recorder.v1";
    private final SharedPreferences storage;

    Vault(Context context) { storage = context.getSharedPreferences("recorder", Context.MODE_PRIVATE); }

    private KeyStore keys() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        return store;
    }

    private SecretKey key(boolean create) throws Exception {
        SecretKey secret = (SecretKey) keys().getKey(ALIAS, null);
        if (secret != null) return secret;
        if (!create) throw new java.security.KeyStoreException("Pairing key unavailable");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    void save(Recorder device) throws Exception {
        JSONObject data = new JSONObject().put("host", device.host).put("model", device.model)
                .put("httpPort", device.httpPort).put("rtspPort", device.rtspPort)
                .put("user", device.user).put("password", device.password).put("channels", new JSONArray(device.channels));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        byte[] payload = Arrays.copyOf(iv, iv.length + sealed.length);
        System.arraycopy(sealed, 0, payload, iv.length, sealed.length);
        if (!storage.edit().putString("sealed", Base64.encodeToString(payload, Base64.NO_WRAP)).commit())
            throw new java.io.IOException("Could not save pairing");
    }

    Recorder read() throws Exception {
        String sealed = storage.getString("sealed", null);
        if (sealed == null) return null;
        byte[] payload = Base64.decode(sealed, Base64.DEFAULT);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, payload, 0, 12));
        JSONObject data = new JSONObject(new String(cipher.doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8));
        ArrayList<String> names = new ArrayList<>();
        JSONArray channels = data.getJSONArray("channels");
        for (int i = 0; i < channels.length(); i++) names.add(channels.getString(i));
        return new Recorder(data.getString("host"), data.getString("model"), data.getInt("httpPort"),
                data.getInt("rtspPort"), data.getString("user"), data.getString("password"), names);
    }

    void forget() throws Exception {
        if (!storage.edit().clear().commit()) throw new java.io.IOException("Could not remove pairing");
        keys().deleteEntry(ALIAS);
    }
}
