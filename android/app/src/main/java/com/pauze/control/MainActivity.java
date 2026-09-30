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
import android.widget.EditText;
import android.widget.ScrollView;
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
    private TextView navDashboard;
    private TextView navActivity;
    private TextView navSettings;
    private View statusCard;
    private View restrictButton;
    private View allowButton;
    private View content;
    private View activityCard;
    private ScrollView rootScroll;

    private SecureStore secureStore;
    private ExecutorService executor;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static final int MUTED = Color.rgb(115, 121, 136);
    private static final int WHITE = Color.WHITE;
    private static final int ACCENT = Color.rgb(229, 9, 20);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        content = findViewById(R.id.content);
        rootScroll = findViewById(R.id.rootScroll);
        statusCard = findViewById(R.id.statusCard);
        restrictButton = findViewById(R.id.restrictButton);
        allowButton = findViewById(R.id.allowButton);
        hostInput = findViewById(R.id.hostInput);
        tokenInput = findViewById(R.id.tokenInput);
        statusText = findViewById(R.id.statusText);
        macStateText = findViewById(R.id.macStateText);
        macNameText = findViewById(R.id.macNameText);
        connectionPill = findViewById(R.id.connectionPill);
        navDashboard = findViewById(R.id.navDashboard);
        navActivity = findViewById(R.id.navActivity);
        navSettings = findViewById(R.id.navSettings);
        activityCard = findViewById(R.id.activityCard);

        secureStore = new SecureStore(this);
        executor = Executors.newSingleThreadExecutor();

        animateEntrance();
        setupRevealAnimations();
        setupNavigation();
        loadSavedConnection();

        findViewById(R.id.saveButton)
                .setOnClickListener(view -> animatePressAndRun(view, this::saveConnection));

        restrictButton.setOnClickListener(
                view -> animatePressAndRun(restrictButton, () -> sendCommand("/v1/restrict"))
        );

        allowButton.setOnClickListener(
                view -> animatePressAndRun(allowButton, () -> sendCommand("/v1/allow"))
        );

        findViewById(R.id.statusButton)
                .setOnClickListener(view -> animatePressAndRun(view, this::checkStatus));

        statusCard.setOnClickListener(view -> animateCard(statusCard, this::checkStatus));

        findViewById(R.id.screenCard).setOnClickListener(
                view -> animateCard(view, this::openScreenViewer)
        );
    }

    // Scroll reveal: sections glide into place as they enter the viewport.
    private void setupRevealAnimations() {
        View[] revealViews = {
                statusCard,
                findViewById(R.id.restrictButton),
                allowButton,
                findViewById(R.id.statusButton),
                findViewById(R.id.screenCard),
                findViewById(R.id.securityAnchor),
                findViewById(R.id.activityAnchor),
                activityCard,
                findViewById(R.id.settingsAnchor),
                hostInput,
                tokenInput,
                findViewById(R.id.saveButton)
        };

        for (View view : revealViews) {
            view.setAlpha(0f);
            view.setTranslationY(22f);
            view.setTag(Boolean.FALSE);
        }

        rootScroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            for (View view : revealViews) {
                revealIfVisible(view);
            }
        });

        rootScroll.post(() -> {
            for (View view : revealViews) {
                revealIfVisible(view);
            }
        });
    }

    private void revealIfVisible(View view) {
        if (Boolean.TRUE.equals(view.getTag()) || !view.isShown()) {
            return;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);

        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int top = location[1];
        int bottom = top + view.getHeight();

        if (bottom < 70 || top > screenHeight - 40) {
            return;
        }

        view.setTag(Boolean.TRUE);
        view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(420)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void setupNavigation() {
        navDashboard.setOnClickListener(view -> scrollTo(statusCard, navDashboard));
        navActivity.setOnClickListener(view -> scrollTo(activityCard, navActivity));
        navSettings.setOnClickListener(view -> scrollTo(hostInput, navSettings));
    }

    private void scrollTo(View target, TextView selected) {
        rootScroll.post(() -> rootScroll.smoothScrollTo(0, Math.max(0, target.getTop() - 20)));
        setActiveNav(selected);
    }

    private void setActiveNav(TextView selected) {
        navDashboard.setTextColor(MUTED);
        navActivity.setTextColor(MUTED);
        navSettings.setTextColor(MUTED);
        selected.setTextColor(WHITE);

        selected.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(120)
                .withEndAction(() -> selected.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .setInterpolator(new DecelerateInterpolator())
                        .start())
                .start();
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
    }

    private void animatePressAndRun(View target, Runnable action) {
        target.animate()
                .scaleX(0.96f)
                .scaleY(0.96f)
                .alpha(0.88f)
                .setDuration(75)
                .withEndAction(() -> target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .alpha(1f)
                        .setDuration(170)
                        .setInterpolator(new DecelerateInterpolator())
                        .withEndAction(action)
                        .start())
                .start();
    }

    private void animateCard(View target, Runnable action) {
        target.animate()
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(80)
                .withEndAction(() -> target.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(180)
                        .withEndAction(action)
                        .start())
                .start();
    }

    private void openScreenViewer() {
        executor.execute(() -> {
            try {
                String host = hostInput.getText().toString().trim();
                String token = secureStore.getToken();

                if (host.isEmpty() || token.isEmpty()) {
                    throw new IllegalStateException("Save the Mac connection first.");
                }

                mainHandler.post(() -> {
                    android.content.Intent intent = new android.content.Intent(this, ScreenActivity.class);
                    intent.putExtra("host", host);
                    intent.putExtra("token", token);
                    startActivity(intent);
                });
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void loadSavedConnection() {
        String host = getPreferences(MODE_PRIVATE).getString("host", "");
        hostInput.setText(host);

        executor.execute(() -> {
            try {
                String token = secureStore.getToken();
                mainHandler.post(() -> tokenInput.setText(token));

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

        getPreferences(MODE_PRIVATE).edit().putString("host", host).apply();

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
                    throw new IllegalStateException("Save the connection first.");
                }

                setStatus("Sending command…");
                ApiClient.Result result = ApiClient.post(host, token, endpoint);

                if (result.code < 200 || result.code >= 300) {
                    throw new IllegalStateException("Mac returned HTTP " + result.code);
                }

                updateState(ApiClient.restricted(result.body));
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
                    throw new IllegalStateException("Save the connection first.");
                }

                checkStatusInternal(host, token);
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void checkStatusInternal(String host, String token) throws Exception {
        ApiClient.Result result = ApiClient.get(host, token, "/v1/status");

        if (result.code < 200 || result.code >= 300) {
            throw new IllegalStateException("Mac returned HTTP " + result.code);
        }

        updateState(ApiClient.restricted(result.body));
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
                .withEndAction(() -> statusCard.animate()
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
        final String safe = message == null || message.trim().isEmpty() ? "Unknown error." : message;

        mainHandler.post(() -> {
            statusText.setText("🔴 " + safe);
            connectionPill.setText("● OFFLINE");
            connectionPill.setTextColor(Color.rgb(170, 174, 185));
            Toast.makeText(this, safe, Toast.LENGTH_SHORT).show();
        });
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }
}
