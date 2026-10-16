// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String PREFS = "pauze_secure";
    private static final String TOKEN = "token";
    private static final String KEY_ALIAS = "PauzeControlKeyV3";
    private static final String LEGACY_KEY_ALIAS = "PauzeControlKeyV2";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void putToken(String token) throws Exception {
        prefs.edit().putString(TOKEN, encryptValue(token)).apply();
    }

    public String getToken() throws Exception {
        String encoded = prefs.getString(TOKEN, null);
        if (encoded == null || encoded.isEmpty()) return "";
        return decryptOrMigrate(encoded, TOKEN);
    }

    public void clearToken() {
        prefs.edit().remove(TOKEN).apply();
    }

    /** Stores a per-device secret using the Android Keystore-backed AES-GCM key. */
    public void putSecret(String key, String value) throws Exception {
        validateSecretKey(key);
        if (value == null) throw new IllegalArgumentException("Secret cannot be null.");
        prefs.edit().putString("secret_" + key, encryptValue(value)).apply();
    }

    public String getSecret(String key) throws Exception {
        validateSecretKey(key);
        String preferenceKey = "secret_" + key;
        String encoded = prefs.getString(preferenceKey, null);
        if (encoded == null || encoded.isEmpty()) return "";
        return decryptOrMigrate(encoded, preferenceKey);
    }

    public void removeSecret(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) return;
        prefs.edit().remove("secret_" + key).apply();
    }

    private void validateSecretKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid secure-storage key.");
        }
    }

    /**
     * Decrypt current data first. If the installed app stored this value under
     * the V2 alias, decrypt with that existing key and immediately re-encrypt
     * under V3. Do not delete the legacy key: profiles migrate on first use.
     */
    private String decryptOrMigrate(String encoded, String preferenceKey) throws Exception {
        Exception currentFailure;
        try {
            return decryptValue(encoded, KEY_ALIAS, true);
        } catch (Exception error) {
            currentFailure = error;
        }

        try {
            String legacyValue = decryptValue(encoded, LEGACY_KEY_ALIAS, false);
            String migrated = encryptValue(legacyValue);
            if (!prefs.edit().putString(preferenceKey, migrated).commit()) {
                throw new IllegalStateException("Could not persist migrated secure data.");
            }
            return legacyValue;
        } catch (Exception legacyFailure) {
            currentFailure.addSuppressed(legacyFailure);
            throw currentFailure;
        }
    }

    private String encryptValue(String value) throws Exception {
        if (value == null) throw new IllegalArgumentException("Secret cannot be null.");
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(TAG_LENGTH, iv)
        );
        byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, packed, 0, IV_LENGTH);
        System.arraycopy(ciphertext, 0, packed, IV_LENGTH, ciphertext.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private String decryptValue(String encoded, String alias, boolean createCurrentIfMissing) throws Exception {
        byte[] packed = Base64.decode(encoded, Base64.DEFAULT);
        if (packed.length <= IV_LENGTH) {
            throw new IllegalArgumentException("Stored encrypted data is incomplete.");
        }

        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey key;
        if (keyStore.containsAlias(alias)) {
            KeyStore.Entry entry = keyStore.getEntry(alias, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry)) {
                throw new IllegalStateException("Stored encryption key is unavailable.");
            }
            key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        } else if (createCurrentIfMissing && KEY_ALIAS.equals(alias)) {
            key = getOrCreateKey();
        } else {
            throw new IllegalStateException("Legacy encryption key is unavailable.");
        }

        byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(packed, IV_LENGTH, packed.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
            if (entry instanceof KeyStore.SecretKeyEntry) {
                return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            }
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );
        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        // This class generates and stores its own random IV.
                        .setRandomizedEncryptionRequired(false)
                        .setUserAuthenticationRequired(false)
                        .build()
        );
        return generator.generateKey();
    }
}
