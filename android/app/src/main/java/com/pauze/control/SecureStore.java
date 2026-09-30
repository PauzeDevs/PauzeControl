// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class SecureStore {
    private static final String PREFS = "pauze_secure";
    private static final String TOKEN = "token";
    private static final String KEY_ALIAS = "PauzeControlKeyV2";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void putToken(String token) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new java.security.SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(TAG_LENGTH, iv)
        );

        byte[] ciphertext = cipher.doFinal(
                token.getBytes(StandardCharsets.UTF_8)
        );

        byte[] packed = new byte[IV_LENGTH + ciphertext.length];
        System.arraycopy(iv, 0, packed, 0, IV_LENGTH);
        System.arraycopy(
                ciphertext,
                0,
                packed,
                IV_LENGTH,
                ciphertext.length
        );

        prefs.edit()
                .putString(
                        TOKEN,
                        Base64.encodeToString(packed, Base64.NO_WRAP)
                )
                .apply();
    }

    public String getToken() throws Exception {
        String encoded = prefs.getString(TOKEN, null);
        if (encoded == null || encoded.isEmpty()) {
            return "";
        }

        byte[] packed = Base64.decode(encoded, Base64.DEFAULT);
        if (packed.length <= IV_LENGTH) {
            return "";
        }

        byte[] iv = Arrays.copyOfRange(packed, 0, IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(
                packed,
                IV_LENGTH,
                packed.length
        );

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(TAG_LENGTH, iv)
        );

        return new String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8
        );
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry) keyStore.getEntry(
                            KEY_ALIAS,
                            null
                    );

            return entry.getSecretKey();
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
        );

        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT |
                                KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(
                                KeyProperties.ENCRYPTION_PADDING_NONE
                        )
                        // The IV is generated randomly by this class and
                        // stored alongside the ciphertext. Android Keystore
                        // must allow that IV to be supplied again for GCM
                        // decryption.
                        .setRandomizedEncryptionRequired(false)
                        .setUserAuthenticationRequired(false)
                        .build()
        );

        return generator.generateKey();
    }
}
