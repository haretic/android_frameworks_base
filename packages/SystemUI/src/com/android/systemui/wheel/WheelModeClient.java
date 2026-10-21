/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Sets the mode of the device's wheel daemon (config_wheelDaemonSocket, r1-wheeld on the
 * rabbit r1), which owns the scroll wheel and sends each step on as the mode's keys: the
 * taskbar's, the D-pad for the app, or the volume keys. One line per change on its socket;
 * the daemon falls back to the taskbar mode on its own. The daemon answers each step it sends
 * an app with "step up" or "step down", which go to the step listener, so the wheel ticks in
 * apps as it does in the taskbar.
 */
final class WheelModeClient {

    private static final String TAG = "WheelModeClient";

    private final String mSocketName;
    private final Executor mBackground;
    private final Executor mMain;
    /** Called on mMain with whether the step went up. */
    private final Consumer<Boolean> mOnStep;
    /** Only touched on mBackground. */
    private LocalSocket mSocket;
    /** Set on mBackground, read by the reader. */
    private volatile String mMode;

    WheelModeClient(String socketName, Executor background, Executor main,
            Consumer<Boolean> onStep) {
        mSocketName = socketName;
        mBackground = background;
        mMain = main;
        mOnStep = onStep;
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
                listen(socket);
            }
            mSocket.getOutputStream().write(
                    ("mode " + mode + "\n").getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Can't reach " + mSocketName + ": " + e.getMessage());
            close(mSocket);
            return false;
        }
    }

    /** Reads the daemon's steps until it closes the connection. */
    private void listen(LocalSocket socket) {
        final Thread reader = new Thread(() -> {
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    if (line.equals("step up") || line.equals("step down")) {
                        final boolean up = line.equals("step up");
                        mMain.execute(() -> mOnStep.accept(up));
                    }
                }
            } catch (IOException e) {
                // Closed by us or by the daemon
            }
            // Gone with a restarted daemon, which starts in the taskbar mode: the next change
            // connects again
            final String mode = mMode;
            mBackground.execute(() -> {
                if (mSocket == socket) {
                    close(socket);
                    if (mode != null) {
                        set(mode);
                    }
                }
            });
        }, TAG);
        reader.setDaemon(true);
        reader.start();
    }

    private void close(LocalSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        if (mSocket == socket) {
            mSocket = null;
        }
        mMode = null;
    }
}
