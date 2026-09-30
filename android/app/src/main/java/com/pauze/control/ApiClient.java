// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class ApiClient {

    public static final class Result {
        public final int code;
        public final String body;

        public Result(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private ApiClient() {}

    public static Result get(
            String host,
            String token,
            String path
    ) throws Exception {
        return request(
                host,
                token,
                "GET",
                path,
                ""
        );
    }

    public static Result post(
            String host,
            String token,
            String path
    ) throws Exception {
        return request(
                host,
                token,
                "POST",
                path,
                "{}"
        );
    }

    public static Result post(
            String host,
            String token,
            String path,
            String body
    ) throws Exception {
        return request(
                host,
                token,
                "POST",
                path,
                body
        );
    }

    public static HttpURLConnection openScreen(
            String host,
            String token
    ) throws Exception {
        String normalized = host.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Mac address is empty."
            );
        }

        if (!normalized.startsWith("http://") &&
                !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            );
        }

        URI uri = URI.create(
                normalized + "/v1/screen"
        );

        HttpURLConnection connection =
                (HttpURLConnection) uri.toURL().openConnection();

        long timestamp =
                System.currentTimeMillis() / 1000L;

        String nonce = randomHex(16);

        String signature = sign(
                token,
                timestamp,
                nonce,
                "GET",
                "/v1/screen",
                ""
        );

        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(0);
        connection.setUseCaches(false);
        connection.setRequestProperty(
                "Accept",
                "video/H264"
        );
        connection.setRequestProperty(
                "X-Pauze-Timestamp",
                Long.toString(timestamp)
        );
        connection.setRequestProperty(
                "X-Pauze-Nonce",
                nonce
        );
        connection.setRequestProperty(
                "X-Pauze-Signature",
                signature
        );

        return connection;
    }

    private static Result request(
            String host,
            String token,
            String method,
            String path,
            String body
    ) throws Exception {

        String normalized = host.trim();

        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    "Mac address is empty."
            );
        }

        if (!normalized.startsWith("http://") &&
                !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }

        while (normalized.endsWith("/")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            );
        }

        URI uri = URI.create(
                normalized + path
        );

        HttpURLConnection connection =
                (HttpURLConnection)
                        uri.toURL().openConnection();

        long timestamp =
                System.currentTimeMillis() / 1000L;

        String nonce = randomHex(16);

        String signature = sign(
                token,
                timestamp,
                nonce,
                method,
                path,
                body
        );

        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setUseCaches(false);

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        connection.setRequestProperty(
                "X-Pauze-Timestamp",
                Long.toString(timestamp)
        );

        connection.setRequestProperty(
                "X-Pauze-Nonce",
                nonce
        );

        connection.setRequestProperty(
                "X-Pauze-Signature",
                signature
        );

        if ("POST".equals(method)) {
            byte[] payload = body.getBytes(
                    StandardCharsets.UTF_8
            );

            connection.setDoOutput(true);

            connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
            );

            connection.setRequestProperty(
                    "Content-Length",
                    Integer.toString(payload.length)
            );

            try (OutputStream output =
                         connection.getOutputStream()) {
                output.write(payload);
            }
        }

        int code = connection.getResponseCode();

        java.io.InputStream stream =
                code >= 400
                        ? connection.getErrorStream()
                        : connection.getInputStream();

        StringBuilder result =
                new StringBuilder();

        if (stream != null) {
            try (BufferedReader reader =
                         new BufferedReader(
                                 new InputStreamReader(
                                         stream,
                                         StandardCharsets.UTF_8
                                 )
                         )) {

                String line;

                while ((line = reader.readLine())
                        != null) {
                    result.append(line);
                }
            }
        }

        connection.disconnect();

        return new Result(
                code,
                result.toString()
        );
    }

    private static String sign(
            String token,
            long timestamp,
            String nonce,
            String method,
            String path,
            String body
    ) throws Exception {

        String canonical =
                timestamp + "\n" +
                nonce + "\n" +
                method.toUpperCase(Locale.US) + "\n" +
                path + "\n" +
                body;

        Mac mac = Mac.getInstance(
                "HmacSHA256"
        );

        mac.init(
                new SecretKeySpec(
                        token.getBytes(
                                StandardCharsets.UTF_8
                        ),
                        "HmacSHA256"
                )
        );

        byte[] digest = mac.doFinal(
                canonical.getBytes(
                        StandardCharsets.UTF_8
                )
        );

        StringBuilder result =
                new StringBuilder(digest.length * 2);

        for (byte item : digest) {
            result.append(
                    String.format(
                            Locale.US,
                            "%02x",
                            item & 0xff
                    )
            );
        }

        return result.toString();
    }

    private static String randomHex(
            int byteCount
    ) {
        byte[] value =
                new byte[byteCount];

        new SecureRandom().nextBytes(value);

        StringBuilder result =
                new StringBuilder(
                        byteCount * 2
                );

        for (byte item : value) {
            result.append(
                    String.format(
                            Locale.US,
                            "%02x",
                            item & 0xff
                    )
            );
        }

        return result.toString();
    }

    public static boolean restricted(
            String body
    ) {
        try {
            return new JSONObject(body)
                    .optBoolean(
                            "restricted",
                            false
                    );
        } catch (Exception ignored) {
            return false;
        }
    }
}
