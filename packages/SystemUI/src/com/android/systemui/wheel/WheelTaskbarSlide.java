/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.util.ArrayMap;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.window.DisplayAreaAppearedInfo;
import android.window.DisplayAreaInfo;
import android.window.DisplayAreaOrganizer;
import android.window.WindowContainerToken;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * Slides the screen aside for the wheel taskbar the way one-handed mode moves it down: by
 * moving the OneHanded display areas, which hold the apps, the wallpaper and the status bar
 * (each in its own area) but not the navigation bar panels the taskbar is one of. Touches follow the surface.
 * The area is taken over when SystemUI starts, so the first slide doesn't wait for it, and only
 * where one-handed mode (ro.support_one_handed_mode) doesn't use it. With every area moving,
 * the space the slide opens shows only the taskbar.
 */
final class WheelTaskbarSlide extends DisplayAreaOrganizer {

    private static final String TAG = "WheelTaskbar";

    /** The OneHanded areas: the wallpaper, the apps and the system bars sit in separate ones. */
    private final Map<WindowContainerToken, SurfaceControl> mLeashes = new ArrayMap<>();
    private boolean mRegistered;
    private float mX;
    private ValueAnimator mAnimator;

    WheelTaskbarSlide(Executor executor) {
        super(executor);
    }

    /**
     * Moves the screen to {@code x} pixels from where it belongs, 0 to put it back.
     * {@code follower} (the taskbar) moves along at {@code x + width}: off screen at 0, in place
     * when the screen has moved aside by its width. {@code endAction} runs once it got there,
     * after {@code durationMs}; 0 moves both at once.
     */
    void slide(float x, View follower, float width, long durationMs, Runnable endAction) {
        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator = null;
        }
        if (durationMs == 0) {
            moveTo(x);
            if (follower != null) {
                follower.setTranslationX(x + width);
            }
            if (endAction != null) {
                endAction.run();
            }
            return;
        }
        mAnimator = ValueAnimator.ofFloat(mX, x);
        mAnimator.setDuration(durationMs);
        mAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        mAnimator.addUpdateListener(a -> {
            final float now = (float) a.getAnimatedValue();
            moveTo(now);
            if (follower != null) {
                follower.setTranslationX(now + width);
            }
        });
        if (endAction != null) {
            mAnimator.addListener(new AnimatorListenerAdapter() {
                private boolean mCancelled;

                @Override
                public void onAnimationCancel(Animator animation) {
                    mCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animation) {
                    if (!mCancelled) {
                        endAction.run();
                    }
                }
            });
        }
        mAnimator.start();
    }

    /** Takes the display areas over, once, when the taskbar starts. */
    void register() {
        if (mRegistered) {
            return;
        }
        mRegistered = true;
        try {
            final List<DisplayAreaAppearedInfo> areas = registerOrganizer(FEATURE_ONE_HANDED);
            for (DisplayAreaAppearedInfo area : areas) {
                onDisplayAreaAppeared(area.getDisplayAreaInfo(), area.getLeash());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "No display area to slide, the taskbar covers the app instead", e);
        }
    }

    private void moveTo(float x) {
        mX = x;
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            for (SurfaceControl leash : mLeashes.values()) {
                if (leash.isValid()) {
                    t.setPosition(leash, x, 0);
                }
            }
            t.apply();
        }
    }

    @Override
    public void onDisplayAreaAppeared(DisplayAreaInfo info, SurfaceControl leash) {
        if (info.displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        mLeashes.put(info.token, leash);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            t.setPosition(leash, mX, 0).apply();
        }
    }

    @Override
    public void onDisplayAreaVanished(DisplayAreaInfo info) {
        mLeashes.remove(info.token);
    }
}
