/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

/**
 * A wheel taskbar list's scroll. It never scrolls for the focus by itself, which ScrollView
 * would do with an animation of its own that turns into a jump when steps come quicker than
 * it runs. The taskbar asks it to show the focus instead, and it glides there on one spring
 * that each step only retargets, so a fast spin is one smooth glide that keeps its speed.
 * {@link Row} is the same along a bar at the top or bottom of the screen.
 */
final class WheelTaskbarScroll extends ScrollView implements WheelTaskbarGlides {

    private final Glide mGlide;

    WheelTaskbarScroll(Context context) {
        super(context);
        // A ScrollView takes the focus itself when it has no app to give it to, as while the
        // bar fills: the wheel then stopped on the whole section instead of a button
        setFocusable(false);
        setVerticalScrollBarEnabled(false);
        setVerticalFadingEdgeEnabled(true);
        setOverScrollMode(View.OVER_SCROLL_NEVER);
        setClipChildren(false);
        mGlide = new Glide(this, false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int pad = getMeasuredHeight() - peekEnd(this, getMeasuredHeight(), false);
        if (pad != getPaddingBottom()) {
            setPadding(0, 0, 0, pad);
        }
    }

    /** The fade at the end of the list's room, over the app peeking out, see peekEnd. */
    @Override
    protected boolean isPaddingOffsetRequired() {
        return true;
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
                getScrollY() + getHeight() - getPaddingBottom()
                        + (canScrollVertically(1) ? 0 : margin));
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override
    public void setSpeed(float speed) {
        mGlide.setSpeed(speed);
    }

    @Override
    public void glideTo(int y) {
        mGlide.glideTo(y);
    }

    @Override
    public void jumpTo(int y) {
        mGlide.jumpTo(y);
    }

    @Override
    public void reveal(View child, int margin, boolean jump) {
        mGlide.reveal(child, margin, jump);
    }

    /** The taskbar shows the focus itself, see {@link #reveal}. */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    @Override
    protected void onDetachedFromWindow() {
        mGlide.cancel();
        super.onDetachedFromWindow();
    }

    /** The bar's apps in a row, along a bar at the top or bottom of the screen. */
    static final class Row extends HorizontalScrollView implements WheelTaskbarGlides {
        private final Glide mGlide;

        Row(Context context) {
            super(context);
            setFocusable(false);
            setHorizontalScrollBarEnabled(false);
            setHorizontalFadingEdgeEnabled(true);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
            setClipChildren(false);
            mGlide = new Glide(this, true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            final int pad = getMeasuredWidth() - peekEnd(this, getMeasuredWidth(), true);
            if (pad != getPaddingRight()) {
                setPadding(0, 0, pad, 0);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // Hidden past the ends, grown out of the bar above and below
            final int save = canvas.save();
            final int margin = getWidth();
            canvas.clipRect(getScrollX() - (canScrollHorizontally(-1) ? 0 : margin),
                    getScrollY() - getHeight(),
                    getScrollX() + getWidth() - getPaddingRight()
                            + (canScrollHorizontally(1) ? 0 : margin),
                    getScrollY() + 2 * getHeight());
            super.dispatchDraw(canvas);
            canvas.restoreToCount(save);
        }

        @Override
        public void setSpeed(float speed) {
            mGlide.setSpeed(speed);
        }

        @Override
        public void glideTo(int x) {
            mGlide.glideTo(x);
        }

        @Override
        public void jumpTo(int x) {
            mGlide.jumpTo(x);
        }

        @Override
        public void reveal(View child, int margin, boolean jump) {
            mGlide.reveal(child, margin, jump);
        }

        @Override
        protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
            return 0;
        }

        @Override
        protected void onDetachedFromWindow() {
            mGlide.cancel();
            super.onDetachedFromWindow();
        }
    }

    /**
     * Where a list of the bar's apps too long for its room ends: half way into an app, as the
     * focus's reveal leaves it, its second half faded out, so the cut reads as more to come and
     * not as an app drawn wrong. The room past it is padding, so the buttons after the list stay
     * where they are. The whole room when the list fits.
     */
    static int peekEnd(ViewGroup scroll, int room, boolean across) {
        if (scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof ViewGroup column)) {
            return room;
        }
        if ((across ? column.getMeasuredWidth() : column.getMeasuredHeight()) <= room) {
            return room;
        }
        int at = across ? column.getPaddingLeft() : column.getPaddingTop();
        int end = 0;
        for (int i = 0; i < column.getChildCount(); i++) {
            final View child = column.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            int before = 0;
            int after = 0;
            if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams lp) {
                before = across ? lp.leftMargin : lp.topMargin;
                after = across ? lp.rightMargin : lp.bottomMargin;
            }
            final int size = across ? child.getMeasuredWidth() : child.getMeasuredHeight();
            final int middle = at + before + size / 2;
            if (middle > room) {
                break;
            }
            end = middle;
            at += before + size + after;
        }
        return end > 0 ? end : room;
    }

