/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.ContentResolver;
import android.content.Context;
import android.text.TextUtils;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The wheel taskbar's settings (LineageSettings.Secure.WHEEL_TASKBAR_*, set in its own settings
 * app), read each time the bar opens.
 */
final class WheelTaskbarSettings {

    static final String BUTTON_BACK = "back";
    static final String BUTTON_HOME = "home";
    static final String BUTTON_DRAWER = "drawer";
    static final String BUTTON_APPS = "apps";
    static final String BUTTON_NOTIFICATIONS = "notifications";
    static final String BUTTON_MODE = "mode";
    static final String BUTTON_POWER = "power";
    private static final String[] BUTTONS = {
        BUTTON_BACK, BUTTON_HOME, BUTTON_DRAWER, BUTTON_APPS, BUTTON_NOTIFICATIONS, BUTTON_MODE,
        BUTTON_POWER,
    };

    /** The screen's edges, a quarter turn apart the way the wheel goes as the screen turns. */
    static final int EDGE_RIGHT = 0;
    static final int EDGE_TOP = 1;
    static final int EDGE_LEFT = 2;
    static final int EDGE_BOTTOM = 3;

    static final int LIST_END_BACK = 0;
    static final int LIST_END_LOOP = 1;
    static final int LIST_END_CLOSE = 2;

    /** The buttons shown, top to bottom; the settings button follows them. */
    List<String> buttons = List.of(BUTTONS);
    int running = 6;
    /** 0 beside the wheel, 1 across from it, 2 the right, 3 the left; see {@link #edge}. */
    int edgeSetting;
    /** Whether the wheel's way turns with the screen. */
    boolean turnWithScreen = true;
    boolean push = true;
    int listEnd = LIST_END_LOOP;

    /** 0: stays open. */
    long timeoutMs = 3000;
    boolean labels = true;
    /** The Power button shows its options in the bar when the highlight rests on it. */
    boolean powerLinger = true;
    /** 1 full, 0.5 quick, 0 none. */
    float animation = 1f;
    int size = 1;
    int color;
    int ring;

    private final Context mContext;

    WheelTaskbarSettings(Context context) {
        mContext = context;
    }

    /**
     * The screen edge the bar is on at {@code rotation}: the wheel's, on the right upright,
     * at the top a quarter turn on, or the one across from it, or one of the sides.
     */
    int edge(int rotation) {
        switch (edgeSetting) {
            case 1:
                return (rotation + 2) % 4;
            case 2:
                return EDGE_RIGHT;
            case 3:
                return EDGE_LEFT;
            default:
                return rotation;
        }
    }

    /**
     * The user's wheel tuning for the wheel daemon, as its "tune" lines (WheelModeClient), at
     * the screen's {@code rotation}: upside down or turned right, with the wheel on the left or
     * at the bottom, the wheel's way is turned round so a step towards the screen's top or
     * start still goes up.
     */
    String daemonTuning(int userId, int rotation) {
        final ContentResolver resolver = mContext.getContentResolver();
        final boolean turned = getInt(resolver, LineageSettings.Secure.WHEEL_TURN_WITH_SCREEN, 1,
                userId) != 0 && (rotation == 2 || rotation == 3);
        final boolean reverse = getInt(resolver, LineageSettings.Secure.WHEEL_REVERSE, 0,
                userId) != 0;
        return "tune distance "
                + getInt(resolver, LineageSettings.Secure.WHEEL_SCROLL_DISTANCE, 100, userId)
                + "\ntune spin "
                + getInt(resolver, LineageSettings.Secure.WHEEL_SPIN, 1, userId)
                + "\ntune guard "
                + getInt(resolver, LineageSettings.Secure.WHEEL_PRESS_GUARD, 150, userId)
                + "\ntune reverse "
                + (reverse != turned ? 1 : 0)
                + "\n";
    }

    void read(int userId) {
        final ContentResolver resolver = mContext.getContentResolver();
        buttons = parseButtons(LineageSettings.Secure.getStringForUser(resolver,
                LineageSettings.Secure.WHEEL_TASKBAR_BUTTONS, userId));
        running = Math.max(0, getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_RUNNING,
                6, userId));
        edgeSetting = Math.max(0, Math.min(3, getInt(resolver,
                LineageSettings.Secure.WHEEL_TASKBAR_EDGE, 0, userId)));
        push = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_PUSH, 1, userId) != 0;
        listEnd =
                getInt(
                        resolver,
                        LineageSettings.Secure.WHEEL_TASKBAR_LIST_END,
                        LIST_END_LOOP,
                        userId);
        timeoutMs = 1000L * Math.max(0, getInt(resolver,
                LineageSettings.Secure.WHEEL_TASKBAR_TIMEOUT, 3, userId));
        labels = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_LABELS, 1, userId) != 0;
        powerLinger = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_POWER_LINGER, 1,
                userId) != 0;
        final int animations = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_ANIMATIONS,
                2, userId);
        animation = animations >= 2 ? 1f : animations == 1 ? 0.5f : 0f;
        size = Math.max(0, Math.min(2, getInt(resolver,
                LineageSettings.Secure.WHEEL_TASKBAR_SIZE, 1, userId)));
        color = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_COLOR, 0, userId);
        ring = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_RING, 0, userId);
    }

    /** {@code ms} as the animation setting has it. */
    long duration(long ms) {
        return (long) (ms * animation);
    }

    /**
     * Every shown button once, in order; a '!' in front hides one. Unset: all of them. One the
     * value doesn't name is newer than it and shows where the defaults have it.
     */
    private static List<String> parseButtons(String value) {
        if (TextUtils.isEmpty(value)) {
            return List.of(BUTTONS);
        }
        final List<String> shown = new ArrayList<>();
        final List<String> named = new ArrayList<>();
        for (String button : value.split(",")) {
            final String name = button.startsWith("!") ? button.substring(1) : button;
            if (List.of(BUTTONS).contains(name) && !named.contains(name)) {
                named.add(name);
                if (name.equals(button)) {
                    shown.add(button);
                }
            }
        }
        for (int i = 0; i < BUTTONS.length; i++) {
            if (!named.contains(BUTTONS[i])) {
                shown.add(Math.min(i, shown.size()), BUTTONS[i]);
            }
        }
        return shown;
    }

    private static int getInt(ContentResolver resolver, String key, int def, int userId) {
        return LineageSettings.Secure.getIntForUser(resolver, key, def, userId);
    }
}
