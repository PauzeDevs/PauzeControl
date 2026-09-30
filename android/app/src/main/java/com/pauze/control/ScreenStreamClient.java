// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

public final class ScreenStreamClient {

    public interface Listener {
        void onFrame(byte[] jpeg, int width, int height);
        void onConnected();
        void onError(String message);
        void onClosed();
    }

    private final String host;
    private final String token;
    private final Listener listener;

    private volatile boolean running;
    private HttpURLConnection connection;

    public ScreenStreamClient(
            String host,
            String token,
            Listener listener
    ) {
        this.host = host;
        this.token = token;
        this.listener = listener;
    }

    public void start() {
        new Thread(
                this::run,
                "PauzeControl-ScreenStream"
        ).start();
    }

    public void stop() {
        running = false;

        HttpURLConnection current = connection;

        if (current != null) {
            current.disconnect();
        }
    }

    private void run() {
        try {
            connection = ApiClient.openScreen(
                    host,
                    token
            );

            int code = connection.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "Mac returned HTTP " + code
                );
            }

            running = true;
            listener.onConnected();

            readMultipart(
                    connection.getInputStream()
            );

        } catch (Exception error) {
            if (running) {
                listener.onError(
                        error.getMessage() == null
                                ? "Screen stream failed."
                                : error.getMessage()
                );
            }
        } finally {
            running = false;

            if (connection != null) {
                connection.disconnect();
            }

            listener.onClosed();
        }
    }

    private void readMultipart(
            InputStream input
    ) throws Exception {

        ByteArrayOutputStream frame =
                new ByteArrayOutputStream(256 * 1024);

        boolean collecting = false;
        boolean previousWasFF = false;

        byte[] chunk = new byte[32 * 1024];

        while (running) {
            int count = input.read(chunk);

            if (count == -1) {
                break;
            }

            for (int i = 0; i < count; i++) {
                int value = chunk[i] & 0xFF;

                if (!collecting) {
                    if (previousWasFF && value == 0xD8) {
                        collecting = true;
                        frame.reset();
                        frame.write(0xFF);
                        frame.write(0xD8);
                    }

                    previousWasFF = value == 0xFF;
                    continue;
                }

                frame.write(value);

                int size = frame.size();

                if (size > 2_500_000) {
                    collecting = false;
                    frame.reset();
                    previousWasFF = false;
                    continue;
                }

                if (previousWasFF && value == 0xD9) {
                    byte[] jpeg = frame.toByteArray();
                    emitFrame(jpeg);

                    collecting = false;
                    frame.reset();
                    previousWasFF = false;
                    continue;
                }

                previousWasFF = value == 0xFF;
            }
        }
    }

    private void emitFrame(
            byte[] jpeg
    ) {
        android.graphics.BitmapFactory.Options options =
                new android.graphics.BitmapFactory.Options();

        options.inJustDecodeBounds = true;

        android.graphics.BitmapFactory.decodeByteArray(
                jpeg,
                0,
                jpeg.length,
                options
        );

        int width = options.outWidth;
        int height = options.outHeight;

        if (width <= 0 || height <= 0) {
            return;
        }

        listener.onFrame(
                jpeg,
                width,
                height
        );
    }
}
