/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.view.View;
import android.view.Window;

import java.util.ArrayList;
import java.util.List;

/**
 * SystemUI's own windows the wheel moves through, such as the power menu: while one shows,
 * the wheel daemon stays in taskbar mode, whose steps reach a system window as the D-pad,
 * whatever mode the app under it has. Main thread only.
 */
public final class WheelSystemWindows {

    private static final List<Runnable> sListeners = new ArrayList<>();
    private static int sShowing;
    private static boolean sVolumeShowing;

    private WheelSystemWindows() {
    }

    /** Counts {@code window} as showing while its decor view is attached. */
    public static void track(Window window) {
        window.getDecorView().addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {
                        sShowing++;
                        changed();
                    }

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        sShowing--;
                        changed();
                    }
                });
    }

    static boolean showing() {
        return sShowing > 0;
    }

    /**
     * The volume panel showing, or gone. It takes no focus, so while it shows the wheel turns
     * the volume rather than moving the highlight in the app under it.
     */
    public static void setVolumeShowing(boolean showing) {
        if (sVolumeShowing != showing) {
            sVolumeShowing = showing;
            changed();
        }
    }

    static boolean volumeShowing() {
        return sVolumeShowing;
    }

    static void addListener(Runnable listener) {
        sListeners.add(listener);
    }

    private static void changed() {
        for (Runnable listener : sListeners) {
            listener.run();
        }
    }
}
