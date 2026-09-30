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
    private static final String KEY_ALIAS = "PauzeControlKey";

    private final SharedPreferences prefs;

    public SecureStore(Context context) {
        prefs = context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
        );
    }

    public void putToken(String token) throws Exception {
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
        );

        cipher.init(
                Cipher.ENCRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(128, iv)
        );

        byte[] ciphertext = cipher.doFinal(
                token.getBytes(StandardCharsets.UTF_8)
        );

        byte[] packed = new byte[
                iv.length + ciphertext.length
        ];

        System.arraycopy(
                iv,
                0,
                packed,
                0,
                iv.length
        );

        System.arraycopy(
                ciphertext,
                0,
                packed,
                iv.length,
                ciphertext.length
        );

        prefs.edit()
                .putString(
                        TOKEN,
                        Base64.encodeToString(
                                packed,
                                Base64.NO_WRAP
                        )
                )
                .apply();
    }

    public String getToken() throws Exception {
        String encoded = prefs.getString(
                TOKEN,
                null
        );

        if (encoded == null || encoded.isEmpty()) {
            return "";
        }

        byte[] packed = Base64.decode(
                encoded,
                Base64.DEFAULT
        );

        if (packed.length < 13) {
            return "";
        }

        byte[] iv = Arrays.copyOfRange(
                packed,
                0,
                12
        );

        byte[] ciphertext = Arrays.copyOfRange(
                packed,
                12,
                packed.length
        );

        Cipher cipher = Cipher.getInstance(
                "AES/GCM/NoPadding"
        );

        cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                new GCMParameterSpec(128, iv)
        );

        return new String(
                cipher.doFinal(ciphertext),
                StandardCharsets.UTF_8
        );
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(
                "AndroidKeyStore"
        );

        keyStore.load(null);

        if (keyStore.containsAlias(KEY_ALIAS)) {
            KeyStore.SecretKeyEntry entry =
                    (KeyStore.SecretKeyEntry)
                            keyStore.getEntry(
                                    KEY_ALIAS,
                                    null
                            );

            return entry.getSecretKey();
        }

        KeyGenerator generator =
                KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        "AndroidKeyStore"
                );

        generator.init(
                new KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT |
                                KeyProperties.PURPOSE_DECRYPT
                )
                        .setBlockModes(
                                KeyProperties.BLOCK_MODE_GCM
                        )
                        .setEncryptionPaddings(
                                KeyProperties
                                        .ENCRYPTION_PADDING_NONE
                        )
                        .setUserAuthenticationRequired(false)
                        .build()
        );

        return generator.generateKey();
    }
}
