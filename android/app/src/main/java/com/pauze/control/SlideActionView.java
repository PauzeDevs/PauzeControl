// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Shared remote-control surface.
 *
 * Toggle-style controls (restriction and mute) use an Apple-inspired
 * ON/OFF switch. One-shot commands use a normal pressable button.
 */
public final class SlideActionView extends FrameLayout {

    public interface OnSlideCompleteListener {
        void onSlideComplete(SlideActionView view);
    }

    private static final int TRACK = Color.rgb(18, 26, 42);
    private static final int BORDER = Color.rgb(45, 60, 84);
    private static final int TEXT = Color.WHITE;
    private static final int SWITCH_OFF = Color.rgb(68, 76, 91);
    private static final int SWITCH_ON = Color.rgb(52, 199, 89);

    private final TextView labelView;
    private final View switchTrack;
    private final View switchThumb;

    private OnSlideCompleteListener listener;
    private boolean toggleControl;
    private boolean checked;
    private String currentText = "";

    public SlideActionView(Context context) {
        this(context, null);
    }

    public SlideActionView(
            Context context,
            AttributeSet attrs
    ) {
        this(context, attrs, 0);
    }

    public SlideActionView(
            Context context,
            AttributeSet attrs,
            int defStyleAttr
    ) {
        super(context, attrs, defStyleAttr);

        setClickable(true);
        setFocusable(true);
        setClipChildren(false);
        setPadding(
                dp(14),
                dp(6),
                dp(12),
                dp(6)
        );

        GradientDrawable background =
                new GradientDrawable();
        background.setColor(TRACK);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), BORDER);
        setBackground(background);

        labelView = new TextView(context);
        labelView.setGravity(Gravity.CENTER_VERTICAL);
        labelView.setTextColor(TEXT);
        labelView.setTextSize(13);
        labelView.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD
        );

        LayoutParams labelParams =
                new LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                );
        addView(labelView, labelParams);

        switchTrack = new View(context);
        switchTrack.setVisibility(INVISIBLE);
        LayoutParams trackParams =
                new LayoutParams(
                        dp(50),
                        dp(30)
                );
        trackParams.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        addView(switchTrack, trackParams);

        switchThumb = new View(context);
        switchThumb.setVisibility(INVISIBLE);
        LayoutParams thumbParams =
                new LayoutParams(
                        dp(24),
                        dp(24)
                );
        thumbParams.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        thumbParams.rightMargin = dp(3);
        addView(switchThumb, thumbParams);

        post(this::configureMode);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        configureMode();
    }

    public void setText(String text) {
        currentText = text == null ? "" : text;

        if (toggleControl) {
            checked = textIndicatesOn(currentText);
            labelView.setText(toggleLabel(currentText));
            updateSwitchVisuals();
        } else {
            labelView.setText(normalizeActionLabel(currentText));
        }
    }

    public void setOnSlideCompleteListener(
            OnSlideCompleteListener listener
    ) {
        this.listener = listener;
        setOnClickListener(v -> {
            if (toggleControl) {
                checked = !checked;
                updateSwitchVisuals();
            }

            animatePress();

            if (this.listener != null) {
                this.listener.onSlideComplete(this);
            }
        });
    }

    private void configureMode() {
        int id = getId();
        toggleControl =
                id == R.id.restrictionCard ||
                id == R.id.muteButton;

        if (toggleControl) {
            labelView.setPadding(
                    0,
                    0,
                    dp(64),
                    0
            );
            switchTrack.setVisibility(VISIBLE);
            switchThumb.setVisibility(VISIBLE);
            setContentDescription(
                    id == R.id.restrictionCard
                            ? "Mac restriction switch"
                            : "Mac mute switch"
            );
            checked = textIndicatesOn(currentText);
            updateSwitchVisuals();
        } else {
            labelView.setPadding(0, 0, 0, 0);
            switchTrack.setVisibility(INVISIBLE);
            switchThumb.setVisibility(INVISIBLE);
        }

        if (!currentText.isEmpty()) {
            labelView.setText(
                    toggleControl
                            ? toggleLabel(currentText)
                            : normalizeActionLabel(currentText)
            );
        }
    }

    private String normalizeActionLabel(String text) {
        return text
                .replaceFirst(
                        "(?i)^\\s*SLIDE\\s+TO\\s+",
                        ""
                )
                .trim();
    }

    private boolean textIndicatesOn(String text) {
        String value = text == null
                ? ""
                : text.toUpperCase();

        if (getId() == R.id.restrictionCard) {
            return value.contains("ALLOW");
        }

        if (getId() == R.id.muteButton) {
            return value.contains("UNMUTE");
        }

        return false;
    }

    private String toggleLabel(String text) {
        if (getId() == R.id.restrictionCard) {
            return "MAC RESTRICTION";
        }

        if (getId() == R.id.muteButton) {
            return "MUTE AUDIO";
        }

        return text;
    }

    private void updateSwitchVisuals() {
        if (!toggleControl) {
            return;
        }

        GradientDrawable track =
                new GradientDrawable();
        track.setColor(
                checked
                        ? SWITCH_ON
                        : SWITCH_OFF
        );
        track.setCornerRadius(dp(16));
        switchTrack.setBackground(track);

        GradientDrawable thumb =
                new GradientDrawable();
        thumb.setColor(Color.WHITE);
        thumb.setShape(GradientDrawable.OVAL);
        switchThumb.setBackground(thumb);

        switchThumb.setTranslationX(
                checked
                        ? -dp(23)
                        : 0
        );
    }

    private void animatePress() {
        animate()
                .scaleX(0.985f)
                .scaleY(0.985f)
                .alpha(0.9f)
                .setDuration(70)
                .withEndAction(
                        () -> animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(160)
                                .start()
                )
                .start();
    }

    private int dp(int value) {
        return Math.round(
                value *
                        getResources()
                                .getDisplayMetrics()
                                .density
        );
    }
}
