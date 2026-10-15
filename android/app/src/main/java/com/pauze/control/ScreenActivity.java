// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.Toast;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

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
    private LinearLayout zoomControls;
    private TextView zoomLevelText;
    private ScaleGestureDetector zoomGestureDetector;
    private float zoomScale = 1f;
    private float zoomTranslationX;
    private float zoomTranslationY;
    private float lastGestureFocusX;
    private float lastGestureFocusY;
    private boolean haveGestureFocus;
    private boolean zoomGestureConsumed;

    private static final float MIN_ZOOM = 1f;
    private static final float MAX_ZOOM = 3f;

    private ScreenStreamClient streamClient;
    private MediaCodec decoder;
    private Surface decoderSurface;

    private static final int SYSTEM_AUDIO_SAMPLE_RATE = 24_000;
    private static final int AUDIO_QUEUE_CAPACITY = 12;
    private final ArrayBlockingQueue<byte[]> audioBuffers =
            new ArrayBlockingQueue<>(AUDIO_QUEUE_CAPACITY);
    private final Object audioLock = new Object();
    private AudioTrack audioTrack;
    private Thread audioPlaybackThread;
    private volatile boolean audioPlaybackRunning;
    private volatile boolean audioEnabled;
    private volatile boolean audioMuted;
    private volatile float audioVolume = 0.85f;
    private Button audioButton;

    private String host;
    private String token;
    private boolean surfaceReady;
    private boolean streamError;
    private boolean shuttingDown;
    private boolean controlsVisible = true;
    private boolean qualityConfigured;
    private boolean qualityRequestInFlight;
    private boolean controlMode;
    private volatile boolean hasReceivedVideoPacket;
    private float touchStartX;
    private float touchStartY;
    private long touchStartTime;
    private boolean touchMoved;
    private boolean manualReconnectRequested;
    private int reconnectAttempts;
    private static final long MAX_RECONNECT_DELAY_MS = 30_000L;

    private int streamWidth;
    private int streamHeight;
    private boolean aspectApplied;
    private int decoderWidth;
    private int decoderHeight;

    private String selectedQuality = "720p";
    private int selectedFPS = 30;

    private static final String[] QUALITY_LABELS = {
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
            "1080p", "1080p", "720p", "720p",
            "480p", "360p", "240p", "144p"
    };

    private static final int[] QUALITY_FPS = {
            60, 30, 60, 30, 30, 30, 30, 30
    };

    private int frameCount;
    private long fpsWindowStart;

    private final Handler uiHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable hideControlsRunnable =
            () -> setControlsVisible(false, true);

    private final Runnable noVideoFrameRunnable = () -> {
        if (!shuttingDown && !hasReceivedVideoPacket && streamClient != null) {
            liveStatus.setText("CONNECTED • NO VIDEO");
            streamInfo.setText("Check Screen Recording permission and Mac agent logs.");
        }
    };

    private final Runnable automaticReconnectRunnable = () -> {
        if (!shuttingDown && surfaceReady && streamClient == null) {
            startStream();
        }
    };

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
        zoomControls = findViewById(R.id.zoomControls);
        zoomLevelText = findViewById(R.id.zoomLevelText);

        zoomGestureDetector = new ScaleGestureDetector(
                this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        setZoomLevel(
                                zoomScale * detector.getScaleFactor(),
                                detector.getFocusX(),
                                detector.getFocusY()
                        );
                        return true;
                    }
                }
        );

        findViewById(R.id.zoomInButton).setOnClickListener(
                view -> zoomBy(0.25f)
        );
        findViewById(R.id.zoomOutButton).setOnClickListener(
                view -> zoomBy(-0.25f)
        );
        findViewById(R.id.zoomResetButton).setOnClickListener(
                view -> resetZoom()
        );
        updateZoomLabel();

        host = getIntent().getStringExtra("host");
        token = getIntent().getStringExtra("token");

        selectedQuality = getPreferences(MODE_PRIVATE)
                .getString("screen_quality", "720p");
        selectedFPS = getPreferences(MODE_PRIVATE)
                .getInt("screen_fps", 30);
        if (!("1080p".equals(selectedQuality) ||
                "720p".equals(selectedQuality) ||
                "480p".equals(selectedQuality) ||
                "360p".equals(selectedQuality) ||
                "240p".equals(selectedQuality) ||
                "144p".equals(selectedQuality)) ||
                (selectedFPS == 60 &&
                        !("1080p".equals(selectedQuality) || "720p".equals(selectedQuality))) ||
                (selectedFPS != 30 && selectedFPS != 60)) {
            selectedQuality = "720p";
            selectedFPS = 30;
        }

        Button closeButton = findViewById(R.id.closeButton);
        Button reconnectButton = findViewById(R.id.reconnectButton);
        Button reconnectBottomButton = findViewById(R.id.reconnectBottomButton);
        Button fullscreenButton = findViewById(R.id.fullscreenButton);
        Button qualityButton = findViewById(R.id.qualityButton);
        Button controlModeButton = findViewById(R.id.controlModeButton);
        Button typeTextButton = findViewById(R.id.typeTextButton);
        typeTextButton.setEnabled(false);
        audioButton = findViewById(R.id.audioButton);
        audioEnabled = getPreferences(MODE_PRIVATE)
                .getBoolean("screen_audio_enabled", false);
        audioVolume = Math.max(0f, Math.min(1f, getPreferences(MODE_PRIVATE)
                .getFloat("screen_audio_volume", 0.85f)));
        audioMuted = getPreferences(MODE_PRIVATE)
                .getBoolean("screen_audio_muted", false);
        updateAudioButton();
        audioButton.setOnClickListener(view -> toggleAudio());
        audioButton.setOnLongClickListener(view -> {
            showAudioVolumeDialog();
            return true;
        });
        qualityButton.setText(selectedQuality.toUpperCase(Locale.US));
        controlModeButton.setOnClickListener(view -> {
            controlMode = !controlMode;
            updateControlModeUi();
            showControlsTemporarily();
        });
        typeTextButton.setOnClickListener(view -> showTextInput());

        closeButton.setOnClickListener(view -> finish());
        reconnectButton.setOnClickListener(view -> reconnect());
        reconnectBottomButton.setOnClickListener(view -> reconnect());
        fullscreenButton.setOnClickListener(view -> toggleFullscreen());
        qualityButton.setOnClickListener(view -> showQualityPicker());

        // Pinch to zoom and two-finger pan work in both VIEW and CONTROL
        // modes. Single-finger input remains mapped to the Mac's full frame.
        screenSurface.setOnTouchListener((view, event) -> {
            zoomGestureDetector.onTouchEvent(event);
            int action = event.getActionMasked();

            if (event.getPointerCount() >= 2) {
                zoomGestureConsumed = true;
                float focusX = getGestureFocusX(event);
                float focusY = getGestureFocusY(event);

                if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    lastGestureFocusX = focusX;
                    lastGestureFocusY = focusY;
                    haveGestureFocus = true;
                } else if (action == MotionEvent.ACTION_MOVE) {
                    if (haveGestureFocus && zoomScale > MIN_ZOOM) {
                        zoomTranslationX += focusX - lastGestureFocusX;
                        zoomTranslationY += focusY - lastGestureFocusY;
                        applyZoomTransform();
                    }
                    lastGestureFocusX = focusX;
                    lastGestureFocusY = focusY;
                    haveGestureFocus = true;
                } else if (action == MotionEvent.ACTION_POINTER_UP ||
                        action == MotionEvent.ACTION_CANCEL) {
                    haveGestureFocus = false;
                }
                return true;
            }

            if (zoomGestureConsumed) {
                if (action == MotionEvent.ACTION_UP ||
                        action == MotionEvent.ACTION_CANCEL) {
                    zoomGestureConsumed = false;
                    haveGestureFocus = false;
                }
                return true;
            }

            if (controlMode) {
                handleControlTouch(event, view.getWidth(), view.getHeight());
            } else if (action == MotionEvent.ACTION_UP) {
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
        synchronized (this) {
            decoderSurface = holder.getSurface();
            surfaceReady = true;
        }

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
        screenSurface.removeCallbacks(automaticReconnectRunnable);
        synchronized (this) {
            surfaceReady = false;
            decoderSurface = null;
            aspectApplied = false;
            releaseDecoder();
        }

        ScreenStreamClient activeClient = streamClient;
        if (activeClient != null) {
            activeClient.stop();
        }
        stopAudioPlayback();
    }

    private void startStream() {
        if (!surfaceReady || streamClient != null) {
            return;
        }

        if (!qualityConfigured) {
            requestQuality(selectedQuality, selectedFPS);
            return;
        }

        streamError = false;
        hasReceivedVideoPacket = false;
        frameCount = 0;
        fpsWindowStart = System.currentTimeMillis();
        streamWidth = 0;
        streamHeight = 0;
        aspectApplied = false;

        centerControls.setVisibility(View.GONE);
        streamClient = new ScreenStreamClient(host, token, this, audioEnabled);
        streamClient.start();
    }

    private void reconnect() {
        if (shuttingDown) {
            return;
        }

        stopAudioPlayback();
        reconnectAttempts = 0;
        manualReconnectRequested = true;
        screenSurface.removeCallbacks(automaticReconnectRunnable);
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
            manualReconnectRequested = false;
            screenSurface.postDelayed(automaticReconnectRunnable, 100);
        }

        showControlsTemporarily();
    }

    @Override
    public void onAudio(byte[] pcmS16LittleEndian, long ptsMicroseconds) {
        if (!audioEnabled || shuttingDown || pcmS16LittleEndian == null ||
                pcmS16LittleEndian.length == 0) {
            return;
        }

        try {
            ensureAudioPlayback();
        } catch (Exception error) {
            runOnUiThread(() -> Toast.makeText(
                    this,
                    "Live audio unavailable: " + error.getMessage(),
                    Toast.LENGTH_LONG
            ).show());
            stopAudioPlayback();
            return;
        }

        // Bound queued latency. When the phone or network stalls, drop the
        // oldest audio chunk rather than building up seconds of delayed sound.
        if (!audioBuffers.offer(pcmS16LittleEndian)) {
            audioBuffers.poll();
            audioBuffers.offer(pcmS16LittleEndian);
        }
    }

    @Override
    public void onConnected() {
        runOnUiThread(() -> {
            liveStatus.setText("CONNECTED • WAITING FOR VIDEO");
            streamInfo.setText("Waiting for the first Mac screen frame…");
            centerControls.setVisibility(View.GONE);
            uiHandler.removeCallbacks(noVideoFrameRunnable);
            uiHandler.postDelayed(noVideoFrameRunnable, 8000L);
            scheduleControlsHide();
        });
    }

    @Override
    public synchronized void onFrame(
            byte[] accessUnit,
            int width,
            int height,
            boolean keyFrame,
            long ptsMicroseconds
    ) {
        Surface surface = decoderSurface;
        hasReceivedVideoPacket = true;
        uiHandler.removeCallbacks(noVideoFrameRunnable);

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
                    // Reset the codec so a dequeued input slot cannot leave
                    // the decoder in an unrecoverable partially-ready state.
                    releaseDecoder();
                    return;
                }

                input.clear();

                if (accessUnit.length > input.remaining()) {
                    try {
                        activeDecoder.queueInputBuffer(
                                inputIndex, 0, 0,
                                Math.max(0L, ptsMicroseconds), 0
                        );
                    } catch (Exception ignored) {
                        // Resetting below releases the input slot if the
                        // codec rejects an empty buffer during reconfiguration.
                    }
                    releaseDecoder();
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
                    (controlMode ? "  •  CONTROL  •  H.264" : "  •  VIEW ONLY  •  H.264");

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
        screenSurface.post(this::applyZoomTransform);
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
        stopAudioPlayback();
        releaseDecoder();

        runOnUiThread(() -> {
            liveStatus.setText("● STREAM CLOSED");
            streamClient = null;
            aspectApplied = false;

            if (!shuttingDown && surfaceReady &&
                    host != null && token != null &&
                    !host.isEmpty() && !token.isEmpty()) {
                if (manualReconnectRequested) {
                    manualReconnectRequested = false;
                    reconnectAttempts = 0;
                    liveStatus.setText("CONNECTING • H.264");
                    streamInfo.setText("Reconnecting to MacBook Air…");
                    screenSurface.removeCallbacks(automaticReconnectRunnable);
                    screenSurface.postDelayed(automaticReconnectRunnable, 150);
                } else {
                    scheduleAutomaticReconnect();
                }
            }
        });
    }

    private void scheduleAutomaticReconnect() {
        if (shuttingDown || !surfaceReady) {
            return;
        }

        int attempt = Math.min(reconnectAttempts++, 6);
        long delay = Math.min(
                MAX_RECONNECT_DELAY_MS,
                500L * (1L << attempt)
        );
        liveStatus.setText("● RECONNECTING");
        streamInfo.setText("Retrying in " + (delay / 1000.0) + " seconds…");
        screenSurface.removeCallbacks(automaticReconnectRunnable);
        screenSurface.postDelayed(automaticReconnectRunnable, delay);
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
        return selectedQuality.toUpperCase(Locale.US) +
                " • " + selectedFPS + " FPS";
    }

    private void requestQuality(String quality, int fps) {
        if (host == null || token == null || host.isEmpty() || token.isEmpty() ||
                qualityRequestInFlight) {
            return;
        }

        qualityRequestInFlight = true;

        runOnUiThread(() -> {
            liveStatus.setText("● APPLYING QUALITY");
            streamInfo.setText(
                    "Applying " + quality.toUpperCase(Locale.US) +
                            " • " + fps + " FPS…"
            );
        });

        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("quality", quality);
                body.put("fps", fps);

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
                getPreferences(MODE_PRIVATE)
                        .edit()
                        .putString("screen_quality", quality)
                        .putInt("screen_fps", fps)
                        .apply();
                qualityConfigured = true;

                runOnUiThread(() -> {
                    qualityRequestInFlight = false;
                    Button qualityButton = findViewById(R.id.qualityButton);
                    qualityButton.setText(quality.toUpperCase(Locale.US));
                    liveStatus.setText("● RECONNECTING • H.264");
                    streamInfo.setText("MacBook Air • View only • " + qualitySummary());
                    if (surfaceReady && streamClient == null) {
                        startStream();
                    }
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    qualityRequestInFlight = false;
                    liveStatus.setText("● QUALITY ERROR");
                    String message = error.getMessage();
                    streamInfo.setText(message == null || message.isEmpty()
                            ? "Unable to apply the selected quality."
                            : message);
                    centerControls.setVisibility(View.VISIBLE);
                    showControlsTemporarily();
                });
            }
        }, "PauzeControl-Quality").start();
    }

    private void updateControlModeUi() {
        Button modeButton = findViewById(R.id.controlModeButton);
        modeButton.setText(controlMode ? "CONTROL" : "VIEW");
        findViewById(R.id.typeTextButton).setEnabled(controlMode);
        resolutionText.setText(controlMode ? "CONTROL ENABLED" : "VIEW ONLY • H.264");
        if (controlMode) {
            uiHandler.removeCallbacks(hideControlsRunnable);
            setControlsVisible(true, true);
        }
        streamInfo.setText(controlMode
                ? "Tap to click • swipe to scroll • hold+swipe to drag • ⌨ to type"
                : "MacBook Air • View only • " + qualitySummary());
    }

    private void zoomBy(float delta) {
        if (screenSurface == null) return;
        setZoomLevel(
                zoomScale + delta,
                screenSurface.getWidth() / 2f,
                screenSurface.getHeight() / 2f
        );
        showControlsTemporarily();
    }

    private void resetZoom() {
        zoomScale = MIN_ZOOM;
        zoomTranslationX = 0f;
        zoomTranslationY = 0f;
        applyZoomTransform();
        showControlsTemporarily();
    }

    private void setZoomLevel(float requested, float focusX, float focusY) {
        float next = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, requested));
        if (screenSurface == null || next == zoomScale) {
            updateZoomLabel();
            return;
        }

        float old = zoomScale;
        float centerX = screenSurface.getWidth() / 2f;
        float centerY = screenSurface.getHeight() / 2f;

        // Keep the point under the fingers stationary while scaling.
        zoomTranslationX += (focusX - centerX) * (old - next);
        zoomTranslationY += (focusY - centerY) * (old - next);
        zoomScale = next;
        applyZoomTransform();
    }

    private void applyZoomTransform() {
        if (screenSurface == null || screenSurface.getWidth() <= 0 ||
                screenSurface.getHeight() <= 0) {
            updateZoomLabel();
            return;
        }

        screenSurface.setPivotX(screenSurface.getWidth() / 2f);
        screenSurface.setPivotY(screenSurface.getHeight() / 2f);
        screenSurface.setScaleX(zoomScale);
        screenSurface.setScaleY(zoomScale);

        View parent = (View) screenSurface.getParent();
        float parentWidth = parent == null
                ? screenSurface.getWidth() : parent.getWidth();
        float parentHeight = parent == null
                ? screenSurface.getHeight() : parent.getHeight();

        float maxX = Math.max(
                0f,
                (screenSurface.getWidth() * zoomScale - parentWidth) / 2f
        );
        float maxY = Math.max(
                0f,
                (screenSurface.getHeight() * zoomScale - parentHeight) / 2f
        );

        zoomTranslationX = Math.max(-maxX, Math.min(maxX, zoomTranslationX));
        zoomTranslationY = Math.max(-maxY, Math.min(maxY, zoomTranslationY));
        screenSurface.setTranslationX(zoomTranslationX);
        screenSurface.setTranslationY(zoomTranslationY);
        updateZoomLabel();
    }

    private void updateZoomLabel() {
        if (zoomLevelText != null) {
            zoomLevelText.setText(String.format(
                    Locale.US, "%.1f×", zoomScale
            ));
        }
    }

    private float getGestureFocusX(MotionEvent event) {
        float total = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            total += event.getX(i);
        }
        return total / Math.max(1, event.getPointerCount());
    }

    private float getGestureFocusY(MotionEvent event) {
        float total = 0f;
        for (int i = 0; i < event.getPointerCount(); i++) {
            total += event.getY(i);
        }
        return total / Math.max(1, event.getPointerCount());
    }

    private void handleControlTouch(MotionEvent event, int width, int height) {
        if (width <= 0 || height <= 0) return;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchStartX = event.getX();
                touchStartY = event.getY();
                touchStartTime = System.currentTimeMillis();
                touchMoved = false;
                return;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(event.getX() - touchStartX) > 24 ||
                        Math.abs(event.getY() - touchStartY) > 24) touchMoved = true;
                return;
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - touchStartX;
                float dy = event.getY() - touchStartY;
                JSONObject input = new JSONObject();
                try {
                    if (touchMoved && System.currentTimeMillis() - touchStartTime >= 500L) {
                        input.put("action", "drag");
                        input.put("startX", clamp01(touchStartX / width));
                        input.put("startY", clamp01(touchStartY / height));
                        input.put("x", clamp01(event.getX() / width));
                        input.put("y", clamp01(event.getY() / height));
                    } else if (touchMoved) {
                        input.put("action", "scroll");
                        input.put("x", clamp01(touchStartX / width));
                        input.put("y", clamp01(touchStartY / height));
                        input.put("deltaY", (int) (-dy * 2.5f));
                        input.put("deltaX", (int) (-dx * 2.5f));
                    } else {
                        input.put("action", "click");
                        input.put("x", clamp01(event.getX() / width));
                        input.put("y", clamp01(event.getY() / height));
                    }
                    sendRemoteInput(input);
                } catch (Exception ignored) { }
                return;
            default:
                return;
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private void sendRemoteInput(JSONObject input) {
        new Thread(() -> {
            try {
                ApiClient.Result result = ApiClient.post(
                        host, token, "/v1/screen/input", input.toString());
                if (result.code < 200 || result.code >= 300) {
                    String message = "Mac rejected remote input.";
                    try { message = new JSONObject(result.body).optString("error", message); }
                    catch (Exception ignored) { }
                    final String error = message;
                    runOnUiThread(() -> {
                        liveStatus.setText("CONTROL ERROR");
                        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
                    });
                }
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(
                        this, "Could not send input to Mac.", Toast.LENGTH_SHORT).show());
            }
        }, "PauzeControl-RemoteInput").start();
    }

    private void showTextInput() {
        EditText text = new EditText(this);
        text.setSingleLine(false);
        text.setMinLines(2);
        new AlertDialog.Builder(this)
                .setTitle("Type on Mac")
                .setMessage("Text will be typed at the Mac's current focus.")
                .setView(text)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", (dialog, which) -> {
                    String value = text.getText().toString();
                    if (value.isEmpty()) return;
                    JSONObject input = new JSONObject();
                    try {
                        input.put("action", "type");
                        input.put("text", value);
                        sendRemoteInput(input);
                    } catch (Exception ignored) { }
                })
                .show();
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
        if (controlMode) return;
        uiHandler.postDelayed(hideControlsRunnable, 3500L);
    }

    private void setControlsVisible(boolean visible, boolean animate) {
        controlsVisible = visible;
        float target = visible ? 1f : 0f;

        if (animate) {
            topControls.animate().alpha(target).setDuration(220).start();
            bottomControls.animate().alpha(target).setDuration(220).start();
            zoomControls.animate().alpha(target).setDuration(220).start();
        } else {
            topControls.setAlpha(target);
            bottomControls.setAlpha(target);
            zoomControls.setAlpha(target);
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

    private void toggleAudio() {
        audioEnabled = !audioEnabled;
        getPreferences(MODE_PRIVATE).edit()
                .putBoolean("screen_audio_enabled", audioEnabled)
                .apply();

        if (!audioEnabled) {
            stopAudioPlayback();
        }
        updateAudioButton();
        showControlsTemporarily();
        Toast.makeText(
                this,
                audioEnabled ? "Mac system audio enabled" : "Mac system audio disabled",
                Toast.LENGTH_SHORT
        ).show();

        if (streamClient != null) {
            reconnect();
        } else if (surfaceReady) {
            startStream();
        }
    }

    private void updateAudioButton() {
        if (audioButton == null) return;
        audioButton.setText(audioEnabled ? "♫ ON" : "♫ OFF");
        audioButton.setContentDescription(
                audioEnabled
                        ? "System audio streaming enabled. Tap to disable; hold for volume."
                        : "System audio streaming disabled. Tap to enable; hold for volume."
        );
    }

    private void ensureAudioPlayback() {
        synchronized (audioLock) {
            if (audioTrack != null && audioPlaybackRunning) return;

            int minimumBuffer = AudioTrack.getMinBufferSize(
                    SYSTEM_AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
            );
            if (minimumBuffer <= 0) {
                throw new IllegalStateException("This device does not support 24 kHz mono PCM.");
            }

            AudioTrack candidate = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SYSTEM_AUDIO_SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(Math.max(minimumBuffer, SYSTEM_AUDIO_SAMPLE_RATE * 2 / 5))
                    .build();

            if (candidate.getState() != AudioTrack.STATE_INITIALIZED) {
                candidate.release();
                throw new IllegalStateException("Android could not initialize audio playback.");
            }

            candidate.setVolume(audioMuted ? 0f : audioVolume);
            candidate.play();
            audioTrack = candidate;
            audioPlaybackRunning = true;

            audioPlaybackThread = new Thread(
                    () -> audioPlaybackLoop(candidate),
                    "PauzeControl-LiveAudio"
            );
            audioPlaybackThread.start();
        }
    }

    private void audioPlaybackLoop(AudioTrack playbackTrack) {
        while (audioPlaybackRunning &&
                isCurrentAudioTrack(playbackTrack) &&
                !Thread.currentThread().isInterrupted()) {
            final byte[] chunk;
            try {
                chunk = audioBuffers.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (chunk == null) continue;

            int offset = 0;
            while (offset < chunk.length &&
                    audioPlaybackRunning &&
                    isCurrentAudioTrack(playbackTrack) &&
                    !Thread.currentThread().isInterrupted()) {
                int written = playbackTrack.write(
                        chunk,
                        offset,
                        chunk.length - offset,
                        AudioTrack.WRITE_BLOCKING
                );
                if (written < 0) break;
                if (written == 0) break;
                offset += written;
            }
        }
    }

    private boolean isCurrentAudioTrack(AudioTrack expectedTrack) {
        synchronized (audioLock) {
            return audioTrack == expectedTrack;
        }
    }

    private void stopAudioPlayback() {
        audioPlaybackRunning = false;
        audioBuffers.clear();

        Thread worker;
        synchronized (audioLock) {
            worker = audioPlaybackThread;
            audioPlaybackThread = null;
            AudioTrack activeTrack = audioTrack;
            audioTrack = null;

            if (activeTrack != null) {
                try { activeTrack.pause(); } catch (Exception ignored) { }
                try { activeTrack.flush(); } catch (Exception ignored) { }
                try { activeTrack.stop(); } catch (Exception ignored) { }
                activeTrack.release();
            }
        }
        if (worker != null) worker.interrupt();
    }

    private void applyAudioVolume() {
        synchronized (audioLock) {
            if (audioTrack != null) {
                audioTrack.setVolume(audioMuted ? 0f : audioVolume);
            }
        }
    }

    private void showAudioVolumeDialog() {
        SeekBar volume = new SeekBar(this);
        volume.setMax(100);
        volume.setProgress(Math.round(audioVolume * 100f));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                audioVolume = progress / 100f;
                applyAudioVolume();
                getPreferences(MODE_PRIVATE).edit()
                        .putFloat("screen_audio_volume", audioVolume)
                        .apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        new AlertDialog.Builder(this)
                .setTitle("Live audio volume")
                .setMessage("Adjust playback on this Android device. Hold the audio button to reopen this control.")
                .setView(volume)
                .setNeutralButton(audioMuted ? "Unmute" : "Mute", (dialog, which) -> {
                    audioMuted = !audioMuted;
                    getPreferences(MODE_PRIVATE).edit()
                            .putBoolean("screen_audio_muted", audioMuted)
                            .apply();
                    applyAudioVolume();
                })
                .setPositiveButton("Done", null)
                .show();
    }

    private synchronized void releaseDecoder() {
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
        screenSurface.removeCallbacks(automaticReconnectRunnable);
        uiHandler.removeCallbacksAndMessages(null);

        synchronized (this) {
            shuttingDown = true;
            surfaceReady = false;
            decoderSurface = null;
            ScreenStreamClient activeClient = streamClient;
            streamClient = null;
            if (activeClient != null) {
                activeClient.stop();
            }
            releaseDecoder();
        }

        stopAudioPlayback();
        super.onDestroy();
    }
}
