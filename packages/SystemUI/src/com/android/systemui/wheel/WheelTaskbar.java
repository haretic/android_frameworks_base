/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.android.systemui.CoreStartable;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.res.R;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.domain.interactor.ShadeInteractor;
import com.android.systemui.shared.system.ActivityManagerWrapper;
import com.android.systemui.shared.system.TaskStackChangeListener;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.kotlin.JavaAdapter;

import lineageos.providers.LineageSettings;

import org.lineageos.internal.util.PowerMenuUtils;

import dagger.Lazy;

import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The taskbar of a device with a scroll wheel and one side button (config_wheelTaskbar), a
 * column at the screen's edge like the Unity launcher: home, all apps, the pinned and the
 * running apps (a dot for each running one, a tall one for the app in front; an app the
 * system closed loses its dot or, unpinned, its place), notifications, the wheel mode of the
 * app in front and, last at the bottom, the taskbar's own settings (WheelTaskbarSettings: the
 * buttons and their order, the edge, sizes, colours, timing and more). The app slides aside to
 * make room. The wheel moves the focus with a tick and goes round at the ends; up out of a
 * list goes back to the bar. The side button presses (INPUT_FEATURE_POWER_KEY_SELECTS);
 * holding it closes the focused running app, or pins and unpins an app in the list of all
 * apps. The bar closes on back, on a touch outside, or when left alone for a moment.
 *
 * <p>With a wheel daemon (config_wheelDaemonSocket) the wheel is in one of its modes, set
 * here for the app in front (Wheel mode, LineageSettings.Secure.WHEEL_APP_MODES, defaults in
 * config_wheelAppModeDefaults): taskbar, where the policy hands every step an app would get
 * to SystemUI as a system key and the first one opens the bar; app, the D-pad for the app;
 * volume. A double press of the side button opens the bar in any mode. Without the daemon,
 * the bar opens on the steps the app leaves unhandled.
 */
@SysUISingleton
public class WheelTaskbar implements CoreStartable, CommandQueue.Callbacks {

    private static final int MENU_NONE = 0;
    private static final int MENU_DRAWER = 1;
    private static final int MENU_MODES = 2;

    /** The wheel daemon's modes (r1-wheeld on the rabbit r1). */
    private static final String MODE_TASKBAR = "taskbar";
    private static final String MODE_APP = "app";
    private static final String MODE_VOLUME = "volume";

    /** Steps closer than this make a fast spin, which skips by letter in the list of apps. */
    private static final long FAST_STEP_MS = 70;
    private static final int FAST_STEPS = 3;

    private static final long SLIDE_MS = 280;
    private static final long PANEL_MS = 200;
    private static final long FOCUS_MS = 120;

    private final Context mContext;
    private final CommandQueue mCommandQueue;
    private final KeyguardStateController mKeyguardStateController;
    private final Lazy<ShadeController> mShadeController;
    private final NotificationListener mNotificationListener;
    private final UserTracker mUserTracker;
    private final Handler mHandler;
    private final WheelTaskbarSlide mSlide;
    private final WindowManager mWindowManager;
    private final Executor mBackground;
    private final JavaAdapter mJavaAdapter;
    private final ShadeInteractor mShadeInteractor;
    private final WheelTaskbarSettings mSettings;
    private final Runnable mHide = this::hide;
    private final Runnable mStackChanged = this::onStackChanged;

    /** Null without a wheel daemon. */
    private WheelModeClient mModeClient;
    /** The modes picked per app, package to mode. */
    private final Map<String, String> mAppModes = new LinkedHashMap<>();
    /** The device's modes for apps the user hasn't picked one for. */
    private final Map<String, String> mDefaultModes = new LinkedHashMap<>();
    private String mForegroundPackage;
    private int mForegroundTask = -1;
    private boolean mShadeExpanded;

    private View mRoot;
    /** The bar sliding out, removed once it is off screen. */
    private View mClosing;
    private LinearLayout mBar;
    private LinearLayout mAppsColumn;
    private List<WheelTaskbarApps.Entry> mApps = new ArrayList<>();
    private LinearLayout mPanel;
    private TextView mPanelTitle;
    private ScrollView mPanelScroll;
    private LinearLayout mPanelItems;
    private TextView mLabel;
    private TextView mBadge;
    private ImageView mModeIcon;
    private int mMenuType = MENU_NONE;
    /** The bar button a list was opened from, where the focus goes back. */
    private View mMenuButton;
    /** A long press of the side button already acted, ignore the rest of it. */
    private boolean mLongPressed;
    private long mLastStep;
    /** A long press is closing an app, its button fading out. */
    private boolean mClosingApp;
    private int mFastSteps;

    @Inject
    public WheelTaskbar(Context context, CommandQueue commandQueue,
            KeyguardStateController keyguardStateController,
            NotificationListener notificationListener, UserTracker userTracker,
            Lazy<ShadeController> shadeController, ShadeInteractor shadeInteractor,
            JavaAdapter javaAdapter, @Main Handler handler, @Main Executor executor,
            @Background Executor background) {
        mContext = context;
        mShadeController = shadeController;
        mShadeInteractor = shadeInteractor;
        mJavaAdapter = javaAdapter;
        mBackground = background;
        mCommandQueue = commandQueue;
        mKeyguardStateController = keyguardStateController;
        mNotificationListener = notificationListener;
        mUserTracker = userTracker;
        mHandler = handler;
        mSlide = new WheelTaskbarSlide(executor);
        mWindowManager = context.getSystemService(WindowManager.class);
        mSettings = new WheelTaskbarSettings(context);
    }

