// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {

    private static final int MUTED = Color.rgb(115, 121, 136);
    private static final int WHITE = Color.WHITE;
    private static final int GREEN = Color.rgb(70, 220, 150);
    private static final int AMBER = Color.rgb(255, 193, 7);

    private EditText hostInput;
    private EditText tokenInput;

    private TextView statusText;
    private TextView macStateText;
    private TextView macNameText;
    private TextView connectionPill;

    private TextView cpuValue;
    private TextView memoryValue;
    private TextView diskValue;
    private TextView batteryValue;
    private TextView uptimeValue;
    private TextView macosValue;
    private TextView volumeValue;
    private SlideActionView restrictionControl;
    private SlideActionView muteButton;
    private boolean macRestricted;

    private SeekBar volumeSeek;

    private View content;
    private View statusCard;
    private View systemCard;
    private View powerCard;
    private ScrollView rootScroll;

    private TextView navDashboard;
    private TextView navActivity;
    private TextView navSettings;

    private SecureStore secureStore;
    private ExecutorService executor;
    private boolean macMuted;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    private final Runnable statusPoll = new Runnable() {
        @Override
        public void run() {
            refreshStatus(false);

            if (!isFinishing()) {
                mainHandler.postDelayed(
                        this,
                        5000L
                );
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();

        secureStore = new SecureStore(this);
        executor = Executors.newSingleThreadExecutor();

        animateEntrance();
        setupRevealAnimations();
        setupNavigation();
        setupActions();
        loadSavedConnection();
    }

    private void bindViews() {
        content = findViewById(R.id.content);
        rootScroll = findViewById(R.id.rootScroll);

        statusCard = findViewById(R.id.statusCard);
        systemCard = findViewById(R.id.systemCard);
        powerCard = findViewById(R.id.powerCard);

        hostInput = findViewById(R.id.hostInput);
        tokenInput = findViewById(R.id.tokenInput);

        statusText = findViewById(R.id.statusText);
        macStateText = findViewById(R.id.macStateText);
        macNameText = findViewById(R.id.macNameText);
        connectionPill = findViewById(R.id.connectionPill);

        cpuValue = findViewById(R.id.cpuValue);
        memoryValue = findViewById(R.id.memoryValue);
        diskValue = findViewById(R.id.diskValue);
        batteryValue = findViewById(R.id.batteryValue);
        uptimeValue = findViewById(R.id.uptimeValue);
        macosValue = findViewById(R.id.macosValue);

        volumeValue = findViewById(R.id.volumeValue);
        muteButton = findViewById(R.id.muteButton);
        volumeSeek = findViewById(R.id.volumeSeek);

        navDashboard = findViewById(R.id.navDashboard);
        navActivity = findViewById(R.id.navActivity);
        navSettings = findViewById(R.id.navSettings);
        restrictionControl = findViewById(R.id.restrictionCard);
        muteButton = findViewById(R.id.muteButton);
    }

    private void setupActions() {
        setupToggleAction(
                R.id.saveButton,
                "SAVE CONNECTION",
                this::saveConnection
        );

        setupToggleAction(
                R.id.statusButton,
                "REFRESH STATUS",
                () -> refreshStatus(true)
        );

        restrictionControl.setText(
                "MAC RESTRICTION"
        );
        restrictionControl.setOnSlideCompleteListener(
                view -> sendCommand(
                        macRestricted
                                ? "/v1/allow"
                                : "/v1/restrict"
                )
        );

        setupToggleAction(
                R.id.lockButton,
                "LOCK MAC",
                () -> sendCommand("/v1/lock")
        );

        setupToggleAction(
                R.id.sleepButton,
                "SLEEP MAC",
                () -> sendCommand("/v1/sleep")
        );

        setupToggleAction(
                R.id.restartButton,
                "RESTART MAC",
                () -> confirmAction(
                        "Restart Mac?",
                        "The Mac will restart.",
                        "/v1/restart"
                )
        );

        setupToggleAction(
                R.id.shutdownButton,
                "SHUT DOWN MAC",
                () -> confirmAction(
                        "Shut down Mac?",
                        "The Mac will shut down.",
                        "/v1/shutdown"
                )
        );

        muteButton.setText(
                "MUTE AUDIO"
        );
        muteButton.setOnSlideCompleteListener(
                view -> sendCommand(
                        macMuted
                                ? "/v1/unmute"
                                : "/v1/mute"
                )
        );

        findViewById(R.id.screenCard).setOnClickListener(
                view -> animateCard(
                        view,
                        this::openScreenViewer
                )
        );

        volumeSeek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        volumeValue.setText(
                                progress + "%"
                        );
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                        sendVolume(
                                seekBar.getProgress()
                        );
                    }
                }
        );

        statusCard.setOnClickListener(
                view -> animateCard(
                        statusCard,
                        () -> refreshStatus(true)
                )
        );

        TextView githubLink =
                findViewById(R.id.githubLink);

        githubLink.setOnClickListener(
                view -> {
                    try {
                        startActivity(
                                new Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(
                                                "https://github.com/PauzeDevs/PauzeControl"
                                        )
                                )
                        );
                    } catch (Exception ignored) {
                    }
                }
        );
    }

    private void setupToggleAction(
            int viewId,
            String label,
            Runnable action
    ) {
        SlideActionView control =
                findViewById(viewId);

        control.setText(label);
        control.setOnSlideCompleteListener(
                view -> action.run()
        );
    }

    private void setupNavigation() {
        navDashboard.setOnClickListener(
                view -> scrollTo(
                        statusCard,
                        navDashboard
                )
        );

        navActivity.setOnClickListener(
                view -> scrollTo(
                        powerCard,
                        navActivity
                )
        );

        navSettings.setOnClickListener(
                view -> scrollTo(
                        hostInput,
                        navSettings
                )
        );
    }

    private void scrollTo(
            View target,
            TextView selected
    ) {
        rootScroll.post(
                () -> rootScroll.smoothScrollTo(
                        0,
                        Math.max(
                                0,
                                target.getTop() - 20
                        )
                )
        );

        navDashboard.setTextColor(MUTED);
        navActivity.setTextColor(MUTED);
        navSettings.setTextColor(MUTED);
        selected.setTextColor(WHITE);

        selected.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(120)
                .withEndAction(
                        () -> selected.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(180)
                                .start()
                )
                .start();
    }

    private void setupRevealAnimations() {
        View[] views = {
                statusCard,
                systemCard,
                findViewById(R.id.restrictionCard),
                findViewById(R.id.statusButton),
                findViewById(R.id.screenCard),
                findViewById(R.id.lockButton),
                findViewById(R.id.sleepButton),
                findViewById(R.id.restartButton),
                findViewById(R.id.shutdownButton),
                findViewById(R.id.muteButton),
                powerCard,
                findViewById(R.id.securityAnchor),
                findViewById(R.id.activityAnchor),
                findViewById(R.id.settingsAnchor),
                hostInput,
                tokenInput,
                findViewById(R.id.saveButton)
        };

        for (View view : views) {
            view.setAlpha(0f);
            view.setTranslationY(24f);
            view.setTag(Boolean.FALSE);
        }

        rootScroll.setOnScrollChangeListener(
                (v, sx, sy, osx, osy) -> refreshRevealStates(views)
        );

        rootScroll.post(() -> refreshRevealStates(views));
    }

    private void refreshRevealStates(View[] views) {
        for (View view : views) {
            updateRevealState(view);
        }
    }

    private void updateRevealState(View view) {
        if (!view.isShown()) {
            return;
        }

        int[] location = new int[2];
        view.getLocationOnScreen(location);

        int screenHeight =
                getResources().getDisplayMetrics().heightPixels;

        boolean visible =
                location[1] + view.getHeight() > 80 &&
                        location[1] < screenHeight - 50;

        boolean revealed = Boolean.TRUE.equals(view.getTag());

        if (visible && !revealed) {
            view.setTag(Boolean.TRUE);
            view.setAlpha(0f);
            view.setTranslationY(24f);

            view.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(380)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        } else if (!visible && revealed) {
            view.setTag(Boolean.FALSE);
            view.animate()
                    .alpha(0f)
                    .translationY(18f)
                    .setDuration(220)
                    .start();
        }
    }

    private void animateEntrance() {
        content.setAlpha(0f);
        content.setTranslationY(30f);

        AnimatorSet intro =
                new AnimatorSet();

        intro.playTogether(
                ObjectAnimator.ofFloat(
                        content,
                        View.ALPHA,
                        0f,
                        1f
                ),
                ObjectAnimator.ofFloat(
                        content,
                        View.TRANSLATION_Y,
                        30f,
                        0f
                )
        );

        intro.setDuration(650);
        intro.setInterpolator(
                new DecelerateInterpolator()
        );
        intro.start();
    }

    private void animatePress(
            View view,
            Runnable action
    ) {
        view.animate()
                .scaleX(0.96f)
                .scaleY(0.96f)
                .alpha(0.88f)
                .setDuration(75)
                .withEndAction(
                        () -> view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(170)
                                .setInterpolator(
                                        new DecelerateInterpolator()
                                )
                                .withEndAction(action)
                                .start()
                )
                .start();
    }

    private void animateCard(
            View view,
            Runnable action
    ) {
        view.animate()
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(80)
                .withEndAction(
                        () -> view.animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(180)
                                .withEndAction(action)
                                .start()
                )
                .start();
    }

    private void loadSavedConnection() {
        String host =
                getPreferences(MODE_PRIVATE)
                        .getString(
                                "host",
                                ""
                        );

        hostInput.setText(host);

        executor.execute(
                () -> {
                    try {
                        String token =
                                secureStore.getToken();

                        mainHandler.post(
                                () -> tokenInput.setText(token)
                        );

                        if (!host.isEmpty() &&
                                !token.isEmpty()) {
                            requestStatus(
                                    host,
                                    token,
                                    false
                            );
                        }
                    } catch (Exception error) {
                        showError(
                                error.getMessage()
                        );
                    }
                }
        );
    }

    private void saveConnection() {
        String host =
                hostInput.getText()
                        .toString()
                        .trim();

        String token =
                tokenInput.getText()
                        .toString()
                        .trim();

        if (host.isEmpty() ||
                token.isEmpty()) {
            toast(
                    "Enter the Mac address and pairing secret."
            );
            return;
        }

        getPreferences(MODE_PRIVATE)
                .edit()
                .putString("host", host)
                .apply();

        executor.execute(
                () -> {
                    try {
                        secureStore.putToken(token);
                        setStatus(
                                "Connection saved • syncing Mac…"
                        );

                        requestStatus(
                                host,
                                token,
                                true
                        );
                    } catch (Exception error) {
                        showError(
                                error.getMessage()
                        );
                    }
                }
        );
    }

    private void refreshStatus(
            boolean userInitiated
    ) {
        String host =
                hostInput.getText()
                        .toString()
                        .trim();

        executor.execute(
                () -> {
                    try {
                        String token =
                                secureStore.getToken();

                        if (host.isEmpty() ||
                                token.isEmpty()) {
                            if (userInitiated) {
                                throw new IllegalStateException(
                                        "Save the connection first."
                                );
                            }

                            return;
                        }

                        requestStatus(
                                host,
                                token,
                                userInitiated
                        );
                    } catch (Exception error) {
                        if (userInitiated) {
                            showError(
                                    error.getMessage()
                            );
                        }
                    }
                }
        );
    }

    private void requestStatus(
            String host,
            String token,
            boolean showErrors
    ) throws Exception {
        ApiClient.Result result =
                ApiClient.get(
                        host,
                        token,
                        "/v1/status"
                );

        if (result.code < 200 ||
                result.code >= 300) {
            if (showErrors) {
                throw new IllegalStateException(
                        "Mac returned HTTP " +
                                result.code
                );
            }

            return;
        }

        JSONObject root =
                new JSONObject(result.body);

        updateState(
                root.optBoolean(
                        "restricted",
                        false
                )
        );

        updateSystem(
                root.optString(
                        "device",
                        "Mac"
                ),
                root.optJSONObject(
                        "system"
                )
        );

        setStatus(
                "Live • synced with your Mac"
        );
    }

    private void updateState(
            boolean restricted
    ) {
        mainHandler.post(
                () -> {
                    macRestricted = restricted;
                    restrictionControl.setText(
                            "MAC RESTRICTION"
                    );
                    restrictionControl.setChecked(
                            restricted
                    );

                    if (restricted) {
                        macStateText.setText(
                                "Restricted • awaiting ALLOW"
                        );
                        connectionPill.setText(
                                "● RESTRICTED"
                        );
                        connectionPill.setTextColor(
                                AMBER
                        );
                    } else {
                        macStateText.setText(
                                "Available • session active"
                        );
                        connectionPill.setText(
                                "● ONLINE"
                        );
                        connectionPill.setTextColor(
                                GREEN
                        );
                    }

                    statusCard.animate()
                            .alpha(0.76f)
                            .setDuration(80)
                            .withEndAction(
                                    () -> statusCard.animate()
                                            .alpha(1f)
                                            .setDuration(220)
                                            .start()
                            )
                            .start();
                }
        );
    }

    private void updateSystem(
            String device,
            JSONObject system
    ) {
        if (system == null) {
            return;
        }

        final double cpu =
                system.optDouble(
                        "cpu_usage_percent",
                        0
                );

        final double memoryUsed =
                system.optDouble(
                        "memory_used_gb",
                        0
                );

        final double memoryTotal =
                system.optDouble(
                        "memory_total_gb",
                        0
                );

        final double diskFree =
                system.optDouble(
                        "disk_free_gb",
                        0
                );

        final int battery =
                system.has("battery_percent")
                        ? system.optInt(
                                "battery_percent",
                                -1
                        )
                        : -1;

        final boolean charging =
                system.optBoolean(
                        "battery_charging",
                        false
                );

        final long uptime =
                Math.round(
                        system.optDouble(
                                "uptime_seconds",
                                0
                        )
                );

        final int volume =
                Math.max(
                        0,
                        Math.min(
                                100,
                                system.optInt(
                                        "output_volume",
                                        0
                                )
                        )
                );

        macMuted =
                system.optBoolean(
                        "output_muted",
                        false
                );

        final String version =
                system.optString(
                        "macos_version",
                        "—"
                );

        mainHandler.post(
                () -> {
                    macNameText.setText(
                            device.isEmpty()
                                    ? "Mac"
                                    : device
                    );

                    cpuValue.setText(
                            String.format(
                                    Locale.US,
                                    "%.0f%%",
                                    cpu
                            )
                    );

                    memoryValue.setText(
                            String.format(
                                    Locale.US,
                                    "%.1f / %.1f GB",
                                    memoryUsed,
                                    memoryTotal
                            )
                    );

                    diskValue.setText(
                            String.format(
                                    Locale.US,
                                    "%.0f GB free",
                                    diskFree
                            )
                    );

                    batteryValue.setText(
                            battery < 0
                                    ? "—"
                                    : battery +
                                            "%" +
                                            (charging
                                                    ? " • charging"
                                                    : "")
                    );

                    uptimeValue.setText(
                            formatUptime(uptime)
                    );

                    macosValue.setText(
                            version
                    );

                    volumeSeek.setProgress(
                            volume
                    );

                    volumeValue.setText(
                            volume + "%"
                    );

                    muteButton.setText(
                            "MUTE AUDIO"
                    );
                    muteButton.setChecked(
                            macMuted
                    );
                }
        );
    }

    private void sendCommand(
            String endpoint
    ) {
        String host =
                hostInput.getText()
                        .toString()
                        .trim();

        executor.execute(
                () -> {
                    try {
                        String token =
                                secureStore.getToken();

                        if (host.isEmpty() ||
                                token.isEmpty()) {
                            throw new IllegalStateException(
                                    "Save the connection first."
                            );
                        }

                        setStatus(
                                "Sending command…"
                        );

                        ApiClient.Result result =
                                ApiClient.post(
                                        host,
                                        token,
                                        endpoint
                                );

                        if (result.code < 200 ||
                                result.code >= 300) {
                            JSONObject error =
                                    new JSONObject(
                                            result.body
                                    );

                            throw new IllegalStateException(
                                    error.optString(
                                            "error",
                                            "Command failed."
                                    )
                            );
                        }

                        mainHandler.postDelayed(
                                () -> refreshStatus(false),
                                250L
                        );

                    } catch (Exception error) {
                        showError(
                                error.getMessage()
                        );
                    }
                }
        );
    }

    private void sendVolume(
            int volume
    ) {
        String host =
                hostInput.getText()
                        .toString()
                        .trim();

        executor.execute(
                () -> {
                    try {
                        String token =
                                secureStore.getToken();

                        if (host.isEmpty() ||
                                token.isEmpty()) {
                            throw new IllegalStateException(
                                    "Save the connection first."
                            );
                        }

                        JSONObject body =
                                new JSONObject()
                                        .put(
                                                "volume",
                                                volume
                                        );

                        ApiClient.Result result =
                                ApiClient.post(
                                        host,
                                        token,
                                        "/v1/volume",
                                        body.toString()
                                );

                        if (result.code < 200 ||
                                result.code >= 300) {
                            throw new IllegalStateException(
                                    "Volume command failed."
                            );
                        }

                        setStatus(
                                "Volume set to " +
                                        volume +
                                        "%."
                        );

                    } catch (Exception error) {
                        showError(
                                error.getMessage()
                        );
                    }
                }
        );
    }

    private void confirmAction(
            String title,
            String message,
            String endpoint
    ) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(
                        "CANCEL",
                        null
                )
                .setPositiveButton(
                        "CONFIRM",
                        (dialog, which) ->
                                sendCommand(endpoint)
                )
                .show();
    }

    private void openScreenViewer() {
        String host =
                hostInput.getText()
                        .toString()
                        .trim();

        executor.execute(
                () -> {
                    try {
                        String token =
                                secureStore.getToken();

                        if (host.isEmpty() ||
                                token.isEmpty()) {
                            throw new IllegalStateException(
                                    "Save the Mac connection first."
                            );
                        }

                        mainHandler.post(
                                () -> {
                                    android.content.Intent intent =
                                            new android.content.Intent(
                                                    this,
                                                    ScreenActivity.class
                                            );

                                    intent.putExtra(
                                            "host",
                                            host
                                    );

                                    intent.putExtra(
                                            "token",
                                            token
                                    );

                                    startActivity(
                                            intent
                                    );
                                }
                        );
                    } catch (Exception error) {
                        showError(
                                error.getMessage()
                        );
                    }
                }
        );
    }

    private void setStatus(
            String message
    ) {
        mainHandler.post(
                () -> statusText.setText(message)
        );
    }

    private void showError(
            String message
    ) {
        final String safe =
                message == null ||
                message.trim().isEmpty()
                        ? "Unknown error."
                        : message;

        mainHandler.post(
                () -> {
                    statusText.setText(
                            "● " + safe
                    );

                    connectionPill.setText(
                            "● OFFLINE"
                    );

                    connectionPill.setTextColor(
                            MUTED
                    );

                    Toast.makeText(
                            this,
                            safe,
                            Toast.LENGTH_SHORT
                    ).show();
                }
        );
    }

    private void toast(
            String message
    ) {
        Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
        ).show();
    }

    private String formatUptime(
            long seconds
    ) {
        long days =
                seconds / 86_400;
        seconds %= 86_400;

        long hours =
                seconds / 3_600;
        seconds %= 3_600;

        long minutes =
                seconds / 60;

        if (days > 0) {
            return days + "d " +
                    hours + "h";
        }

        if (hours > 0) {
            return hours + "h " +
                    minutes + "m";
        }

        return minutes + "m";
    }

    @Override
    protected void onResume() {
        super.onResume();

        mainHandler.removeCallbacks(
                statusPoll
        );

        mainHandler.postDelayed(
                statusPoll,
                1200L
        );
    }

    @Override
    protected void onPause() {
        mainHandler.removeCallbacks(
                statusPoll
        );

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(
                statusPoll
        );

        if (executor != null) {
            executor.shutdownNow();
        }

        super.onDestroy();
    }
}
