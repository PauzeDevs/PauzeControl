// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    private SecureStore secureStore;
    private ExecutorService executor;

    private final Handler mainHandler =
            new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        hostInput = findViewById(R.id.hostInput);
        tokenInput = findViewById(R.id.tokenInput);
        statusText = findViewById(R.id.statusText);

        secureStore = new SecureStore(this);
        executor = Executors.newSingleThreadExecutor();

        loadSavedConnection();

        Button saveButton =
                findViewById(R.id.saveButton);

        Button restrictButton =
                findViewById(R.id.restrictButton);

        Button allowButton =
                findViewById(R.id.allowButton);

        Button statusButton =
                findViewById(R.id.statusButton);

        saveButton.setOnClickListener(
                view -> saveConnection()
        );

        restrictButton.setOnClickListener(
                view -> sendCommand("/v1/restrict")
        );

        allowButton.setOnClickListener(
                view -> sendCommand("/v1/allow")
        );

        statusButton.setOnClickListener(
                view -> checkStatus()
        );
    }

    private void loadSavedConnection() {
        String host = getPreferences(
                MODE_PRIVATE
        ).getString("host", "");

        hostInput.setText(host);

        executor.execute(() -> {
            try {
                String token =
                        secureStore.getToken();

                mainHandler.post(
                        () -> tokenInput.setText(token)
                );
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
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

        executor.execute(() -> {
            try {
                secureStore.putToken(token);

                setStatus(
                        "Connection saved."
                );

                checkStatusInternal(
                        host,
                        token
                );
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void sendCommand(
            String endpoint
    ) {
        executor.execute(() -> {
            try {
                String host =
                        hostInput.getText()
                                .toString()
                                .trim();

                String token =
                        secureStore.getToken();

                if (host.isEmpty() ||
                        token.isEmpty()) {

                    throw new IllegalStateException(
                            "Save the connection first."
                    );
                }

                setStatus(
                        "Contacting Mac..."
                );

                ApiClient.Result result =
                        ApiClient.post(
                                host,
                                token,
                                endpoint
                        );

                if (result.code < 200 ||
                        result.code >= 300) {

                    throw new IllegalStateException(
                            "Mac returned HTTP " +
                                    result.code
                    );
                }

                boolean restricted =
                        ApiClient.restricted(
                                result.body
                        );

                setStatus(
                        restricted
                                ? "🟡 Mac is restricted."
                                : "🟢 Mac is available."
                );
            } catch (Exception error) {
                showError(error.getMessage());
            }
        });
    }

    private void checkStatus() {
        executor.execute(() -> {
            try {
                String host =
                        hostInput.getText()
                                .toString()
                                .trim();

                String token =
                        secureStore.getToken();

                if (host.isEmpty() ||
                        token.isEmpty()) {

                    throw new IllegalStateException(
                            "Save the connection first."
                    );
                }

                checkStatusInternal(
                        host,
                        token
                );
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
                ApiClient.get(
                        host,
                        token,
                        "/v1/status"
                );

        if (result.code < 200 ||
                result.code >= 300) {

            throw new IllegalStateException(
                    "Mac returned HTTP " +
                            result.code
            );
        }

        boolean restricted =
                ApiClient.restricted(
                        result.body
                );

        setStatus(
                restricted
                        ? "🟡 Mac is restricted."
                        : "🟢 Mac is available."
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

        mainHandler.post(() -> {
            statusText.setText(
                    "🔴 " + safe
            );

            Toast.makeText(
                    this,
                    safe,
                    Toast.LENGTH_SHORT
            ).show();
        });
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

    @Override
    protected void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }

        super.onDestroy();
    }
}
