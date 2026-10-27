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
 * Aside, the screen is drawn smaller to fit beside the bar, all of the app in sight, not cut at
 * the edge the bar takes.
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
    /** The display's size, which the screen is made smaller from to fit beside the bar. */
    private int mWidth;
    private int mHeight;
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
                    place(t, leash);
                }
            }
            t.apply();
        }
    }

    /**
     * The screen {@code mX} aside: as wide as the room beside the bar, as much smaller in height,
     * level in the middle, on the side away from the bar.
     */
    private void place(SurfaceControl.Transaction t, SurfaceControl leash) {
        if (mWidth <= 0 || mHeight <= 0 || mX == 0) {
            t.setMatrix(leash, 1, 0, 0, 1);
            t.setPosition(leash, mX, 0);
            return;
        }
        final float scale = Math.max(0.5f, 1 - Math.abs(mX) / mWidth);
        t.setMatrix(leash, scale, 0, 0, scale);
        t.setPosition(leash, mX > 0 ? mX : 0, mHeight * (1 - scale) / 2);
    }

    private void size(DisplayAreaInfo info) {
        final android.graphics.Rect bounds = info.configuration.windowConfiguration.getBounds();
        mWidth = bounds.width();
        mHeight = bounds.height();
    }

    @Override
    public void onDisplayAreaAppeared(DisplayAreaInfo info, SurfaceControl leash) {
        if (info.displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        mLeashes.put(info.token, leash);
        size(info);
        try (SurfaceControl.Transaction t = new SurfaceControl.Transaction()) {
            place(t, leash);
            t.apply();
        }
    }

    @Override
    public void onDisplayAreaInfoChanged(DisplayAreaInfo info) {
        if (info.displayId == Display.DEFAULT_DISPLAY) {
            size(info);
        }
    }

    @Override
    public void onDisplayAreaVanished(DisplayAreaInfo info) {
        mLeashes.remove(info.token);
    }
}
