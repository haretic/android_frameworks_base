/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo;
import android.view.accessibility.AccessibilityNodeProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Walks a system window's controls with the scroll wheel and presses them with the side button,
 * whether they are views or Compose: it reads the window's accessibility nodes in the process,
 * as a screen reader would, so a Compose tile is a stop as much as a notification row.
 *
 * <p>The stops are the clickable controls and the sliders in reading order. A clickable control
 * is one stop with what it holds: a quick settings tile toggles with a press (its checkable
 * part) and opens its settings with a hold. A press on a slider grabs it: the wheel then moves
 * its value, a step a tick, until a press or a few seconds without a turn let go. Down past the
 * last stop goes round to the first; up past the first goes back (onBackOut). A ring of its own
 * shows the stop, drawn over the window.
 *
 * <p>Keys: the wheel daemon's taskbar steps (NAVIGATE_NEXT and PREVIOUS, or the D-pad they fall
 * back to) and the side button, which the window gets as KEYCODE_POWER with
 * INPUT_FEATURE_POWER_KEY_SELECTS. Main thread only.
 */
public final class WheelNavigator {

    /** Let go of a grabbed slider after this long without a turn. */
    private static final long GRAB_TIMEOUT_MS = 4000;
    /** Slider steps: a fiftieth of the range, a tenth for steps that come this close. */
    private static final int SLIDER_STEPS = 50;
    private static final int SLIDER_FAST_STEPS = 10;
    private static final long FAST_STEP_MS = 60;
    /** How long a scroll the wheel asked for takes to lay out. */
    private static final long SCROLL_SETTLE_MS = 120;
    /** How many more times to look for what a scroll brought in while it still moves. */
    private static final int SCROLL_TRIES = 4;
    /** How long the shade takes to open or close its full panel. */
    private static final long LEVEL_SETTLE_MS = 450;

    /** A stop: a view, or a virtual node of the view's provider (Compose). */
    private static final class Stop {
        final View host;
        final int virtualId;
        final Rect bounds = new Rect();

        Stop(View host, int virtualId, Rect bounds) {
            this.host = host;
            this.virtualId = virtualId;
            this.bounds.set(bounds);
        }

        boolean same(Stop other) {
            return other != null && host == other.host && virtualId == other.virtualId;
        }
    }

    private final View mRoot;
    private final Predicate<View> mSkip;
    private final Runnable mOnBackOut;
    private Levels mLevels;
    private final Ring mRing;
    private final int[] mRootLocation = new int[2];
    private final ViewTreeObserver.OnPreDrawListener mFollow = () -> {
        updateRing();
        return true;
    };
    private final Runnable mLetGo = this::letGo;

    private Stop mStop;
    /** The scrolling containers stops() found, outermost first. */
    private final List<Stop> mScrollers = new ArrayList<>();
    /** Where the container the wheel last scrolled is on screen. */
    private final Rect mScrolled = new Rect();
    private final int[] mRootOnScreen = new int[2];
    /** What a scroll the wheel asked for leads to, while it still moves. */
    private Runnable mPendingStep;
    private boolean mGrabbed;
    /** The side button was held: the long press acted, the release must not. */
    private boolean mHeld;
    private long mLastStepTime;

    /**
     * @param root the window's root view
     * @param skip views to leave out with what they hold, as a header of status icons
     * @param onBackOut run when the wheel turns up past the first stop
     */
    public WheelNavigator(View root, Predicate<View> skip, Runnable onBackOut) {
        mRoot = root;
        mSkip = skip;
        mOnBackOut = onBackOut;
        mRing = new Ring(root.getContext());
    }

    /**
     * A window with more than one level, as the shade's quick tiles over its full panel: the wheel
     * goes a level down past the last stop, and a level up past the first.
     */
    public interface Levels {
        /** Opens the level below; whether there was one. */
        boolean deeper();

        /** Goes back to the level above; whether there was one. */
        boolean shallower();
    }

    public void setLevels(Levels levels) {
        mLevels = levels;
    }

