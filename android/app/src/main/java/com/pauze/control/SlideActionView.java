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
 * Shared action-row control.
 *
 * All remote actions use one consistent Apple-inspired toggle surface.
 * Persistent controls keep their ON/OFF state; one-shot actions briefly
 * animate ON, run the command, and return to OFF automatically.
 */
public final class SlideActionView extends FrameLayout {

    public interface OnSlideCompleteListener {
        void onSlideComplete(SlideActionView view);
    }

    private static final int SURFACE = Color.rgb(16, 22, 34);
    private static final int BORDER = Color.rgb(38, 49, 69);
    private static final int TEXT = Color.WHITE;
    private static final int SUBTLE = Color.rgb(148, 157, 174);
    private static final int SWITCH_OFF = Color.rgb(62, 68, 79);
    private static final int SWITCH_ON = Color.rgb(52, 199, 89);

    private final TextView labelView;
    private final View switchTrack;
    private final View switchThumb;

    private OnSlideCompleteListener listener;
    private boolean toggleControl;
    private boolean persistentControl;
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
        setMinimumHeight(dp(60));
        setPadding(
                dp(16),
                dp(7),
                dp(12),
                dp(7)
        );

        GradientDrawable background = new GradientDrawable();
        background.setColor(SURFACE);
        background.setCornerRadius(dp(17));
        background.setStroke(dp(1), BORDER);
        setBackground(background);

        labelView = new TextView(context);
        labelView.setGravity(Gravity.CENTER_VERTICAL);
        labelView.setTextColor(TEXT);
        labelView.setTextSize(14);
        labelView.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD
        );
        labelView.setSingleLine(true);

        LayoutParams labelParams = new LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
        );
        labelParams.rightMargin = dp(70);
        addView(labelView, labelParams);

        switchTrack = new View(context);
        LayoutParams trackParams = new LayoutParams(
                dp(54),
                dp(32)
        );
        trackParams.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        addView(switchTrack, trackParams);

        switchThumb = new View(context);
        LayoutParams thumbParams = new LayoutParams(
                dp(26),
                dp(26)
        );
        thumbParams.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        thumbParams.rightMargin = dp(3);
        addView(switchThumb, thumbParams);

        switchThumb.setElevation(dp(2));

        post(this::configureMode);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        configureMode();
    }

    public void setText(String text) {
        currentText = text == null ? "" : text;
        labelView.setText(currentText);
        configureMode();
    }

    public void setChecked(boolean value) {
        checked = value;
        updateSwitchVisuals();
    }

    public boolean isChecked() {
        return checked;
    }

    public void setOnSlideCompleteListener(
            OnSlideCompleteListener listener
    ) {
        this.listener = listener;

        setOnClickListener(view -> {
            if (!toggleControl) {
                animatePress(
                        () -> {
                            if (this.listener != null) {
                                this.listener.onSlideComplete(this);
                            }
                        }
                );
                return;
            }

            if (persistentControl) {
                checked = !checked;
                updateSwitchVisuals();

                animatePress(
                        () -> {
                            if (this.listener != null) {
                                this.listener.onSlideComplete(this);
                            }
                        }
                );
                return;
            }

            checked = true;
            updateSwitchVisuals();

            animatePress(
                    () -> {
                        if (this.listener != null) {
                            this.listener.onSlideComplete(this);
                        }

                        postDelayed(
                                () -> {
                                    checked = false;
                                    updateSwitchVisuals();
                                },
                                260L
                        );
                    }
            );
        });
    }

    private void configureMode() {
        int id = getId();

        toggleControl =
                id == R.id.saveButton ||
                id == R.id.statusButton ||
                id == R.id.restrictionCard ||
                id == R.id.lockButton ||
                id == R.id.sleepButton ||
                id == R.id.restartButton ||
                id == R.id.shutdownButton ||
                id == R.id.muteButton;

        persistentControl =
                id == R.id.restrictionCard ||
                id == R.id.muteButton;

        labelView.setText(
                currentText
        );

        switchTrack.setVisibility(
                toggleControl
                        ? VISIBLE
                        : INVISIBLE
        );

        switchThumb.setVisibility(
                toggleControl
                        ? VISIBLE
                        : INVISIBLE
        );

        if (toggleControl) {
            setContentDescription(
                    persistentControl
                            ? "Toggle " + currentText
                            : "Activate " + currentText
            );
            updateSwitchVisuals();
        }
    }

    private void updateSwitchVisuals() {
        if (!toggleControl) {
            return;
        }

        GradientDrawable track = new GradientDrawable();
        track.setColor(
                checked
                        ? SWITCH_ON
                        : SWITCH_OFF
        );
        track.setCornerRadius(dp(18));
        switchTrack.setBackground(track);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(Color.WHITE);
        thumb.setShape(GradientDrawable.OVAL);
        switchThumb.setBackground(thumb);

        switchThumb.setTranslationX(
                checked
                        ? -dp(23)
                        : 0
        );
    }

    private void animatePress(Runnable endAction) {
        animate()
                .scaleX(0.985f)
                .scaleY(0.985f)
                .alpha(0.92f)
                .setDuration(70)
                .withEndAction(
                        () -> animate()
                                .scaleX(1f)
                                .scaleY(1f)
                                .alpha(1f)
                                .setDuration(160)
                                .withEndAction(endAction)
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