    @Override
    public void start() {
        if (!mContext.getResources().getBoolean(R.bool.config_wheelTaskbar)) {
            return;
        }
        mSlide.register();
        mCommandQueue.addCallback(this);
        for (String entry : mContext.getResources().getStringArray(
                R.array.config_wheelAppModeDefaults)) {
            final int equals = entry.indexOf('=');
            if (equals > 0) {
                mDefaultModes.put(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
        TaskStackChangeListeners.getInstance().registerTaskStackListener(
                new TaskStackChangeListener() {
                    @Override
                    public void onTaskMovedToFront(ActivityManager.RunningTaskInfo info) {
                        mForegroundPackage = packageOf(info);
                        mForegroundTask = info == null ? -1 : info.taskId;
                        updateMode();
                    }

                    @Override
                    public void onTaskStackChanged() {
                        // A task that finishes brings the one under it to the front without
                        // a move; apps started, closed or killed show in an open bar
                        mHandler.removeCallbacks(mStackChanged);
                        mHandler.postDelayed(mStackChanged, 150);
                    }
                });
        final ActivityManager.RunningTaskInfo front =
                ActivityManagerWrapper.getInstance().getRunningTask();
        mForegroundPackage = packageOf(front);
        mForegroundTask = front == null ? -1 : front.taskId;

        final String socket = mContext.getResources().getString(
                com.android.internal.R.string.config_wheelDaemonSocket);
        if (socket.isEmpty()) {
            return;
        }
        mModeClient = new WheelModeClient(socket, mBackground);
        WheelSystemWindows.addListener(this::updateMode);
        final android.database.ContentObserver modes =
                new android.database.ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        readAppModes();
                        updateMode();
                    }
                };
        for (String key : new String[] {LineageSettings.Secure.WHEEL_UI,
                LineageSettings.Secure.WHEEL_APP_MODES}) {
            mContext.getContentResolver().registerContentObserver(
                    LineageSettings.Secure.getUriFor(key), false, modes, UserHandle.USER_ALL);
        }
        mUserTracker.addCallback(new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                mSettings.read(newUser);
                readAppModes();
                updateMode();
            }
        }, mContext.getMainExecutor());
        mKeyguardStateController.addCallback(new KeyguardStateController.Callback() {
            @Override
            public void onKeyguardShowingChanged() {
                updateMode();
            }
        });
        mJavaAdapter.alwaysCollectFlow(mShadeInteractor.isAnyExpanded(), expanded -> {
            mShadeExpanded = expanded;
            updateMode();
        });
        readAppModes();
        updateMode();
    }

    private void onStackChanged() {
        mBackground.execute(() -> {
            final ActivityManager.RunningTaskInfo front =
                    ActivityManagerWrapper.getInstance().getRunningTask();
            mHandler.post(() -> {
                final String packageName = packageOf(front);
                final int taskId = front == null ? -1 : front.taskId;
                if (taskId != mForegroundTask || !TextUtils.equals(packageName,
                        mForegroundPackage)) {
                    mForegroundPackage = packageName;
                    mForegroundTask = taskId;
                    updateMode();
                }
                // An app closing from the bar refreshes once it faded out
                if (!mClosingApp) {
                    refreshApps(-1);
                }
            });
        });
    }

    @Override
    public void handleSystemKey(KeyEvent key) {
        if (key.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        final int step;
        switch (key.getKeyCode()) {
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                step = 1;
                break;
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
            case KeyEvent.KEYCODE_DPAD_UP:
                step = -1;
                break;
            case KeyEvent.KEYCODE_NAVIGATE_IN:
                // The side button's double press
                step = 0;
                break;
            default:
                return;
        }
        if (mRoot != null) {
            // Steps that came before the bar had the focus
            if (step != 0) {
                keepOpen();
                move(step);
            }
            return;
        }
        if (mKeyguardStateController.isShowing() || !isEnabled() || mShadeExpanded
                || mShadeController.get().isExpandedVisible()) {
            return;
        }
        show();
    }

    /** The stock look (Settings > System > Taskbar and wheel) has no taskbar. */
    private boolean isEnabled() {
        return LineageSettings.Secure.getIntForUser(mContext.getContentResolver(),
                LineageSettings.Secure.WHEEL_UI, 1, mUserTracker.getUserId()) != 0;
    }

    // ---- The wheel daemon's mode ----

    private static String packageOf(ActivityManager.RunningTaskInfo info) {
        if (info == null) {
            return null;
        }
        final ComponentName top = info.topActivity != null ? info.topActivity
                : info.baseActivity;
        return top == null ? null : top.getPackageName();
    }

    private void readAppModes() {
        mAppModes.clear();
        final String modes = LineageSettings.Secure.getStringForUser(
                mContext.getContentResolver(), LineageSettings.Secure.WHEEL_APP_MODES,
                mUserTracker.getUserId());
        if (TextUtils.isEmpty(modes)) {
            return;
        }
        for (String entry : modes.split(",")) {
            final int equals = entry.indexOf('=');
            if (equals > 0) {
                final String mode = entry.substring(equals + 1);
                if (MODE_TASKBAR.equals(mode) || MODE_APP.equals(mode)
                        || MODE_VOLUME.equals(mode)) {
                    mAppModes.put(entry.substring(0, equals), mode);
                }
            }
        }
    }

    private String appMode(String packageName) {
        if (packageName == null) {
            return MODE_TASKBAR;
        }
        final String mode = mAppModes.get(packageName);
        if (mode != null) {
            return mode;
        }
        return mDefaultModes.getOrDefault(packageName, MODE_TASKBAR);
    }

    private void setAppMode(String packageName, String mode) {
        mAppModes.remove(packageName);
        if (!mode.equals(mDefaultModes.getOrDefault(packageName, MODE_TASKBAR))) {
            mAppModes.put(packageName, mode);
        }
        final StringBuilder modes = new StringBuilder();
        for (Map.Entry<String, String> entry : mAppModes.entrySet()) {
            if (modes.length() > 0) {
                modes.append(',');
            }
            modes.append(entry.getKey()).append('=').append(entry.getValue());
        }
        LineageSettings.Secure.putStringForUser(mContext.getContentResolver(),
                LineageSettings.Secure.WHEEL_APP_MODES, modes.toString(),
                mUserTracker.getUserId());
        updateMode();
    }

    /**
     * The daemon's mode: the bar, the shade and the lock screen move with taskbar steps (the
     * key character map makes them D-pad steps there); an app gets its own mode. The stock
     * look (WHEEL_UI off) has the wheel as the D-pad, as without the daemon.
     */
    private void updateMode() {
        if (mModeClient == null) {
            return;
        }
        final String mode;
        if (!isEnabled()) {
            mode = MODE_APP;
        } else if (mRoot != null || mShadeExpanded || mKeyguardStateController.isShowing()
                || WheelSystemWindows.showing()) {
            mode = MODE_TASKBAR;
        } else {
            mode = appMode(mForegroundPackage);
        }
        mModeClient.set(mode);
    }

    // ---- The bar ----

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                mContext.getResources().getDisplayMetrics());
    }

    /** The bar's width, its cells and buttons for the size setting. */
    private int barWidth() {
        return dp(new int[] {56, 64, 76}[mSettings.size]);
    }

    private int cellSize() {
        return dp(new int[] {42, 48, 58}[mSettings.size]);
    }

    private int buttonSize() {
        return dp(new int[] {36, 40, 50}[mSettings.size]);
    }

    private int barColor() {
        switch (mSettings.color) {
            case 1:
                return mContext.getColor(R.color.wheel_taskbar_background) & 0xccffffff;
            case 2:
                return mContext.getColor(android.R.color.system_accent1_900);
            default:
                return mContext.getColor(R.color.wheel_taskbar_background);
        }
    }

    private int ringColor() {
        switch (mSettings.ring) {
            case 1:
                return Color.WHITE;
            case 2:
                return mContext.getColor(android.R.color.system_accent1_300);
            default:
                return mContext.getColor(R.color.wheel_taskbar_ring);
        }
    }

    /** A button or row background with the focus ring in the ring colour. */
    private Drawable background(float radius, int fill) {
        final GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(radius);
        focused.setColor(fill);
        focused.setStroke(dp(2), ringColor());
        final GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(radius);
        pressed.setColor(ringColor() & 0x66ffffff);
        final GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(radius);
        normal.setColor(fill);
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, focused);
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], normal);
        states.setEnterFadeDuration((int) mSettings.duration(FOCUS_MS));
        states.setExitFadeDuration((int) mSettings.duration(FOCUS_MS));
        return states;
    }

    private void show() {
        removeClosing();
        mSettings.read(mUserTracker.getUserId());
        final FrameLayout root = new FrameLayout(mContext) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                return onKey(event) || super.dispatchKeyEvent(event);
            }

            @Override
            public boolean dispatchTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    hide();
                    return true;
                }
                keepOpen();
                return super.dispatchTouchEvent(event);
            }
        };
        mRoot = root;
        mMenuType = MENU_NONE;
        mMenuButton = null;

        // [label and lists] [bar], mirrored on the left edge
        final LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(mSettings.left ? View.LAYOUT_DIRECTION_RTL
                : View.LAYOUT_DIRECTION_LTR);
        root.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        final FrameLayout side = new FrameLayout(mContext);
        side.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        row.addView(side, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        buildLabel(side);
        buildPanel(side);

        mBar = new LinearLayout(mContext);
        mBar.setOrientation(LinearLayout.VERTICAL);
        mBar.setGravity(Gravity.CENTER_HORIZONTAL);
        mBar.setBackgroundColor(barColor());
        mBar.setPadding(0, mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_top), 0, dp(12));
        row.addView(mBar, new LinearLayout.LayoutParams(barWidth(),
                ViewGroup.LayoutParams.MATCH_PARENT));
        buildBar();

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                // Outside the OneHanded display area, so it stays put while the app slides
                WindowManager.LayoutParams.TYPE_NAVIGATION_BAR_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | (mSettings.left ? Gravity.START : Gravity.END);
        lp.setTitle("WheelTaskbar");
        lp.setFitInsetsTypes(0);
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        // Read when the window gets the focus: the side button presses instead of sleeping
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        // Drawn off screen first; once that frame is up, the bar and the app slide together
        final int width = barWidth();
        final int out = mSettings.left ? -width : width;
        root.setTranslationX(out);
        root.getViewTreeObserver().registerFrameCommitCallback(() -> mHandler.post(() -> {
            if (mRoot == root) {
                slide(mSettings.left ? width : -width, root, out, null);
            }
        }));
        // The focus asked for before the window has it may not stick: ask again once it does
        root.getViewTreeObserver().addOnWindowFocusChangeListener(hasFocus -> {
            if (hasFocus && mRoot == root && root.findFocus() == null) {
                focus(mBar, 0);
            }
        });
        mWindowManager.addView(root, lp);
        // The wheel starts at the top, the ring shows at once
        focus(mBar, 0);
        keepOpen();
        updateMode();
    }

    /** Moves the screen to {@code x} with the bar, or only the bar when it covers the app. */
    private void slide(float x, View bar, float barOut, Runnable end) {
        final long duration = mSettings.duration(SLIDE_MS);
        if (mSettings.push) {
            mSlide.slide(x, bar, barOut, duration, end);
            return;
        }
        final float to = x == 0 ? barOut : 0;
        if (duration == 0) {
            bar.setTranslationX(to);
            if (end != null) {
                end.run();
            }
            return;
        }
        bar.animate().translationX(to).setDuration(duration)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .withEndAction(end).start();
    }

    private void buildBar() {
        mBar.removeAllViews();
        mAppsColumn = null;
        mModeIcon = null;
        for (String button : mSettings.buttons) {
            switch (button) {
                case WheelTaskbarSettings.BUTTON_HOME:
                    addButton(mBar, R.drawable.ic_wheel_taskbar_home,
                            mContext.getString(R.string.wheel_taskbar_home), this::goHome);
                    break;
                case WheelTaskbarSettings.BUTTON_DRAWER:
                    addButton(mBar, R.drawable.ic_wheel_taskbar_drawer,
                            mContext.getString(R.string.wheel_taskbar_drawer),
                            () -> openMenu(MENU_DRAWER));
                    break;
                case WheelTaskbarSettings.BUTTON_APPS:
                    buildApps();
                    break;
                case WheelTaskbarSettings.BUTTON_NOTIFICATIONS: {
                    final View cell = addButton(mBar, R.drawable.ic_wheel_taskbar_notifications,
                            mContext.getString(R.string.wheel_taskbar_notifications),
                            this::openNotifications);
                    mBadge = badge((FrameLayout) cell);
                    final int count = notificationCount();
                    mBadge.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                    mBadge.setText(count > 9 ? "9+" : Integer.toString(count));
                    break;
                }
                case WheelTaskbarSettings.BUTTON_MODE:
                    // The home screen has no mode of its own
                    if (mModeClient != null && mForegroundPackage != null
                            && !mForegroundPackage.equals(WheelTaskbarApps.homePackage(
                                    userContext().getPackageManager()))) {
                        final View cell = addButton(mBar, modeIcon(appMode(mForegroundPackage)),
                                mContext.getString(R.string.wheel_taskbar_mode),
                                () -> openMenu(MENU_MODES));
                        mModeIcon = (ImageView) ((FrameLayout) cell).getChildAt(0);
                    }
                    break;
            }
        }
        if (mAppsColumn == null) {
            // Without the apps the buttons still end at the top, settings at the bottom
            final View space = new View(mContext);
            mBar.addView(space, new LinearLayout.LayoutParams(1, 0, 1f));
        }
        addButton(mBar, R.drawable.ic_wheel_taskbar_settings,
                mContext.getString(R.string.wheel_taskbar_settings), this::openSettings);
    }

    /** The pinned and running apps, scrolling between the buttons when they don't fit. */
    private void buildApps() {
        final ScrollView scroll = new ScrollView(mContext);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(16));
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setSmoothScrollingEnabled(mSettings.animation > 0);
        mAppsColumn = new LinearLayout(mContext);
        mAppsColumn.setOrientation(LinearLayout.VERTICAL);
        mAppsColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(mAppsColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(6);
        mBar.addView(scroll, lp);
        mApps = WheelTaskbarApps.load(userContext(), mUserTracker.getUserId(),
                mSettings.running, mForegroundTask);
        for (WheelTaskbarApps.Entry app : mApps) {
            addApp(app, -1);
        }
    }

    /**
     * Brings the apps up to date while the bar is open, the focus staying on its app. When that
     * app went, the app now in its place takes the focus (the one above, if it was the last),
     * or the bar's button at {@code fallbackIndex} when no apps are left (-1: the focused one).
     */
    private void refreshApps(int fallbackIndex) {
        if (mRoot == null || mAppsColumn == null) {
            return;
        }
        if (fallbackIndex < 0) {
            fallbackIndex = targets(mBar).indexOf(mRoot.findFocus());
        }
        final List<WheelTaskbarApps.Entry> apps = WheelTaskbarApps.load(userContext(),
                mUserTracker.getUserId(), mSettings.running, mForegroundTask);
        final View focused = mRoot.findFocus();
        final String focusedApp = focused != null && focused.getTag()
                instanceof WheelTaskbarApps.Entry entry ? entry.packageName : null;
        final int focusedRow = focusedApp != null
                ? mAppsColumn.indexOfChild((View) focused.getParent()) : -1;
        boolean same = apps.size() == mApps.size();
        for (int i = 0; same && i < apps.size(); i++) {
            same = apps.get(i).same(mApps.get(i));
        }
        if (same) {
            return;
        }
        mApps = apps;
        mAppsColumn.removeAllViews();
        View refocus = null;
        for (WheelTaskbarApps.Entry app : apps) {
            final View button = addApp(app, -1);
            if (app.packageName.equals(focusedApp)) {
                refocus = button;
            }
        }
        final int rows = mAppsColumn.getChildCount();
        if (refocus != null) {
            refocus.requestFocusFromTouch();
        } else if (focusedRow >= 0 && rows > 0) {
            ((ViewGroup) mAppsColumn.getChildAt(Math.min(focusedRow, rows - 1))).getChildAt(0)
                    .requestFocusFromTouch();
        } else if (focusedApp != null) {
            focus(mBar, Math.max(0, fallbackIndex));
        }
    }

    private View addApp(WheelTaskbarApps.Entry app, int index) {
        final PackageManager pm = userContext().getPackageManager();
        final FrameLayout cell = new FrameLayout(mContext);
        final ImageView button = new ImageView(mContext);
        button.setFocusable(true);
        button.setBackground(background(dp(12), mContext.getColor(R.color.wheel_taskbar_button)));
        final int pad = dp(4);
        button.setPadding(pad, pad, pad, pad);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setImageDrawable(appIcon(pm, app.component));
        final CharSequence name = appLabel(pm, app.packageName);
        button.setContentDescription(name);
        button.setTag(app);
        final FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(buttonSize(),
                buttonSize(), Gravity.CENTER);
        cell.addView(button, blp);
        if (app.running()) {
            // The dot sits on the bar's inner side, towards the app
            final View dot = new View(mContext);
            final GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(2));
            shape.setColor(app.front ? ringColor()
                    : mContext.getColor(R.color.wheel_taskbar_text_secondary));
            dot.setBackground(shape);
            final FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(dp(3),
                    app.front ? dp(18) : dp(7),
                    Gravity.CENTER_VERTICAL | (mSettings.left ? Gravity.RIGHT : Gravity.LEFT));
            cell.addView(dot, dlp);
        }
        button.setOnClickListener(v -> openApp(app));
        final CharSequence label = app.front
                ? mContext.getString(R.string.wheel_taskbar_app_front, name)
                : app.running() ? mContext.getString(R.string.wheel_taskbar_app_running, name)
                : name;
        decorate(button, label);
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(barWidth(),
                cellSize());
        lp.bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_gap) / 2;
        mAppsColumn.addView(cell, index, lp);
        return button;
    }

    private View addButton(LinearLayout parent, int icon, CharSequence name, Runnable action) {
        return addButton(parent, mContext.getDrawable(icon), name, action);
    }

    private View addButton(LinearLayout parent, Drawable icon, CharSequence name,
            Runnable action) {
        final FrameLayout cell = new FrameLayout(mContext);
        final ImageView button = new ImageView(mContext);
        button.setFocusable(true);
        button.setBackground(background(dp(12), mContext.getColor(R.color.wheel_taskbar_button)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(icon);
        button.setImageTintList(ColorStateList.valueOf(
                mContext.getColor(R.color.wheel_taskbar_text)));
        button.setContentDescription(name);
        button.setOnClickListener(v -> action.run());
        decorate(button, name);
        cell.addView(button, new FrameLayout.LayoutParams(buttonSize(), buttonSize(),
                Gravity.CENTER));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellSize(),
                cellSize());
        lp.bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_gap) / 2;
        parent.addView(cell, lp);
        return cell;
    }

    /** The focus grows a button a little and names it beside the bar. */
    private void decorate(View button, CharSequence label) {
        button.setOnFocusChangeListener((v, hasFocus) -> {
            final float scale = hasFocus ? 1.08f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(mSettings.duration(FOCUS_MS))
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (hasFocus) {
                showLabel(v, label);
            }
        });
    }

    private TextView badge(FrameLayout cell) {
        final TextView badge = new TextView(mContext);
        badge.setBackgroundResource(R.drawable.wheel_taskbar_badge_background);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_badge_size));
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Badge);
        cell.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                mContext.getResources().getDimensionPixelSize(R.dimen.wheel_taskbar_badge_size),
                Gravity.TOP | Gravity.END));
        return badge;
    }

    private void buildLabel(FrameLayout side) {
        mLabel = new TextView(mContext);
        mLabel.setBackgroundResource(R.drawable.wheel_taskbar_label_background);
        mLabel.setPadding(dp(12), dp(6), dp(12), dp(6));
        mLabel.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Label);
        mLabel.setSingleLine(true);
        mLabel.setVisibility(View.GONE);
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | (mSettings.left ? Gravity.START : Gravity.END));
        lp.setMarginStart(mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_gap));
        lp.setMarginEnd(mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_gap));
        side.addView(mLabel, lp);
    }

    private void buildPanel(FrameLayout side) {
        mPanel = new LinearLayout(mContext);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundResource(R.drawable.wheel_taskbar_menu_background);
        mPanel.setPadding(dp(8), dp(12), dp(8), dp(12));
        mPanel.setVisibility(View.GONE);
        mPanelTitle = new TextView(mContext);
        mPanelTitle.setAllCaps(true);
        mPanelTitle.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Title);
        mPanelTitle.setPadding(dp(12), 0, dp(12), dp(8));
        mPanel.addView(mPanelTitle);
        mPanelScroll = new ScrollView(mContext);
        mPanelScroll.setVerticalScrollBarEnabled(false);
        mPanelScroll.setVerticalFadingEdgeEnabled(true);
        mPanelScroll.setFadingEdgeLength(dp(24));
        mPanelScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        mPanelItems = new LinearLayout(mContext);
        mPanelItems.setOrientation(LinearLayout.VERTICAL);
        mPanelScroll.addView(mPanelItems, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // As tall as its rows, up to the screen's height
        mPanel.addView(mPanelScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                mContext.getResources().getDimensionPixelSize(R.dimen.wheel_taskbar_menu_width),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | (mSettings.left ? Gravity.START : Gravity.END));
        lp.topMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_menu_top);
        lp.bottomMargin = dp(16);
        lp.setMarginStart(mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_gap));
        lp.setMarginEnd(mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_gap));
        side.addView(mPanel, lp);
    }

    /** The name of the focused bar button beside it, gliding along as the focus moves. */
    private void showLabel(View button, CharSequence label) {
        if (mMenuType != MENU_NONE || !mSettings.labels) {
            mLabel.setVisibility(View.GONE);
            return;
        }
        final boolean appearing = mLabel.getVisibility() != View.VISIBLE;
        mLabel.setText(label);
        mLabel.setVisibility(View.VISIBLE);
        mLabel.post(() -> {
            final int[] at = new int[2];
            final int[] side = new int[2];
            button.getLocationInWindow(at);
            ((View) mLabel.getParent()).getLocationInWindow(side);
            final float y = at[1] - side[1] + (button.getHeight() - mLabel.getHeight()) / 2f;
            final long duration = mSettings.duration(FOCUS_MS);
            if (appearing || duration == 0) {
                mLabel.setTranslationY(y);
                mLabel.setAlpha(0f);
                mLabel.setTranslationX(mSettings.left ? -dp(8) : dp(8));
                mLabel.animate().alpha(1f).translationX(0).setDuration(duration).start();
            } else {
                mLabel.animate().translationY(y).alpha(1f).translationX(0)
                        .setDuration(duration).setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        });
    }

    private void hide() {
        mHandler.removeCallbacks(mHide);
        if (mRoot == null) {
            return;
        }
        final View closing = mRoot;
        mRoot = null;
        mMenuType = MENU_NONE;
        // No more keys or touches while it slides out
        final WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams) closing.getLayoutParams();
        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.inputFeatures &= ~WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        mWindowManager.updateViewLayout(closing, lp);
        mClosing = closing;
        final int width = barWidth();
        slide(0, closing, mSettings.left ? -width : width, () -> {
            if (mClosing == closing) {
                removeClosing();
            }
        });
        updateMode();
    }

    private void removeClosing() {
        if (mClosing != null) {
            mWindowManager.removeViewImmediate(mClosing);
            mClosing = null;
        }
    }

    private void keepOpen() {
        mHandler.removeCallbacks(mHide);
        if (mSettings.timeoutMs > 0) {
            mHandler.postDelayed(mHide, mMenuType == MENU_NONE ? mSettings.timeoutMs
                    : 2 * mSettings.timeoutMs);
        }
    }

    // ---- Keys ----

    private boolean onKey(KeyEvent event) {
        keepOpen();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_NAVIGATE_PREVIOUS:
                if (down) {
                    move(-1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_NAVIGATE_NEXT:
                if (down) {
                    move(1);
                }
                return true;
            case KeyEvent.KEYCODE_POWER:
                onSideButton(event);
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!down && !event.isCanceled()) {
                    if (mMenuType != MENU_NONE) {
                        closeMenu();
                    } else {
                        hide();
                    }
                }
                return true;
            default:
                return false;
        }
    }

    private void tick(View view) {
        if (mSettings.haptics && view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void move(int step) {
        final boolean inList = mMenuType != MENU_NONE;
        final ViewGroup list = inList ? mPanelItems : mBar;
        final List<View> targets = targets(list);
        if (targets.isEmpty()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        mFastSteps = now - mLastStep < FAST_STEP_MS ? mFastSteps + 1 : 0;
        mLastStep = now;
        final int current = targets.indexOf(mRoot.findFocus());
        if (current < 0) {
            focusAndTick(targets.get(0));
            return;
        }
        if (mMenuType == MENU_DRAWER && mFastSteps >= FAST_STEPS) {
            // A fast spin through all apps skips to the next letter
            final View jump = letterJump(targets, current, step);
            if (jump != null) {
                focusAndTick(jump);
                return;
            }
        }
        final int next = current + step;
        if (next < 0 || next >= targets.size()) {
            final int end = mSettings.listEnd;
            if (end == WheelTaskbarSettings.LIST_END_CLOSE
                    || (inList && end == WheelTaskbarSettings.LIST_END_BACK && next < 0)) {
                // Out of a list back to its button in the bar, out of the bar closes it
                if (inList) {
                    closeMenu();
                    tick(mRoot.findFocus());
                } else {
                    hide();
                }
                return;
            }
        }
        // Round at the ends, as a click wheel does
        focusAndTick(targets.get(Math.floorMod(next, targets.size())));
    }

    private void focusAndTick(View target) {
        target.requestFocusFromTouch();
        tick(target);
    }

    /** The first app under the next (or this, going up) letter. */
    private View letterJump(List<View> targets, int current, int step) {
        final char letter = initial(targets.get(current));
        for (int i = current + step; i >= 0 && i < targets.size(); i += step) {
            final char other = initial(targets.get(i));
            if (other != letter && other != 0) {
                if (step < 0) {
                    // Up: to the top of that letter
                    while (i > 0 && initial(targets.get(i - 1)) == other) {
                        i--;
                    }
                }
                return targets.get(i);
            }
        }
        return null;
    }

    private static char initial(View row) {
        final CharSequence description = row.getContentDescription();
        return TextUtils.isEmpty(description) ? 0
                : Character.toUpperCase(description.charAt(0));
    }

    private void onSideButton(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                mLongPressed = false;
            } else if (event.isLongPress() && !mLongPressed) {
                mLongPressed = true;
                onLongPress();
            }
            return;
        }
        if (mLongPressed || event.isCanceled()) {
            return;
        }
        final View focused = mRoot.findFocus();
        if (focused == null || focused.isInTouchMode()) {
            focus(mMenuType == MENU_NONE ? mBar : mPanelItems, 0);
            return;
        }
        if (mSettings.haptics) {
            focused.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
        focused.performClick();
    }

    /**
     * Closes the focused running app, or pins or unpins the focused app in the list of all
     * apps; anywhere else the r1 sleeps, as the side button does.
     */
    private void onLongPress() {
        final View focused = mRoot.findFocus();
        if (focused != null && focused.getTag() instanceof WheelTaskbarApps.Entry app
                && app.running()) {
            focused.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            closeApp(focused, app);
            return;
        }
        if (mMenuType == MENU_DRAWER && focused != null
                && focused.getTag() instanceof LauncherActivityInfo info) {
            focused.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            togglePin(info.getComponentName().getPackageName());
            final int index = targets(mPanelItems).indexOf(focused);
            fillDrawer();
            focus(mPanelItems, index);
            return;
        }
        hide();
        mContext.getSystemService(PowerManager.class).goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
    }

    /** Fades the app's button out of the bar while its task goes away. */
    private void closeApp(View button, WheelTaskbarApps.Entry app) {
        final View cell = (View) button.getParent();
        final List<View> targets = targets(mBar);
        final int index = targets.indexOf(button);
        mClosingApp = true;
        ActivityManagerWrapper.getInstance().removeTask(app.taskId);
        cell.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f)
                .setDuration(mSettings.duration(PANEL_MS)).withEndAction(() -> {
                    mClosingApp = false;
                    if (mRoot == null) {
                        return;
                    }
                    // The next app takes its place and the focus; a pinned one stays, its dot gone
                    refreshApps(index);
                    cell.setAlpha(1f);
                    cell.setScaleX(1f);
                    cell.setScaleY(1f);
                    if (mRoot.findFocus() == null || !mRoot.findFocus().isShown()) {
                        focus(mBar, index);
                    }
                }).start();
    }

    private void togglePin(String packageName) {
        final List<String> pinned = new ArrayList<>();
        final String value = PowerMenuUtils.getPinnedApps(mContext, mUserTracker.getUserId());
        if (!TextUtils.isEmpty(value)) {
            for (String app : value.split("\\|")) {
                if (!app.isEmpty()) {
                    pinned.add(app);
                }
            }
        }
        if (!pinned.remove(packageName)) {
            pinned.add(packageName);
        }
        LineageSettings.Secure.putStringForUser(mContext.getContentResolver(),
                LineageSettings.Secure.POWER_MENU_PINNED_APPS, String.join("|", pinned),
                mUserTracker.getUserId());
        // Settings observers rebuild later; the bar shows it now
        mHandler.post(() -> refreshApps(-1));
    }

    private void focus(ViewGroup list, int index) {
        final List<View> targets = targets(list);
        if (!targets.isEmpty()) {
            targets.get(Math.max(0, Math.min(index, targets.size() - 1))).requestFocusFromTouch();
        }
    }

    private static List<View> targets(ViewGroup list) {
        final ArrayList<View> views = new ArrayList<>();
        list.addFocusables(views, View.FOCUS_FORWARD, View.FOCUSABLES_ALL);
        final List<View> targets = new ArrayList<>();
        for (View view : views) {
            if (view.isShown() && view.isEnabled()) {
                targets.add(view);
            }
        }
        return targets;
    }

    // ---- Lists ----

    private void openMenu(int type) {
        mMenuButton = mRoot.findFocus();
        mMenuType = type;
        mLabel.animate().cancel();
        mLabel.setVisibility(View.GONE);
        int focusIndex = 0;
        if (type == MENU_DRAWER) {
            mPanelTitle.setText(R.string.wheel_taskbar_drawer);
            focusIndex = fillDrawer();
        } else {
            mPanelTitle.setText(R.string.wheel_taskbar_mode);
            focusIndex = fillModes();
        }
        mPanelScroll.scrollTo(0, 0);
        mPanel.setVisibility(View.VISIBLE);
        final long duration = mSettings.duration(PANEL_MS);
        mPanel.animate().cancel();
        mPanel.setAlpha(0f);
        mPanel.setTranslationX(mSettings.left ? -dp(16) : dp(16));
        mPanel.setScaleY(0.96f);
        mPanel.animate().alpha(1f).translationX(0).scaleY(1f).setDuration(duration)
                .setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        focus(mPanelItems, focusIndex);
        keepOpen();
    }

    private void closeMenu() {
        final View button = mMenuButton;
        mMenuType = MENU_NONE;
        mMenuButton = null;
        final long duration = mSettings.duration(PANEL_MS * 3 / 4);
        mPanel.animate().cancel();
        mPanel.animate().alpha(0f).translationX(mSettings.left ? -dp(16) : dp(16))
                .setDuration(duration).withEndAction(() -> {
                    if (mMenuType == MENU_NONE) {
                        mPanel.setVisibility(View.GONE);
                        mPanelItems.removeAllViews();
                    }
                }).start();
        if (button != null && button.isShown()) {
            button.requestFocusFromTouch();
        } else {
            focus(mBar, 0);
        }
        keepOpen();
    }

    /** All apps, A to Z under their letters; the focus starts at the first one. */
    private int fillDrawer() {
        mPanelItems.removeAllViews();
        final LauncherApps launcher = mContext.getSystemService(LauncherApps.class);
        final List<LauncherActivityInfo> apps = new ArrayList<>(
                launcher.getActivityList(null, mUserTracker.getUserHandle()));
        final Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((a, b) -> collator.compare(a.getLabel().toString(), b.getLabel().toString()));
        final String pinned = "|" + PowerMenuUtils.getPinnedApps(mContext,
                mUserTracker.getUserId()) + "|";
        char letter = 0;
        for (LauncherActivityInfo app : apps) {
            final CharSequence label = app.getLabel();
            final char initial = TextUtils.isEmpty(label) ? '#'
                    : Character.toUpperCase(label.charAt(0));
            if (initial != letter) {
                letter = initial;
                addHeader(Character.isLetter(initial) ? String.valueOf(initial) : "#");
            }
            final boolean isPinned = pinned.contains(
                    "|" + app.getComponentName().getPackageName() + "|");
            final View row = addRow(app.getBadgedIcon(0), label, isPinned
                    ? mContext.getString(R.string.wheel_taskbar_pinned) : null);
            row.setTag(app);
            row.setContentDescription(label);
            row.setOnClickListener(v -> {
                hide();
                launcher.startMainActivity(app.getComponentName(), app.getUser(), null, null);
            });
        }
        if (apps.isEmpty()) {
            addRow(null, mContext.getString(R.string.wheel_taskbar_no_apps), null)
                    .setEnabled(false);
        }
        return 0;
    }

    /** What the wheel does in the app in front; it is kept for that app. Focus: the current. */
    private int fillModes() {
        mPanelItems.removeAllViews();
        final String app = mForegroundPackage;
        final String current = appMode(app);
        final PackageManager pm = userContext().getPackageManager();
        final String[][] modes = {
            {MODE_TASKBAR, mContext.getString(R.string.wheel_taskbar_mode_taskbar)},
            {MODE_APP, mContext.getString(R.string.wheel_taskbar_mode_app, appLabel(pm, app))},
            {MODE_VOLUME, mContext.getString(R.string.wheel_taskbar_mode_volume)},
        };
        int focusIndex = 0;
        for (int i = 0; i < modes.length; i++) {
            final String[] mode = modes[i];
            final boolean isCurrent = mode[0].equals(current);
            if (isCurrent) {
                focusIndex = i;
            }
            final CharSequence summary = isCurrent
                    ? mContext.getString(R.string.wheel_taskbar_mode_current)
                    : MODE_TASKBAR.equals(mode[0]) ? null
                    : mContext.getString(R.string.wheel_taskbar_mode_back);
            addRow(mContext.getDrawable(modeIconRes(mode[0])), mode[1], summary)
                    .setOnClickListener(v -> {
                        setAppMode(app, mode[0]);
                        if (mModeIcon != null) {
                            mModeIcon.setImageResource(modeIconRes(mode[0]));
                        }
                        hide();
                    });
        }
        return focusIndex;
    }

    private Drawable modeIcon(String mode) {
        return mContext.getDrawable(modeIconRes(mode));
    }

    private static int modeIconRes(String mode) {
        switch (mode) {
            case MODE_APP:
                return R.drawable.ic_wheel_taskbar_mode_app;
            case MODE_VOLUME:
                return R.drawable.ic_wheel_taskbar_mode_volume;
            default:
                return R.drawable.ic_wheel_taskbar_mode;
        }
    }

    private void addHeader(String text) {
        final TextView header = new TextView(mContext);
        header.setText(text);
        header.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Title);
        header.setTextColor(ringColor());
        header.setPadding(dp(12), dp(8), dp(12), dp(2));
        mPanelItems.addView(header);
    }

    private View addRow(Drawable icon, CharSequence title, CharSequence summary) {
        final View row = LayoutInflater.from(mContext).inflate(
                R.layout.wheel_taskbar_menu_item, mPanelItems, false);
        row.setBackground(background(dp(10), Color.TRANSPARENT));
        final ImageView iconView = row.findViewById(R.id.wheel_taskbar_item_icon);
        iconView.setImageDrawable(icon);
        iconView.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        ((TextView) row.findViewById(R.id.wheel_taskbar_item_title)).setText(title);
        final TextView summaryView = row.findViewById(R.id.wheel_taskbar_item_summary);
        summaryView.setText(summary);
        summaryView.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                // Keep a row of context visible around the focus while scrolling
                final int margin = dp(24);
                final int top = v.getTop() - margin;
                final int bottom = v.getBottom() + margin;
                final int scrollY = mPanelScroll.getScrollY();
                final int height = mPanelScroll.getHeight();
                if (top < scrollY) {
                    scrollPanel(Math.max(0, top));
                } else if (bottom > scrollY + height) {
                    scrollPanel(bottom - height);
                }
            }
        });
        mPanelItems.addView(row);
        return row;
    }

    private void scrollPanel(int y) {
        if (mSettings.animation > 0) {
            mPanelScroll.smoothScrollTo(0, y);
        } else {
            mPanelScroll.scrollTo(0, y);
        }
    }

    // ---- Actions ----

    private void openApp(WheelTaskbarApps.Entry app) {
        hide();
        if (app.running()) {
            if (!app.front) {
                ActivityManagerWrapper.getInstance().startActivityFromRecents(app.taskId, null);
            }
        } else if (app.launch != null) {
            startActivity(new Intent(app.launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        }
    }

    private void goHome() {
        hide();
        startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void openNotifications() {
        hide();
        mContext.getSystemService(StatusBarManager.class).expandNotificationsPanel();
    }

    /** The taskbar's own settings (config_wheelTaskbarSettings), else the Settings app. */
    private void openSettings() {
        hide();
        final ComponentName settings = ComponentName.unflattenFromString(
                mContext.getString(R.string.config_wheelTaskbarSettings));
        final Intent intent = settings != null ? new Intent().setComponent(settings)
                : new Intent(Settings.ACTION_SETTINGS);
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void startActivity(Intent intent) {
        mContext.startActivityAsUser(intent, mUserTracker.getUserHandle());
    }

    /** Notifications the user can clear: what the shade would show as new. */
    private int notificationCount() {
        final StatusBarNotification[] active = mNotificationListener.getActiveNotifications();
        if (active == null) {
            return 0;
        }
        final UserHandle user = mUserTracker.getUserHandle();
        int count = 0;
        for (StatusBarNotification sbn : active) {
            if (sbn.isClearable() && !sbn.isOngoing()
                    && (sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) == 0
                    && (sbn.getUser().equals(user) || sbn.getUser().equals(UserHandle.ALL))) {
                count++;
            }
        }
        return count;
    }

    private Context userContext() {
        return mContext.createContextAsUser(mUserTracker.getUserHandle(), 0);
    }

    private static CharSequence appLabel(PackageManager pm, String packageName) {
        if (packageName == null) {
            return "";
        }
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static Drawable appIcon(PackageManager pm, ComponentName component) {
        try {
            return pm.getActivityIcon(component);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getDefaultActivityIcon();
        }
    }
}
