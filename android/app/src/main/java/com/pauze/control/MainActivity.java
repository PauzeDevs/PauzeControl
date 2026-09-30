// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {

    private EditText hostInput;
    private EditText tokenInput;
    private TextView statusText;
    private TextView macStateText;
    private TextView macNameText;
    private TextView connectionPill;
    private View statusCard;
    private View restrictButton;
    private View allowButton;
    private View content;

    private SecureStore secureStore;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        statusCard = findViewById(R.id.statusCard);
        restrictButton = findViewById(R.id.restrictButton);
        allowButton = findViewById(R.id.allowButton);
        hostInput = findViewById(R.id.hostInput);
        tokenInput = findViewById(R.id.tokenInput);
        statusText = findViewById(R.id.statusText);
        macStateText = findViewById(R.id.macStateText);
        macNameText = findViewById(R.id.macNameText);
        connectionPill = findViewById(R.id.connectionPill);

        secureStore = new SecureStore(this);
        executor = Executors.newSingleThreadExecutor();

        animateEntrance();
        loadSavedConnection();

        findViewById(R.id.saveButton)
                .setOnClickListener(view -> saveConnection());

        restrictButton.setOnClickListener(
                view -> animatePressAndRun(
                        restrictButton,
                        () -> sendCommand("/v1/restrict")
                )
        );

        allowButton.setOnClickListener(
                view -> animatePressAndRun(
                        allowButton,
                        () -> sendCommand("/v1/allow")
                )
        );

        findViewById(R.id.statusButton)
                .setOnClickListener(view -> checkStatus());

        statusCard.setOnClickListener(
                view -> checkStatus()
        );
    }

    private void animateEntrance() {
        content.setAlpha(0f);
        content.setTranslationY(28f);

        AnimatorSet intro = new AnimatorSet();
        intro.playTogether(
                ObjectAnimator.ofFloat(content, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(content, View.TRANSLATION_Y, 28f, 0f)
        );
        intro.setDuration(600);
        intro.setInterpolator(new DecelerateInterpolator());
        intro.start();

        animateOnScroll();
    }

    private void animateOnScroll() {
        final android.widget.ScrollView scroll =
                findViewById(R.id.rootScroll);

        scroll.getViewTreeObserver().addOnScrollChangedListener(
                () -> {
                    int distance = scroll.getScrollY();
                    float offset = Math.min(distance / 280f, 1f);

                    statusCard.setScaleX(1f - (offset * 0.015f));
                    statusCard.setScaleY(1f - (offset * 0.015f));
                }
        );
    }

    private void animatePressAndRun(
            View target,
            Runnable action
    ) {
        target.animate()
                .scaleX(0.97f)
                .scaleY(0.97f)
                .setDuration(70)
                .withEndAction(() -> target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(120)
                        .withEndAction(action)
                        .start())
                .start();
    }

    private void loadSavedConnection() {
        String host = getPreferences(MODE_PRIVATE)
                .getString("host", "");

        hostInput.setText(host);

        executor.execute(() -> {
            try {
                String token = secureStore.getToken();

                mainHandler.post(
                        () -> tokenInput.setText(token)
                );

                if (!host.isEmpty() && !token.isEmpty()) {
                    checkStatusInternal(host, token);
                }
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void saveConnection() {
        String host = hostInput.getText().toString().trim();
        String token = tokenInput.getText().toString().trim();

        if (host.isEmpty() || token.isEmpty()) {
            toast("Enter the Mac address and pairing secret.");
            return;
        }

        getPreferences(MODE_PRIVATE)
                .edit()
                .putString("host", host)
                .apply();

        executor.execute(() -> {
            try {
                secureStore.putToken(token);
                setStatus("Connection saved • checking Mac…");
                checkStatusInternal(host, token);
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void sendCommand(String endpoint) {
        executor.execute(() -> {
            try {
                String host = hostInput.getText().toString().trim();
                String token = secureStore.getToken();

                if (host.isEmpty() || token.isEmpty()) {
                    throw new IllegalStateException(
                            "Save the connection first."
                    );
                }

                setStatus("Sending command…");
                ApiClient.Result result =
                        ApiClient.post(host, token, endpoint);

                if (result.code < 200 || result.code >= 300) {
                    throw new IllegalStateException(
                            "Mac returned HTTP " + result.code
                    );
                }

                boolean restricted = ApiClient.restricted(result.body);
                updateState(restricted);
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void checkStatus() {
        executor.execute(() -> {
            try {
                String host = hostInput.getText().toString().trim();
                String token = secureStore.getToken();

                if (host.isEmpty() || token.isEmpty()) {
                    throw new IllegalStateException(
                            "Save the connection first."
                    );
                }

                checkStatusInternal(host, token);
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void checkStatusInternal(
            String host,
            String token
    ) throws Exception {
        ApiClient.Result result =
                ApiClient.get(host, token, "/v1/status");

        if (result.code < 200 || result.code >= 300) {
            throw new IllegalStateException(
                    "Mac returned HTTP " + result.code
            );
        }

        updateState(
                ApiClient.restricted(result.body)
        );

        setStatus("Live • synced with your Mac");
    }

    private void updateState(boolean restricted) {
        mainHandler.post(() -> {
            if (restricted) {
                macStateText.setText("Restricted • awaiting ALLOW");
                connectionPill.setText("● RESTRICTED");
                connectionPill.setTextColor(Color.rgb(255, 193, 7));
                animateStatusPulse();
            } else {
                macStateText.setText("Available • session active");
                connectionPill.setText("● ONLINE");
                connectionPill.setTextColor(Color.rgb(105, 240, 174));
            }

            animateStateRefresh();
        });
    }

    private void animateStateRefresh() {
        statusCard.animate()
                .alpha(0.72f)
                .setDuration(80)
                .withEndAction(() ->
                        statusCard.animate()
                                .alpha(1f)
                                .setDuration(220)
                                .start())
                .start();
    }

    private void animateStatusPulse() {
        connectionPill.animate()
                .alpha(0.55f)
                .setDuration(450)
                .withEndAction(() -> connectionPill.animate()
                        .alpha(1f)
                        .setDuration(450)
                        .start())
                .start();
    }

    private void setStatus(String message) {
        mainHandler.post(() -> statusText.setText(message));
    }

    private void showError(String message) {
        final String safe =
                message == null || message.trim().isEmpty()
                        ? "Unknown error."
                        : message;

        mainHandler.post(() -> {
            statusText.setText("🔴 " + safe);
            connectionPill.setText("● OFFLINE");
            connectionPill.setTextColor(
                    Color.rgb(170, 174, 185)
            );

            Toast.makeText(
                    this,
                    safe,
                    Toast.LENGTH_SHORT
            ).show();
        });
    }

    private void toast(String message) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    @Override
    protected void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
