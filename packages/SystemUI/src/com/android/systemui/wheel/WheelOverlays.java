/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.content.om.OverlayManager;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;

import lineageos.providers.LineageSettings;

import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The revised parts of the wheel UI that live in the framework, there in every app: the overlays
 * that turn them on follow the wheel UI's settings for the user in front. Dialogs (the framework's
 * config_wheelDialogs) follow WHEEL_DIALOGS, the focus ring and sliders the side button grabs
 * (config_wheelFocus) WHEEL_SETTINGS.
 */
@SysUISingleton
public class WheelOverlays implements CoreStartable {

    private static final String TAG = "WheelOverlays";

    private static final String DIALOGS = "com.rabbit.r1.overlay.wheeldialogs";
    private static final String FOCUS = "com.rabbit.r1.overlay.wheelfocus";

    private final Context mContext;
    private final OverlayManager mOverlayManager;
    private final UserTracker mUserTracker;
    private final Handler mHandler;
    private final Executor mBackground;

    @Inject
    public WheelOverlays(Context context, OverlayManager overlayManager, UserTracker userTracker,
            @Main Handler handler, @Background Executor background) {
        mContext = context;
        mOverlayManager = overlayManager;
        mUserTracker = userTracker;
        mHandler = handler;
        mBackground = background;
    }

    @Override
    public void start() {
        if (!mContext.getResources().getBoolean(R.bool.config_r1CompactQs)) {
            return;
        }
        final ContentObserver observer = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                update();
            }
        };
        for (String setting : new String[] {
                LineageSettings.Secure.WHEEL_UI,
                LineageSettings.Secure.WHEEL_DIALOGS,
                LineageSettings.Secure.WHEEL_SETTINGS}) {
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.Secure.getUriFor(setting), false, observer,
                    UserHandle.USER_ALL);
        }
        mUserTracker.addCallback(new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, @NonNull Context userContext) {
                update();
            }
        }, mBackground);
        update();
    }

    private void update() {
        final boolean dialogs =
                WheelLayoutSettings.component(mContext, LineageSettings.Secure.WHEEL_DIALOGS);
        final boolean focus =
                WheelLayoutSettings.component(mContext, LineageSettings.Secure.WHEEL_SETTINGS);
        final UserHandle user = mUserTracker.getUserHandle();
        mBackground.execute(() -> {
            setEnabled(DIALOGS, dialogs, user);
            setEnabled(FOCUS, focus, user);
        });
    }

    private void setEnabled(String overlay, boolean enabled, UserHandle user) {
        try {
            mOverlayManager.setEnabled(overlay, enabled, user);
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Can't " + (enabled ? "enable " : "disable ") + overlay, e);
        }
    }
}
