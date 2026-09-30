// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.app.Activity;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ScreenActivity extends Activity
        implements ScreenStreamClient.Listener,
        SurfaceHolder.Callback {

    private SurfaceView screenSurface;
    private TextView liveStatus;
    private TextView resolutionText;

    private ScreenStreamClient streamClient;
    private MediaCodec decoder;
    private Surface decoderSurface;

    private String host;
    private String token;
    private boolean surfaceReady;
    private boolean streamError;

    private int frameCount;
    private long fpsWindowStart;

    private final MediaCodec.BufferInfo bufferInfo =
            new MediaCodec.BufferInfo();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_screen);

        screenSurface =
                findViewById(R.id.screenSurface);
        liveStatus =
                findViewById(R.id.liveStatus);
        resolutionText =
                findViewById(R.id.resolutionText);

        host =
                getIntent().getStringExtra("host");
        token =
                getIntent().getStringExtra("token");

        Button closeButton =
                findViewById(R.id.closeButton);

        closeButton.setOnClickListener(
                view -> finish()
        );

        screenSurface
                .getHolder()
                .addCallback(this);

        fpsWindowStart =
                System.currentTimeMillis();

        if (host == null || token == null ||
                host.isEmpty() || token.isEmpty()) {
            liveStatus.setText("NOT CONFIGURED");
        } else {
            liveStatus.setText("CONNECTING • H.264");
        }
    }

    @Override
    public void surfaceCreated(
            SurfaceHolder holder
    ) {
        decoderSurface =
                holder.getSurface();

        surfaceReady = true;

        if (streamClient == null &&
                host != null &&
                token != null &&
                !host.isEmpty() &&
                !token.isEmpty()) {
            startStream();
        }
    }

    @Override
    public void surfaceChanged(
            SurfaceHolder holder,
            int format,
            int width,
            int height
    ) {
        // Decoder dimensions follow the incoming H.264 stream.
    }

    @Override
    public void surfaceDestroyed(
            SurfaceHolder holder
    ) {
        surfaceReady = false;
        decoderSurface = null;

        if (streamClient != null) {
            streamClient.stop();
        }
    }

    private void startStream() {
        if (!surfaceReady || streamClient != null) {
            return;
        }

        streamError = false;
        frameCount = 0;
        fpsWindowStart =
                System.currentTimeMillis();

        streamClient = new ScreenStreamClient(
                host,
                token,
                this
        );

        streamClient.start();
    }

    @Override
    public void onConnected() {
        runOnUiThread(
                () -> liveStatus.setText(
                        "● CONNECTED  •  H.264  •  MAX 720P / 30 FPS"
                )
        );
    }

    @Override
    public void onFrame(
            byte[] accessUnit,
            int width,
            int height,
            boolean keyFrame,
            long ptsMicroseconds
    ) {
        Surface surface = decoderSurface;

        if (surface == null || !surface.isValid()) {
            return;
        }

        try {
            if (decoder == null) {
                if (!keyFrame) {
                    return;
                }

                byte[] sps =
                        findNalUnit(accessUnit, 7);
                byte[] pps =
                        findNalUnit(accessUnit, 8);

                if (sps == null || pps == null) {
                    return;
                }

                MediaFormat format =
                        MediaFormat.createVideoFormat(
                                MediaFormat.MIMETYPE_VIDEO_AVC,
                                width,
                                height
                        );

                format.setByteBuffer(
                        "csd-0",
                        ByteBuffer.wrap(sps)
                );
                format.setByteBuffer(
                        "csd-1",
                        ByteBuffer.wrap(pps)
                );

                decoder =
                        MediaCodec.createDecoderByType(
                                MediaFormat.MIMETYPE_VIDEO_AVC
                        );

                decoder.configure(
                        format,
                        surface,
                        null,
                        0
                );

                decoder.start();
            }

            MediaCodec activeDecoder = decoder;

            int inputIndex =
                    activeDecoder.dequeueInputBuffer(0);

            if (inputIndex >= 0) {
                ByteBuffer input =
                        activeDecoder.getInputBuffer(
                                inputIndex
                        );

                if (input == null) {
                    return;
                }

                input.clear();

                if (accessUnit.length >
                        input.remaining()) {
                    return;
                }

                input.put(accessUnit);

                activeDecoder.queueInputBuffer(
                        inputIndex,
                        0,
                        accessUnit.length,
                        Math.max(0L, ptsMicroseconds),
                        0
                );
            }

            drainDecoder(activeDecoder);

            frameCount++;

            long now =
                    System.currentTimeMillis();
            long elapsed =
                    now - fpsWindowStart;

            if (elapsed >= 1000L) {
                int fps =
                        Math.round(
                                frameCount *
                                1000f /
                                elapsed
                        );

                frameCount = 0;
                fpsWindowStart = now;

                String status =
                        "● LIVE  •  " +
                        fps +
                        " FPS  •  H.264";

                runOnUiThread(
                        () -> liveStatus.setText(status)
                );
            }

            final String dimensions =
                    width + " × " + height +
                    "  •  VIEW ONLY  •  H.264";

            runOnUiThread(
                    () -> resolutionText.setText(dimensions)
            );

        } catch (Exception error) {
            streamError = true;

            runOnUiThread(
                    () -> liveStatus.setText(
                            "● DECODER ERROR"
                    )
            );
        }
    }

    private void drainDecoder(
            MediaCodec activeDecoder
    ) {
        while (true) {
            int outputIndex =
                    activeDecoder.dequeueOutputBuffer(
                            bufferInfo,
                            0
                    );

            if (outputIndex >= 0) {
                activeDecoder.releaseOutputBuffer(
                        outputIndex,
                        true
                );
                continue;
            }

            if (outputIndex ==
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format =
                        activeDecoder.getOutputFormat();

                if (format.containsKey(
                        MediaFormat.KEY_WIDTH
                ) && format.containsKey(
                        MediaFormat.KEY_HEIGHT
                )) {
                    int width =
                            format.getInteger(
                                    MediaFormat.KEY_WIDTH
                            );
                    int height =
                            format.getInteger(
                                    MediaFormat.KEY_HEIGHT
                            );

                    if (width > 0 && height > 0) {
                        final String value =
                                width + " × " + height +
                                "  •  VIEW ONLY  •  H.264";

                        runOnUiThread(
                                () -> resolutionText
                                        .setText(value)
                        );
                    }
                }

                continue;
            }

            break;
        }
    }

    @Override
    public void onError(String message) {
        streamError = true;

        runOnUiThread(
                () -> liveStatus.setText(
                        "● STREAM ERROR"
                )
        );
    }

    @Override
    public void onClosed() {
        releaseDecoder();

        runOnUiThread(
                () -> liveStatus.setText(
                        "● STREAM CLOSED"
                )
        );

        streamClient = null;
    }

    private void releaseDecoder() {
        MediaCodec activeDecoder = decoder;
        decoder = null;

        if (activeDecoder == null) {
            return;
        }

        try {
            activeDecoder.stop();
        } catch (Exception ignored) {
        }

        try {
            activeDecoder.release();
        } catch (Exception ignored) {
        }
    }

    private static byte[] findNalUnit(
            byte[] data,
            int wantedType
    ) {
        int position = 0;

        while (position < data.length) {
            int startCode =
                    findStartCode(data, position);

            if (startCode < 0) {
                return null;
            }

            int nalStart =
                    startCode +
                    startCodeLength(
                            data,
                            startCode
                    );

            int next =
                    findStartCode(
                            data,
                            nalStart
                    );

            int nalEnd =
                    next >= 0
                            ? next
                            : data.length;

            if (nalStart < nalEnd &&
                    (data[nalStart] & 0x1F) ==
                            wantedType) {
                byte[] nal = Arrays.copyOfRange(
                        data,
                        nalStart,
                        nalEnd
                );

                // MediaCodec expects AVC codec-specific parameter sets
                // (SPS/PPS) in start-code-prefixed form when supplied
                // through csd-0 / csd-1.
                byte[] withStartCode =
                        new byte[nal.length + 4];

                withStartCode[0] = 0x00;
                withStartCode[1] = 0x00;
                withStartCode[2] = 0x00;
                withStartCode[3] = 0x01;

                System.arraycopy(
                        nal,
                        0,
                        withStartCode,
                        4,
                        nal.length
                );

                return withStartCode;
            }

            position = nalEnd;
        }

        return null;
    }

    private static int findStartCode(
            byte[] data,
            int from
    ) {
        for (int index = Math.max(0, from);
             index + 2 < data.length;
             index++) {
            if (index + 3 < data.length &&
                    data[index] == 0 &&
                    data[index + 1] == 0 &&
                    data[index + 2] == 0 &&
                    data[index + 3] == 1) {
                return index;
            }

            if (data[index] == 0 &&
                    data[index + 1] == 0 &&
                    data[index + 2] == 1) {
                return index;
            }
        }

        return -1;
    }

    private static int startCodeLength(
            byte[] data,
            int start
    ) {
        if (start + 3 < data.length &&
                data[start] == 0 &&
                data[start + 1] == 0 &&
                data[start + 2] == 0 &&
                data[start + 3] == 1) {
            return 4;
        }

        return 3;
    }

    @Override
    protected void onDestroy() {
        if (streamClient != null) {
            streamClient.stop();
        }

        releaseDecoder();
        super.onDestroy();
    }
}
