/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import com.android.systemui.res.R;
import com.google.android.material.slider.Slider;

/**
 * The framework's slider grab (AbsSeekBar, config_wheelFocus) for Material sliders, which aren't
 * seek bars: the side button's press (Enter) grabs the slider, the wheel's steps (up and down)
 * then set it, and a press, a pause or leaving it lets it go.
 */
public final class WheelSliderGrab implements View.OnKeyListener, View.OnFocusChangeListener,
        View.OnAttachStateChangeListener {

    /** What the slider's owner does with the wheel's values, as with a drag. */
    public interface Callback {
        void onStart();

        void onValue(float value);

        void onStop();
    }

    private static final long TIMEOUT_MS = 4000;
    private static final int STEPS = 50;
    private static final int FAST_STEPS = 10;
    private static final long FAST_STEP_MS = 60;

    private final Slider mSlider;
    private final Callback mCallback;
    private final Runnable mLetGo = () -> setGrabbed(false);
    private boolean mGrabbed;
    private long mLastStep;

    private WheelSliderGrab(Slider slider, Callback callback) {
        mSlider = slider;
        mCallback = callback;
    }

    /** Gives the slider the grab under the wheel UI, replacing one given before. */
    public static void attach(Slider slider, Callback callback) {
        final Object old = slider.getTag(R.id.wheel_slider_grab);
        if (old instanceof WheelSliderGrab) {
            ((WheelSliderGrab) old).setGrabbed(false);
            slider.removeOnAttachStateChangeListener((WheelSliderGrab) old);
        }
        if (!slider.getResources().getBoolean(com.android.internal.R.bool.config_wheelFocus)) {
            return;
        }
        final WheelSliderGrab grab = new WheelSliderGrab(slider, callback);
        slider.setTag(R.id.wheel_slider_grab, grab);
        slider.setOnKeyListener(grab);
        slider.setOnFocusChangeListener(grab);
        slider.addOnAttachStateChangeListener(grab);
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (!mSlider.isEnabled()) {
            return false;
        }
        if (KeyEvent.isConfirmKey(keyCode)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                setGrabbed(!mGrabbed);
            }
            return true;
        }
        if (!mGrabbed
                || keyCode != KeyEvent.KEYCODE_DPAD_DOWN && keyCode != KeyEvent.KEYCODE_DPAD_UP) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN) {
            return true;
        }
        mSlider.removeCallbacks(mLetGo);
        mSlider.postDelayed(mLetGo, TIMEOUT_MS);
        final long now = event.getEventTime();
        final boolean fast = now - mLastStep < FAST_STEP_MS;
        mLastStep = now;
        final float from = mSlider.getValueFrom();
        final float to = mSlider.getValueTo();
        final float stepSize = mSlider.getStepSize();
        float step = (to - from) / (fast ? FAST_STEPS : STEPS);
        if (stepSize > 0) {
            step = Math.max(stepSize, Math.round(step / stepSize) * stepSize);
        }
        final float direction = keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
        final float value = Math.max(from, Math.min(to, mSlider.getValue() + direction * step));
        if (value != mSlider.getValue()) {
            mSlider.setValue(value);
            mCallback.onValue(value);
        }
        return true;
    }

    private void setGrabbed(boolean grabbed) {
        mSlider.removeCallbacks(mLetGo);
        if (grabbed == mGrabbed) {
            return;
        }
        mGrabbed = grabbed;
        // The thumb shows its pressed halo while the wheel has the slider
        mSlider.setPressed(grabbed);
        if (grabbed) {
            mSlider.postDelayed(mLetGo, TIMEOUT_MS);
            Toast.makeText(mSlider.getContext(), com.android.internal.R.string.wheel_slider_grabbed,
                    Toast.LENGTH_SHORT).show();
            mCallback.onStart();
        } else {
            mCallback.onStop();
        }
    }

    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (!hasFocus) {
            setGrabbed(false);
        }
    }

    @Override
    public void onViewAttachedToWindow(View v) {
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        setGrabbed(false);
    }
}
