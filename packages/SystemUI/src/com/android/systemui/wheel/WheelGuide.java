/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.android.systemui.res.R;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The wheel UI's guidance in SystemUI. The legend is a strip by the wheel naming what a turn, a
 * press and a hold do in the wheel's mode, shown for a moment when the mode changes, or kept
 * (WHEEL_GUIDE_LEGEND). Hints are tips shown once, the first time something new is possible
 * (WHEEL_GUIDE_HINTS), remembered in WHEEL_GUIDE_HINTS_SEEN. Neither takes keys or touches.
 */
final class WheelGuide {

    static final String HINT_TASKBAR = "taskbar";
    static final String HINT_POWER = "power";
    static final String HINT_SHADE = "shade";

    static final int LEGEND_OFF = 0;
    static final int LEGEND_ON_CHANGE = 1;
    static final int LEGEND_ALWAYS = 2;

    private static final long LEGEND_MS = 2500;
    private static final long HINT_MS = 5000;
    private static final long FADE_MS = 150;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler;
    private final IntSupplier mUserId;
    private final Runnable mHide = this::hide;

    private TextView mStrip;
    /** The mode the legend last named, so it shows on a change only. */
    private String mLegendMode;
    /** A hint showing: the legend waits for it. */
    private boolean mHinting;

    WheelGuide(Context context, WindowManager windowManager, Handler handler, IntSupplier userId) {
        mContext = context;
        mWindowManager = windowManager;
        mHandler = handler;
        mUserId = userId;
    }

    /**
     * The wheel's mode in an app ({@code null} while the bar, the shade or the lock screen has
     * the wheel, where the guide stays out of the way), the strip at the wheel's {@code gravity}.
     */
    void onMode(String mode, int gravity) {
        if (mode == null) {
            if (!mHinting) {
                hide();
            }
            return;
        }
        final int legend = getInt(LineageSettings.Secure.WHEEL_GUIDE_LEGEND, LEGEND_ON_CHANGE);
        final boolean changed = !mode.equals(mLegendMode);
        mLegendMode = mode;
        if (legend == LEGEND_OFF || mHinting || (legend == LEGEND_ON_CHANGE && !changed)) {
            return;
        }
        show(legendText(mode), gravity, legend == LEGEND_ALWAYS ? 0 : LEGEND_MS);
    }

    /** The hint {@code id}'s text, unless it was shown before or hints are off. */
    void hint(String id, int text, int gravity) {
        if (getInt(LineageSettings.Secure.WHEEL_GUIDE_HINTS, 1) == 0) {
            return;
        }
        final ContentResolver resolver = mContext.getContentResolver();
        final String seen = LineageSettings.Secure.getStringForUser(resolver,
                LineageSettings.Secure.WHEEL_GUIDE_HINTS_SEEN, mUserId.getAsInt());
        final List<String> ids = TextUtils.isEmpty(seen) ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(seen.split(",")));
        if (ids.contains(id)) {
            return;
        }
        ids.add(id);
        LineageSettings.Secure.putStringForUser(resolver,
                LineageSettings.Secure.WHEEL_GUIDE_HINTS_SEEN, TextUtils.join(",", ids),
                mUserId.getAsInt());
        mHinting = true;
        show(mContext.getString(text), gravity, HINT_MS);
    }

    void hide() {
        mHandler.removeCallbacks(mHide);
        mHinting = false;
        final TextView strip = mStrip;
        if (strip == null) {
            return;
        }
        mStrip = null;
        strip.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> mWindowManager.removeViewImmediate(strip));
    }

    private void show(CharSequence text, int gravity, long forMs) {
        mHandler.removeCallbacks(mHide);
        if (mStrip == null) {
            mStrip = makeStrip();
            mWindowManager.addView(mStrip, layoutParams(gravity));
            mStrip.setAlpha(0f);
            mStrip.animate().alpha(1f).setDuration(FADE_MS);
        } else {
            mWindowManager.updateViewLayout(mStrip, layoutParams(gravity));
        }
        mStrip.setText(text);
        if (forMs > 0) {
            mHandler.postDelayed(mHide, forMs);
        }
    }

    private TextView makeStrip() {
        final TextView strip = new TextView(mContext);
        final float density = mContext.getResources().getDisplayMetrics().density;
        strip.setTextColor(mContext.getColor(com.android.internal.R.color.materialColorOnSurface));
        strip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        strip.setLineSpacing(0f, 1.1f);
        strip.setMaxWidth(Math.round(220 * density));
        strip.setPadding(Math.round(12 * density), Math.round(8 * density),
                Math.round(12 * density), Math.round(8 * density));
        final GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(16 * density);
        pill.setColor(mContext.getColor(
                com.android.internal.R.color.materialColorSurfaceContainerHigh));
        strip.setBackground(pill);
        strip.setElevation(4 * density);
        strip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return strip;
    }

    /** Beside the wheel, over the app, taking no keys or touches. */
    private WindowManager.LayoutParams layoutParams(int gravity) {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        final boolean across = gravity == Gravity.TOP || gravity == Gravity.BOTTOM;
        lp.gravity = gravity | (across ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        final int margin = Math.round(8 * mContext.getResources().getDisplayMetrics().density);
        lp.x = across ? 0 : margin;
        lp.y = across ? margin : 0;
        lp.setTitle("WheelGuide");
        lp.setFitInsetsTypes(0);
        return lp;
    }

    /** What a turn, a press and a hold do in {@code mode}, a line each. */
    private String legendText(String mode) {
        final int turn;
        int press = R.string.wheel_legend_press_select;
        boolean hold = false;
        switch (mode) {
            case "app":
                turn = R.string.wheel_legend_turn_move;
                hold = true;
                break;
            case "scroll":
                turn = R.string.wheel_legend_turn_scroll;
                break;
            case "tab":
                turn = R.string.wheel_legend_turn_tab;
                break;
            case "arrows":
                turn = R.string.wheel_legend_turn_arrows;
                break;
            case "page":
                turn = R.string.wheel_legend_turn_page;
                break;
            case "volume":
                turn = R.string.wheel_legend_turn_volume;
                press = 0;
                break;
            case "brightness":
                turn = R.string.wheel_legend_turn_brightness;
                press = 0;
                break;
            case "media":
                turn = R.string.wheel_legend_turn_media;
                press = R.string.wheel_legend_press_play;
                break;
            case "zoom":
                // The press is Enter, as a camera's shutter
                turn = R.string.wheel_legend_turn_zoom;
                break;
            case "pinch":
                turn = R.string.wheel_legend_turn_zoom;
                press = 0;
                break;
            case "taskbar":
                turn = R.string.wheel_legend_turn_taskbar;
                press = 0;
                break;
            default:
                turn = mode.startsWith("touch") ? R.string.wheel_legend_turn_touch
                        : R.string.wheel_legend_turn_move;
                press = 0;
                break;
        }
        final StringBuilder text = new StringBuilder(mContext.getString(turn));
        if (press != 0) {
            text.append('\n').append(mContext.getString(press));
        }
        if (hold) {
            text.append('\n').append(mContext.getString(R.string.wheel_legend_hold_options));
        }
        text.append('\n').append(mContext.getString(R.string.wheel_legend_double_taskbar));
        return text.toString();
    }

    private int getInt(String name, int def) {
        return LineageSettings.Secure.getIntForUser(mContext.getContentResolver(), name, def,
                mUserId.getAsInt());
    }
}