    /** The one spring a scroll glides on, down or, {@code across}, sideways. */
    private static final class Glide {
        private final ViewGroup mView;
        private final boolean mAcross;
        private final SpringAnimation mSpring;
        private final Rect mRect = new Rect();
        private boolean mAnimate = true;

        Glide(ViewGroup view, boolean across) {
            mView = view;
            mAcross = across;
            mSpring = new SpringAnimation(this, new FloatPropertyCompat<Glide>("scroll") {
                @Override
                public float getValue(Glide glide) {
                    return glide.at();
                }

                @Override
                public void setValue(Glide glide, float value) {
                    glide.scroll(Math.round(value));
                }
            });
            mSpring.setMinimumVisibleChange(1f);
            setSpeed(1f);
        }

        void setSpeed(float speed) {
            mAnimate = speed > 0;
            // Critically damped: it eases in and never overshoots past the focus
            mSpring.setSpring(new SpringForce()
                    .setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
                    .setStiffness(speed >= 1 ? 900f : 1800f));
        }

        /** Where it is going, or is when still. */
        int target() {
            return mSpring.isRunning() ? Math.round(mSpring.getSpring().getFinalPosition())
                    : at();
        }

        void glideTo(int to) {
            to = Math.max(0, Math.min(to, maxScroll()));
            if (!mAnimate || !mView.isLaidOut()) {
                jumpTo(to);
                return;
            }
            if (to == target()) {
                return;
            }
            mSpring.animateToFinalPosition(to);
        }

        void jumpTo(int to) {
            mSpring.cancel();
            scroll(Math.max(0, Math.min(to, maxScroll())));
        }

        /** Measured from where the scroll is going, so steps quicker than the glide add up. */
        void reveal(View child, int margin, boolean jump) {
            if (mView.getChildCount() == 0 || !child.isLaidOut()) {
                return;
            }
            mRect.set(0, 0, child.getWidth(), child.getHeight());
            // In content coordinates, by layout: moves under way don't count
            mView.offsetDescendantRectToMyCoords(child, mRect);
            final int from = target();
            final int length = room();
            final int start = (mAcross ? mRect.left : mRect.top) - margin;
            final int end = (mAcross ? mRect.right : mRect.bottom) + margin;
            final int centre = mAcross ? mRect.centerX() : mRect.centerY();
            final int to = end - start >= length ? centre - length / 2
                    : start < from ? start : end > from + length ? end - length : from;
            if (jump) {
                jumpTo(to);
            } else {
                glideTo(to);
            }
        }

        void cancel() {
            mSpring.cancel();
        }

        private int at() {
            return mAcross ? mView.getScrollX() : mView.getScrollY();
        }

        private void scroll(int to) {
            if (mAcross) {
                mView.scrollTo(to, 0);
            } else {
                mView.scrollTo(0, to);
            }
        }

        private int room() {
            return mAcross ? mView.getWidth() - mView.getPaddingLeft() - mView.getPaddingRight()
                    : mView.getHeight() - mView.getPaddingTop() - mView.getPaddingBottom();
        }

        private int maxScroll() {
            if (mView.getChildCount() == 0) {
                return 0;
            }
            final View content = mView.getChildAt(0);
            return Math.max(0, (mAcross ? content.getWidth() : content.getHeight()) - room());
        }
    }
}
