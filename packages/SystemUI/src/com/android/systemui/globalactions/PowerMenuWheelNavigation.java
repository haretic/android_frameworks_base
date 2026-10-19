/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.globalactions;

import android.app.Dialog;
import android.content.DialogInterface;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.android.systemui.res.R;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The power menu on a device with a scroll wheel and one side button
 * (config_powerMenuWheelNavigation): the wheel, which sends DPAD up and down, walks the focus
 * through the buttons and then the pinned apps in reading order, round and round, with a
 * haptic tick per step; the side button, which is the power key, presses the focused one
 * instead of turning the screen off (INPUT_FEATURE_POWER_KEY_SELECTS).
 */
final class PowerMenuWheelNavigation implements DialogInterface.OnKeyListener {

    private final Window mWindow;
    /** Whether the wheel moved the focus yet, see move(). */
    private boolean mMoved;

    private PowerMenuWheelNavigation(Window window) {
        mWindow = window;
    }

    /** Call before the dialog shows, the policy reads the window's flags when it gets focus. */
    static void attach(Dialog dialog) {
        if (!dialog.getContext().getResources().getBoolean(
                R.bool.config_powerMenuWheelNavigation)) {
            return;
        }
        final Window window = dialog.getWindow();
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        window.setAttributes(lp);
        dialog.setOnKeyListener(new PowerMenuWheelNavigation(window));
    }

    @Override
    public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    move(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                }
                return true;
            case KeyEvent.KEYCODE_POWER:
                if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                        && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) == 0) {
                    press();
                }
                return true;
            default:
                return false;
        }
    }

    private void move(int step) {
        final List<View> targets = targets();
        if (targets.isEmpty()) {
            return;
        }
        // Until the wheel moved, the focus is wherever the window put it when it opened (a
        // pinned app, say); the wheel always starts at the first button instead
        final int current = mMoved ? targets.indexOf(mWindow.getDecorView().findFocus()) : -1;
        mMoved = true;
        final int next = current < 0 ? (step > 0 ? 0 : targets.size() - 1)
                : Math.floorMod(current + step, targets.size());
        final View target = targets.get(next);
        // Also leaves touch mode, so the ring shows after a tap
        target.requestFocusFromTouch();
        target.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void press() {
        final View focused = mWindow.getDecorView().findFocus();
        if (!mMoved || focused == null || !targets().contains(focused)) {
            // Nothing picked yet: show where the wheel starts instead of guessing
            move(1);
            return;
        }
        focused.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        focused.performClick();
    }

    /** Everything the wheel stops at: the buttons in reading order, then the pinned apps. */
    private List<View> targets() {
        final View root = mWindow.getDecorView();
        final ArrayList<View> views = new ArrayList<>();
        root.addFocusables(views, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
        final View pinned = root.findViewById(R.id.global_actions_pinned);
        final List<View> targets = new ArrayList<>();
        for (View view : views) {
            if (view.isShown() && view.isEnabled()) {
                targets.add(view);
            }
        }
        final int[] location = new int[2];
        final Comparator<View> readingOrder = Comparator
                .comparingInt((View view) -> isInside(view, pinned) ? 1 : 0)
                .thenComparingInt(view -> row(view, location))
                .thenComparingInt(view -> {
                    view.getLocationOnScreen(location);
                    return location[0];
                });
        targets.sort(readingOrder);
        return targets;
    }

    /** The vertical centre, rounded to half a view so a row's views compare equal. */
    private int row(View view, int[] location) {
        view.getLocationOnScreen(location);
        final int half = Math.max(1, view.getHeight() / 2);
        return (location[1] + view.getHeight() / 2) / half;
    }

    private static boolean isInside(View view, View ancestor) {
        for (View v = view; ancestor != null && v != null;
                v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v == ancestor) {
                return true;
            }
        }
        return false;
    }
}
