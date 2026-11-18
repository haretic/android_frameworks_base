/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.ContentResolver;
import android.content.Context;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.ArrayMap;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    /** Include the boot-selected offset when locating hardware around the display. */
    static int physicalRotation(int rotation) {
        final int offset = SystemProperties.getInt("ro.vendor.display.primary_orientation", 0);
        return (rotation + ((offset == 90 || offset == 180 || offset == 270)
                ? offset / 90 : 0)) % 4;
    }

    /**
     * The screen edge the bar is on at {@code rotation}: the wheel's, on the right upright,
     * at the top a quarter turn on, or the one across from it, or one of the sides.
     */
    int edge(int rotation) {
        rotation = physicalRotation(rotation);
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
    String daemonTuning(int userId, int rotation, String app) {
        rotation = physicalRotation(rotation);
        final ContentResolver resolver = mContext.getContentResolver();
        final boolean turned = getInt(resolver, LineageSettings.Secure.WHEEL_TURN_WITH_SCREEN, 1,
                userId) != 0 && (rotation == 2 || rotation == 3);
        final boolean reverse = tuning(userId, app, TUNE_REVERSE) != 0;
        return "tune distance "
                + tuning(userId, app, TUNE_DISTANCE)
                + "\ntune spin "
                + tuning(userId, app, TUNE_SPIN)
                + "\ntune guard "
                + tuning(userId, app, TUNE_GUARD)
                + "\ntune reverse "
                + (reverse != turned ? 1 : 0)
                + "\ntune natural "
                + tuning(userId, app, TUNE_NATURAL)
                + "\ntune divide "
                + tuning(userId, app, TUNE_DIVIDE)
                + "\n";
    }

    static final String TUNE_DISTANCE = "distance";
    static final String TUNE_SPIN = "spin";
    static final String TUNE_GUARD = "guard";
    static final String TUNE_REVERSE = "reverse";
    static final String TUNE_NATURAL = "natural";
    static final String TUNE_DIVIDE = "divide";

    /** The wheel's feel apps have of their own, LineageSettings.Secure.WHEEL_APP_TUNING. */
    private final Map<String, Map<String, Integer>> mAppTuning = new ArrayMap<>();

    void readAppTuning(int userId) {
        mAppTuning.clear();
        final String value = LineageSettings.Secure.getStringForUser(
                mContext.getContentResolver(), LineageSettings.Secure.WHEEL_APP_TUNING, userId);
        if (TextUtils.isEmpty(value)) {
            return;
        }
        for (String entry : value.split(",")) {
            final int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            final Map<String, Integer> tunings = new ArrayMap<>();
            for (String tuning : entry.substring(equals + 1).split(";")) {
                final int colon = tuning.indexOf(':');
                try {
                    tunings.put(tuning.substring(0, Math.max(0, colon)),
                            Integer.parseInt(tuning.substring(colon + 1)));
                } catch (NumberFormatException e) {
                    // Not one of ours
                }
            }
            tunings.remove("");
            if (!tunings.isEmpty()) {
                mAppTuning.put(entry.substring(0, equals), tunings);
            }
        }
    }

    /** The user's general setting of a tuning. */
    int generalTuning(int userId, String name) {
        final ContentResolver resolver = mContext.getContentResolver();
        switch (name) {
            case TUNE_DISTANCE:
                return getInt(resolver, LineageSettings.Secure.WHEEL_SCROLL_DISTANCE, 100, userId);
            case TUNE_SPIN:
                return getInt(resolver, LineageSettings.Secure.WHEEL_SPIN, 1, userId);
            case TUNE_GUARD:
                return getInt(resolver, LineageSettings.Secure.WHEEL_PRESS_GUARD, 150, userId);
            case TUNE_REVERSE:
                return getInt(resolver, LineageSettings.Secure.WHEEL_REVERSE, 0, userId);
            case TUNE_NATURAL:
                return getInt(resolver, LineageSettings.Secure.WHEEL_SCROLL_NATURAL, 0, userId);
            default:
                return 1; // The clicks that make a step: an app's only
        }
    }

    /** Whether {@code app} has a tuning of its own. */
    boolean appTuned(String app, String name) {
        final Map<String, Integer> tunings = app == null ? null : mAppTuning.get(app);
        return tunings != null && tunings.containsKey(name);
    }

    /** How many tunings {@code app} has of its own. */
    int appTunings(String app) {
        final Map<String, Integer> tunings = app == null ? null : mAppTuning.get(app);
        return tunings == null ? 0 : tunings.size();
    }

    /** A tuning as it is in {@code app}: its own, else the general one. */
    int tuning(int userId, String app, String name) {
        final Map<String, Integer> tunings = app == null ? null : mAppTuning.get(app);
        final Integer own = tunings == null ? null : tunings.get(name);
        return own != null ? own : generalTuning(userId, name);
    }

    /**
     * Gives {@code app} a tuning of its own, none where it is the general one's value; a null
     * name takes all of the app's away.
     */
    void setAppTuning(int userId, String app, String name, int value) {
        Map<String, Integer> tunings = mAppTuning.get(app);
        if (name == null) {
            mAppTuning.remove(app);
        } else {
            if (tunings == null) {
                tunings = new ArrayMap<>();
                mAppTuning.put(app, tunings);
            }
            if (value == generalTuning(userId, name)) {
                tunings.remove(name);
            } else {
                tunings.put(name, value);
            }
            if (tunings.isEmpty()) {
                mAppTuning.remove(app);
            }
        }
        final StringBuilder all = new StringBuilder();
        for (Map.Entry<String, Map<String, Integer>> entry : mAppTuning.entrySet()) {
            if (all.length() > 0) {
                all.append(',');
            }
            all.append(entry.getKey()).append('=');
            boolean first = true;
            for (Map.Entry<String, Integer> tuning : entry.getValue().entrySet()) {
                if (!first) {
                    all.append(';');
                }
                first = false;
                all.append(tuning.getKey()).append(':').append(tuning.getValue());
            }
        }
        LineageSettings.Secure.putStringForUser(mContext.getContentResolver(),
                LineageSettings.Secure.WHEEL_APP_TUNING, all.toString(), userId);
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
