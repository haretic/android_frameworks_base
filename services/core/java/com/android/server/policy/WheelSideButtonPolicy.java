// SPDX-License-Identifier: Apache-2.0
package com.android.server.policy;

import android.content.Context;
import android.os.UserHandle;

import lineageos.providers.LineageSettings;

/** Shared device/user gate for policy and the power-key gesture launcher. */
public final class WheelSideButtonPolicy {
    private WheelSideButtonPolicy() {}

    public static boolean isEnabled(Context context) {
        return context.getResources()
                        .getBoolean(com.android.internal.R.bool.config_r1SideButtonInput)
                && LineageSettings.Secure.getIntForUser(
                                context.getContentResolver(),
                                LineageSettings.Secure.WHEEL_UI,
                                1,
                                UserHandle.USER_CURRENT)
                        != 0;
    }
}
