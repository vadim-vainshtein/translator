package dev.translator.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import dev.translator.client.SavedSession;
import dev.translator.client.SessionStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class AndroidSessionStore implements SessionStore {
    private static final String KEY_ALIAS = "translator.session";
    private static final String SAVED_SESSION = "encryptedSession";
    private static final int IV_BYTES = 12;
    private static AndroidSessionStore instance;

    private final SharedPreferences preferences;

    private AndroidSessionStore(Context context) {
        preferences = context.getSharedPreferences("session", Context.MODE_PRIVATE);
    }

    static synchronized AndroidSessionStore get(Context context) {
        if (instance == null) {
            instance = new AndroidSessionStore(context.getApplicationContext());
        }
        return instance;
    }

    @Override
    public SavedSession load() throws IOException {
        String encoded = preferences.getString(SAVED_SESSION, null);
        if (encoded == null) {
            return null;
        }
        try {
            byte[] encrypted = Base64.decode(encoded, Base64.NO_WRAP);
            if (encrypted.length <= IV_BYTES || encrypted.length > 65_536) {
                throw new IOException("Invalid saved session");
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, encrypted, 0, IV_BYTES));
            return SavedSession.decode(cipher.doFinal(encrypted, IV_BYTES, encrypted.length - IV_BYTES));
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("Could not decrypt saved session", error);
        }
    }

    @Override
    public void save(SavedSession session) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] encrypted = cipher.doFinal(session.encode());
            byte[] saved = ByteBuffer.allocate(IV_BYTES + encrypted.length)
                    .put(cipher.getIV()).put(encrypted).array();
            String encoded = Base64.encodeToString(saved, Base64.NO_WRAP);
            if (!preferences.edit().putString(SAVED_SESSION, encoded).commit()) {
                throw new IOException("Could not save session");
            }
        } catch (GeneralSecurityException error) {
            throw new IOException("Could not encrypt saved session", error);
        }
    }

    @Override
    public void clear() throws IOException {
        if (!preferences.edit().remove(SAVED_SESSION).commit()) {
            throw new IOException("Could not clear saved session");
        }
    }

    private static synchronized SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return (SecretKey) store.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
