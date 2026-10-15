// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.
package com.pauze.control;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FeatureStudioActivity extends Activity {
    private static final int REQUEST_UPLOAD = 8101, REQUEST_DOWNLOAD = 8102;
    private static final int CHUNK = 24 * 1024;
    private static final long MAX_FILE = 50L * 1024L * 1024L;
    private static final String PROFILE_PREFS = "pauze_profiles";
    private static final String CONNECTION_PREFS = "pauze_connection";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SecureStore store;
    private LinearLayout content, profiles, files;
    private EditText profileName, host, secret;
    private TextView activeText, clipboardText, transferText, audioText, outputText, inputText;
    private String activeHost = "", activeToken = "", pendingDownloadName = "";
    private long pendingDownloadSize;
    private final ArrayList<AudioDevice> outputs = new ArrayList<>(), inputs = new ArrayList<>();

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SecureStore(this);
        activeHost = getSharedPreferences(CONNECTION_PREFS, MODE_PRIVATE).getString("host", "").trim();
        try { activeToken = store.getToken(); } catch (Exception ignored) { activeToken = ""; }
        buildUi();
        renderProfiles();
        updateActiveLabel();
        refreshRemoteFiles();
        refreshAudioDevices();
        Personalization.apply(this);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setId(R.id.rootContainer);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Personalization.palette(this).background);
        ScrollView scroll = new ScrollView(this);
        scroll.setId(R.id.rootScroll);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setId(R.id.content);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(20), dp(18), dp(36));
        content.addView(text("Device Studio", 28, true, false), match());
        TextView intro = text("Profiles, personalization, transfers, clipboard and audio", 13, false, true);
        LinearLayout.LayoutParams introParams = match(); introParams.topMargin = dp(5); introParams.bottomMargin = dp(16);
        content.addView(intro, introParams);
        buildDevices(); buildPersonalization(); buildClipboard(); buildTransfers(); buildAudio();
        Button done = button("DONE", this::finish);
        LinearLayout.LayoutParams doneParams = match(); doneParams.topMargin = dp(10);
        content.addView(done, doneParams);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);
    }

    private void buildDevices() {
        LinearLayout card = section("MULTI-MAC DEVICE MANAGER",
                "Save multiple trusted Macs. Each profile's pairing secret is encrypted independently.");
        profileName = field("Profile name", InputType.TYPE_CLASS_TEXT);
        host = field("Mac Tailscale hostname or IP", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        secret = field("Pairing secret", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        card.addView(profileName, fieldParams()); card.addView(host, fieldParams()); card.addView(secret, fieldParams());
        card.addView(button("SAVE DEVICE PROFILE", this::saveProfile), buttonParams());
        activeText = text("", 12, false, true); activeText.setTag(R.id.pauzeSurfaceView, Boolean.TRUE);
        activeText.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(activeText, fieldParams());
        profiles = new LinearLayout(this); profiles.setOrientation(LinearLayout.VERTICAL);
        card.addView(profiles, match());
        if (!activeHost.isEmpty()) host.setText(activeHost);
        if (!activeToken.isEmpty()) secret.setText(activeToken);
    }

    private void buildPersonalization() {
        LinearLayout card = section("PERSONALISATION STUDIO", "Settings are saved on this Android device and applied across the app.");
        card.addView(text("Theme", 13, true, false), fieldParams());
        android.widget.Spinner themes = new android.widget.Spinner(this);
        ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Warm Paper", "Midnight", "Ocean"});
        themeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        themes.setAdapter(themeAdapter);
        String theme = Personalization.preferences(this).getString("theme", "warm");
        themes.setSelection("midnight".equals(theme) ? 1 : ("ocean".equals(theme) ? 2 : 0));
        card.addView(themes, fieldParams());
        card.addView(text("Accent colour", 13, true, false), fieldParams());
        android.widget.Spinner accents = new android.widget.Spinner(this);
        ArrayAdapter<String> accentAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Terracotta", "Blue", "Violet", "Emerald"});
        accentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        accents.setAdapter(accentAdapter);
        String accent = Personalization.preferences(this).getString("accent", "terracotta");
        accents.setSelection("blue".equals(accent) ? 1 : ("violet".equals(accent) ? 2 : ("emerald".equals(accent) ? 3 : 0)));
        card.addView(accents, fieldParams());
        CheckBox compact = new CheckBox(this); compact.setText("Compact spacing");
        compact.setTextColor(Personalization.foregroundColor(this));
        compact.setChecked(Personalization.preferences(this).getBoolean("compact", false));
        card.addView(compact, fieldParams());
        themes.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Personalization.preferences(FeatureStudioActivity.this).edit()
                        .putString("theme", pos == 1 ? "midnight" : (pos == 2 ? "ocean" : "warm")).apply();
                Personalization.apply(FeatureStudioActivity.this);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        accents.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                Personalization.preferences(FeatureStudioActivity.this).edit()
                        .putString("accent", pos == 1 ? "blue" : (pos == 2 ? "violet" : (pos == 3 ? "emerald" : "terracotta"))).apply();
                Personalization.apply(FeatureStudioActivity.this);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        compact.setOnCheckedChangeListener((v, checked) -> {
            Personalization.preferences(FeatureStudioActivity.this).edit().putBoolean("compact", checked).apply();
            Personalization.apply(FeatureStudioActivity.this);
        });
    }

    private void buildClipboard() {
        LinearLayout card = section("CROSS-DEVICE CLIPBOARD",
                "Explicit manual actions only. Clipboard text is not monitored or synchronised in the background.");
        clipboardText = text("Ready when a Mac is connected.", 12, false, true);
        card.addView(clipboardText, fieldParams());
        card.addView(button("SEND PHONE CLIPBOARD TO MAC", this::sendPhoneClipboard), buttonParams());
        card.addView(button("GET MAC CLIPBOARD TO PHONE", this::getMacClipboard), buttonParams());
    }

    private void buildTransfers() {
        LinearLayout card = section("SECURE FILE TRANSFER",
                "Files are confined to the Mac's PauzeControl/Transfers folder. Maximum file size: 50 MiB.");
        transferText = text("Choose a file to upload or refresh the Mac file list.", 12, false, true);
        card.addView(transferText, fieldParams());
        card.addView(button("UPLOAD FILE TO MAC", () -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
            startActivityForResult(intent, REQUEST_UPLOAD);
        }), buttonParams());
        card.addView(button("REFRESH MAC FILES", this::refreshRemoteFiles), buttonParams());
        files = new LinearLayout(this); files.setOrientation(LinearLayout.VERTICAL);
        card.addView(files, match());
    }

    private void buildAudio() {
        LinearLayout card = section("ADVANCED AUDIO CONTROL", "Inspect and change the Mac's system-default audio output and input devices.");
        audioText = text("Audio device status has not loaded yet.", 12, false, true);
        outputText = text("Output: not loaded", 12, true, false);
        inputText = text("Input: not loaded", 12, true, false);
        card.addView(audioText, fieldParams()); card.addView(outputText, fieldParams());
        card.addView(button("CHOOSE OUTPUT DEVICE", () -> chooseAudio(true)), buttonParams());
        card.addView(inputText, fieldParams());
        card.addView(button("CHOOSE INPUT DEVICE", () -> chooseAudio(false)), buttonParams());
        card.addView(button("REFRESH AUDIO DEVICES", this::refreshAudioDevices), buttonParams());
    }

    private LinearLayout section(String title, String detail) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(Personalization.roundRect(this, Personalization.surfaceColor(this), Personalization.mutedColor(this)));
        card.setTag(R.id.pauzeSurfaceView, Boolean.TRUE);
        LinearLayout.LayoutParams params = match(); params.bottomMargin = dp(14); content.addView(card, params);
        TextView heading = text(title, 11, true, false); heading.setLetterSpacing(.09f); card.addView(heading, match());
        TextView description = text(detail, 12, false, true);
        LinearLayout.LayoutParams dpms = match(); dpms.topMargin = dp(6); dpms.bottomMargin = dp(10);
        card.addView(description, dpms);
        return card;
    }

    private EditText field(String hint, int type) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setSingleLine(true); edit.setInputType(type);
        edit.setTextColor(Personalization.foregroundColor(this)); edit.setHintTextColor(Personalization.mutedColor(this));
        edit.setPadding(dp(12), dp(6), dp(12), dp(6));
        edit.setBackground(Personalization.roundRect(this, Personalization.surfaceColor(this), Personalization.mutedColor(this)));
        edit.setTag(R.id.pauzeSurfaceView, Boolean.TRUE); return edit;
    }
    private TextView text(String value, int size, boolean bold, boolean muted) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size);
        t.setTextColor(muted ? Personalization.mutedColor(this) : Personalization.foregroundColor(this));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }
    private Button button(String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextColor(android.graphics.Color.WHITE);
        b.setBackground(Personalization.roundRect(this, Personalization.accent(this), Personalization.accent(this)));
        b.setTag(R.id.pauzeAccentView, Boolean.TRUE); b.setMinHeight(dp(48)); b.setOnClickListener(v -> action.run()); return b;
    }
    private LinearLayout.LayoutParams match() { return new LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT); }
    private LinearLayout.LayoutParams fieldParams() { LinearLayout.LayoutParams p = match(); p.topMargin = dp(5); p.bottomMargin = dp(5); return p; }
    private LinearLayout.LayoutParams buttonParams() { return fieldParams(); }
    private int dp(int value) { return Personalization.dp(this, value); }

    private JSONArray readProfiles() {
        try { return new JSONArray(getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).getString("profiles", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void saveProfile() {
        String name = profileName.getText().toString().trim(), deviceHost = host.getText().toString().trim(),
                deviceSecret = secret.getText().toString().trim();
        if (name.isEmpty() || deviceHost.isEmpty() || deviceSecret.isEmpty()) {
            toast("Enter a profile name, Mac address and pairing secret."); return;
        }
        try {
            JSONArray old = readProfiles(), next = new JSONArray(); String id = null;
            for (int i = 0; i < old.length(); i++) {
                JSONObject entry = old.getJSONObject(i);
                if (name.equalsIgnoreCase(entry.optString("name")) && deviceHost.equalsIgnoreCase(entry.optString("host")))
                    id = entry.optString("id");
            }
            if (id == null || id.isEmpty()) id = UUID.randomUUID().toString();
            String secretKey = "device_" + id.replace("-", "");
            store.putSecret(secretKey, deviceSecret);
            JSONObject replacement = new JSONObject().put("id", id).put("name", name).put("host", deviceHost).put("secret_key", secretKey);
            boolean replaced = false;
            for (int i = 0; i < old.length(); i++) {
                JSONObject entry = old.getJSONObject(i);
                if (id.equals(entry.optString("id"))) { next.put(replacement); replaced = true; } else next.put(entry);
            }
            if (!replaced) next.put(replacement);
            getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).edit().putString("profiles", next.toString()).apply();
            renderProfiles(); toast("Device profile saved securely.");
            if (activeHost.isEmpty()) activateProfile(replacement);
        } catch (Exception error) { toast("Could not save profile: " + message(error)); }
    }

    private void renderProfiles() {
        if (profiles == null) return;
        profiles.removeAllViews(); JSONArray array = readProfiles();
        if (array.length() == 0) { profiles.addView(text("No saved devices yet.", 12, false, true), match()); return; }
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i); if (item == null) continue;
            final JSONObject profile = item;
            LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(10), dp(10), dp(10), dp(10));
            row.setBackground(Personalization.roundRect(this, Personalization.surfaceColor(this), Personalization.mutedColor(this)));
            row.setTag(R.id.pauzeSurfaceView, Boolean.TRUE);
            LinearLayout.LayoutParams rp = match(); rp.topMargin = dp(5);
            row.addView(text(profile.optString("name", "Mac"), 14, true, false), match());
            row.addView(text(profile.optString("host", ""), 11, false, true), match());
            LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -2, 1f); half.rightMargin = dp(4);
            actions.addView(button("USE DEVICE", () -> activateProfile(profile)), half);
            LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -2, 1f); half2.leftMargin = dp(4);
            actions.addView(button("REMOVE", () -> new AlertDialog.Builder(this).setTitle("Remove profile?")
                    .setMessage("Remove this saved profile from the phone? The Mac is not changed.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove", (d, w) -> removeProfile(profile)).show()), half2);
            row.addView(actions, match()); profiles.addView(row, rp);
        }
    }

    private void removeProfile(JSONObject profile) {
        try {
            JSONArray old = readProfiles(), next = new JSONArray(); String key = profile.optString("secret_key");
            for (int i = 0; i < old.length(); i++) {
                JSONObject entry = old.getJSONObject(i);
                if (!profile.optString("id").equals(entry.optString("id"))) next.put(entry);
            }
            getSharedPreferences(PROFILE_PREFS, MODE_PRIVATE).edit().putString("profiles", next.toString()).apply();
            store.removeSecret(key); renderProfiles(); toast("Profile removed.");
        } catch (Exception error) { toast("Could not remove profile: " + message(error)); }
    }

    private void activateProfile(JSONObject profile) {
        try {
            String deviceHost = profile.optString("host", "").trim();
            String token = store.getSecret(profile.optString("secret_key", ""));
            if (deviceHost.isEmpty() || token.isEmpty()) throw new IllegalStateException("Profile is incomplete or its secret is unavailable.");
            store.putToken(token);
            getSharedPreferences(CONNECTION_PREFS, MODE_PRIVATE).edit().putString("host", deviceHost).apply();
            activeHost = deviceHost; activeToken = token;
            host.setText(deviceHost); secret.setText(token); profileName.setText(profile.optString("name", ""));
            updateActiveLabel(); toast("Active Mac changed to " + profile.optString("name", "device") + ".");
            refreshRemoteFiles(); refreshAudioDevices();
        } catch (Exception error) { toast("Could not activate profile: " + message(error)); }
    }

    private void updateActiveLabel() {
        if (activeText != null) activeText.setText(activeHost.isEmpty() || activeToken.isEmpty()
                ? "ACTIVE MAC\nNo connected profile selected." : "ACTIVE MAC\n" + activeHost);
    }
    private boolean ready() {
        if (activeHost.isEmpty() || activeToken.isEmpty()) { toast("Save or select a Mac profile first."); return false; }
        return true;
    }
    private ApiClient.Result get(String path) throws Exception {
        ApiClient.Result r = ApiClient.get(activeHost, activeToken, path);
        if (r.code < 200 || r.code >= 300) throw apiError(r); return r;
    }
    private ApiClient.Result post(String path, JSONObject body) throws Exception {
        ApiClient.Result r = ApiClient.post(activeHost, activeToken, path, body.toString());
        if (r.code < 200 || r.code >= 300) throw apiError(r); return r;
    }
    private IllegalStateException apiError(ApiClient.Result r) {
        String m = "Remote request failed (HTTP " + r.code + ").";
        try { m = new JSONObject(r.body).optString("error", m); } catch (Exception ignored) {}
        return new IllegalStateException(m);
    }

    private void sendPhoneClipboard() {
        if (!ready()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) { toast("Phone clipboard is empty."); return; }
        CharSequence chars = cm.getPrimaryClip().getItemAt(0).coerceToText(this);
        String value = chars == null ? "" : chars.toString();
        if (value.getBytes(StandardCharsets.UTF_8).length > 24576) { toast("Clipboard text must be 24 KiB or smaller."); return; }
        clipboardText.setText("Sending clipboard text…");
        io.execute(() -> {
            try { post("/v1/clipboard", new JSONObject().put("text", value));
                main.post(() -> { clipboardText.setText("Phone clipboard sent to Mac."); toast("Clipboard sent to Mac."); });
            } catch (Exception e) { main.post(() -> { clipboardText.setText("Send failed: " + message(e)); toast("Clipboard send failed: " + message(e)); }); }
        });
    }
    private void getMacClipboard() {
        if (!ready()) return; clipboardText.setText("Reading Mac clipboard…");
        io.execute(() -> {
            try {
                String value = new JSONObject(get("/v1/clipboard").body).optString("text", "");
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null) throw new IllegalStateException("Android clipboard is unavailable.");
                cm.setPrimaryClip(ClipData.newPlainText("PauzeControl Mac clipboard", value));
                main.post(() -> { clipboardText.setText("Mac text copied to the phone clipboard."); toast("Mac clipboard copied."); });
            } catch (Exception e) { main.post(() -> { clipboardText.setText("Receive failed: " + message(e)); toast("Clipboard receive failed: " + message(e)); }); }
        });
    }

    private void refreshRemoteFiles() {
        if (files == null || transferText == null) return;
        if (activeHost.isEmpty() || activeToken.isEmpty()) {
            files.removeAllViews(); files.addView(text("Select an active Mac to see transferred files.", 12, false, true), match()); return;
        }
        transferText.setText("Loading Mac files…");
        io.execute(() -> {
            try {
                JSONArray array = new JSONObject(get("/v1/files/list").body).optJSONArray("files");
                ArrayList<RemoteFile> result = new ArrayList<>();
                if (array != null) for (int i = 0; i < array.length(); i++) {
                    JSONObject f = array.optJSONObject(i); if (f != null) result.add(new RemoteFile(f.optString("name"), f.optLong("size")));
                }
                main.post(() -> showRemoteFiles(result));
            } catch (Exception e) { main.post(() -> transferText.setText("File list unavailable: " + message(e))); }
        });
    }
    private void showRemoteFiles(ArrayList<RemoteFile> items) {
        files.removeAllViews();
        if (items.isEmpty()) files.addView(text("No files in the Mac Transfers folder.", 12, false, true), match());
        for (RemoteFile f : items) files.addView(button(f.name + " • " + formatBytes(f.size), () -> beginDownload(f)), buttonParams());
        transferText.setText(items.size() + " file(s) available • tap a file to download.");
    }
    private void beginDownload(RemoteFile f) {
        pendingDownloadName = f.name; pendingDownloadSize = f.size;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); intent.putExtra(Intent.EXTRA_TITLE, f.name); startActivityForResult(intent, REQUEST_DOWNLOAD);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode == REQUEST_UPLOAD) upload(data.getData());
        else if (requestCode == REQUEST_DOWNLOAD) download(data.getData(), pendingDownloadName, pendingDownloadSize);
    }

    private void upload(Uri uri) {
        if (!ready()) return;
        String filename = displayName(uri); long size = fileSize(uri);
        if (filename.isEmpty() || size < 0 || size > MAX_FILE) { toast("Choose a file with a readable name and known size up to 50 MiB."); return; }
        String id = UUID.randomUUID().toString(); transferText.setText("Uploading " + filename + "…");
        io.execute(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IllegalStateException("Could not open selected file.");
                long offset = 0;
                if (size == 0) sendUploadChunk(id, filename, 0, 0, new byte[0]);
                byte[] buffer = new byte[CHUNK];
                while (offset < size) {
                    int count = in.read(buffer, 0, (int) Math.min(buffer.length, size - offset));
                    if (count < 0) break; if (count == 0) continue;
                    byte[] piece = Arrays.copyOf(buffer, count);
                    sendUploadChunk(id, filename, offset, size, piece);
                    offset += count;
                    long progress = offset;
                    main.post(() -> transferText.setText("Uploading " + filename + " • " + formatBytes(progress) + " / " + formatBytes(size)));
                }
                if (offset != size) throw new IllegalStateException("Selected file ended before its reported size.");
                main.post(() -> { transferText.setText("Upload complete: " + filename); toast("File uploaded to Mac."); refreshRemoteFiles(); });
            } catch (Exception e) { main.post(() -> { transferText.setText("Upload failed: " + message(e)); toast("Upload failed: " + message(e)); }); }
        });
    }
    private void sendUploadChunk(String id, String filename, long offset, long total, byte[] data) throws Exception {
        String encoded = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        JSONObject response = new JSONObject(post("/v1/files/upload-chunk", new JSONObject().put("transfer_id", id)
                .put("filename", filename).put("offset", offset).put("total", total).put("data", encoded)).body);
        if (!response.optBoolean("ok", false)) throw new IllegalStateException("Mac rejected an upload chunk.");
        if (offset + data.length == total && !response.optBoolean("complete", false))
            throw new IllegalStateException("Mac did not finalize the uploaded file.");
    }
    private void download(Uri destination, String filename, long expectedSize) {
        if (!ready()) return; transferText.setText("Downloading " + filename + "…");
        io.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(destination, "w")) {
                if (out == null) throw new IllegalStateException("Could not open destination file.");
                long offset = 0;
                while (offset < expectedSize) {
                    JSONObject response = new JSONObject(post("/v1/files/download-chunk",
                            new JSONObject().put("filename", filename).put("offset", offset)).body);
                    if (response.optLong("total", -1) != expectedSize) throw new IllegalStateException("Remote file changed during download.");
                    byte[] chunk = android.util.Base64.decode(response.optString("data", ""), android.util.Base64.DEFAULT);
                    if (chunk.length == 0 || offset + chunk.length > expectedSize) throw new IllegalStateException("Mac returned an invalid file chunk.");
                    out.write(chunk); offset += chunk.length; long progress = offset;
                    main.post(() -> transferText.setText("Downloading " + filename + " • " + formatBytes(progress) + " / " + formatBytes(expectedSize)));
                }
                out.flush();
                main.post(() -> { transferText.setText("Download complete: " + filename); toast("File downloaded."); });
            } catch (Exception e) { main.post(() -> { transferText.setText("Download failed: " + message(e)); toast("Download failed: " + message(e) + ". A partial destination file may remain."); }); }
        });
    }
    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0) return c.getString(i); }
        } catch (Exception ignored) {} return "";
    }
    private long fileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int i = c.getColumnIndex(OpenableColumns.SIZE); if (i >= 0 && !c.isNull(i)) return c.getLong(i); }
        } catch (Exception ignored) {} return -1;
    }

    private void refreshAudioDevices() {
        if (audioText == null) return;
        if (activeHost.isEmpty() || activeToken.isEmpty()) { audioText.setText("Select an active Mac to inspect audio devices."); return; }
        audioText.setText("Reading macOS audio devices…");
        io.execute(() -> {
            try {
                JSONArray array = new JSONObject(get("/v1/audio/devices").body).optJSONArray("devices");
                ArrayList<AudioDevice> out = new ArrayList<>(), in = new ArrayList<>();
                if (array != null) for (int i = 0; i < array.length(); i++) {
                    JSONObject d = array.optJSONObject(i); if (d == null) continue;
                    AudioDevice device = new AudioDevice(d.optLong("id"), d.optString("name", "Audio device"),
                            d.optBoolean("supportsOutput"), d.optBoolean("supportsInput"),
                            d.optBoolean("isDefaultOutput"), d.optBoolean("isDefaultInput"));
                    if (device.output) out.add(device); if (device.input) in.add(device);
                }
                main.post(() -> {
                    outputs.clear(); outputs.addAll(out); inputs.clear(); inputs.addAll(in);
                    audioText.setText("Found " + out.size() + " output and " + in.size() + " input device(s).");
                    updateAudioLabels();
                });
            } catch (Exception e) { main.post(() -> audioText.setText("Audio devices unavailable: " + message(e))); }
        });
    }
    private void updateAudioLabels() {
        AudioDevice out = currentDefault(outputs, true), in = currentDefault(inputs, false);
        outputText.setText("Output: " + (out == null ? "none detected" : out.name));
        inputText.setText("Input: " + (in == null ? "none detected" : in.name));
    }
    private AudioDevice currentDefault(ArrayList<AudioDevice> list, boolean output) {
        for (AudioDevice d : list) if (output ? d.defaultOutput : d.defaultInput) return d;
        return list.isEmpty() ? null : list.get(0);
    }
    private void chooseAudio(boolean output) {
        ArrayList<AudioDevice> list = output ? outputs : inputs;
        if (list.isEmpty()) { refreshAudioDevices(); toast("Refresh audio devices and try again."); return; }
        String[] names = new String[list.size()]; int selected = 0;
        for (int i = 0; i < list.size(); i++) {
            AudioDevice d = list.get(i); names[i] = d.name + ((output ? d.defaultOutput : d.defaultInput) ? " • Current" : "");
            if (output ? d.defaultOutput : d.defaultInput) selected = i;
        }
        new AlertDialog.Builder(this).setTitle(output ? "Choose output device" : "Choose input device")
                .setSingleChoiceItems(names, selected, (dialog, which) -> {
                    dialog.dismiss(); setAudioDevice(list.get(which), output ? "output" : "input");
                }).setNegativeButton("Cancel", null).show();
    }
    private void setAudioDevice(AudioDevice device, String direction) {
        if (!ready()) return; audioText.setText("Changing " + direction + " device…");
        io.execute(() -> {
            try {
                post("/v1/audio/device", new JSONObject().put("id", device.id).put("direction", direction));
                main.post(() -> { audioText.setText("Default " + direction + " device updated."); refreshAudioDevices(); });
            } catch (Exception e) { main.post(() -> { audioText.setText("Audio change failed: " + message(e)); toast("Audio selection failed: " + message(e)); }); }
        });
    }

    private String formatBytes(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.US, "%.1f KiB", size / 1024.0);
        return String.format(Locale.US, "%.1f MiB", size / (1024.0 * 1024.0));
    }
    private String message(Throwable e) {
        String m = e == null ? null : e.getMessage();
        return m == null || m.trim().isEmpty() ? "Unknown error" : m;
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy() { io.shutdownNow(); main.removeCallbacksAndMessages(null); super.onDestroy(); }

    private static final class RemoteFile {
        final String name; final long size;
        RemoteFile(String name, long size) { this.name = name; this.size = size; }
    }
    private static final class AudioDevice {
        final long id; final String name;
        final boolean output, input, defaultOutput, defaultInput;
        AudioDevice(long id, String name, boolean output, boolean input, boolean defaultOutput, boolean defaultInput) {
            this.id = id; this.name = name; this.output = output; this.input = input;
            this.defaultOutput = defaultOutput; this.defaultInput = defaultInput;
        }
    }
}
