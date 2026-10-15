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
    private static final int PAUZE_PORT = 47777;
    private static final SecureRandom NONCE_RANDOM = new SecureRandom();
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    public static final class Result {
        public final int code;
        public final String body;
        public Result(int code, String body) { this.code = code; this.body = body; }
    }

    private ApiClient() {}

    public static Result get(String host, String token, String path) throws Exception {
        return request(host, token, "GET", path, "");
    }

    public static Result post(String host, String token, String path) throws Exception {
        return request(host, token, "POST", path, "{}");
    }

    public static Result post(String host, String token, String path, String body) throws Exception {
        return request(host, token, "POST", path, body);
    }

    public static HttpURLConnection openScreen(String host, String token) throws Exception {
        return openScreen(host, token, false);
    }

    public static HttpURLConnection openScreen(
            String host,
            String token,
            boolean audioEnabled
    ) throws Exception {
        String path = audioEnabled ? "/v1/screen?audio=1" : "/v1/screen";
        URI uri = buildUri(host, path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        configure(connection, token, "GET", path, "");
        connection.setReadTimeout(0);
        connection.setRequestProperty("Accept", audioEnabled
                ? "video/H264, application/x-pauze-pcm"
                : "video/H264");
        return connection;
    }

    private static Result request(String host, String token, String method, String path, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) buildUri(host, path).toURL().openConnection();
        try {
            configure(connection, token, method, path, body);
            connection.setReadTimeout(15000);

            if ("POST".equals(method)) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }

            int code = connection.getResponseCode();
            java.io.InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder result = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        result.append(line);
                    }
                }
            }
            return new Result(code, result.toString());
        } finally {
            connection.disconnect();
        }
    }

    private static URI buildUri(String host, String path) throws Exception {
        String normalized = host.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Mac address is empty.");
        }
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://" + normalized;
        }

        URI base = URI.create(normalized);
        String scheme = base.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) ||
                base.getHost() == null || base.getRawUserInfo() != null ||
                base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("Enter a valid Mac hostname or IP address.");
        }
        String basePath = base.getRawPath();
        if (basePath != null && !basePath.isEmpty() && !"/".equals(basePath)) {
            throw new IllegalArgumentException("Enter only the Mac hostname or IP address, without a path.");
        }

        int port = base.getPort() == -1 ? PAUZE_PORT : base.getPort();
        int queryIndex = path.indexOf('?');
        String requestPath = queryIndex >= 0 ? path.substring(0, queryIndex) : path;
        String query = queryIndex >= 0 ? path.substring(queryIndex + 1) : null;
        return new URI(scheme, null, base.getHost(), port, requestPath, query, null);
    }

    private static void configure(HttpURLConnection connection, String token, String method, String path, String body) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = randomHex(16);
        String signature = sign(token, timestamp, nonce, method, path, body);

        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-Pauze-Timestamp", Long.toString(timestamp));
        connection.setRequestProperty("X-Pauze-Nonce", nonce);
        connection.setRequestProperty("X-Pauze-Signature", signature);
    }

    private static String sign(String token, long timestamp, String nonce, String method, String path, String body) throws Exception {
        String canonical = timestamp + "\n" + nonce + "\n" + method.toUpperCase(Locale.US) + "\n" + path + "\n" + body;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        return toHex(digest);
    }

    private static String randomHex(int byteCount) {
        byte[] value = new byte[byteCount];
        NONCE_RANDOM.nextBytes(value);
        return toHex(value);
    }

    private static String toHex(byte[] value) {
        char[] output = new char[value.length * 2];
        for (int index = 0; index < value.length; index++) {
            int unsigned = value[index] & 0xff;
            output[index * 2] = HEX_DIGITS[unsigned >>> 4];
            output[index * 2 + 1] = HEX_DIGITS[unsigned & 0x0f];
        }
        return new String(output);
    }

    public static boolean restricted(String body) {
        try { return new JSONObject(body).optBoolean("restricted", false); }
        catch (Exception ignored) { return false; }
    }
}
