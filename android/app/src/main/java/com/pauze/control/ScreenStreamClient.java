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

        void onAudio(byte[] pcmS16LittleEndian, long ptsMicroseconds);

        void onConnected();
        void onError(String message);
        void onClosed();
    }

    private static final int HEADER_SIZE = 24;
    private static final int MAX_ACCESS_UNIT_BYTES = 5 * 1024 * 1024;

    private final String host;
    private final String token;
    private final Listener listener;
    private final boolean audioEnabled;

    private volatile boolean running;
    private volatile HttpURLConnection connection;

    public ScreenStreamClient(
            String host,
            String token,
            Listener listener
    ) {
        this(host, token, listener, false);
    }

    public ScreenStreamClient(
            String host,
            String token,
            Listener listener,
            boolean audioEnabled
    ) {
        this.host = host;
        this.token = token;
        this.listener = listener;
        this.audioEnabled = audioEnabled;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
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
        HttpURLConnection activeConnection = null;
        try {
            activeConnection = ApiClient.openScreen(host, token, audioEnabled);
            connection = activeConnection;

            // stop() may run while the socket is being created; never let
            // an in-flight connect resurrect a cancelled stream.
            if (!running) {
                activeConnection.disconnect();
                return;
            }

            int code = activeConnection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException(
                        "Mac returned HTTP " + code
                );
            }

            if (!running) {
                return;
            }

            listener.onConnected();
            readStream(activeConnection.getInputStream());

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
            HttpURLConnection current = activeConnection;
            if (current != null) {
                current.disconnect();
            }
            if (connection == activeConnection) {
                connection = null;
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
                    headerLength != HEADER_SIZE ||
                    (flags & ~0x03) != 0 ||
                    flags == 0x03) {
                throw new IllegalStateException(
                        "Unsupported PZV1 stream packet."
                );
            }

            if (payloadLength <= 0 ||
                    payloadLength > MAX_ACCESS_UNIT_BYTES) {
                throw new IllegalStateException(
                        "Stream packet payload is too large."
                );
            }

            byte[] payload = new byte[payloadLength];
            readFully(stream, payload, 0, payload.length);

            boolean audioPacket = (flags & 0x02) != 0;
            if (audioPacket) {
                if (width != 0 || height != 0 || (payloadLength & 1) != 0) {
                    throw new IllegalStateException(
                            "Invalid PCM audio packet."
                    );
                }
                if (audioEnabled) {
                    listener.onAudio(payload, Math.max(0L, ptsMicroseconds));
                }
                continue;
            }

            if (width <= 0 ||
                    width > 1920 ||
                    height <= 0 ||
                    height > 1080) {
                throw new IllegalStateException(
                        "Invalid screen dimensions."
                );
            }

            listener.onFrame(
                    payload,
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
