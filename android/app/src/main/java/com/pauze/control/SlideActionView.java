// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.

package com.pauze.control;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Compact slide-to-action control used for remote Mac commands.
 * The action fires only after the thumb is deliberately dragged across
 * the completion threshold, which helps prevent accidental remote commands.
 */
public final class SlideActionView extends FrameLayout {

    public interface OnSlideCompleteListener {
        void onSlideComplete(SlideActionView view);
    }

    private static final int TRACK = Color.rgb(18, 26, 42);
    private static final int BORDER = Color.rgb(45, 60, 84);
    private static final int THUMB = Color.rgb(124, 92, 255);
    private static final int TEXT = Color.WHITE;

    private final TextView labelView;
    private final TextView thumbView;

    private OnSlideCompleteListener listener;
    private float downX;
    private float startTranslationX;
    private boolean dragging;

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

        setWillNotDraw(false);
        setClickable(true);
        setFocusable(true);
        setClipChildren(true);
        setPadding(
                dp(6),
                dp(6),
                dp(6),
                dp(6)
        );

        GradientDrawable background =
                new GradientDrawable();
        background.setColor(TRACK);
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), BORDER);
        setBackground(background);

        labelView = new TextView(context);
        labelView.setGravity(Gravity.CENTER);
        labelView.setTextColor(TEXT);
        labelView.setTextSize(12);
        labelView.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD
        );

        LayoutParams labelParams =
                new LayoutParams(
                        LayoutParams.MATCH_PARENT,
                        LayoutParams.MATCH_PARENT
                );
        addView(labelView, labelParams);

        thumbView = new TextView(context);
        thumbView.setText("→");
        thumbView.setGravity(Gravity.CENTER);
        thumbView.setTextColor(Color.WHITE);
        thumbView.setTextSize(20);
        thumbView.setTypeface(
                android.graphics.Typeface.DEFAULT_BOLD
        );

        GradientDrawable thumbBackground =
                new GradientDrawable();
        thumbBackground.setColor(THUMB);
        thumbBackground.setCornerRadius(dp(16));
        thumbView.setBackground(thumbBackground);

        LayoutParams thumbParams =
                new LayoutParams(
                        dp(44),
                        dp(44)
                );
        thumbParams.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        addView(thumbView, thumbParams);

        thumbView.setOnTouchListener(
                (view, event) -> handleThumbTouch(event)
        );
    }

    public void setText(String text) {
        labelView.setText(text);
    }

    public void setOnSlideCompleteListener(
            OnSlideCompleteListener listener
    ) {
        this.listener = listener;
    }

    private boolean handleThumbTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getRawX();
                startTranslationX = thumbView.getTranslationX();
                dragging = true;
                return true;

            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    return false;
                }

                float delta =
                        event.getRawX() - downX;

                float maxTravel =
                        Math.max(
                                0,
                                getWidth()
                                        - getPaddingLeft()
                                        - getPaddingRight()
                                        - thumbView.getWidth()
                                        - dp(2)
                        );

                float next =
                        clamp(
                                startTranslationX + delta,
                                0,
                                maxTravel
                        );

                thumbView.setTranslationX(next);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging) {
                    return false;
                }

                dragging = false;

                float max =
                        Math.max(
                                1,
                                getWidth()
                                        - getPaddingLeft()
                                        - getPaddingRight()
                                        - thumbView.getWidth()
                                        - dp(2)
                        );

                float progress =
                        thumbView.getTranslationX() / max;

                if (progress >= 0.78f &&
                        event.getActionMasked() == MotionEvent.ACTION_UP) {
                    OnSlideCompleteListener callback = listener;

                    thumbView.animate()
                            .translationX(max)
                            .setDuration(90)
                            .withEndAction(
                                    () -> {
                                        if (callback != null) {
                                            callback.onSlideComplete(this);
                                        }

                                        resetThumb();
                                    }
                            )
                            .start();
                } else {
                    resetThumb();
                }

                return true;

            default:
                return false;
        }
    }

    private void resetThumb() {
        thumbView.animate()
                .translationX(0)
                .setDuration(220)
                .start();
    }

    private static float clamp(
            float value,
            float min,
            float max
    ) {
        return Math.max(min, Math.min(max, value));
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
