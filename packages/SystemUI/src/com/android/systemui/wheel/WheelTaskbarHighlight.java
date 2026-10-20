/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * The wheel taskbar's focus: a lit shape behind the focused button or row that glides to the
 * next one on a spring as the wheel turns, following it while it scrolls or moves. A view of
 * the container it is drawn in, below the buttons; it doesn't take touches or the focus.
 */
final class WheelTaskbarHighlight extends View implements ViewTreeObserver.OnPreDrawListener {

    /** Its bounds in the container, each on a spring of its own. */
    private final float[] mBounds = new float[4];
    private final SpringAnimation[] mSprings = new SpringAnimation[4];
    private final float[] mTarget = new float[4];
    private final int[] mHere = new int[2];
    private final int[] mThere = new int[2];
    private final GradientDrawable mShape = new GradientDrawable();
    private View mFollowed;
    /** Its size around the followed view, 0 for the view's own. */
    private int mWidth;
    private int mHeight;
    private boolean mJump = true;
    /** Shows at the next view at once, not gliding there from where it was. */
    private boolean mAppear = true;
    private float mPress = 1f;
    private final SpringAnimation mPressSpring;

    WheelTaskbarHighlight(Context context) {
        super(context);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setAlpha(0f);
        for (int i = 0; i < 4; i++) {
            final int edge = i;
            mSprings[i] = new SpringAnimation(this, new FloatPropertyCompat<View>("edge" + i) {
                @Override
                public float getValue(View view) {
                    return mBounds[edge];
                }

                @Override
                public void setValue(View view, float value) {
                    mBounds[edge] = value;
                    invalidate();
                }
            });
        }
        mPressSpring = new SpringAnimation(this, new FloatPropertyCompat<View>("press") {
            @Override
            public float getValue(View view) {
                return mPress;
            }

            @Override
            public void setValue(View view, float value) {
                mPress = value;
                invalidate();
            }
        });
        mPressSpring.setSpring(new SpringForce(1f).setStiffness(SpringForce.STIFFNESS_MEDIUM)
                .setDampingRatio(0.45f));
        setSpeed(1f);
    }

    /** 1 for the full glide, smaller for a quicker one, 0 to jump. */
    void setSpeed(float speed) {
        mJump = speed <= 0;
        final float stiffness = speed >= 1 ? 700f : 1500f;
        for (SpringAnimation spring : mSprings) {
            spring.setSpring(new SpringForce().setStiffness(stiffness).setDampingRatio(0.78f));
        }
    }

    /** The fill, the ring around it and the corner radius. */
    void setLook(int fill, int ring, int ringWidth, float radius) {
        mShape.setColor(fill);
        mShape.setStroke(ringWidth, ring);
        mShape.setCornerRadius(radius);
        invalidate();
    }

    /**
     * Glides to {@code view}, {@code width} by {@code height} around its centre (0: its own
     * size); null fades it out. After that it shows at the next view at once.
     */
    void follow(View view, int width, int height) {
        mFollowed = view;
        mWidth = width;
        mHeight = height;
        if (view == null) {
            mAppear = true;
            animate().alpha(0f).setDuration(mJump ? 0 : 120).start();
            return;
        }
        if (mAppear && measureTarget()) {
            mAppear = false;
            jumpToTarget();
            setAlpha(0f);
            animate().alpha(1f).setDuration(mJump ? 0 : 160).start();
        } else if (!mAppear) {
            animate().alpha(1f).setDuration(mJump ? 0 : 120).start();
        }
        invalidate();
    }

    /** A short squeeze, for a press of the side button. */
    void press() {
        if (mJump) {
            return;
        }
        mPress = 0.88f;
        mPressSpring.animateToFinalPosition(1f);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(this);
        for (SpringAnimation spring : mSprings) {
            spring.cancel();
        }
        mPressSpring.cancel();
        super.onDetachedFromWindow();
    }

    /** Before every frame: where the followed view is now, as it moves, scrolls or grows. */
    @Override
    public boolean onPreDraw() {
        if (mFollowed == null || !mFollowed.isAttachedToWindow() || !measureTarget()) {
            return true;
        }
        if (mJump || mAppear) {
            if (mAppear) {
                mAppear = false;
                animate().alpha(1f).setDuration(mJump ? 0 : 160).start();
            }
            jumpToTarget();
            return true;
        }
        for (int i = 0; i < 4; i++) {
            if (mSprings[i].isRunning() || mBounds[i] != mTarget[i]) {
                mSprings[i].animateToFinalPosition(mTarget[i]);
            }
        }
        return true;
    }

    private boolean measureTarget() {
        final View view = mFollowed;
        if (view == null || view.getWidth() == 0 || !isAttachedToWindow()) {
            return false;
        }
        ((View) getParent()).getLocationInWindow(mHere);
        // The view's place without its own scale, which the focus gives the buttons
        view.getLocationInWindow(mThere);
        final float scaledWidth = view.getWidth() * view.getScaleX();
        final float scaledHeight = view.getHeight() * view.getScaleY();
        final float centreX = mThere[0] - mHere[0] + scaledWidth / 2f;
        final float centreY = mThere[1] - mHere[1] + scaledHeight / 2f;
        final float width = mWidth > 0 ? mWidth : view.getWidth();
        final float height = mHeight > 0 ? mHeight : view.getHeight();
        mTarget[0] = centreX - width / 2f;
        mTarget[1] = centreY - height / 2f;
        mTarget[2] = centreX + width / 2f;
        mTarget[3] = centreY + height / 2f;
        return true;
    }

    private void jumpToTarget() {
        for (int i = 0; i < 4; i++) {
            mSprings[i].cancel();
            mBounds[i] = mTarget[i];
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float width = mBounds[2] - mBounds[0];
        final float height = mBounds[3] - mBounds[1];
        if (width <= 0 || height <= 0) {
            return;
        }
        final float insetX = width * (1f - mPress) / 2f;
        final float insetY = height * (1f - mPress) / 2f;
        mShape.setBounds(Math.round(mBounds[0] + insetX), Math.round(mBounds[1] + insetY),
                Math.round(mBounds[2] - insetX), Math.round(mBounds[3] - insetY));
        mShape.draw(canvas);
    }
}
