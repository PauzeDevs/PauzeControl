// PauzeControl — Copyright (c) 2026 PauzeDevs. All rights reserved.
package com.pauze.control;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;

public final class Personalization {
    private static final String PREFS = "pauze_personalization";
    private static final int INK = Color.rgb(40, 33, 30);
    private static final int MUTED = Color.rgb(133, 117, 106);
    private static final int TERRACOTTA = Color.rgb(149, 87, 64);
    private static final int PAPER_WHITE = Color.rgb(248, 240, 232);

    public static final class Palette {
        public final int background, surface, foreground, muted, accent;
        Palette(int background, int surface, int foreground, int muted, int accent) {
            this.background = background; this.surface = surface; this.foreground = foreground;
            this.muted = muted; this.accent = accent;
        }
    }
    private Personalization() {}

    public static SharedPreferences preferences(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int accentColor(String value) {
        if ("blue".equals(value)) return Color.rgb(57, 119, 213);
        if ("violet".equals(value)) return Color.rgb(128, 90, 213);
        if ("emerald".equals(value)) return Color.rgb(39, 133, 102);
        return TERRACOTTA;
    }

    public static Palette palette(Context c) {
        SharedPreferences p = preferences(c);
        int accent = accentColor(p.getString("accent", "terracotta"));
        String theme = p.getString("theme", "warm");
        if ("midnight".equals(theme)) return new Palette(
                Color.rgb(16, 20, 27), Color.rgb(28, 35, 46),
                Color.rgb(241, 244, 248), Color.rgb(162, 173, 186), accent);
        if ("ocean".equals(theme)) return new Palette(
                Color.rgb(234, 243, 247), Color.rgb(248, 252, 255),
                Color.rgb(23, 42, 56), Color.rgb(93, 119, 136), accent);
        return new Palette(Color.rgb(243, 234, 225), Color.rgb(255, 249, 242), INK, MUTED, accent);
    }

    public static int foregroundColor(Context c) { return palette(c).foreground; }
    public static int mutedColor(Context c) { return palette(c).muted; }
    public static int surfaceColor(Context c) { return palette(c).surface; }
    public static int accent(Context c) { return palette(c).accent; }

    public static void apply(Activity activity) {
        if (activity == null) return;
        View root = activity.findViewById(R.id.rootContainer);
        if (root == null) root = activity.findViewById(android.R.id.content);
        if (root == null) return;
        Palette p = palette(activity);
        boolean warm = "warm".equals(preferences(activity).getString("theme", "warm"));
        if (warm && activity instanceof MainActivity) root.setBackgroundResource(R.drawable.bg_screen);
        else root.setBackgroundColor(p.background);
        applyTree(activity, root, p, warm);

        View content = activity.findViewById(R.id.content);
        if (content != null) {
            boolean compact = preferences(activity).getBoolean("compact", false);
            int horizontal = dp(activity, compact ? 12 : 18);
            content.setPadding(horizontal, dp(activity, compact ? 14 : 20),
                    horizontal, dp(activity, compact ? 24 : 36));
        }
        View navBar = activity.findViewById(R.id.navBar);
        if (navBar != null) navBar.setBackgroundColor(p.surface);
    }

    private static void applyTree(Activity activity, View v, Palette p, boolean warm) {
        boolean surface = Boolean.TRUE.equals(v.getTag(R.id.pauzeSurfaceView)) || isSurface(v.getId());
        boolean accent = Boolean.TRUE.equals(v.getTag(R.id.pauzeAccentView));
        if (surface) v.setBackground(roundRect(activity, p.surface, p.muted));
        if (accent) v.setBackground(roundRect(activity, p.accent, p.accent));
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            Object saved = v.getTag(R.id.pauzeOriginalTextColor);
            int original;
            if (saved instanceof Integer) original = (Integer) saved;
            else {
                original = t.getCurrentTextColor();
                v.setTag(R.id.pauzeOriginalTextColor, original);
            }
            if (accent) t.setTextColor(Color.WHITE);
            else t.setTextColor(mapText(original, p));
        }
        if (v instanceof SeekBar) {
            ((SeekBar) v).setProgressTintList(ColorStateList.valueOf(p.accent));
            ((SeekBar) v).setThumbTintList(ColorStateList.valueOf(p.accent));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) applyTree(activity, g.getChildAt(i), p, warm);
        }
    }

    private static int mapText(int color, Palette p) {
        if (near(color, TERRACOTTA) || (Color.red(color) > Color.green(color) * 1.12
                && Color.red(color) > Color.blue(color) * 1.12)) return p.accent;
        if (near(color, MUTED)) return p.muted;
        if (near(color, PAPER_WHITE)) return p.foreground;
        int y = (int) (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color));
        return (y < 90 || y > 205) ? p.foreground : p.muted;
    }

    private static boolean near(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) < 18
                && Math.abs(Color.green(a) - Color.green(b)) < 18
                && Math.abs(Color.blue(a) - Color.blue(b)) < 18;
    }

    private static boolean isSurface(int id) {
        return id == R.id.statusCard || id == R.id.systemCard || id == R.id.powerCard
                || id == R.id.activityCard || id == R.id.hostInput || id == R.id.tokenInput
                || id == R.id.connectionPill;
    }

    public static GradientDrawable roundRect(Context c, int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(c, 18));
        d.setStroke(dp(c, 1), Color.argb(46, Color.red(stroke), Color.green(stroke), Color.blue(stroke)));
        return d;
    }

    public static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }
}
