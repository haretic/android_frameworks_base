/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.WheelCue;

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
    /** At most one legend or hint this often: opening apps one after another changes modes. */
    private static final long COOLDOWN_MS = 5000;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler;
    private final IntSupplier mUserId;
    private final Runnable mHide = this::hide;

    private View mStrip;
    /** The mode the legend last named, so it shows on a change only. */
    private String mLegendMode;
    /** A hint showing: the legend waits for it. */
    private boolean mHinting;
    /** When the last legend or hint showed, uptime, for the cooldown. */
    private long mLastShown = -COOLDOWN_MS;

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
        // Always shown, it follows the mode; on a change, not so soon after the last
        if (legend == LEGEND_ON_CHANGE && coolingDown()) {
            return;
        }
        final List<Integer> gestures = new ArrayList<>();
        final List<CharSequence> texts = new ArrayList<>();
        legend(mode, gestures, texts);
        show(toArray(gestures), texts.toArray(new CharSequence[0]), gravity,
                legend == LEGEND_ALWAYS ? 0 : LEGEND_MS, 0);
    }

    /**
     * The hint {@code id}: what each of {@code gestures} (WheelCue's) does, {@code texts} a word
     * or two each, unless it was shown before or hints are off; {@code inset} further in from
     * the {@code gravity} edge, past the bar.
     */
    void hint(String id, int[] gestures, int[] texts, int gravity, int inset) {
        if (getInt(LineageSettings.Secure.WHEEL_GUIDE_HINTS, 1) == 0) {
            return;
        }
        final ContentResolver resolver = mContext.getContentResolver();
        final String seen = LineageSettings.Secure.getStringForUser(resolver,
                LineageSettings.Secure.WHEEL_GUIDE_HINTS_SEEN, mUserId.getAsInt());
        final List<String> ids = TextUtils.isEmpty(seen) ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(seen.split(",")));
        // Not marked seen while cooling down: it shows the next time it applies
        if (ids.contains(id) || coolingDown()) {
            return;
        }
        ids.add(id);
        LineageSettings.Secure.putStringForUser(resolver,
                LineageSettings.Secure.WHEEL_GUIDE_HINTS_SEEN, TextUtils.join(",", ids),
                mUserId.getAsInt());
        mHinting = true;
        final CharSequence[] words = new CharSequence[texts.length];
        for (int i = 0; i < texts.length; i++) {
            words[i] = mContext.getText(texts[i]);
        }
        show(gestures, words, gravity, HINT_MS, inset);
    }

    private boolean coolingDown() {
        return SystemClock.uptimeMillis() - mLastShown < COOLDOWN_MS;
    }

    void hide() {
        mHandler.removeCallbacks(mHide);
        mHinting = false;
        final View strip = mStrip;
        if (strip == null) {
            return;
        }
        mStrip = null;
        strip.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction(() -> mWindowManager.removeViewImmediate(strip));
    }

    private void show(int[] gestures, CharSequence[] texts, int gravity, long forMs, int inset) {
        mHandler.removeCallbacks(mHide);
        mLastShown = SystemClock.uptimeMillis();
        final boolean shown = mStrip != null;
        if (shown) {
            mStrip.animate().cancel();
            mWindowManager.removeViewImmediate(mStrip);
        }
        // Pointing at the wheel, or at the bar it is beside
        final int pointTo = inset > 0 || gravity == wheelEdge() ? gravity : 0;
        mStrip = WheelCue.card(mContext, gestures, texts, pointTo);
        mWindowManager.addView(mStrip, layoutParams(gravity, inset));
        if (!shown) {
            mStrip.setAlpha(0f);
            mStrip.animate().alpha(1f).setDuration(FADE_MS);
        }
        if (forMs > 0) {
            mHandler.postDelayed(mHide, forMs);
        }
    }

    /**
     * How far the wheel's middle is from the middle of the screen's edge at {@code gravity},
     * along it, where the wheel is on that edge: the strip goes level with the wheel, not with
     * the screen's middle. The wheel is on the right in portrait, its middle
     * config_wheelCentre down (debug.r1.wheel_centre, in thousandths, while tuning it).
     */
    private int wheelOffset(int gravity) {
        final int rotation = mWindowManager.getDefaultDisplay().getRotation();
        if (gravity != wheelEdge()) {
            return 0;
        }
        final int tuned = SystemProperties.getInt("debug.r1.wheel_centre", 0);
        float centre = tuned > 0 ? tuned / 1000f : mContext.getResources().getFraction(
                com.android.internal.R.fraction.config_wheelCentre, 1, 1);
        // Turned over, the wheel's middle is as far from the other end
        if (rotation == Surface.ROTATION_180 || rotation == Surface.ROTATION_270) {
            centre = 1 - centre;
        }
        final Rect screen = mWindowManager.getCurrentWindowMetrics().getBounds();
        final int length = gravity == Gravity.TOP || gravity == Gravity.BOTTOM
                ? screen.width() : screen.height();
        return Math.round((centre - 0.5f) * length);
    }

    /** The screen's edge the wheel is on at its rotation: the right in portrait. */
    private int wheelEdge() {
        return switch (mWindowManager.getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90 -> Gravity.TOP;
            case Surface.ROTATION_180 -> Gravity.LEFT;
            case Surface.ROTATION_270 -> Gravity.BOTTOM;
            default -> Gravity.RIGHT;
        };
    }

    /** Beside the wheel, over the app, taking no keys or touches. */
    private WindowManager.LayoutParams layoutParams(int gravity, int inset) {
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
        final int margin = inset
                + Math.round(8 * mContext.getResources().getDisplayMetrics().density);
        final int along = wheelOffset(gravity);
        lp.x = across ? along : margin;
        lp.y = across ? margin : along;
        lp.setTitle("WheelGuide");
        lp.setFitInsetsTypes(0);
        return lp;
    }

    /** What a turn, a press, a hold and a double press do in {@code mode}, a row each. */
    private void legend(String mode, List<Integer> gestures, List<CharSequence> texts) {
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
            case "camera":
                turn = R.string.wheel_legend_turn_zoom;
                press = R.string.wheel_legend_press_photo;
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
        gestures.add(WheelCue.TURN);
        texts.add(mContext.getText(turn));
        if (press != 0) {
            gestures.add(WheelCue.PRESS);
            texts.add(mContext.getText(press));
        }
        if (hold) {
            gestures.add(WheelCue.HOLD);
            texts.add(mContext.getText(R.string.wheel_legend_hold_options));
        }
        gestures.add(WheelCue.DOUBLE);
        texts.add(mContext.getText(R.string.wheel_legend_double_taskbar));
    }

    private int getInt(String name, int def) {
        return LineageSettings.Secure.getIntForUser(mContext.getContentResolver(), name, def,
                mUserId.getAsInt());
    }

    private static int[] toArray(List<Integer> list) {
        final int[] array = new int[list.size()];
        for (int i = 0; i < array.length; i++) {
            array[i] = list.get(i);
        }
        return array;
    }
}
