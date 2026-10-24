/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.keyguard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.systemui.res.R;
import com.android.systemui.wheel.WheelNavigator;

/**
 * The wheel UI's PIN pad: a drum of the digits, OK and Delete. The wheel turns it, the side button
 * enters the item in its middle and holding the button deletes a digit; by touch a drag turns it
 * and a tap enters.
 */
public final class WheelPinDrum extends View implements WheelNavigator.Dial {

    /** What the drum enters. */
    public interface Listener {
        void onDigit(int digit);

        void onOk();

        void onDelete();
    }

    private static final int OK = 10;
    private static final int DELETE = 11;
    private static final int ITEMS = 12;

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBox = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mBoxRect = new RectF();
    private final int[] mOnScreen = new int[2];
    private final float mMaxRowHeight;
    private float mRowHeight;
    private final float mTouchSlop;
    private Listener mListener;
    private int mAt;
    private float mDownY;
    private float mDragged;
    private boolean mDragging;

    public WheelPinDrum(Context context) {
        super(context);
        mMaxRowHeight = dp(44);
        mRowHeight = mMaxRowHeight;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mText.setTextAlign(Paint.Align.CENTER);
        mText.setColor(context.getColor(com.android.internal.R.color.materialColorOnSurface));
        mHint.setTextAlign(Paint.Align.CENTER);
        mHint.setTextSize(sp(15));
        mHint.setColor(
                context.getColor(com.android.internal.R.color.materialColorOnSurfaceVariant));
        mBox.setColor(
                context.getColor(com.android.internal.R.color.materialColorSurfaceContainerHigh));
        setClickable(true);
        setFocusable(true);
        updateDescription();
    }

    public void setListener(Listener listener) {
        mListener = listener;
    }

    /** Back to the first digit, as for a new PIN. */
    public void reset() {
        mAt = 0;
        updateDescription();
        invalidate();
    }

    @Override
    public void onWheelStep(int direction) {
        turn(direction);
    }

    @Override
    public void onWheelPress() {
        enter();
    }

    @Override
    public void onWheelHold() {
        if (mListener != null) {
            mListener.onDelete();
        }
    }

    private void turn(int direction) {
        mAt = Math.floorMod(mAt + direction, ITEMS);
        updateDescription();
        invalidate();
    }

    private void enter() {
        if (mListener == null) {
            return;
        }
        if (mAt == OK) {
            mListener.onOk();
        } else if (mAt == DELETE) {
            mListener.onDelete();
        } else {
            mListener.onDigit(mAt);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        enter();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = event.getY();
                mDragged = 0;
                mDragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                final float dy = event.getY() - mDownY;
                if (!mDragging && Math.abs(dy) > mTouchSlop) {
                    mDragging = true;
                }
                if (mDragging) {
                    // Dragging up brings the items below into the middle, as a list scrolls
                    while (dy - mDragged <= -mRowHeight) {
                        mDragged -= mRowHeight;
                        turn(1);
                    }
                    while (dy - mDragged >= mRowHeight) {
                        mDragged += mRowHeight;
                        turn(-1);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!mDragging) {
                    performClick();
                }
                return true;
            default:
                return true;
        }
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        final int height = (int) (mMaxRowHeight * 5 + dp(56));
        setMeasuredDimension(
                getDefaultSize(getSuggestedMinimumWidth(), widthSpec),
                resolveSize(height, heightSpec));
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        // Five rows and the hints below them, closer together on a short screen
        mRowHeight = Math.max(dp(28), Math.min(mMaxRowHeight, (height - dp(56)) / 5));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f;
        final float cy = mRowHeight * 2.5f;
        updateBox();
        final float radius = mBoxRect.height() / 2;
        canvas.drawRoundRect(mBoxRect, radius, radius, mBox);
        for (int offset = -2; offset <= 2; offset++) {
            final int item = Math.floorMod(mAt + offset, ITEMS);
            final boolean word = item >= OK;
            final float size = offset == 0 ? (word ? sp(30) : sp(44)) : (word ? sp(20) : sp(26));
            mText.setTextSize(size);
            mText.setAlpha(offset == 0 ? 255 : Math.abs(offset) == 1 ? 140 : 70);
            // The middle row's neighbours sit out past its box
            final float y = cy + offset * mRowHeight + Math.signum(offset) * dp(8);
            canvas.drawText(label(item), cx, y - (mText.ascent() + mText.descent()) / 2, mText);
        }
        final float hintY = mRowHeight * 5 + dp(20);
        canvas.drawText(hint(), cx, hintY, mHint);
        canvas.drawText(
                getContext().getString(R.string.wheel_pin_hold), cx, hintY + dp(22), mHint);
    }

    private void updateBox() {
        final float cx = getWidth() / 2f;
        final float cy = mRowHeight * 2.5f;
        final float halfWidth = dp(64);
        final float halfHeight = mRowHeight * 0.7f;
        mBoxRect.set(cx - halfWidth, cy - halfHeight, cx + halfWidth, cy + halfHeight);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        // The wheel's ring goes round the middle row, the item a press enters
        updateBox();
        getLocationOnScreen(mOnScreen);
        info.setBoundsInScreen(
                new Rect(
                        mOnScreen[0] + (int) mBoxRect.left,
                        mOnScreen[1] + (int) mBoxRect.top,
                        mOnScreen[0] + (int) mBoxRect.right,
                        mOnScreen[1] + (int) mBoxRect.bottom));
    }

    private String label(int item) {
        if (item == OK) {
            return getContext().getString(R.string.wheel_pin_ok);
        }
        if (item == DELETE) {
            return getContext().getString(R.string.wheel_pin_delete);
        }
        return Integer.toString(item);
    }

    private String hint() {
        return getContext().getString(
                mAt == OK
                        ? R.string.wheel_pin_hint_ok
                        : mAt == DELETE ? R.string.wheel_pin_hint_delete : R.string.wheel_pin_hint);
    }

    private void updateDescription() {
        setContentDescription(label(mAt));
    }

    private float dp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    private float sp(float value) {
        return TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }
}
