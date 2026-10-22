/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.widget.ScrollView;

import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * A wheel taskbar list's scroll. It never scrolls for the focus by itself, which ScrollView
 * would do with an animation of its own that turns into a jump when steps come quicker than
 * it runs. The taskbar asks it to show the focus instead, and it glides there on one spring
 * that each step only retargets, so a fast spin is one smooth glide that keeps its speed.
 */
final class WheelTaskbarScroll extends ScrollView {

    private final SpringAnimation mSpring;
    private final Rect mRect = new Rect();
    private boolean mAnimate = true;

    WheelTaskbarScroll(Context context) {
        super(context);
        setVerticalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(true);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setClipChildren(false);
        mSpring = new SpringAnimation(this, new FloatPropertyCompat<WheelTaskbarScroll>("scroll") {
            @Override
            public float getValue(WheelTaskbarScroll view) {
                return view.getScrollY();
            }

            @Override
            public void setValue(WheelTaskbarScroll view, float value) {
                view.scrollTo(0, Math.round(value));
            }
        });
        mSpring.setMinimumVisibleChange(1f);
        setSpeed(1f);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Apps scrolled out stay hidden above and below; to the sides one grows out of the bar,
        // and at an end with nothing past it the first or last one moves out a little too
        final int save = canvas.save();
        final int margin = getHeight();
        canvas.clipRect(getScrollX() - getWidth(),
                getScrollY() - (canScrollVertically(-1) ? 0 : margin),
                getScrollX() + 2 * getWidth(),
                getScrollY() + getHeight() + (canScrollVertically(1) ? 0 : margin));
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    /** 1 full, 0.5 quick, 0 none, as the animation setting has it. */
    void setSpeed(float speed) {
        mAnimate = speed > 0;
        // Critically damped: it eases in and never overshoots past the focus
        mSpring.setSpring(new SpringForce().setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                .setStiffness(speed >= 1 ? 900f : 1800f));
    }

    /** Where it is going, or is when still. */
    int target() {
        return mSpring.isRunning() ? Math.round(mSpring.getSpring().getFinalPosition())
                : getScrollY();
    }

    /** Glides to {@code y}, kept within the content. */
    void glideTo(int y) {
        y = Math.max(0, Math.min(y, maxScroll()));
        if (!mAnimate || !isLaidOut()) {
            jumpTo(y);
            return;
        }
        if (y == target()) {
            return;
        }
        mSpring.animateToFinalPosition(y);
    }

    /** At {@code y} at once. */
    void jumpTo(int y) {
        mSpring.cancel();
        scrollTo(0, Math.max(0, Math.min(y, maxScroll())));
    }

    /**
     * Brings {@code child}, a view anywhere in the content, in view with {@code margin} around
     * it, gliding or at once. Measured from where the scroll is going, so steps quicker than
     * the glide add up.
     */
    void reveal(View child, int margin, boolean jump) {
        if (getChildCount() == 0 || !child.isLaidOut()) {
            return;
        }
        mRect.set(0, 0, child.getWidth(), child.getHeight());
        // In content coordinates, by layout: moves under way don't count
        offsetDescendantRectToMyCoords(child, mRect);
        final int from = target();
        final int height = getHeight() - getPaddingTop() - getPaddingBottom();
        final int top = mRect.top - margin;
        final int bottom = mRect.bottom + margin;
        final int y = bottom - top >= height ? mRect.centerY() - height / 2
                : top < from ? top : bottom > from + height ? bottom - height : from;
        if (jump) {
            jumpTo(y);
        } else {
            glideTo(y);
        }
    }

    private int maxScroll() {
        if (getChildCount() == 0) {
            return 0;
        }
        return Math.max(0, getChildAt(0).getHeight()
                - (getHeight() - getPaddingTop() - getPaddingBottom()));
    }

    /** The taskbar shows the focus itself, see {@link #reveal}. */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        mSpring.cancel();
        super.onDetachedFromWindow();
    }
}
