// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ScreenActivity extends Activity
        implements ScreenStreamClient.Listener,
        SurfaceHolder.Callback {

    private SurfaceView screenSurface;
    private TextView liveStatus;
    private TextView resolutionText;
    private TextView streamInfo;
    private LinearLayout topControls;
    private LinearLayout bottomControls;
    private LinearLayout centerControls;

    private ScreenStreamClient streamClient;
    private MediaCodec decoder;
    private Surface decoderSurface;

    private String host;
    private String token;
    private boolean surfaceReady;
    private boolean streamError;
    private boolean shuttingDown;
    private boolean controlsVisible = true;

    private int streamWidth;
    private int streamHeight;
    private boolean aspectApplied;
    private int decoderWidth;
    private int decoderHeight;

    private String selectedQuality = "auto";
    private int selectedFPS = 0;

    private static final String[] QUALITY_LABELS = {
            "Auto • Power Aware",
            "1080p • 60 FPS",
            "1080p • 30 FPS",
            "720p • 60 FPS",
            "720p • 30 FPS",
            "480p • 30 FPS",
            "360p • 30 FPS",
            "240p • 30 FPS",
            "144p • 30 FPS"
    };

    private static final String[] QUALITY_VALUES = {
            "auto", "1080p", "1080p", "720p", "720p",
            "480p", "360p", "240p", "144p"
    };

    private static final int[] QUALITY_FPS = {
            0, 60, 30, 60, 30, 30, 30, 30, 30
    };

    private int frameCount;
    private long fpsWindowStart;

    private final Handler uiHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable hideControlsRunnable =
            () -> setControlsVisible(false, true);

    private final MediaCodec.BufferInfo bufferInfo =
            new MediaCodec.BufferInfo();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        );

        setContentView(R.layout.activity_screen);

        screenSurface = findViewById(R.id.screenSurface);
        liveStatus = findViewById(R.id.liveStatus);
        resolutionText = findViewById(R.id.resolutionText);
        streamInfo = findViewById(R.id.streamInfo);
        topControls = findViewById(R.id.topControls);
        bottomControls = findViewById(R.id.bottomControls);
        centerControls = findViewById(R.id.centerControls);

        host = getIntent().getStringExtra("host");
        token = getIntent().getStringExtra("token");

        Button closeButton = findViewById(R.id.closeButton);
        Button reconnectButton = findViewById(R.id.reconnectButton);
        Button reconnectBottomButton = findViewById(R.id.reconnectBottomButton);
        Button fullscreenButton = findViewById(R.id.fullscreenButton);
        Button qualityButton = findViewById(R.id.qualityButton);

        closeButton.setOnClickListener(view -> finish());
        reconnectButton.setOnClickListener(view -> reconnect());
        reconnectBottomButton.setOnClickListener(view -> reconnect());
        fullscreenButton.setOnClickListener(view -> toggleFullscreen());
        qualityButton.setOnClickListener(view -> showQualityPicker());

        // Tapping the video behaves like a modern video player: controls
        // appear briefly, then fade away so the Mac screen gets the focus.
        screenSurface.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                toggleControls();
            }
            return true;
        });

        screenSurface.getHolder().addCallback(this);
        shuttingDown = false;
        fpsWindowStart = System.currentTimeMillis();

        if (host == null || token == null ||
                host.isEmpty() || token.isEmpty()) {
            showErrorState("NOT CONFIGURED");
        } else {
            liveStatus.setText("CONNECTING • H.264");
            streamInfo.setText("MacBook Air  •  View only");
            scheduleControlsHide();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        decoderSurface = holder.getSurface();
        surfaceReady = true;

        if (streamClient == null &&
                host != null && token != null &&
                !host.isEmpty() && !token.isEmpty()) {
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
        // The SurfaceView itself is sized to the incoming video aspect ratio.
        // The decoder therefore never stretches a 16:9 Mac frame into a
        // portrait phone display.
        if (streamWidth > 0 && streamHeight > 0) {
            applyVideoAspectRatio(streamWidth, streamHeight);
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceReady = false;
        decoderSurface = null;
        aspectApplied = false;
        releaseDecoder();

        ScreenStreamClient activeClient = streamClient;
        if (activeClient != null) {
            activeClient.stop();
        }
    }

    private void startStream() {
        if (!surfaceReady || streamClient != null) {
            return;
        }

        streamError = false;
        frameCount = 0;
        fpsWindowStart = System.currentTimeMillis();
        streamWidth = 0;
        streamHeight = 0;
        aspectApplied = false;

        centerControls.setVisibility(View.GONE);
        streamClient = new ScreenStreamClient(host, token, this);
        streamClient.start();
    }

    private void reconnect() {
        if (shuttingDown) {
            return;
        }

        centerControls.setVisibility(View.GONE);
        liveStatus.setText("CONNECTING • H.264");
        resolutionText.setText("VIEW ONLY • H.264");
        streamInfo.setText("Reconnecting to MacBook Air…");

        releaseDecoder();
        streamWidth = 0;
        streamHeight = 0;
        aspectApplied = false;

        ScreenStreamClient activeClient = streamClient;
        if (activeClient != null) {
            streamClient = null;
            activeClient.stop();
        } else if (surfaceReady) {
            screenSurface.postDelayed(this::startStream, 100);
        }

        showControlsTemporarily();
    }

    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            liveStatus.setText("● LIVE  •  H.264");
            streamInfo.setText("MacBook Air  •  View only  •  " + qualitySummary());
            centerControls.setVisibility(View.GONE);
            scheduleControlsHide();
        });
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
            // Quality changes restart the Mac encoder. Recreate MediaCodec
            // only after the next keyframe arrives at the new dimensions.
            if (decoder != null &&
                    (decoderWidth != width || decoderHeight != height)) {
                if (!keyFrame) {
                    return;
                }
                releaseDecoder();
            }

            if (decoder == null) {
                if (!keyFrame) {
                    return;
                }

                byte[] sps = findNalUnit(accessUnit, 7);
                byte[] pps = findNalUnit(accessUnit, 8);

                if (sps == null || pps == null) {
                    return;
                }

                MediaFormat format = MediaFormat.createVideoFormat(
                        MediaFormat.MIMETYPE_VIDEO_AVC,
                        width,
                        height
                );

                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));

                decoder = MediaCodec.createDecoderByType(
                        MediaFormat.MIMETYPE_VIDEO_AVC
                );

                decoder.configure(format, surface, null, 0);
                decoder.start();
                decoderWidth = width;
                decoderHeight = height;
            }

            MediaCodec activeDecoder = decoder;
            int inputIndex = activeDecoder.dequeueInputBuffer(0);

            if (inputIndex >= 0) {
                ByteBuffer input = activeDecoder.getInputBuffer(inputIndex);

                if (input == null) {
                    return;
                }

                input.clear();

                if (accessUnit.length > input.remaining()) {
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

            long now = System.currentTimeMillis();
            long elapsed = now - fpsWindowStart;

            if (elapsed >= 1000L) {
                int fps = Math.round(frameCount * 1000f / elapsed);
                frameCount = 0;
                fpsWindowStart = now;

                String status = "● LIVE  •  " + fps + " FPS  •  H.264";

                runOnUiThread(() -> liveStatus.setText(status));
            }

            if (streamWidth != width || streamHeight != height) {
                streamWidth = width;
                streamHeight = height;
                aspectApplied = false;
            }

            final String dimensions = width + " × " + height +
                    "  •  VIEW ONLY  •  H.264";

            runOnUiThread(() -> {
                resolutionText.setText(dimensions);

                if (!aspectApplied) {
                    applyVideoAspectRatio(width, height);
                }
            });

        } catch (Exception error) {
            streamError = true;
            showErrorState("● DECODER ERROR");
        }
    }

    /**
     * Keeps the Mac stream at its real aspect ratio, exactly like a normal
     * video player. Portrait phones get black letterbox space above/below;
     * landscape gets a wide player. The frame is never stretched or cropped.
     */
    private void applyVideoAspectRatio(int videoWidth, int videoHeight) {
        if (videoWidth <= 0 || videoHeight <= 0 ||
                screenSurface == null) {
            return;
        }

        View parent = (View) screenSurface.getParent();
        if (parent == null) {
            return;
        }

        int availableWidth = parent.getWidth();
        int availableHeight = parent.getHeight();

        if (availableWidth <= 0 || availableHeight <= 0) {
            screenSurface.post(() ->
                    applyVideoAspectRatio(videoWidth, videoHeight)
            );
            return;
        }

        float videoRatio =
                (float) videoWidth / (float) videoHeight;
        float containerRatio =
                (float) availableWidth / (float) availableHeight;

        int targetWidth;
        int targetHeight;

        if (containerRatio > videoRatio) {
            // Container is wider than the Mac stream: fit by height.
            targetHeight = availableHeight;
            targetWidth = Math.round(targetHeight * videoRatio);
        } else {
            // Container is taller/narrower: fit by width.
            targetWidth = availableWidth;
            targetHeight = Math.round(targetWidth / videoRatio);
        }

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) screenSurface.getLayoutParams();

        params.width = targetWidth;
        params.height = targetHeight;
        params.gravity = android.view.Gravity.CENTER;

        screenSurface.setLayoutParams(params);
        aspectApplied = true;
    }

    private void drainDecoder(MediaCodec activeDecoder) {
        while (true) {
            int outputIndex = activeDecoder.dequeueOutputBuffer(bufferInfo, 0);

            if (outputIndex >= 0) {
                activeDecoder.releaseOutputBuffer(outputIndex, true);
                continue;
            }

            if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat format = activeDecoder.getOutputFormat();

                if (format.containsKey(MediaFormat.KEY_WIDTH) &&
                        format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    int width = format.getInteger(MediaFormat.KEY_WIDTH);
                    int height = format.getInteger(MediaFormat.KEY_HEIGHT);

                    if (width > 0 && height > 0) {
                        final String value = width + " × " + height +
                                "  •  VIEW ONLY  •  H.264";

                        runOnUiThread(() -> {
                            resolutionText.setText(value);
                            streamWidth = width;
                            streamHeight = height;
                            aspectApplied = false;
                            applyVideoAspectRatio(width, height);
                        });
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
        showErrorState("● STREAM ERROR");
    }

    @Override
    public void onClosed() {
        releaseDecoder();

        runOnUiThread(() -> {
            liveStatus.setText("● STREAM CLOSED");
            streamClient = null;
            aspectApplied = false;

            if (!shuttingDown && surfaceReady &&
                    host != null && token != null &&
                    !host.isEmpty() && !token.isEmpty()) {
                liveStatus.setText("CONNECTING • H.264");
                streamInfo.setText("Reconnecting to MacBook Air…");
                screenSurface.postDelayed(this::startStream, 150);
            }
        });
    }

    private void showErrorState(String status) {
        runOnUiThread(() -> {
            liveStatus.setText(status);
            streamInfo.setText("Connection interrupted  •  Tap ↻ to reconnect");
            centerControls.setVisibility(View.VISIBLE);
            showControlsTemporarily();
        });
    }

    private void showQualityPicker() {
        int checked = findSelectedQualityIndex();

        new AlertDialog.Builder(this)
                .setTitle("Quality")
                .setSingleChoiceItems(
                        QUALITY_LABELS,
                        checked,
                        (dialog, which) -> {
                            dialog.dismiss();
                            requestQuality(
                                    QUALITY_VALUES[which],
                                    QUALITY_FPS[which]
                            );
                        }
                )
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private int findSelectedQualityIndex() {
        for (int index = 0; index < QUALITY_VALUES.length; index++) {
            if (QUALITY_VALUES[index].equals(selectedQuality) &&
                    QUALITY_FPS[index] == selectedFPS) {
                return index;
            }
        }
        return 0;
    }

    private String qualitySummary() {
        if ("auto".equals(selectedQuality)) {
            return "AUTO • Power aware";
        }
        return selectedQuality.toUpperCase(Locale.US) +
                (selectedFPS > 0 ? " • " + selectedFPS + " FPS" : "");
    }

    private void requestQuality(String quality, int fps) {
        if (host == null || token == null || host.isEmpty() || token.isEmpty()) {
            return;
        }

        runOnUiThread(() -> {
            liveStatus.setText("● SWITCHING QUALITY");
            streamInfo.setText(
                    "Applying " + quality.toUpperCase(Locale.US) +
                            ("auto".equals(quality)
                                    ? " • AUTO"
                                    : " • " + fps + " FPS") +
                            "…"
            );
        });

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("quality", quality);

                if (!"auto".equals(quality)) {
                    body.put("fps", fps);
                }

                ApiClient.Result result = ApiClient.post(
                        host,
                        token,
                        "/v1/screen/quality",
                        body.toString()
                );

                if (result.code < 200 || result.code >= 300) {
                    String message = "Mac rejected the selected quality.";
                    try {
                        JSONObject error = new JSONObject(result.body);
                        message = error.optString("error", message);
                    } catch (Exception ignored) {
                    }
                    throw new IllegalStateException(message);
                }

                selectedQuality = quality;
                selectedFPS = fps;

                runOnUiThread(() -> {
                    Button qualityButton = findViewById(R.id.qualityButton);
                    qualityButton.setText(
                            "auto".equals(quality)
                                    ? "AUTO"
                                    : quality.toUpperCase(Locale.US)
                    );
                    liveStatus.setText("● RECONNECTING • H.264");
                    streamInfo.setText("MacBook Air • View only • " + qualitySummary());
                });
            } catch (Exception error) {
                showErrorState("● QUALITY ERROR");
            }
        }, "PauzeControl-Quality").start();
    }

    private void toggleControls() {
        setControlsVisible(!controlsVisible, false);

        if (controlsVisible) {
            scheduleControlsHide();
        }
    }

    private void showControlsTemporarily() {
        setControlsVisible(true, false);
        scheduleControlsHide();
    }

    private void scheduleControlsHide() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        uiHandler.postDelayed(hideControlsRunnable, 3500L);
    }

    private void setControlsVisible(boolean visible, boolean animate) {
        controlsVisible = visible;
        float target = visible ? 1f : 0f;

        if (animate) {
            topControls.animate().alpha(target).setDuration(220).start();
            bottomControls.animate().alpha(target).setDuration(220).start();
        } else {
            topControls.setAlpha(target);
            bottomControls.setAlpha(target);
        }
    }

    private void toggleFullscreen() {
        int current = getResources().getConfiguration().orientation;

        if (current == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            getWindow().getDecorView().setSystemUiVisibility(0);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }

    private void releaseDecoder() {
        MediaCodec activeDecoder = decoder;
        decoder = null;
        decoderWidth = 0;
        decoderHeight = 0;

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

    private static byte[] findNalUnit(byte[] data, int wantedType) {
        int position = 0;

        while (position < data.length) {
            int startCode = findStartCode(data, position);

            if (startCode < 0) {
                return null;
            }

            int nalStart = startCode + startCodeLength(data, startCode);
            int next = findStartCode(data, nalStart);
            int nalEnd = next >= 0 ? next : data.length;

            if (nalStart < nalEnd &&
                    (data[nalStart] & 0x1F) == wantedType) {
                byte[] nal = Arrays.copyOfRange(data, nalStart, nalEnd);
                byte[] withStartCode = new byte[nal.length + 4];

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

    private static int findStartCode(byte[] data, int from) {
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

    private static int startCodeLength(byte[] data, int start) {
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
        shuttingDown = true;
        surfaceReady = false;
        uiHandler.removeCallbacksAndMessages(null);

        ScreenStreamClient activeClient = streamClient;
        if (activeClient != null) {
            activeClient.stop();
        }

        streamClient = null;
        releaseDecoder();
        super.onDestroy();
    }
}
