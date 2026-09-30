// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

public final class ScreenActivity extends Activity
        implements ScreenStreamClient.Listener {

    private ImageView screenImage;
    private TextView liveStatus;
    private TextView resolutionText;

    private ScreenStreamClient streamClient;

    private int frameCount;
    private long fpsWindowStart;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_screen);

        screenImage = findViewById(R.id.screenImage);
        liveStatus = findViewById(R.id.liveStatus);
        resolutionText = findViewById(R.id.resolutionText);

        String host = getIntent().getStringExtra("host");
        String token = getIntent().getStringExtra("token");

        if (host == null || token == null ||
                host.isEmpty() || token.isEmpty()) {
            liveStatus.setText("NOT CONFIGURED");
            return;
        }

        fpsWindowStart = System.currentTimeMillis();

        streamClient = new ScreenStreamClient(
                host,
                token,
                this
        );

        liveStatus.setText("CONNECTING…");
        streamClient.start();

        findViewById(R.id.closeButton)
                .setOnClickListener(
                        view -> finish()
                );
    }

    @Override
    public void onConnected() {
        runOnUiThread(
                () -> liveStatus.setText(
                        "● LIVE  •  MAX 720P / 30 FPS"
                )
        );
    }

    @Override
    public void onFrame(
            byte[] jpeg,
            int width,
            int height
    ) {
        Bitmap bitmap =
                BitmapFactory.decodeByteArray(
                        jpeg,
                        0,
                        jpeg.length
                );

        if (bitmap == null) {
            return;
        }

        frameCount++;

        long now = System.currentTimeMillis();
        long elapsed = now - fpsWindowStart;

        if (elapsed >= 1000L) {
            int fps =
                    Math.round(
                            frameCount * 1000f /
                                    elapsed
                    );

            frameCount = 0;
            fpsWindowStart = now;

            mainHandler.post(
                    () -> liveStatus.setText(
                            "● LIVE  •  " +
                                    fps +
                                    " FPS  •  " +
                                    width +
                                    "×" +
                                    height
                    )
            );
        }

        runOnUiThread(
                () -> {
                    screenImage.setImageBitmap(bitmap);
                    resolutionText.setText(
                            width +
                                    " × " +
                                    height +
                                    "  •  VIEW ONLY"
                    );
                }
        );
    }

    @Override
    public void onError(String message) {
        runOnUiThread(
                () -> liveStatus.setText(
                        "● STREAM ERROR"
                )
        );
    }

    @Override
    public void onClosed() {
        runOnUiThread(
                () -> {
                    if (isFinishing()) {
                        return;
                    }

                    liveStatus.setText(
                            "● STREAM CLOSED"
                    );
                }
        );
    }

    @Override
    protected void onDestroy() {
        if (streamClient != null) {
            streamClient.stop();
        }

        super.onDestroy();
    }
}
