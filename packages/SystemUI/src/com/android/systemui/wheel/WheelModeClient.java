/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

/**
 * Sets the mode of the device's wheel daemon (config_wheelDaemonSocket, r1-wheeld on the
 * rabbit r1), which owns the scroll wheel and sends each step on as the mode's keys: the
 * taskbar's, the D-pad for the app, or the volume keys. One line per change on its socket;
 * the daemon falls back to the taskbar mode on its own.
 */
final class WheelModeClient {

    private static final String TAG = "WheelModeClient";

    private final String mSocketName;
    private final Executor mBackground;
    /** Only touched on mBackground. */
    private LocalSocket mSocket;
    private String mMode;

    WheelModeClient(String socketName, Executor background) {
        mSocketName = socketName;
        mBackground = background;
    }

    /** Sends the mode unless the daemon has it already. */
    void set(String mode) {
        mBackground.execute(() -> {
            if (mode.equals(mMode)) {
                return;
            }
            // A daemon restarted since the last change took the old connection with it
            if (send(mode) || send(mode)) {
                mMode = mode;
            }
        });
    }

    private boolean send(String mode) {
        try {
            if (mSocket == null) {
                final LocalSocket socket = new LocalSocket();
                socket.connect(new LocalSocketAddress(mSocketName,
                        LocalSocketAddress.Namespace.RESERVED));
                mSocket = socket;
            }
            mSocket.getOutputStream().write(
                    ("mode " + mode + "\n").getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Can't reach " + mSocketName + ": " + e.getMessage());
            if (mSocket != null) {
                try {
                    mSocket.close();
                } catch (IOException ignored) {
                }
                mSocket = null;
            }
            mMode = null;
            return false;
        }
    }
}
