/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * A swipe across the wheel shade switches its tab (WheelShadeTabs): towards Controls from
 * Notifications, back the other way. A swipe that starts on what takes a sideways drag itself, as a
 * notification row, a slider or the media carousel, stays that control's; one that turns into a
 * scroll up or down stays the shade's.
 */
public final class WheelShadeTabSwipe {

    /** How far across a swipe goes before it switches. */
    private static final float SWITCH_DP = 48;

    /** The shade the swipe is in. */
    public interface Host {
        /** Whether the shade shows its tabs now: open on its own, not on the keyguard or editing. */
        boolean showsTabs();

        /** Whether Controls is the tab showing. */
        boolean showsControls();

        /** Whether what is at the screen point takes a sideways drag of its own. */
        boolean takesSidewaysDrag(float rawX, float rawY);

        /** Whether the shade reads right to left, Controls on the left. */
        boolean isRtl();

        /** Switches to the other tab. */
        void switchTab();
    }

    private final Context mContext;
    private final Host mHost;
    private final int mTouchSlop;
    private final float mSwitchDistance;
    private boolean mTracking;
    private float mDownX;
    private float mDownY;

    public WheelShadeTabSwipe(Context context, Host host) {
        mContext = context;
        mHost = host;
        mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mSwitchDistance =
                TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        SWITCH_DP,
                        context.getResources().getDisplayMetrics());
    }

    /**
     * Follows the shade's touches; whether this one switched the tab. The caller then cancels the
     * touch for the views under it, so the tile or notification the swipe crossed takes no tap.
     */
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = event.getRawX();
                mDownY = event.getRawY();
                mTracking =
                        WheelLayoutSettings.shade(mContext)
                                && mHost.showsTabs()
                                && !mHost.takesSidewaysDrag(mDownX, mDownY);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!mTracking) {
                    return false;
                }
                final float dx = event.getRawX() - mDownX;
                final float dy = Math.abs(event.getRawY() - mDownY);
                if (dy > mTouchSlop && dy > Math.abs(dx)) {
                    mTracking = false; // A scroll of the list, or the shade closing
                    return false;
                }
                if (Math.abs(dx) < mSwitchDistance || Math.abs(dx) < 2 * dy) {
                    return false;
                }
                mTracking = false;
                // Leftwards in a left-to-right shade brings Controls in from the right
                final boolean towardsControls = (dx < 0) != mHost.isRtl();
                if (towardsControls == mHost.showsControls()) {
                    return false;
                }
                mHost.switchTab();
                return true;
            default:
                // A second finger, the end of the touch
                mTracking = false;
                return false;
        }
    }
}
