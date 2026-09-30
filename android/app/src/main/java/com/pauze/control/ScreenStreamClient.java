// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.HttpURLConnection;

public final class ScreenStreamClient {

    public interface Listener {
        void onFrame(
                byte[] accessUnit,
                int width,
                int height,
                boolean keyFrame,
                long ptsMicroseconds
        );

        void onConnected();
        void onError(String message);
        void onClosed();
    }

    private static final int HEADER_SIZE = 24;
    private static final int MAX_ACCESS_UNIT_BYTES = 5 * 1024 * 1024;

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
                "PauzeControl-H264"
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
            connection = ApiClient.openScreen(host, token);

            int code = connection.getResponseCode();

            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "Mac returned HTTP " + code
                );
            }

            running = true;
            listener.onConnected();

            readStream(
                    connection.getInputStream()
            );

        } catch (Exception error) {
            if (running) {
                String message = error.getMessage();

                listener.onError(
                        message == null || message.isEmpty()
                                ? "H.264 stream failed."
                                : message
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

    private void readStream(
            InputStream input
    ) throws Exception {
        DataInputStream stream =
                new DataInputStream(input);

        byte[] magic = new byte[4];

        while (running) {
            readFully(stream, magic, 0, 4);

            if (magic[0] != 0x50 ||
                    magic[1] != 0x5A ||
                    magic[2] != 0x56 ||
                    magic[3] != 0x31) {
                throw new IllegalStateException(
                        "Invalid H.264 stream header."
                );
            }

            int version =
                    stream.readUnsignedByte();
            int flags =
                    stream.readUnsignedByte();
            int headerLength =
                    stream.readUnsignedShort();
            int payloadLength =
                    stream.readInt();
            long ptsMicroseconds =
                    stream.readLong();
            int width =
                    stream.readUnsignedShort();
            int height =
                    stream.readUnsignedShort();

            if (version != 1 ||
                    headerLength != HEADER_SIZE) {
                throw new IllegalStateException(
                        "Unsupported H.264 packet version."
                );
            }

            if (payloadLength <= 0 ||
                    payloadLength > MAX_ACCESS_UNIT_BYTES) {
                throw new IllegalStateException(
                        "H.264 access unit is too large."
                );
            }

            if (width <= 0 ||
                    width > 1920 ||
                    height <= 0 ||
                    height > 1080) {
                throw new IllegalStateException(
                        "Invalid screen dimensions."
                );
            }

            byte[] accessUnit =
                    new byte[payloadLength];

            readFully(
                    stream,
                    accessUnit,
                    0,
                    accessUnit.length
            );

            listener.onFrame(
                    accessUnit,
                    width,
                    height,
                    (flags & 0x01) != 0,
                    Math.max(0L, ptsMicroseconds)
            );
        }
    }

    private static void readFully(
            DataInputStream input,
            byte[] buffer,
            int offset,
            int length
    ) throws Exception {
        int remaining = length;

        while (remaining > 0) {
            int count = input.read(
                    buffer,
                    offset + length - remaining,
                    remaining
            );

            if (count < 0) {
                throw new EOFException(
                        "H.264 stream closed."
                );
            }

            if (count == 0) {
                continue;
            }

            remaining -= count;
        }
    }
}
