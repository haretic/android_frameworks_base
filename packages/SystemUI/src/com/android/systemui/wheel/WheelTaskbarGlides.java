/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.view.View;

/** What the taskbar asks of a list's scroll, whichever way it goes. */
interface WheelTaskbarGlides {
    /** 1 full, 0.5 quick, 0 none, as the animation setting has it. */
    void setSpeed(float speed);

    /** Glides to {@code at}, kept within the content. */
    void glideTo(int at);

    /** At {@code at} at once. */
    void jumpTo(int at);

    /**
     * Brings {@code child}, a view anywhere in the content, in view with {@code margin}
     * around it, gliding or at once.
     */
    void reveal(View child, int margin, boolean jump);
}
