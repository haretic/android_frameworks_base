/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.wheel;

import android.app.ActivityManager;
import android.content.Context;

import com.android.systemui.res.R;

import lineageos.providers.LineageSettings;

/** Shared live policy for view-based notification content and wheel navigation. */
public final class WheelLayoutSettings {
    private WheelLayoutSettings() {}

    public static boolean enabled(Context context) {
        return context.getResources().getBoolean(R.bool.config_r1CompactQs)
                && LineageSettings.Secure.getIntForUser(
                                context.getContentResolver(),
                                LineageSettings.Secure.WHEEL_UI,
                                1,
                                ActivityManager.getCurrentUser())
                        != 0;
    }

    public static int columns(Context context) {
        return Math.max(
                1,
                Math.min(
                        2,
                        LineageSettings.Secure.getIntForUser(
                                context.getContentResolver(),
                                LineageSettings.Secure.WHEEL_BUTTON_COLUMNS,
                                1,
                                ActivityManager.getCurrentUser())));
    }
}
