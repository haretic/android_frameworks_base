/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.app.ActivityManager;
import android.app.IProcessObserver;
import android.app.Notification;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.notification.NotificationListenerService.RankingMap;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

/**
 * The taskbar of a device with a scroll wheel and one side button (config_wheelTaskbar), a
 * column at the screen's edge like the Unity launcher: home, all apps, the pinned and the
 * running apps, notifications, the wheel mode of the app in front and, last at the bottom, the
 * taskbar's own settings (WheelTaskbarSettings: the buttons and their order, the edge, sizes,
 * colours, timing and more). The app slides aside to make room.
 *
 * <p>The apps are a taskbar's: the pinned ones, then the running ones, a dot beside each
 * running one (a tall one for the app in front) and a dot on those with new notifications. An
 * app runs while its process does, also with only a background service (music, navigation);
 * one the system closes loses its dot or, unpinned, its place, while the bar is open too. The
 * apps are kept up to date off the main thread as tasks and processes come and go, so the bar
 * opens at once; changes glide into place.
 *
 * <p>The wheel moves the focus, a light that glides from button to button, with a tick, and
 * goes round at the ends; up out of a list goes back. The focused button's name shows beside
 * the bar with a hint, and a running app's last screen. The side button presses
 * (INPUT_FEATURE_POWER_KEY_SELECTS); holding it on an app, in the bar or in the list of all
 * apps, opens its options: close it (or stop it, in the background), close all apps, pin or
 * unpin it, its app info. Holding it anywhere else sleeps. The bar closes on back, on a touch
 * outside, or when left alone for a moment.
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

    private static final String TAG = "WheelTaskbar";

    private static final int MENU_NONE = 0;
    private static final int MENU_DRAWER = 1;
    private static final int MENU_MODES = 2;
    private static final int MENU_APP = 3;

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
    /** Apps coming, going and moving in the open bar. */
    private static final long CHANGE_MS = 260;
    /** The buttons come in one after the other this far apart as the bar opens. */
    private static final long STAGGER_MS = 16;
    /** Task and process changes closer together than this make one reload. */
    private static final long LOAD_DELAY_MS = 120;

    private static final PathInterpolator EMPHASIZED = new PathInterpolator(0.2f, 0f, 0f, 1f);

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
    private final WheelTaskbarApps mAppsModel = new WheelTaskbarApps();
    private final Runnable mHide = this::hide;
    private final Runnable mStackChanged = this::onStackChanged;
    private final Runnable mLoadApps = this::loadApps;
    private final Runnable mNotificationsChanged = () -> updateNotifications(true);

    /** Null without a wheel daemon. */
    private WheelModeClient mModeClient;
    /** The modes picked per app, package to mode. */
    private final Map<String, String> mAppModes = new LinkedHashMap<>();
    /** The device's modes for apps the user hasn't picked one for. */
    private final Map<String, String> mDefaultModes = new LinkedHashMap<>();
    private String mForegroundPackage;
    private int mForegroundTask = -1;
    private boolean mShadeExpanded;

    /** The apps as last loaded, what the bar shows. */
    private List<WheelTaskbarApps.Entry> mApps = new ArrayList<>();
    private boolean mLoading;
    private boolean mLoadAgain;
    /** All apps for the list, loaded each time the bar opens; null until then. */
    private List<WheelTaskbarApps.Launchable> mDrawerApps;
    /** Running apps' last screens while the bar is open, task to picture (null: none). */
    private final Map<Integer, Bitmap> mThumbnails = new ArrayMap<>();
    private final Set<Integer> mThumbnailsLoading = new ArraySet<>();
    private int mNotificationCount;

    private View mRoot;
    /** The bar sliding out, removed once it is off screen. */
    private View mClosing;
    private LinearLayout mBar;
    private WheelTaskbarHighlight mBarHighlight;
    private AppsColumn mAppsColumn;
    private ScrollView mAppsScroll;
    private View mDrawerButton;
    private View mNotificationsButton;
    private View mModeButton;
    private TextView mBadge;
    private ImageView mModeIcon;

    private LinearLayout mLabel;
    private TextView mLabelTitle;
    private TextView mLabelHint;
    private ImageView mLabelPreview;
    private SpringAnimation mLabelY;
    /** The button the label names, and the task whose screen it shows (-1: none). */
    private View mLabelFor;
    private int mLabelTask = -1;
    private boolean mLabelAppearing;
    private float mLabelTargetY;

    private LinearLayout mPanel;
    private TextView mPanelTitle;
    private ScrollView mPanelScroll;
    private FrameLayout mPanelContent;
    private LinearLayout mPanelItems;
    private WheelTaskbarHighlight mPanelHighlight;
    /** The open lists, the one shown last; empty while the wheel is in the bar. */
    private final List<Menu> mMenus = new ArrayList<>();
    /** The lists' rows change and the focus with them: what that does to the focus is moot. */
    private boolean mRefilling;

    /** A long press of the side button already acted, ignore the rest of it. */
    private boolean mLongPressed;
    private long mLastStep;
    private int mFastSteps;

    /** An open list. */
    private static final class Menu {
        final int type;
        /** The bar's button it was opened from, null when opened from another list. */
        final View button;
        /** The app of MENU_APP. */
        final String app;
        /** Where the focus was when another list opened from it. */
        int focus;

        Menu(int type, View button, String app) {
            this.type = type;
            this.button = button;
            this.app = app;
        }
    }

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
        mSettings.read(mUserTracker.getUserId());
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
                        requestApps();
                    }

                    @Override
                    public void onTaskStackChanged() {
                        // A task that finishes brings the one under it to the front without
                        // a move; apps started, closed or killed show in the bar
                        mHandler.removeCallbacks(mStackChanged);
                        mHandler.postDelayed(mStackChanged, LOAD_DELAY_MS);
                    }
                });
        // A process the system ends takes its app out of the bar, also with no task change
        try {
            ActivityManager.getService().registerProcessObserver(new IProcessObserver.Stub() {
                @Override
                public void onProcessStarted(int pid, int processUid, int packageUid,
                        String packageName, String processName) {
                }

                @Override
                public void onForegroundActivitiesChanged(int pid, int uid, boolean fg) {
                }

                @Override
                public void onForegroundServicesChanged(int pid, int uid, int serviceTypes) {
                    requestApps();
                }

                @Override
                public void onProcessDied(int pid, int uid) {
                    requestApps();
                }
            });
        } catch (RemoteException e) {
            Log.w(TAG, "No process changes, the bar follows the tasks only", e);
        }
        mNotificationListener.addNotificationHandler(new NotificationListener.NotificationHandler() {
            @Override
            public void onNotificationPosted(StatusBarNotification sbn, RankingMap rankingMap) {
                notificationsChanged();
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap) {
                notificationsChanged();
            }

            @Override
            public void onNotificationRemoved(StatusBarNotification sbn, RankingMap rankingMap,
                    int reason) {
                notificationsChanged();
            }

            @Override
            public void onNotificationRankingUpdate(RankingMap rankingMap) {
            }

            @Override
            public void onNotificationsInitialized() {
            }
        });
        final ActivityManager.RunningTaskInfo front =
                ActivityManagerWrapper.getInstance().getRunningTask();
        mForegroundPackage = packageOf(front);
        mForegroundTask = front == null ? -1 : front.taskId;
        mUserTracker.addCallback(new UserTracker.Callback() {
            @Override
            public void onUserChanged(int newUser, Context userContext) {
                mSettings.read(newUser);
                readAppModes();
                updateMode();
                requestApps();
            }
        }, mContext.getMainExecutor());
        requestApps();

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
                requestApps();
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

    // ---- The apps ----

    /** Reloads the apps soon, once for changes that come together. Any thread. */
    private void requestApps() {
        mHandler.removeCallbacks(mLoadApps);
        mHandler.postDelayed(mLoadApps, LOAD_DELAY_MS);
    }

    private void loadApps() {
        if (mLoading) {
            mLoadAgain = true;
            return;
        }
        mLoading = true;
        final Context user = userContext();
        final int userId = mUserTracker.getUserId();
        final int running = mSettings.running;
        final int front = mForegroundTask;
        mBackground.execute(() -> {
            List<WheelTaskbarApps.Entry> apps;
            try {
                apps = mAppsModel.load(user, userId, running, front);
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't load the apps", e);
                apps = null;
            }
            final List<WheelTaskbarApps.Entry> loaded = apps;
            mHandler.post(() -> {
                mLoading = false;
                if (loaded != null && !same(loaded, mApps)) {
                    mApps = loaded;
                    applyApps(true);
                }
                if (mLoadAgain) {
                    mLoadAgain = false;
                    loadApps();
                }
            });
        });
    }

    private static boolean same(List<WheelTaskbarApps.Entry> a, List<WheelTaskbarApps.Entry> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).same(b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void loadDrawer() {
        final Context user = userContext();
        final UserHandle handle = mUserTracker.getUserHandle();
        mBackground.execute(() -> {
            List<WheelTaskbarApps.Launchable> apps;
            try {
                apps = mAppsModel.loadAll(user, handle);
            } catch (RuntimeException e) {
                Log.w(TAG, "Couldn't load the list of apps", e);
                apps = new ArrayList<>();
            }
            final List<WheelTaskbarApps.Launchable> loaded = apps;
            mHandler.post(() -> {
                if (mRoot == null) {
                    return;
                }
                mDrawerApps = loaded;
                if (menuType() == MENU_DRAWER && targets(mPanelItems).isEmpty()) {
                    // It opened before they were in
                    refill(top(), 0);
                }
                if (mLabelFor != null && mLabelFor == mDrawerButton) {
                    showLabel(mDrawerButton);
                }
            });
        });
    }

    /** The running apps' last screens, for their labels. */
    private void loadThumbnail(int taskId) {
        if (mThumbnails.containsKey(taskId) || !mThumbnailsLoading.add(taskId)) {
            return;
        }
        mBackground.execute(() -> {
            Bitmap thumbnail = null;
            try {
                thumbnail = ActivityManagerWrapper.getInstance().getTaskThumbnail(taskId, true)
                        .getThumbnail();
            } catch (RuntimeException e) {
                Log.w(TAG, "No picture of task " + taskId, e);
            }
            final Bitmap loaded = thumbnail;
            mHandler.post(() -> {
                mThumbnailsLoading.remove(taskId);
                if (mRoot == null) {
                    return;
                }
                mThumbnails.put(taskId, loaded);
                if (mLabelTask == taskId && loaded != null) {
                    showPreview(loaded, true);
                }
            });
        });
    }

    private WheelTaskbarApps.Entry entryOf(String packageName) {
        for (WheelTaskbarApps.Entry app : mApps) {
            if (app.packageName.equals(packageName)) {
                return app;
            }
        }
        return null;
    }

    private boolean isPinned(String packageName) {
        final String pinned = PowerMenuUtils.getPinnedApps(mContext, mUserTracker.getUserId());
        return ("|" + pinned + "|").contains("|" + packageName + "|");
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
        return dp(new int[] {34, 40, 50}[mSettings.size]);
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

    /** A focus light in the ring colour, for the bar or a list. */
    private WheelTaskbarHighlight highlight(float radius) {
        final WheelTaskbarHighlight highlight = new WheelTaskbarHighlight(mContext);
        highlight.setLook(ringColor() & 0x2effffff, ringColor(),
                mContext.getResources().getDimensionPixelSize(R.dimen.wheel_taskbar_ring),
                radius);
        highlight.setSpeed(mSettings.animation);
        return highlight;
    }

    /** A touch on a button or row lights it; the wheel's focus is the highlight's. */
    private Drawable pressFeedback(float radius) {
        final GradientDrawable pressed = new GradientDrawable();
        pressed.setCornerRadius(radius);
        pressed.setColor(ringColor() & 0x40ffffff);
        final StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        states.addState(new int[0], new GradientDrawable());
        states.setExitFadeDuration((int) mSettings.duration(FOCUS_MS));
        return states;
    }

    private void show() {
        removeClosing();
        mSettings.read(mUserTracker.getUserId());
        mThumbnails.clear();
        mDrawerApps = null;
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
        mMenus.clear();

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

        // The bar, its focus light under the buttons and a hairline towards the app
        final FrameLayout frame = new FrameLayout(mContext);
        frame.setBackgroundColor(barColor());
        mBarHighlight = highlight(dp(14));
        frame.addView(mBarHighlight, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mBar = new LinearLayout(mContext);
        mBar.setOrientation(LinearLayout.VERTICAL);
        mBar.setGravity(Gravity.CENTER_HORIZONTAL);
        mBar.setPadding(0, mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_top), 0, dp(12));
        frame.addView(mBar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        final View hairline = new View(mContext);
        hairline.setBackgroundColor(mContext.getColor(R.color.wheel_taskbar_divider));
        frame.addView(hairline, new FrameLayout.LayoutParams(Math.max(1, dp(0.5f)),
                ViewGroup.LayoutParams.MATCH_PARENT,
                mSettings.left ? Gravity.RIGHT : Gravity.LEFT));
        row.addView(frame, new LinearLayout.LayoutParams(barWidth(),
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
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            trackLabel();
            return true;
        });
        mWindowManager.addView(root, lp);
        staggerIn();
        // The wheel starts at the top, the light shows at once
        focus(mBar, 0);
        keepOpen();
        updateMode();
        updateNotifications(false);
        prefetchThumbnails();
        requestApps();
        loadDrawer();
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

    /** The buttons come in from the edge one after the other as the bar slides in. */
    private void staggerIn() {
        final long duration = mSettings.duration(220);
        if (duration == 0) {
            return;
        }
        final List<View> cells = new ArrayList<>();
        for (int i = 0; i < mBar.getChildCount(); i++) {
            final View child = mBar.getChildAt(i);
            if (child == mAppsScroll) {
                for (int j = 0; j < mAppsColumn.getChildCount(); j++) {
                    cells.add(mAppsColumn.getChildAt(j));
                }
            } else {
                cells.add(child);
            }
        }
        final float from = mSettings.left ? -dp(14) : dp(14);
        for (int i = 0; i < cells.size(); i++) {
            final View cell = cells.get(i);
            cell.setAlpha(0f);
            cell.setTranslationX(from);
            cell.animate().alpha(1f).translationX(0f).setDuration(duration)
                    .setStartDelay(mSettings.duration(40 + i * STAGGER_MS))
                    .setInterpolator(new DecelerateInterpolator(2f)).start();
        }
    }

    private void buildBar() {
        mBar.removeAllViews();
        mAppsColumn = null;
        mAppsScroll = null;
        mModeIcon = null;
        mBadge = null;
        mDrawerButton = null;
        mNotificationsButton = null;
        mModeButton = null;
        for (String button : mSettings.buttons) {
            switch (button) {
                case WheelTaskbarSettings.BUTTON_HOME:
                    addButton(R.drawable.ic_wheel_taskbar_home,
                            mContext.getString(R.string.wheel_taskbar_home), this::goHome);
                    break;
                case WheelTaskbarSettings.BUTTON_DRAWER:
                    mDrawerButton = ((ViewGroup) addButton(R.drawable.ic_wheel_taskbar_drawer,
                            mContext.getString(R.string.wheel_taskbar_drawer),
                            () -> openMenu(MENU_DRAWER, null))).getChildAt(0);
                    break;
                case WheelTaskbarSettings.BUTTON_APPS:
                    buildApps();
                    break;
                case WheelTaskbarSettings.BUTTON_NOTIFICATIONS: {
                    final FrameLayout cell = (FrameLayout) addButton(
                            R.drawable.ic_wheel_taskbar_notifications,
                            mContext.getString(R.string.wheel_taskbar_notifications),
                            this::openNotifications);
                    mNotificationsButton = cell.getChildAt(0);
                    mBadge = badge(cell);
                    break;
                }
                case WheelTaskbarSettings.BUTTON_MODE:
                    // The home screen has no mode of its own
                    if (mModeClient != null && mForegroundPackage != null
                            && !mForegroundPackage.equals(WheelTaskbarApps.homePackage(
                                    userContext().getPackageManager()))) {
                        final FrameLayout cell = (FrameLayout) addButton(
                                modeIcon(appMode(mForegroundPackage)),
                                mContext.getString(R.string.wheel_taskbar_mode),
                                () -> openMenu(MENU_MODES, null));
                        mModeButton = cell.getChildAt(0);
                        mModeIcon = (ImageView) mModeButton;
                    }
                    break;
            }
        }
        if (mAppsColumn == null) {
            // Without the apps the buttons still end at the top, settings at the bottom
            final View space = new View(mContext);
            mBar.addView(space, new LinearLayout.LayoutParams(1, 0, 1f));
        }
        addButton(R.drawable.ic_wheel_taskbar_settings,
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
        mAppsColumn = new AppsColumn(mContext);
        mAppsColumn.setOrientation(LinearLayout.VERTICAL);
        mAppsColumn.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(mAppsColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lp.topMargin = dp(2);
        lp.bottomMargin = dp(6);
        mBar.addView(scroll, lp);
        mAppsScroll = scroll;
        applyApps(false);
    }

    /** The apps' column, whose buttons can change places without losing the focus. */
    private static final class AppsColumn extends LinearLayout {
        AppsColumn(Context context) {
            super(context);
        }

        void move(View child, int index) {
            final ViewGroup.LayoutParams lp = child.getLayoutParams();
            detachViewFromParent(child);
            attachViewToParent(child, index, lp);
            requestLayout();
            invalidate();
        }
    }

    /** An app's place in the bar: its button, its running dot, its notification dot. */
    private final class AppCell extends FrameLayout {
        final ImageView button;
        final View dot;
        final View notified;
        WheelTaskbarApps.Entry entry;

        AppCell(Context context) {
            super(context);
            button = new ImageView(mContext);
            button.setFocusable(true);
            button.setBackground(pressFeedback(dp(12)));
            final int pad = dp(1);
            button.setPadding(pad, pad, pad, pad);
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setOnClickListener(v -> openApp(entry));
            decorate(button);
            addView(button, new FrameLayout.LayoutParams(buttonSize(), buttonSize(),
                    Gravity.CENTER));
            // The running dot on the bar's inner side, towards the app
            dot = new View(mContext);
            final GradientDrawable line = new GradientDrawable();
            line.setCornerRadius(dp(2));
            dot.setBackground(line);
            dot.setScaleY(0f);
            addView(dot, new FrameLayout.LayoutParams(dp(3), dp(18),
                    Gravity.CENTER_VERTICAL | (mSettings.left ? Gravity.RIGHT : Gravity.LEFT)));
            // New notifications: a dot on the icon's top corner
            notified = new View(mContext);
            final GradientDrawable spot = new GradientDrawable();
            spot.setShape(GradientDrawable.OVAL);
            spot.setColor(mContext.getColor(R.color.wheel_taskbar_badge));
            spot.setStroke(dp(2), barColor());
            notified.setBackground(spot);
            notified.setScaleX(0f);
            notified.setScaleY(0f);
            final int size = dp(11);
            notified.setTranslationX(buttonSize() / 2f - size / 2f + dp(1));
            notified.setTranslationY(-buttonSize() / 2f + size / 2f - dp(1));
            addView(notified, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        }

        void bind(WheelTaskbarApps.Entry app, boolean animate) {
            final WheelTaskbarApps.Entry old = entry;
            entry = app;
            button.setTag(app);
            if (old == null || old.icon != app.icon) {
                button.setImageDrawable(WheelTaskbarApps.copy(app.icon));
            }
            button.setContentDescription(app.front
                    ? mContext.getString(R.string.wheel_taskbar_app_front, app.label)
                    : app.background
                    ? mContext.getString(R.string.wheel_taskbar_app_background, app.label)
                    : app.running()
                    ? mContext.getString(R.string.wheel_taskbar_app_running, app.label)
                    : app.pinned
                    ? mContext.getString(R.string.wheel_taskbar_app_pinned, app.label)
                    : app.label);
            // Tall for the app in front, short for one running, none for one that doesn't
            ((GradientDrawable) dot.getBackground()).setColor(app.front ? ringColor()
                    : mContext.getColor(R.color.wheel_taskbar_text_secondary));
            final float scale = app.front ? 1f : app.running() ? 7f / 18f : 0f;
            if (animate) {
                dot.animate().scaleY(scale).alpha(scale > 0 ? 1f : 0f)
                        .setDuration(mSettings.duration(CHANGE_MS)).setInterpolator(EMPHASIZED)
                        .start();
            } else {
                dot.animate().cancel();
                dot.setScaleY(scale);
                dot.setAlpha(scale > 0 ? 1f : 0f);
            }
            if (old != null && !old.same(app) && button.isFocused()) {
                showLabel(button);
            }
        }

        void setNotified(boolean on, boolean animate) {
            final float scale = on ? 1f : 0f;
            if (notified.getScaleX() == scale) {
                return;
            }
            if (animate && mSettings.animation > 0) {
                notified.animate().scaleX(scale).scaleY(scale)
                        .setDuration(mSettings.duration(CHANGE_MS))
                        .setInterpolator(on ? new OvershootInterpolator(2.5f) : EMPHASIZED)
                        .start();
            } else {
                notified.setScaleX(scale);
                notified.setScaleY(scale);
            }
        }
    }

    /** The line between the pinned apps and the others. */
    private View divider() {
        final FrameLayout cell = new FrameLayout(mContext);
        final View line = new View(mContext);
        line.setBackgroundColor(mContext.getColor(R.color.wheel_taskbar_divider));
        cell.addView(line, new FrameLayout.LayoutParams(dp(22), Math.max(1, dp(1)),
                Gravity.CENTER));
        return cell;
    }

    private LinearLayout.LayoutParams cellParams(View cell) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(barWidth(),
                cell instanceof AppCell ? cellSize() : dp(9));
        if (cell instanceof AppCell) {
            lp.bottomMargin = mContext.getResources().getDimensionPixelSize(
                    R.dimen.wheel_taskbar_gap) / 2;
        }
        return lp;
    }

    /**
     * Brings the bar's apps to mApps: an app that stays keeps its button, the focus and the
     * label, and glides to its new place; new ones grow in, gone ones fade out where they
     * were. When the focused app went, the app now in its place takes the focus (the one
     * above, if it was the last), or the bar's button now in its place when none are left.
     */
    private void applyApps(boolean animate) {
        if (mRoot == null || mAppsColumn == null) {
            return;
        }
        animate &= mSettings.animation > 0 && mAppsColumn.isLaidOut();
        final AppsColumn column = mAppsColumn;
        final View focused = mRoot.findFocus();
        final int focusedTarget = targets(mBar).indexOf(focused);
        final AppCell focusedCell = focused != null && focused.getParent() instanceof AppCell cell
                ? cell : null;

        // Where each one is drawn now, and the apps' buttons by app
        final ScrollView scroll = mAppsScroll;
        final int wasScrolled = scroll.getScrollY();
        final Map<View, Float> was = new ArrayMap<>();
        final Map<String, AppCell> cells = new ArrayMap<>();
        final List<AppCell> oldCells = new ArrayList<>();
        View oldDivider = null;
        for (int i = 0; i < column.getChildCount(); i++) {
            final View child = column.getChildAt(i);
            was.put(child, child.getTop() + child.getTranslationY());
            if (child instanceof AppCell cell) {
                cells.put(cell.entry.packageName, cell);
                oldCells.add(cell);
            } else {
                oldDivider = child;
            }
        }

        final List<View> order = new ArrayList<>();
        final List<AppCell> appCells = new ArrayList<>();
        WheelTaskbarApps.Entry previous = null;
        for (WheelTaskbarApps.Entry app : mApps) {
            if (previous != null && previous.pinned && !app.pinned) {
                order.add(oldDivider != null ? oldDivider : divider());
                oldDivider = null;
            }
            AppCell cell = cells.remove(app.packageName);
            final boolean isNew = cell == null;
            if (isNew) {
                cell = new AppCell(mContext);
            }
            cell.bind(app, animate && !isNew);
            order.add(cell);
            appCells.add(cell);
            previous = app;
        }
        final List<View> gone = new ArrayList<>(cells.values());
        if (oldDivider != null) {
            gone.add(oldDivider);
        }

        for (int i = 0; i < order.size(); i++) {
            final View view = order.get(i);
            if (view.getParent() == column) {
                if (column.indexOfChild(view) != i) {
                    column.move(view, i);
                }
            } else {
                column.addView(view, i, cellParams(view));
            }
        }

        // The focus moves on before its button goes
        if (focusedCell != null && gone.contains(focusedCell)) {
            final int at = oldCells.indexOf(focusedCell);
            if (!appCells.isEmpty()) {
                appCells.get(Math.min(at, appCells.size() - 1)).button.requestFocusFromTouch();
            } else {
                final List<View> targets = targets(mBar);
                targets.removeIf(v -> gone.contains((View) v.getParent()));
                if (!targets.isEmpty()) {
                    targets.get(Math.max(0, Math.min(focusedTarget, targets.size() - 1)))
                            .requestFocusFromTouch();
                }
            }
        }
        final long duration = mSettings.duration(CHANGE_MS);
        for (View view : gone) {
            if (animate) {
                // Drawn where it was while it fades, the others closing up over it; over the
                // scroll, as the shorter column no longer reaches the last one's place
                scroll.getOverlay().add(view);
                view.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(duration)
                        .setStartDelay(0).setInterpolator(EMPHASIZED)
                        .withEndAction(() -> scroll.getOverlay().remove(view)).start();
            } else {
                column.removeView(view);
            }
        }
        updateNotifications(false);
        if (!animate) {
            return;
        }
        column.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                column.getViewTreeObserver().removeOnPreDrawListener(this);
                // A shorter column scrolls back at once: that jump glides too (the fading
                // ones stay put, the scroll's overlay doesn't scroll)
                final int scrolled = scroll.getScrollY() - wasScrolled;
                for (View view : order) {
                    final Float from = was.get(view);
                    if (from == null) {
                        view.setAlpha(0f);
                        view.setScaleX(0.5f);
                        view.setScaleY(0.5f);
                        view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(duration)
                                .setStartDelay(duration / 3)
                                .setInterpolator(new OvershootInterpolator(1.4f)).start();
                    } else if (from + scrolled != view.getTop()) {
                        view.setTranslationY(from + scrolled - view.getTop());
                        view.animate().translationY(0f).setDuration(duration).setStartDelay(0)
                                .setInterpolator(EMPHASIZED).start();
                    }
                }
                return true;
            }
        });
    }

    /** Closes an app, its button going at once while its task goes off the main thread. */
    private void closeApps(List<WheelTaskbarApps.Entry> apps) {
        final Set<String> closing = new ArraySet<>();
        for (WheelTaskbarApps.Entry app : apps) {
            closing.add(app.packageName);
        }
        final List<WheelTaskbarApps.Entry> left = new ArrayList<>();
        for (WheelTaskbarApps.Entry app : mApps) {
            if (!closing.contains(app.packageName)) {
                left.add(app);
            } else if (app.pinned) {
                left.add(app.stopped());
            }
        }
        final int userId = mUserTracker.getUserId();
        mBackground.execute(() -> {
            for (WheelTaskbarApps.Entry app : apps) {
                try {
                    if (app.taskId >= 0) {
                        ActivityManagerWrapper.getInstance().removeTask(app.taskId);
                    } else if (app.background) {
                        ActivityManager.getService().stopAppForUser(app.packageName, userId);
                    }
                } catch (RemoteException | RuntimeException e) {
                    Log.w(TAG, "Couldn't close " + app.packageName, e);
                }
            }
        });
        mApps = left;
        applyApps(true);
        // After the closing on the same thread: the load sees them gone
        requestApps();
    }

    private View addButton(int icon, CharSequence name, Runnable action) {
        return addButton(mContext.getDrawable(icon), name, action);
    }

    private View addButton(Drawable icon, CharSequence name, Runnable action) {
        final FrameLayout cell = new FrameLayout(mContext);
        final ImageView button = new ImageView(mContext);
        button.setFocusable(true);
        button.setBackground(pressFeedback(dp(12)));
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setImageDrawable(icon);
        button.setImageTintList(ColorStateList.valueOf(
                mContext.getColor(R.color.wheel_taskbar_text)));
        button.setContentDescription(name);
        button.setOnClickListener(v -> action.run());
        decorate(button);
        cell.addView(button, new FrameLayout.LayoutParams(buttonSize(), buttonSize(),
                Gravity.CENTER));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellSize(),
                cellSize());
        lp.bottomMargin = mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_gap) / 2;
        mBar.addView(cell, lp);
        return cell;
    }

    /** The focus lights a button up, grows it a little and names it beside the bar. */
    private void decorate(View button) {
        button.setOnFocusChangeListener((v, hasFocus) -> {
            if (mRefilling) {
                return;
            }
            final float scale = hasFocus ? 1.06f : 1f;
            v.animate().scaleX(scale).scaleY(scale).setDuration(mSettings.duration(FOCUS_MS))
                    .setInterpolator(new DecelerateInterpolator()).start();
            if (hasFocus) {
                mBarHighlight.follow(v, cellSize(), cellSize());
                showLabel(v);
                revealAppsEnd(v);
            }
        });
    }

    /**
     * A button above or below the apps scrolls them to that end, so the wheel enters the list
     * at its first or last app, none half under a button. A focused app scrolls itself in view.
     */
    private void revealAppsEnd(View button) {
        if (mAppsScroll == null || mBar == null) {
            return;
        }
        final int at = mBar.indexOfChild((View) button.getParent());
        if (at < 0) {
            return;
        }
        final int y = at < mBar.indexOfChild(mAppsScroll) ? 0
                : Math.max(0, mAppsColumn.getHeight() - mAppsScroll.getHeight());
        if (mSettings.animation > 0) {
            mAppsScroll.smoothScrollTo(0, y);
        } else {
            mAppsScroll.scrollTo(0, y);
        }
    }

    private TextView badge(FrameLayout cell) {
        final TextView badge = new TextView(mContext);
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(9));
        shape.setColor(mContext.getColor(R.color.wheel_taskbar_badge));
        shape.setStroke(dp(2), barColor());
        badge.setBackground(shape);
        badge.setGravity(Gravity.CENTER);
        final int size = mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_badge_size) + dp(2);
        badge.setMinWidth(size);
        badge.setPadding(dp(5), 0, dp(5), 0);
        badge.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Badge);
        badge.setVisibility(View.GONE);
        cell.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                size, Gravity.TOP | Gravity.END));
        return badge;
    }

    private void notificationsChanged() {
        if (mRoot != null) {
            mHandler.removeCallbacks(mNotificationsChanged);
            mHandler.postDelayed(mNotificationsChanged, 50);
        }
    }

    /** The count on the notifications button and the dots on the apps, popping as they come. */
    private void updateNotifications(boolean animate) {
        if (mRoot == null) {
            return;
        }
        animate &= mSettings.animation > 0;
        final Set<String> packages = new ArraySet<>();
        final int count = notificationCount(packages);
        final boolean changed = count != mNotificationCount;
        mNotificationCount = count;
        if (mBadge != null) {
            final boolean shown = mBadge.getVisibility() == View.VISIBLE;
            if (count > 0) {
                mBadge.setText(count > 9 ? "9+" : Integer.toString(count));
                mBadge.setVisibility(View.VISIBLE);
                if (animate && (changed || !shown)) {
                    mBadge.setScaleX(0.4f);
                    mBadge.setScaleY(0.4f);
                    mBadge.animate().scaleX(1f).scaleY(1f)
                            .setDuration(mSettings.duration(CHANGE_MS))
                            .setInterpolator(new OvershootInterpolator(3f)).start();
                }
            } else if (shown) {
                if (animate) {
                    mBadge.animate().scaleX(0f).scaleY(0f)
                            .setDuration(mSettings.duration(PANEL_MS)).withEndAction(() -> {
                                if (mNotificationCount == 0 && mBadge != null) {
                                    mBadge.setVisibility(View.GONE);
                                }
                            }).start();
                } else {
                    mBadge.setVisibility(View.GONE);
                }
            }
        }
        if (mAppsColumn != null) {
            for (int i = 0; i < mAppsColumn.getChildCount(); i++) {
                if (mAppsColumn.getChildAt(i) instanceof AppCell cell) {
                    cell.setNotified(packages.contains(cell.entry.packageName), animate);
                }
            }
        }
        if (changed && mLabelFor != null && mLabelFor == mNotificationsButton) {
            showLabel(mNotificationsButton);
        }
    }

    // ---- The label ----

    private void buildLabel(FrameLayout side) {
        final int gap = mContext.getResources().getDimensionPixelSize(R.dimen.wheel_taskbar_gap);
        mLabel = new LinearLayout(mContext);
        mLabel.setOrientation(LinearLayout.VERTICAL);
        mLabel.setBackgroundResource(R.drawable.wheel_taskbar_label_background);
        mLabel.setPadding(dp(12), dp(7), dp(12), dp(8));
        mLabel.setVisibility(View.GONE);
        mLabelPreview = new ImageView(mContext);
        mLabelPreview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        mLabelPreview.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(8));
            }
        });
        mLabelPreview.setClipToOutline(true);
        mLabelPreview.setVisibility(View.GONE);
        final int previewWidth = mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_preview_width);
        final LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(previewWidth,
                previewWidth * 4 / 3);
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.topMargin = dp(3);
        plp.bottomMargin = dp(8);
        mLabel.addView(mLabelPreview, plp);
        // As wide as the screen leaves beside the bar
        final int maxWidth = mContext.getResources().getDisplayMetrics().widthPixels
                - barWidth() - 2 * gap - dp(24);
        mLabelTitle = new TextView(mContext);
        mLabelTitle.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Label);
        mLabelTitle.setSingleLine(true);
        mLabelTitle.setEllipsize(TextUtils.TruncateAt.END);
        mLabelTitle.setMaxWidth(maxWidth);
        // Each line as wide as its words; a vertical LinearLayout's default fills the card
        mLabel.addView(mLabelTitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mLabelHint = new TextView(mContext);
        mLabelHint.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Hint);
        mLabelHint.setSingleLine(true);
        mLabelHint.setEllipsize(TextUtils.TruncateAt.END);
        mLabelHint.setMaxWidth(maxWidth);
        mLabel.addView(mLabelHint, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | (mSettings.left ? Gravity.START : Gravity.END));
        lp.setMarginStart(gap);
        lp.setMarginEnd(gap);
        side.addView(mLabel, lp);
        mLabelY = new SpringAnimation(mLabel, DynamicAnimation.TRANSLATION_Y);
        mLabelY.setSpring(new SpringForce().setStiffness(mSettings.animation >= 1 ? 700f : 1500f)
                .setDampingRatio(0.85f));
    }

    /**
     * Names the focused bar button beside it, what holding the side button does there and,
     * for a running app, its last screen; the label glides along as the focus moves.
     */
    private void showLabel(View button) {
        if (!mMenus.isEmpty() || !mSettings.labels || mLabel == null) {
            hideLabel();
            return;
        }
        final CharSequence title;
        CharSequence hint = null;
        int task = -1;
        if (button.getTag() instanceof WheelTaskbarApps.Entry app) {
            title = app.label;
            final String state = app.front
                    ? mContext.getString(R.string.wheel_taskbar_state_front)
                    : app.background
                    ? mContext.getString(R.string.wheel_taskbar_state_background)
                    : app.running() ? mContext.getString(R.string.wheel_taskbar_running)
                    : mContext.getString(R.string.wheel_taskbar_pinned);
            hint = mContext.getString(R.string.wheel_taskbar_hint_state, state,
                    mContext.getString(R.string.wheel_taskbar_hint_hold));
            if (app.taskId >= 0 && !app.front) {
                task = app.taskId;
            }
        } else {
            title = button.getContentDescription();
            if (button == mNotificationsButton) {
                hint = mNotificationCount > 0 ? mContext.getResources().getQuantityString(
                        R.plurals.wheel_taskbar_hint_notifications, mNotificationCount,
                        mNotificationCount)
                        : mContext.getString(R.string.wheel_taskbar_hint_no_notifications);
            } else if (button == mDrawerButton && mDrawerApps != null) {
                hint = mContext.getResources().getQuantityString(
                        R.plurals.wheel_taskbar_hint_drawer, mDrawerApps.size(),
                        mDrawerApps.size());
            } else if (button == mModeButton) {
                hint = modeName(appMode(mForegroundPackage));
            }
        }
        mLabelTitle.setText(title);
        mLabelHint.setText(hint);
        mLabelHint.setVisibility(TextUtils.isEmpty(hint) ? View.GONE : View.VISIBLE);
        mLabelFor = button;
        mLabelTask = task;
        final Bitmap thumbnail = task >= 0 ? mThumbnails.get(task) : null;
        if (thumbnail != null) {
            showPreview(thumbnail, false);
        } else {
            mLabelPreview.setVisibility(View.GONE);
            mLabelPreview.setImageDrawable(null);
            if (task >= 0) {
                loadThumbnail(task);
            }
        }
        if (mLabel.getVisibility() != View.VISIBLE) {
            mLabel.setVisibility(View.VISIBLE);
            mLabel.setAlpha(0f);
            mLabelAppearing = true;
        }
        mLabel.invalidate();
    }

    private void showPreview(Bitmap thumbnail, boolean fadeIn) {
        final int width = mContext.getResources().getDimensionPixelSize(
                R.dimen.wheel_taskbar_preview_width);
        final int height = thumbnail.getWidth() <= 0 ? width * 4 / 3 : Math.max(width / 2,
                Math.min(width * 2, width * thumbnail.getHeight() / thumbnail.getWidth()));
        final ViewGroup.LayoutParams lp = mLabelPreview.getLayoutParams();
        if (lp.height != height) {
            lp.height = height;
            mLabelPreview.setLayoutParams(lp);
        }
        mLabelPreview.setImageBitmap(thumbnail);
        mLabelPreview.setVisibility(View.VISIBLE);
        if (fadeIn && mSettings.animation > 0) {
            mLabelPreview.setAlpha(0f);
            mLabelPreview.animate().alpha(1f).setDuration(mSettings.duration(PANEL_MS)).start();
        } else {
            mLabelPreview.setAlpha(1f);
        }
    }

    private void hideLabel() {
        if (mLabel == null) {
            return;
        }
        mLabelFor = null;
        mLabelTask = -1;
        mLabelY.cancel();
        mLabel.animate().cancel();
        mLabel.setVisibility(View.GONE);
    }

    /** Before every frame: the label beside its button, as it moves, scrolls or resizes. */
    private void trackLabel() {
        if (mLabel == null || mLabel.getVisibility() != View.VISIBLE || mLabelFor == null
                || !mLabelFor.isAttachedToWindow() || mLabel.getHeight() == 0) {
            return;
        }
        final int[] at = new int[2];
        final int[] side = new int[2];
        mLabelFor.getLocationInWindow(at);
        final View parent = (View) mLabel.getParent();
        parent.getLocationInWindow(side);
        final float centre = at[1] - side[1] + mLabelFor.getHeight() * mLabelFor.getScaleY() / 2f;
        final float margin = dp(8);
        final float y = Math.max(margin, Math.min(parent.getHeight() - mLabel.getHeight()
                - margin, centre - mLabel.getHeight() / 2f));
        if (mLabelAppearing) {
            mLabelAppearing = false;
            mLabelY.cancel();
            mLabel.setTranslationY(y);
            mLabelTargetY = y;
            mLabel.setTranslationX(mSettings.left ? -dp(8) : dp(8));
            mLabel.animate().alpha(1f).translationX(0f)
                    .setDuration(mSettings.duration(FOCUS_MS * 3 / 2))
                    .setInterpolator(new DecelerateInterpolator()).start();
            return;
        }
        if (y != mLabelTargetY) {
            mLabelTargetY = y;
            if (mSettings.animation > 0) {
                mLabelY.animateToFinalPosition(y);
            } else {
                mLabel.setTranslationY(y);
            }
        }
    }

    // ---- Opening and closing ----

    private void hide() {
        mHandler.removeCallbacks(mHide);
        if (mRoot == null) {
            return;
        }
        final View closing = mRoot;
        mRoot = null;
        mMenus.clear();
        hideLabel();
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
        mThumbnails.clear();
        mDrawerApps = null;
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
            mHandler.postDelayed(mHide, mMenus.isEmpty() ? mSettings.timeoutMs
                    : 2 * mSettings.timeoutMs);
        }
    }

    /** The running apps' last screens, so their labels show them at once. */
    private void prefetchThumbnails() {
        if (!mSettings.labels) {
            return;
        }
        for (WheelTaskbarApps.Entry app : mApps) {
            if (app.taskId >= 0 && !app.front) {
                loadThumbnail(app.taskId);
            }
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
                    if (!mMenus.isEmpty()) {
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
        final boolean inList = !mMenus.isEmpty();
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
        if (menuType() == MENU_DRAWER && mFastSteps >= FAST_STEPS) {
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
                // Out of a list back to where it opened from, out of the bar closes it
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
        final ViewGroup list = mMenus.isEmpty() ? mBar : mPanelItems;
        if (focused == null || focused.isInTouchMode() || !isInside(focused, list)) {
            // Nothing focused yet, or a list still filling
            focus(list, 0);
            return;
        }
        if (mSettings.haptics) {
            focused.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
        (mMenus.isEmpty() ? mBarHighlight : mPanelHighlight).press();
        focused.performClick();
    }

    /**
     * Opens the options of the focused app, in the bar or the list of all apps; anywhere else
     * the r1 sleeps, as the side button does.
     */
    private void onLongPress() {
        final View focused = mRoot.findFocus();
        String app = null;
        if (focused != null && focused.getTag() instanceof WheelTaskbarApps.Entry entry) {
            app = entry.packageName;
        } else if (focused != null
                && focused.getTag() instanceof WheelTaskbarApps.Launchable launchable) {
            app = launchable.packageName();
        }
        if (app != null && menuType() != MENU_APP) {
            focused.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            openMenu(MENU_APP, app);
            return;
        }
        hide();
        mContext.getSystemService(PowerManager.class).goToSleep(SystemClock.uptimeMillis(),
                PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON, 0);
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
        requestApps();
    }

    private void focus(ViewGroup list, int index) {
        final List<View> targets = targets(list);
        if (!targets.isEmpty()) {
            targets.get(Math.max(0, Math.min(index, targets.size() - 1))).requestFocusFromTouch();
        }
    }

    private static boolean isInside(View view, ViewGroup group) {
        for (View v = view; v != null; v = v.getParent() instanceof View p ? p : null) {
            if (v == group) {
                return true;
            }
        }
        return false;
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

    private void buildPanel(FrameLayout side) {
        mPanel = new LinearLayout(mContext);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setBackgroundResource(R.drawable.wheel_taskbar_menu_background);
        mPanel.setPadding(dp(8), dp(12), dp(8), dp(12));
        mPanel.setVisibility(View.GONE);
        mPanelTitle = new TextView(mContext);
        mPanelTitle.setAllCaps(true);
        mPanelTitle.setSingleLine(true);
        mPanelTitle.setEllipsize(TextUtils.TruncateAt.END);
        mPanelTitle.setTextAppearance(R.style.TextAppearance_WheelTaskbar_Title);
        mPanelTitle.setPadding(dp(12), 0, dp(12), dp(8));
        mPanel.addView(mPanelTitle);
        mPanelScroll = new ScrollView(mContext);
        mPanelScroll.setVerticalScrollBarEnabled(false);
        mPanelScroll.setVerticalFadingEdgeEnabled(true);
        mPanelScroll.setFadingEdgeLength(dp(24));
        mPanelScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // The rows over their focus light, which scrolls with them
        mPanelContent = new FrameLayout(mContext);
        mPanelHighlight = highlight(dp(12));
        mPanelContent.addView(mPanelHighlight, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        mPanelItems = new LinearLayout(mContext);
        mPanelItems.setOrientation(LinearLayout.VERTICAL);
        mPanelContent.addView(mPanelItems, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mPanelScroll.addView(mPanelContent, new FrameLayout.LayoutParams(
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

    private int menuType() {
        return mMenus.isEmpty() ? MENU_NONE : top().type;
    }

    private Menu top() {
        return mMenus.get(mMenus.size() - 1);
    }

    /**
     * Opens a list from the bar, or one from the list shown (an app's options from all apps),
     * which back returns to.
     */
    private void openMenu(int type, String app) {
        final View focused = mRoot.findFocus();
        final boolean nested = !mMenus.isEmpty();
        if (nested) {
            top().focus = Math.max(0, targets(mPanelItems).indexOf(focused));
        }
        mMenus.add(new Menu(type, nested ? null : focused, app));
        hideLabel();
        // Shown first: only rows on screen take the focus
        mPanel.setVisibility(View.VISIBLE);
        refill(top(), -1);
        mPanelScroll.scrollTo(0, 0);
        final long duration = mSettings.duration(PANEL_MS);
        if (nested) {
            slideContent(1);
        } else {
            mPanel.animate().cancel();
            mPanel.setAlpha(0f);
            mPanel.setTranslationX(mSettings.left ? -dp(16) : dp(16));
            mPanel.setScaleY(0.96f);
            mPanel.animate().alpha(1f).translationX(0).scaleY(1f).setDuration(duration)
                    .setInterpolator(EMPHASIZED).start();
            // The bar's light stays on the button the list came from, dimmed
            mBarHighlight.animate().alpha(0.35f).setDuration(duration).start();
        }
        keepOpen();
    }

    /** Back one list: to the list it came from, or to its button in the bar. */
    private void closeMenu() {
        if (mMenus.isEmpty()) {
            return;
        }
        final Menu closed = mMenus.remove(mMenus.size() - 1);
        if (!mMenus.isEmpty()) {
            refill(top(), top().focus);
            slideContent(-1);
            keepOpen();
            return;
        }
        final long duration = mSettings.duration(PANEL_MS * 3 / 4);
        mPanel.animate().cancel();
        mPanel.animate().alpha(0f).translationX(mSettings.left ? -dp(16) : dp(16))
                .setDuration(duration).withEndAction(() -> {
                    if (mMenus.isEmpty()) {
                        mPanel.setVisibility(View.GONE);
                        mRefilling = true;
                        mPanelItems.removeAllViews();
                        mRefilling = false;
                        mPanelHighlight.follow(null, 0, 0);
                    }
                }).start();
        mBarHighlight.animate().alpha(1f).setDuration(duration).start();
        if (closed.button != null && closed.button.isShown()) {
            closed.button.requestFocusFromTouch();
        } else {
            focus(mBar, 0);
        }
        keepOpen();
    }

    /** The lists that open from an app's options close back to the bar. */
    private void closeMenus() {
        while (mMenus.size() > 1) {
            mMenus.remove(mMenus.size() - 1);
        }
        closeMenu();
    }

    /** Fills the panel with a list's rows and focuses its row {@code focus}, -1: its own. */
    private void refill(Menu menu, int focus) {
        mRefilling = true;
        mPanelItems.removeAllViews();
        mPanelHighlight.follow(null, 0, 0);
        final int own;
        switch (menu.type) {
            case MENU_DRAWER:
                mPanelTitle.setText(R.string.wheel_taskbar_drawer);
                own = fillDrawer();
                break;
            case MENU_MODES:
                mPanelTitle.setText(R.string.wheel_taskbar_mode);
                own = fillModes();
                break;
            default:
                own = fillAppMenu(menu.app);
                break;
        }
        mRefilling = false;
        focus(mPanelItems, focus < 0 ? own : focus);
    }

    /** The panel's rows come in from the side the list came from, or go back to. */
    private void slideContent(int direction) {
        final long duration = mSettings.duration(PANEL_MS);
        if (duration == 0) {
            return;
        }
        mPanelContent.setAlpha(0f);
        mPanelContent.setTranslationX(direction * dp(24) * (mSettings.left ? -1 : 1));
        mPanelContent.animate().alpha(1f).translationX(0f).setDuration(duration)
                .setInterpolator(EMPHASIZED).start();
        mPanelTitle.setAlpha(0f);
        mPanelTitle.animate().alpha(1f).setDuration(duration).start();
    }

    /** All apps, A to Z under their letters, pinned and running ones said so. */
    private int fillDrawer() {
        if (mDrawerApps == null) {
            // Loading, they come in when they are in
            return 0;
        }
        final LauncherApps launcher = mContext.getSystemService(LauncherApps.class);
        // From the setting, which a pin from an app's options changed just now
        final String pinnedApps = "|" + PowerMenuUtils.getPinnedApps(mContext,
                mUserTracker.getUserId()) + "|";
        char letter = 0;
        for (WheelTaskbarApps.Launchable app : mDrawerApps) {
            final CharSequence label = app.label;
            final char initial = TextUtils.isEmpty(label) ? '#'
                    : Character.toUpperCase(label.charAt(0));
            if (initial != letter) {
                letter = initial;
                addHeader(Character.isLetter(initial) ? String.valueOf(initial) : "#");
            }
            final WheelTaskbarApps.Entry entry = entryOf(app.packageName());
            final boolean running = entry != null && entry.running();
            final boolean pinned = pinnedApps.contains("|" + app.packageName() + "|");
            final View row = addRow(WheelTaskbarApps.copy(app.icon), label, pinned && running
                    ? mContext.getString(R.string.wheel_taskbar_pinned_running)
                    : pinned ? mContext.getString(R.string.wheel_taskbar_pinned)
                    : running ? mContext.getString(R.string.wheel_taskbar_running) : null,
                    false);
            row.setTag(app);
            row.setContentDescription(label);
            row.setOnClickListener(v -> {
                hide();
                launcher.startMainActivity(app.info.getComponentName(), app.info.getUser(),
                        null, null);
            });
        }
        if (mDrawerApps.isEmpty()) {
            addRow(null, mContext.getString(R.string.wheel_taskbar_no_apps), null, false)
                    .setEnabled(false);
        }
        return 0;
    }

    /** What the wheel does in the app in front; it is kept for that app. Focus: the current. */
    private int fillModes() {
        final String app = mForegroundPackage;
        final String current = appMode(app);
        final String[] modes = {MODE_TASKBAR, MODE_APP, MODE_VOLUME};
        int focusIndex = 0;
        for (int i = 0; i < modes.length; i++) {
            final String mode = modes[i];
            final boolean isCurrent = mode.equals(current);
            if (isCurrent) {
                focusIndex = i;
            }
            final CharSequence summary = isCurrent
                    ? mContext.getString(R.string.wheel_taskbar_mode_current)
                    : MODE_TASKBAR.equals(mode) ? null
                    : mContext.getString(R.string.wheel_taskbar_mode_back);
            addRow(mContext.getDrawable(modeIconRes(mode)), modeName(mode), summary, true)
                    .setOnClickListener(v -> {
                        setAppMode(app, mode);
                        if (mModeIcon != null) {
                            mModeIcon.setImageResource(modeIconRes(mode));
                        }
                        hide();
                    });
        }
        return focusIndex;
    }

    /**
     * An app's options: close it (stop it, running in the background), close all apps, pin or
     * unpin it, its app info. The focus starts on the first, so a hold and a press close it.
     */
    private int fillAppMenu(String packageName) {
        final WheelTaskbarApps.Entry app = entryOf(packageName);
        mPanelTitle.setText(app != null ? app.label
                : WheelTaskbarApps.appLabel(userContext().getPackageManager(), packageName));
        if (app != null && app.taskId >= 0) {
            addRow(mContext.getDrawable(R.drawable.ic_wheel_taskbar_close),
                    mContext.getString(R.string.wheel_taskbar_app_close), null, true)
                    .setOnClickListener(v -> {
                        closeMenus();
                        closeApps(List.of(app));
                    });
        } else if (app != null && app.background) {
            addRow(mContext.getDrawable(R.drawable.ic_wheel_taskbar_stop),
                    mContext.getString(R.string.wheel_taskbar_app_stop), null, true)
                    .setOnClickListener(v -> {
                        closeMenus();
                        closeApps(List.of(app));
                    });
        }
        final List<WheelTaskbarApps.Entry> running = new ArrayList<>();
        for (WheelTaskbarApps.Entry entry : mApps) {
            if (entry.taskId >= 0) {
                running.add(entry);
            }
        }
        if (running.size() > 1 || (running.size() == 1 && running.get(0) != app)) {
            addRow(mContext.getDrawable(R.drawable.ic_wheel_taskbar_close_all),
                    mContext.getString(R.string.wheel_taskbar_app_close_all), null, true)
                    .setOnClickListener(v -> {
                        closeMenus();
                        closeApps(running);
                    });
        }
        final boolean pinned = isPinned(packageName);
        addRow(mContext.getDrawable(pinned ? R.drawable.ic_wheel_taskbar_unpin
                : R.drawable.ic_wheel_taskbar_pin),
                mContext.getString(pinned ? R.string.wheel_taskbar_app_unpin
                        : R.string.wheel_taskbar_app_pin), null, true)
                .setOnClickListener(v -> {
                    togglePin(packageName);
                    // The bar moves the app along; the list of all apps says so
                    closeMenu();
                });
        addRow(mContext.getDrawable(R.drawable.ic_wheel_taskbar_info),
                mContext.getString(R.string.wheel_taskbar_app_info), null, true)
                .setOnClickListener(v -> {
                    hide();
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                });
        return 0;
    }

    private String modeName(String mode) {
        switch (mode) {
            case MODE_APP:
                return mContext.getString(R.string.wheel_taskbar_mode_app,
                        WheelTaskbarApps.appLabel(userContext().getPackageManager(),
                                mForegroundPackage));
            case MODE_VOLUME:
                return mContext.getString(R.string.wheel_taskbar_mode_volume);
            default:
                return mContext.getString(R.string.wheel_taskbar_mode_taskbar);
        }
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

    private View addRow(Drawable icon, CharSequence title, CharSequence summary, boolean tint) {
        final View row = LayoutInflater.from(mContext).inflate(
                R.layout.wheel_taskbar_menu_item, mPanelItems, false);
        row.setBackground(pressFeedback(dp(12)));
        final ImageView iconView = row.findViewById(R.id.wheel_taskbar_item_icon);
        iconView.setImageDrawable(icon);
        iconView.setVisibility(icon == null ? View.GONE : View.VISIBLE);
        if (tint) {
            iconView.setImageTintList(ColorStateList.valueOf(
                    mContext.getColor(R.color.wheel_taskbar_text)));
            iconView.setScaleType(ImageView.ScaleType.CENTER);
        }
        ((TextView) row.findViewById(R.id.wheel_taskbar_item_title)).setText(title);
        final TextView summaryView = row.findViewById(R.id.wheel_taskbar_item_summary);
        summaryView.setText(summary);
        summaryView.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
        row.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus && !mRefilling) {
                mPanelHighlight.follow(v, 0, 0);
                if (v.isLaidOut()) {
                    revealRow(v);
                } else {
                    v.post(() -> revealRow(v));
                }
            }
        });
        mPanelItems.addView(row);
        return row;
    }

    /** Keeps a row of context visible around the focus while scrolling. */
    private void revealRow(View row) {
        if (!row.hasFocus() && !row.isFocused()) {
            return;
        }
        final int margin = dp(24);
        final int top = row.getTop() - margin;
        final int bottom = row.getBottom() + margin;
        final int scrollY = mPanelScroll.getScrollY();
        final int height = mPanelScroll.getHeight();
        final int y = top < scrollY ? Math.max(0, top)
                : bottom > scrollY + height ? bottom - height : scrollY;
        if (y == scrollY) {
            return;
        }
        if (mSettings.animation > 0) {
            mPanelScroll.smoothScrollTo(0, y);
        } else {
            mPanelScroll.scrollTo(0, y);
        }
    }

    // ---- Actions ----

    private void openApp(WheelTaskbarApps.Entry app) {
        hide();
        if (app.taskId >= 0) {
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

    /**
     * Notifications the user can clear: what the shade would show as new. {@code packages}
     * gets the apps they are from.
     */
    private int notificationCount(Set<String> packages) {
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
                packages.add(sbn.getPackageName());
            }
        }
        return count;
    }

    private Context userContext() {
        return mContext.createContextAsUser(mUserTracker.getUserHandle(), 0);
    }
}