    /** Whether the key was the wheel's or the side button's, and handled. */
    public boolean dispatchKey(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    step(1, event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    step(-1, event.getEventTime());
                }
                return true;
            case KeyEvent.KEYCODE_POWER:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        mHeld = false;
                    } else if ((event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0 && !mHeld) {
                        mHeld = true;
                        hold();
                    }
                } else if (!event.isCanceled() && !mHeld) {
                    press();
                }
                return true;
            default:
                return false;
        }
    }

    /** Forgets the stop and hides the ring, as when the window closes. */
    public void reset() {
        mStop = null;
        mPendingStep = null;
        letGo();
        hideRing();
    }

    // ---- Moving ----

    private void step(int direction, long time) {
        final boolean fast = time - mLastStepTime < FAST_STEP_MS;
        mLastStepTime = time;
        if (mGrabbed && mStop != null) {
            adjust(direction, fast);
            return;
        }
        if (mPendingStep != null) {
            // The step after a scroll comes before this one, at once
            final Runnable pending = mPendingStep;
            mPendingStep = null;
            pending.run();
        }
        final List<Stop> stops = stops();
        if (stops.isEmpty()) {
            return;
        }
        int index = indexOf(stops, mStop);
        if (index >= 0 && leavesScroller(stops, index, direction)
                && scrollOn(mStop, direction)) {
            // More beyond the edge: once it scrolled in, the first stop it brought
            final Stop from = mStop;
            final List<Stop> before = new ArrayList<>(stops);
            mPendingStep = () -> stepFrom(from, before, direction, 0);
            mRoot.postDelayed(() -> stepFrom(from, before, direction, SCROLL_TRIES),
                    SCROLL_SETTLE_MS);
            return;
        }
        if (index < 0) {
            // The first turn shows where the wheel is: the first stop, or the last going up
            index = direction > 0 ? 0 : stops.size() - 1;
        } else if (index + direction >= stops.size() && mLevels != null && mLevels.deeper()
                || index + direction < 0 && mLevels != null && mLevels.shallower()) {
            // The new level's first stop, once it's laid out
            reset();
            WheelFeedback.get(mRoot.getContext()).step(direction < 0);
            mRoot.postDelayed(this::firstStop, LEVEL_SETTLE_MS);
            return;
        } else if (index + direction < 0) {
            reset();
            WheelFeedback.get(mRoot.getContext()).back();
            mOnBackOut.run();
            return;
        } else {
            index = Math.floorMod(index + direction, stops.size());
        }
        moveTo(stops.get(index));
        WheelFeedback.get(mRoot.getContext()).step(direction < 0);
    }

    private void firstStop() {
        if (mStop != null) {
            return;
        }
        final List<Stop> stops = stops();
        if (!stops.isEmpty()) {
            moveTo(stops.get(0));
        }
    }

    /** The step after a scroll: the stop next to where the old one is now. */
    private void stepFrom(Stop from, List<Stop> before, int direction, int tries) {
        // Once only: from the scroll settling, or at once from the step after it
        if (mStop != from || tries > 0 && mPendingStep == null) {
            return;
        }
        // The stops that came into the scrolled container, the first of them that way
        final List<Stop> stops = stops();
        Stop next = null;
        for (Stop stop : stops) {
            if (mScrolled.contains(stop.bounds.centerX(), stop.bounds.centerY())
                    && indexOf(before, stop) < 0 && (next == null || direction < 0)) {
                next = stop;
            }
        }
        final int at = indexOf(stops, from);
        final boolean moved = at < 0 || !stops.get(at).bounds.equals(from.bounds);
        if (next == null && tries > 0 && moved) {
            // Still sliding in, as a page of tiles does; one that took the scroll and stayed
            // put had nothing more that way
            mRoot.postDelayed(() -> stepFrom(from, before, direction, tries - 1),
                    SCROLL_SETTLE_MS);
            return;
        }
        mPendingStep = null;
        if (next == null) {
            final int index = indexOf(stops, from);
            if (index >= 0 && index + direction >= 0 && index + direction < stops.size()) {
                next = stops.get(index + direction);
            }
        }
        if (next != null) {
            moveTo(next);
            WheelFeedback.get(mRoot.getContext()).step(direction < 0);
        }
    }

    private void moveTo(Stop stop) {
        mStop = stop;
        performAction(stop, AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId(), null);
        if (stop.virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
            stop.host.requestRectangleOnScreen(new Rect(0, 0, stop.host.getWidth(),
                    stop.host.getHeight()));
        }
        showRing();
    }

    private void press() {
        final AccessibilityNodeInfo node = mStop == null ? null : node(mStop);
        if (node == null) {
            step(1, SystemClock.uptimeMillis());
            return;
        }
        final WheelFeedback feedback = WheelFeedback.get(mRoot.getContext());
        if (mGrabbed) {
            letGo();
            feedback.select();
            return;
        }
        if (isSlider(node)) {
            mGrabbed = true;
            mRing.setGrabbed(true);
            mRoot.postDelayed(mLetGo, GRAB_TIMEOUT_MS);
            feedback.select();
            return;
        }
        // A tile toggles from its checkable part, anything else clicks itself
        final Stop toggle = checkableIn(mStop);
        feedback.select();
        performAction(toggle != null ? toggle : mStop, AccessibilityNodeInfo.ACTION_CLICK, null);
    }

    private void hold() {
        if (mStop == null || mGrabbed) {
            return;
        }
        WheelFeedback.get(mRoot.getContext()).select();
        performAction(mStop, AccessibilityNodeInfo.ACTION_LONG_CLICK, null);
    }

    private void adjust(int direction, boolean fast) {
        final AccessibilityNodeInfo node = node(mStop);
        final RangeInfo range = node == null ? null : node.getRangeInfo();
        if (range == null) {
            letGo();
            return;
        }
        mRoot.removeCallbacks(mLetGo);
        mRoot.postDelayed(mLetGo, GRAB_TIMEOUT_MS);
        final float span = range.getMax() - range.getMin();
        final float step = span / (fast ? SLIDER_FAST_STEPS : SLIDER_STEPS);
        final float value = Math.max(range.getMin(), Math.min(range.getMax(),
                range.getCurrent() + direction * step));
        if (value == range.getCurrent()) {
            return;
        }
        final Bundle args = new Bundle();
        args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value);
        performAction(mStop, AccessibilityAction.ACTION_SET_PROGRESS.getId(), args);
        WheelFeedback.get(mRoot.getContext()).tick();
    }

    private void letGo() {
        mRoot.removeCallbacks(mLetGo);
        if (mGrabbed) {
            mGrabbed = false;
            mRing.setGrabbed(false);
        }
    }

    // ---- The stops ----

    /** The stops in reading order: rows top to bottom, a row's stops left to right. */
    private List<Stop> stops() {
        final List<Stop> found = new ArrayList<>();
        mScrollers.clear();
        collect(mRoot, found);
        found.sort((a, b) -> Integer.compare(a.bounds.top, b.bounds.top));
        // Rows: stops that overlap the first of the row up and down are side by side
        final List<Stop> stops = new ArrayList<>(found.size());
        int start = 0;
        while (start < found.size()) {
            final Rect first = found.get(start).bounds;
            int end = start + 1;
            while (end < found.size()
                    && found.get(end).bounds.top < first.top + first.height() / 2) {
                end++;
            }
            final List<Stop> row = new ArrayList<>(found.subList(start, end));
            row.sort((a, b) -> Integer.compare(a.bounds.left, b.bounds.left));
            stops.addAll(row);
            start = end;
        }
        return stops;
    }

    private void collect(View view, List<Stop> stops) {
        if (!view.isShown() || view.getAlpha() == 0f || mSkip.test(view)) {
            return;
        }
        final AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
        if (provider != null) {
            collectVirtual(view, provider, AccessibilityNodeProvider.HOST_VIEW_ID, stops);
            return;
        }
        final AccessibilityNodeInfo node = view.createAccessibilityNodeInfo();
        noteScroller(view, AccessibilityNodeProvider.HOST_VIEW_ID, node);
        if (isStop(node) && onScreen(node)) {
            stops.add(new Stop(view, AccessibilityNodeProvider.HOST_VIEW_ID, bounds(node)));
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), stops);
            }
        }
    }

    private void collectVirtual(View host, AccessibilityNodeProvider provider, int id,
            List<Stop> stops) {
        final AccessibilityNodeInfo node = provider.createAccessibilityNodeInfo(id);
        if (node == null || !node.isVisibleToUser()) {
            return;
        }
        noteScroller(host, id, node);
        if (id != AccessibilityNodeProvider.HOST_VIEW_ID && isStop(node) && onScreen(node)) {
            stops.add(new Stop(host, id, bounds(node)));
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            final long childId = node.getChildId(i);
            final int virtualId = virtualIdOf(childId);
            if (virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
                // A view inside Compose (AndroidView): walk it as a view
                final View child = host.findViewByAccessibilityIdTraversal(
                        AccessibilityNodeInfo.getAccessibilityViewId(childId));
                if (child != null && child != host) {
                    collect(child, stops);
                }
            } else {
                collectVirtual(host, provider, virtualId, stops);
            }
        }
    }

    private void noteScroller(View host, int id, AccessibilityNodeInfo node) {
        if (node.isScrollable()) {
            mScrollers.add(new Stop(host, id, bounds(node)));
        }
    }

    /**
     * Whether the step from stops[index] goes out of a container that scrolls, as past the last
     * tile of a page to the buttons below: its next page comes first.
     */
    private boolean leavesScroller(List<Stop> stops, int index, int direction) {
        final int next = index + direction;
        if (next < 0 || next >= stops.size()) {
            return true;
        }
        final Rect from = stops.get(index).bounds;
        final Rect to = stops.get(next).bounds;
        for (Stop scroller : mScrollers) {
            if (scroller.bounds.contains(from.centerX(), from.centerY())
                    && !scroller.bounds.contains(to.centerX(), to.centerY())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scrolls the innermost container that holds the stop, if it still scrolls that way, so the
     * stops beyond its edge come into view. Whether it did.
     */
    private boolean scrollOn(Stop stop, int direction) {
        for (int i = mScrollers.size() - 1; i >= 0; i--) {
            final Stop scroller = mScrollers.get(i);
            if (stop != null && !scroller.bounds.contains(stop.bounds.centerX(),
                    stop.bounds.centerY())) {
                continue;
            }
            // Only the innermost: past the last page of tiles the panel around them stays put
            final int action = scrollAction(scroller, direction);
            if (action == 0 || !performAction(scroller, action, null)) {
                return false;
            }
            mScrolled.set(scroller.bounds);
            return true;
        }
        return false;
    }

    /**
     * The action that scrolls the container that way. One that names its directions offers only
     * those it can still go, as a pager of tiles on its last page offers no more to the right;
     * its plain forward scroll would drag it half way back. Others go forward or backward.
     */
    private static int scrollAction(Stop scroller, int direction) {
        final AccessibilityNodeInfo node = node(scroller);
        final List<AccessibilityAction> actions =
                node != null ? node.getActionList() : List.of();
        final boolean rtl = scroller.host.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        final AccessibilityAction later = rtl ? AccessibilityAction.ACTION_SCROLL_LEFT
                : AccessibilityAction.ACTION_SCROLL_RIGHT;
        final AccessibilityAction earlier = rtl ? AccessibilityAction.ACTION_SCROLL_RIGHT
                : AccessibilityAction.ACTION_SCROLL_LEFT;
        final AccessibilityAction laterPage = rtl ? AccessibilityAction.ACTION_PAGE_LEFT
                : AccessibilityAction.ACTION_PAGE_RIGHT;
        final AccessibilityAction earlierPage = rtl ? AccessibilityAction.ACTION_PAGE_RIGHT
                : AccessibilityAction.ACTION_PAGE_LEFT;
        final AccessibilityAction[] ways = direction > 0
                ? new AccessibilityAction[] {AccessibilityAction.ACTION_PAGE_DOWN, laterPage,
                        AccessibilityAction.ACTION_SCROLL_DOWN, later}
                : new AccessibilityAction[] {AccessibilityAction.ACTION_PAGE_UP, earlierPage,
                        AccessibilityAction.ACTION_SCROLL_UP, earlier};
        for (AccessibilityAction way : ways) {
            if (actions.contains(way)) {
                return way.getId();
            }
        }
        for (AccessibilityAction any : DIRECTIONS) {
            if (actions.contains(any)) {
                // It names its directions, and this is not one it can go
                return 0;
            }
        }
        return direction > 0 ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
    }

    private static final AccessibilityAction[] DIRECTIONS = {
            AccessibilityAction.ACTION_SCROLL_UP, AccessibilityAction.ACTION_SCROLL_DOWN,
            AccessibilityAction.ACTION_SCROLL_LEFT, AccessibilityAction.ACTION_SCROLL_RIGHT,
            AccessibilityAction.ACTION_PAGE_UP, AccessibilityAction.ACTION_PAGE_DOWN,
            AccessibilityAction.ACTION_PAGE_LEFT, AccessibilityAction.ACTION_PAGE_RIGHT};

    /**
     * Whether it shows in the window: a pager keeps the pages next to its own in the tree, a
     * strip of them peeking in at the window's sides, and going to one of those would drag it
     * half way over. The shade's controls keep clear of its sides, unless as wide as it.
     */
    private boolean onScreen(AccessibilityNodeInfo node) {
        final Rect rect = bounds(node);
        mRoot.getLocationOnScreen(mRootOnScreen);
        final int left = mRootOnScreen[0];
        final int right = left + mRoot.getWidth();
        final boolean atSide = rect.left <= left || rect.right >= right;
        return !rect.isEmpty() && (!atSide || rect.width() >= mRoot.getWidth() * 3 / 4)
                && rect.centerX() >= mRootOnScreen[0]
                && rect.centerX() < mRootOnScreen[0] + mRoot.getWidth()
                && rect.centerY() >= mRootOnScreen[1]
                && rect.centerY() < mRootOnScreen[1] + mRoot.getHeight();
    }

    private static boolean isStop(AccessibilityNodeInfo node) {
        return node != null && node.isEnabled() && node.isVisibleToUser()
                && (node.isClickable() || isSlider(node));
    }

    /** A range the user sets, not a progress bar. */
    private static boolean isSlider(AccessibilityNodeInfo node) {
        return node.getRangeInfo() != null
                && node.getActionList().contains(AccessibilityAction.ACTION_SET_PROGRESS);
    }

    /** The checkable control inside a stop, as a quick settings tile's toggle, or null. */
    private Stop checkableIn(Stop stop) {
        final AccessibilityNodeInfo node = node(stop);
        if (node == null || node.isCheckable()) {
            return null;
        }
        final AccessibilityNodeProvider provider = stop.host.getAccessibilityNodeProvider();
        if (stop.virtualId == AccessibilityNodeProvider.HOST_VIEW_ID || provider == null) {
            return null;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            final int id = virtualIdOf(node.getChildId(i));
            final AccessibilityNodeInfo child = provider.createAccessibilityNodeInfo(id);
            if (child != null && child.isCheckable() && child.isClickable()) {
                return new Stop(stop.host, id, bounds(child));
            }
        }
        return null;
    }

    private static int indexOf(List<Stop> stops, Stop stop) {
        for (int i = 0; stop != null && i < stops.size(); i++) {
            if (stops.get(i).same(stop)) {
                return i;
            }
        }
        return -1;
    }

    private static AccessibilityNodeInfo node(Stop stop) {
        if (stop.virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
            return stop.host.isAttachedToWindow() ? stop.host.createAccessibilityNodeInfo()
                    : null;
        }
        final AccessibilityNodeProvider provider = stop.host.getAccessibilityNodeProvider();
        return provider == null ? null : provider.createAccessibilityNodeInfo(stop.virtualId);
    }

    private static boolean performAction(Stop stop, int action, Bundle args) {
        if (stop.virtualId == AccessibilityNodeProvider.HOST_VIEW_ID) {
            // As a screen reader would: a notification's long click opens its controls
            return stop.host.performAccessibilityAction(action, args);
        }
        final AccessibilityNodeProvider provider = stop.host.getAccessibilityNodeProvider();
        return provider != null && provider.performAction(stop.virtualId, action, args);
    }

    private static Rect bounds(AccessibilityNodeInfo node) {
        final Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        return rect;
    }

    private static int virtualIdOf(long nodeId) {
        return AccessibilityNodeInfo.getVirtualDescendantId(nodeId);
    }

    // ---- The ring ----

    private void showRing() {
        if (!mRing.attached) {
            mRoot.getOverlay().add(mRing);
            mRoot.getViewTreeObserver().addOnPreDrawListener(mFollow);
            mRing.attached = true;
        }
        updateRing();
    }

    private void hideRing() {
        if (mRing.attached) {
            mRoot.getOverlay().remove(mRing);
            mRoot.getViewTreeObserver().removeOnPreDrawListener(mFollow);
            mRing.attached = false;
        }
    }

    private void updateRing() {
        final AccessibilityNodeInfo node = mStop == null ? null : node(mStop);
        if (node == null || !node.isVisibleToUser()) {
            mRing.setBounds(0, 0, 0, 0);
            return;
        }
        final Rect bounds = bounds(node);
        mRoot.getLocationOnScreen(mRootLocation);
        bounds.offset(-mRootLocation[0], -mRootLocation[1]);
        if (!Objects.equals(bounds, mRing.getBounds())) {
            mRing.setBounds(bounds);
            mRoot.invalidate();
        }
    }

    /** The focus ring: an outline round the stop, thicker while a slider is grabbed. */
    private static final class Ring extends Drawable {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final float mStroke;
        private final float mRadius;
        boolean attached;
        private boolean mGrabbed;

        Ring(Context context) {
            mStroke = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3,
                    context.getResources().getDisplayMetrics());
            mRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20,
                    context.getResources().getDisplayMetrics());
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setColor(context.getColor(com.android.internal.R.color.system_accent1_100));
        }

        void setGrabbed(boolean grabbed) {
            mGrabbed = grabbed;
            invalidateSelf();
        }

        @Override
        public void draw(Canvas canvas) {
            if (getBounds().isEmpty()) {
                return;
            }
            final float stroke = mGrabbed ? mStroke * 2 : mStroke;
            mPaint.setStrokeWidth(stroke);
            mRect.set(getBounds());
            // Just outside the stop, so the ring doesn't cover it
            mRect.inset(-stroke, -stroke);
            canvas.drawRoundRect(mRect, mRadius, mRadius, mPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            mPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
            mPaint.setColorFilter(filter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
