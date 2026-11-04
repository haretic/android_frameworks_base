/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
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
 *
 * <p>The bar's apps show whole: an icon cut or faded at the list's end read as one drawn
 * wrong. A list longer than its room gets a band at each end, a chevron in it while more lies
 * that way, and scrolls by whole apps; see {@link #setWholeCells}.
 */
final class WheelTaskbarScroll extends ScrollView implements WheelTaskbarGlides {

    private final Glide mGlide;
    private final MoreCue mMore;
    private boolean mWholeCells;

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
        mMore = new MoreCue(context);
    }

    /** Whole rows only, for the bar's apps: see the class's comment. */
    void setWholeCells(boolean whole) {
        mWholeCells = whole;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        final int room = getMeasuredHeight();
        int top = 0;
        int bottom = room - peekEnd(this, room, false);
        if (mWholeCells) {
            final int end = wholeEnd(this, room, mMore.size, false);
            top = end < room ? mMore.size : 0;
            bottom = room - top - end;
        }
        if (top != getPaddingTop() || bottom != getPaddingBottom()) {
            setPadding(0, top, 0, bottom);
        }
    }

    /** The fade at the end of the list's room, over the row peeking out, see peekEnd. */
    @Override
    protected boolean isPaddingOffsetRequired() {
        return !mWholeCells;
    }

    @Override
    protected float getTopFadingEdgeStrength() {
        return mWholeCells ? 0f : super.getTopFadingEdgeStrength();
    }

    @Override
    protected float getBottomFadingEdgeStrength() {
        return mWholeCells ? 0f : super.getBottomFadingEdgeStrength();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        // Apps scrolled out stay hidden above and below; to the sides one grows out of the bar,
        // and at an end with nothing past it the first or last one moves out a little too
        final int save = canvas.save();
        final int margin = getHeight();
        canvas.clipRect(getScrollX() - getWidth(),
                getScrollY() + (canScrollVertically(-1) ? getPaddingTop() : -margin),
                getScrollX() + 2 * getWidth(),
                getScrollY() + getHeight() - getPaddingBottom()
                        + (canScrollVertically(1) ? 0 : margin));
        super.dispatchDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (!mWholeCells) {
            return;
        }
        final float x = getScrollX() + getWidth() / 2f;
        if (canScrollVertically(-1)) {
            mMore.draw(canvas, x, getScrollY() + getPaddingTop() / 2f, false, false);
        }
        if (canScrollVertically(1)) {
            mMore.draw(canvas, x, getScrollY() + getHeight() - getPaddingBottom() / 2f, false,
                    true);
        }
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
        private final MoreCue mMore;

        Row(Context context) {
            super(context);
            setFocusable(false);
            setHorizontalScrollBarEnabled(false);
            setHorizontalFadingEdgeEnabled(true);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
            setClipChildren(false);
            mGlide = new Glide(this, true);
            mMore = new MoreCue(context);
        }

        /** The bar's apps, whole ones only: see the class's comment. */
        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            final int room = getMeasuredWidth();
            final int end = wholeEnd(this, room, mMore.size, true);
            final int left = end < room ? mMore.size : 0;
            final int right = room - left - end;
            if (left != getPaddingLeft() || right != getPaddingRight()) {
                setPadding(left, 0, right, 0);
            }
        }

        @Override
        protected float getLeftFadingEdgeStrength() {
            return 0f;
        }

        @Override
        protected float getRightFadingEdgeStrength() {
            return 0f;
        }

        @Override
        public void draw(Canvas canvas) {
            super.draw(canvas);
            final float y = getScrollY() + getHeight() / 2f;
            if (canScrollHorizontally(-1)) {
                mMore.draw(canvas, getScrollX() + getPaddingLeft() / 2f, y, true, false);
            }
            if (canScrollHorizontally(1)) {
                mMore.draw(canvas, getScrollX() + getWidth() - getPaddingRight() / 2f, y, true,
                        true);
            }
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            // Hidden past the ends, grown out of the bar above and below
            final int save = canvas.save();
            final int margin = getWidth();
            canvas.clipRect(getScrollX() + (canScrollHorizontally(-1) ? getPaddingLeft() : -margin),
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
     * Where a list too long for its room ends: half way into a row, as the focus's reveal
     * leaves it, its second half faded out, so the cut reads as more to come. The room past it is padding, so the buttons after the list stay
     * where they are. The whole room when the list fits.
     */
    static int peekEnd(ViewGroup scroll, int room, boolean across) {
        if (scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof ViewGroup content)) {
            return room;
        }
        // The rows: the content itself, or the column in it over a focus light as big as it, as
        // a list's are; the light no row to end in
        ViewGroup column = content;
        if (!(content instanceof LinearLayout)) {
            for (int i = 0; i < content.getChildCount(); i++) {
                if (content.getChildAt(i) instanceof LinearLayout rows) {
                    column = rows;
                    break;
                }
            }
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

    /**
     * Where a list of the bar's apps too long for its room ends, measured from the start of the
     * room after the band at its start: after the last whole app that leaves a band of
     * {@code cue} at each end. The whole room when the list fits.
     */
    static int wholeEnd(ViewGroup scroll, int room, int cue, boolean across) {
        if (scroll.getChildCount() == 0 || !(scroll.getChildAt(0) instanceof ViewGroup column)
                || (across ? column.getMeasuredWidth() : column.getMeasuredHeight()) <= room) {
            return room;
        }
        final int fits = room - 2 * cue;
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
            final int next = at + before + (across ? child.getMeasuredWidth()
                    : child.getMeasuredHeight()) + after;
            if (next > fits) {
                break;
            }
            end = next;
            at = next;
        }
        // Not even one app fits between the bands: the room as it is, cut as before
        return end > 0 ? end : room;
    }

    /** A small chevron in a list's end band while more lies past it. */
    private static final class MoreCue {
        final int size;
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mPath = new Path();
        private final float mHalf;

        MoreCue(Context context) {
            final float density = context.getResources().getDisplayMetrics().density;
            size = Math.round(16 * density);
            mHalf = 4 * density;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(2 * density);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(context.getColor(
                    com.android.internal.R.color.materialColorOnSurfaceVariant));
        }

        /** Pointing on (down, or right {@code across}) or back, centred on x, y. */
        void draw(Canvas canvas, float x, float y, boolean across, boolean on) {
            final float tip = on ? mHalf / 2 : -mHalf / 2;
            mPath.rewind();
            if (across) {
                mPath.moveTo(x - tip, y - mHalf);
                mPath.lineTo(x + tip, y);
                mPath.lineTo(x - tip, y + mHalf);
            } else {
                mPath.moveTo(x - mHalf, y - tip);
                mPath.lineTo(x, y + tip);
                mPath.lineTo(x + mHalf, y - tip);
            }
            canvas.drawPath(mPath, mPaint);
        }
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
            // The scroll that puts the room's start, after the band there, on the app's start
            final int pad = mAcross ? mView.getPaddingLeft() : mView.getPaddingTop();
            // Whole apps line up with the room's ends, margin or not
            if (wholeCells()) {
                margin = 0;
            }
            final int start = (mAcross ? mRect.left : mRect.top) - pad - margin;
            final int end = (mAcross ? mRect.right : mRect.bottom) - pad + margin;
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

        private boolean wholeCells() {
            return mView instanceof Row
                    || (mView instanceof WheelTaskbarScroll column && column.mWholeCells);
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
