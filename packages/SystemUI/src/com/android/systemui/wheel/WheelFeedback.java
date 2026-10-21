/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

import com.android.systemui.res.R;

import lineageos.providers.LineageSettings;

/**
 * How the wheel feels and sounds, as a click wheel does: a crisp tick for every step, a firmer
 * click when the side button picks something and a softer one going back. The ticks follow
 * WHEEL_TASKBAR_HAPTICS and the system's touch feedback; the quiet clicks play in SystemUI's
 * wheel windows only (the taskbar, its lists, the power menu) and follow WHEEL_TASKBAR_SOUNDS
 * and the system's touch sounds. Main thread only.
 */
public final class WheelFeedback {

    /** Steps closer than this share a tick; the wheel's detents can't come this fast. */
    private static final long MIN_GAP_MS = 12;
    private static final float TICK_SCALE = 0.85f;
    private static final float SELECT_SCALE = 1f;
    private static final float BACK_SCALE = 0.7f;
    private static final float TICK_VOLUME = 0.45f;
    private static final float PRESS_VOLUME = 0.6f;
    /** A step down sounds a touch lower than one up. */
    private static final float DOWN_RATE = 0.94f;

    private static WheelFeedback sInstance;

    private final Context mContext;
    private final Vibrator mVibrator;
    private final VibrationAttributes mAttributes =
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH);
    private final VibrationEffect mTick;
    private final VibrationEffect mSelect;
    private final VibrationEffect mBack;
    private final SoundPool mSounds;
    private final int mTickSound;
    private final int mSelectSound;
    private final int mBackSound;
    private long mLastTick;
    private long mLastSound;

    /** The one instance for {@code context}'s application. */
    public static WheelFeedback get(Context context) {
        if (sInstance == null) {
            sInstance = new WheelFeedback(context.getApplicationContext());
        }
        return sInstance;
    }

    private WheelFeedback(Context context) {
        mContext = context;
        mVibrator = context.getSystemService(Vibrator.class);
        mTick = composition(VibrationEffect.Composition.PRIMITIVE_TICK, TICK_SCALE,
                VibrationEffect.EFFECT_TICK);
        mSelect = composition(VibrationEffect.Composition.PRIMITIVE_CLICK, SELECT_SCALE,
                VibrationEffect.EFFECT_CLICK);
        mBack = composition(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, BACK_SCALE,
                VibrationEffect.EFFECT_TICK);
        mSounds = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        mTickSound = mSounds.load(context, R.raw.wheel_tick, 1);
        mSelectSound = mSounds.load(context, R.raw.wheel_select, 1);
        mBackSound = mSounds.load(context, R.raw.wheel_back, 1);
    }

    /** A primitive where the vibrator has it, the predefined effect where it doesn't. */
    private VibrationEffect composition(int primitive, float scale, int fallback) {
        if (mVibrator != null && mVibrator.areAllPrimitivesSupported(primitive)) {
            return VibrationEffect.startComposition().addPrimitive(primitive, scale).compose();
        }
        return VibrationEffect.createPredefined(fallback);
    }

    /** A step of the wheel through a SystemUI window: a tick and a click. */
    public void step(boolean up) {
        tick();
        play(mTickSound, TICK_VOLUME, up ? 1f : DOWN_RATE);
    }

    /** A step of the wheel in an app, which hears the app's own sounds: only the tick. */
    public void tick() {
        final long now = SystemClock.uptimeMillis();
        if (now - mLastTick < MIN_GAP_MS) {
            return;
        }
        mLastTick = now;
        vibrate(mTick);
    }

    /** The side button picked what the wheel is on. */
    public void select() {
        vibrate(mSelect);
        play(mSelectSound, PRESS_VOLUME, 1f);
    }

    /** Back a list, or the window closing. */
    public void back() {
        vibrate(mBack);
        play(mBackSound, PRESS_VOLUME, 1f);
    }

    private void vibrate(VibrationEffect effect) {
        if (mVibrator == null || !enabled(LineageSettings.Secure.WHEEL_TASKBAR_HAPTICS)
                || Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.HAPTIC_FEEDBACK_ENABLED, 1,
                        UserHandle.USER_CURRENT) == 0) {
            return;
        }
        mVibrator.vibrate(effect, mAttributes);
    }

    private void play(int sound, float volume, float rate) {
        if (!enabled(LineageSettings.Secure.WHEEL_TASKBAR_SOUNDS)
                || Settings.System.getIntForUser(mContext.getContentResolver(),
                        Settings.System.SOUND_EFFECTS_ENABLED, 1,
                        UserHandle.USER_CURRENT) == 0) {
            return;
        }
        // A fast spin's clicks blur into a buzz; a few fewer keep them clicks
        final long now = SystemClock.uptimeMillis();
        if (sound == mTickSound && now - mLastSound < 2 * MIN_GAP_MS) {
            return;
        }
        mLastSound = now;
        mSounds.play(sound, volume, volume, 1, 0, rate);
    }

    private boolean enabled(String setting) {
        return LineageSettings.Secure.getIntForUser(mContext.getContentResolver(), setting, 1,
                UserHandle.USER_CURRENT) != 0;
    }
}
