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

    static final String BUTTON_HOME = "home";
    static final String BUTTON_DRAWER = "drawer";
    static final String BUTTON_APPS = "apps";
    static final String BUTTON_NOTIFICATIONS = "notifications";
    static final String BUTTON_MODE = "mode";
    private static final String[] BUTTONS = {
        BUTTON_HOME, BUTTON_DRAWER, BUTTON_APPS, BUTTON_NOTIFICATIONS, BUTTON_MODE,
    };

    static final int LIST_END_BACK = 0;
    static final int LIST_END_LOOP = 1;
    static final int LIST_END_CLOSE = 2;

    /** The buttons shown, top to bottom; the settings button follows them. */
    List<String> buttons = List.of(BUTTONS);
    int running = 6;
    boolean left;
    boolean push = true;
    int listEnd = LIST_END_BACK;
    /** 0: stays open. */
    long timeoutMs = 3000;
    boolean labels = true;
    boolean haptics = true;
    /** 1 full, 0.5 quick, 0 none. */
    float animation = 1f;
    int size = 1;
    int color;
    int ring;

    private final Context mContext;

    WheelTaskbarSettings(Context context) {
        mContext = context;
    }

    void read(int userId) {
        final ContentResolver resolver = mContext.getContentResolver();
        buttons = parseButtons(LineageSettings.Secure.getStringForUser(resolver,
                LineageSettings.Secure.WHEEL_TASKBAR_BUTTONS, userId));
        running = Math.max(0, getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_RUNNING,
                6, userId));
        left = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_EDGE, 0, userId) == 1;
        push = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_PUSH, 1, userId) != 0;
        listEnd = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_LIST_END,
                LIST_END_BACK, userId);
        timeoutMs = 1000L * Math.max(0, getInt(resolver,
                LineageSettings.Secure.WHEEL_TASKBAR_TIMEOUT, 3, userId));
        labels = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_LABELS, 1, userId) != 0;
        haptics = getInt(resolver, LineageSettings.Secure.WHEEL_TASKBAR_HAPTICS, 1, userId) != 0;
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

    /** Every shown button once, in order; a '!' in front hides one. Unset: all of them. */
    private static List<String> parseButtons(String value) {
        if (TextUtils.isEmpty(value)) {
            return List.of(BUTTONS);
        }
        final List<String> shown = new ArrayList<>();
        for (String button : value.split(",")) {
            if (!button.startsWith("!") && List.of(BUTTONS).contains(button)
                    && !shown.contains(button)) {
                shown.add(button);
            }
        }
        return shown;
    }

    private static int getInt(ContentResolver resolver, String key, int def, int userId) {
        return LineageSettings.Secure.getIntForUser(resolver, key, def, userId);
    }
}
