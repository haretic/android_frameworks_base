/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;

import org.lineageos.internal.util.PowerMenuUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The apps in the wheel taskbar, as a taskbar has them: the pinned ones (shared with the power
 * menu), then the running ones, most recent first. An app runs while its activities' process
 * does: with a task (an app the system closed keeps its task in recents but loses that process,
 * so it drops out, or loses its dot if pinned), or with only a foreground service (music,
 * navigation), running in the background. Loaded off the main thread; the icons and names are kept.
 */
final class WheelTaskbarApps {

    /** One app in the bar. */
    static final class Entry {
        final String packageName;
        /** Launches it when it doesn't run. */
        final Intent launch;
        final ComponentName component;
        /** Its task, -1 when it has none running. */
        final int taskId;
        final boolean pinned;
        final boolean front;
        /** It runs with only a foreground service, no task. */
        final boolean background;
        final CharSequence label;
        final Drawable icon;

        Entry(String packageName, Intent launch, ComponentName component, int taskId,
                boolean pinned, boolean front, boolean background, CharSequence label,
                Drawable icon) {
            this.packageName = packageName;
            this.launch = launch;
            this.component = component;
            this.taskId = taskId;
            this.pinned = pinned;
            this.front = front;
            this.background = background;
            this.label = label;
            this.icon = icon;
        }

        boolean running() {
            return taskId >= 0 || background;
        }

        /** The same app once it was closed: pinned, it stays without its dot. */
        Entry stopped() {
            return new Entry(packageName, launch, component, -1, pinned, false, false, label,
                    icon);
        }

        /** The same app in the same state, so a refresh can leave its button alone. */
        boolean same(Entry other) {
            return other != null && packageName.equals(other.packageName)
                    && taskId == other.taskId && front == other.front && pinned == other.pinned
                    && background == other.background && icon == other.icon
                    && TextUtils.equals(label, other.label);
        }
    }

    /** An app in the list of all apps. */
    static final class Launchable {
        final LauncherActivityInfo info;
        final CharSequence label;
        final Drawable icon;

        Launchable(LauncherActivityInfo info, CharSequence label, Drawable icon) {
            this.info = info;
            this.label = label;
            this.icon = icon;
        }

        String packageName() {
            return info.getComponentName().getPackageName();
        }
    }

    /** An icon and name, for as long as the package isn't updated. */
    private static final class Look {
        final long updated;
        final CharSequence label;
        final Drawable icon;

        Look(long updated, CharSequence label, Drawable icon) {
            this.updated = updated;
            this.label = label;
            this.icon = icon;
        }
    }

    /** Component to its look, used from the loading thread only. */
    private final Map<String, Look> mLooks = new ArrayMap<>();

    /**
     * The pinned apps, then up to {@code maxRunning} more running ones. {@code front} is the
     * task in front, -1 if none (home). Blocks, so off the main thread.
     */
    List<Entry> load(Context userContext, int userId, int maxRunning, int front) {
        final PackageManager pm = userContext.getPackageManager();
        final String home = homePackage(pm);
        // By process: an app may keep a helper process up after the system ended its activities
        final Set<String> alive = new ArraySet<>();
        final List<String> services = new ArrayList<>();
        final ActivityManager am = userContext.getSystemService(ActivityManager.class);
        final List<ActivityManager.RunningAppProcessInfo> processes =
                am.getRunningAppProcesses();
        if (processes != null) {
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (UserHandle.getUserId(process.uid) == userId) {
                    alive.add(process.processName);
                }
            }
        }
        for (ActivityManager.RunningServiceInfo service : am.getRunningServices(1000)) {
            final String packageName = service.service.getPackageName();
            if (service.foreground && service.pid > 0
                    && UserHandle.getUserId(service.uid) == userId
                    && !services.contains(packageName)) {
                services.add(packageName);
            }
        }

        final List<ActivityManager.RecentTaskInfo> tasks = ActivityTaskManager.getInstance()
                .getRecentTasks(Math.max(maxRunning, 8) * 3,
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId);
        final List<ActivityManager.RecentTaskInfo> running = new ArrayList<>();
        for (ActivityManager.RecentTaskInfo task : tasks) {
            final ComponentName component = componentOf(task);
            if (task.isRunning && component != null
                    && !component.getPackageName().equals(home)
                    && (task.taskId == front || alive.contains(processOf(pm, task.topActivity))
                            || alive.contains(processOf(pm, component)))) {
                running.add(task);
            }
        }

        final List<Entry> entries = new ArrayList<>();
        final List<String> seen = new ArrayList<>();
        final String pinned = PowerMenuUtils.getPinnedApps(userContext, userId);
        if (!TextUtils.isEmpty(pinned)) {
            for (String packageName : pinned.split("\\|")) {
                final Intent launch = packageName.isEmpty() || seen.contains(packageName)
                        ? null : pm.getLaunchIntentForPackage(packageName);
                if (launch == null || launch.getComponent() == null) {
                    continue;
                }
                seen.add(packageName);
                final ActivityManager.RecentTaskInfo task = taskOf(running, packageName);
                final Look look = look(pm, launch.getComponent());
                entries.add(new Entry(packageName, launch, launch.getComponent(),
                        task == null ? -1 : task.taskId, true,
                        task != null && task.taskId == front,
                        task == null && services.contains(packageName), look.label,
                        look.icon));
            }
        }
        int count = 0;
        for (ActivityManager.RecentTaskInfo task : running) {
            final ComponentName component = componentOf(task);
            final String packageName = component.getPackageName();
            if (count == maxRunning || seen.contains(packageName)) {
                continue;
            }
            seen.add(packageName);
            final Look look = look(pm, component);
            entries.add(new Entry(packageName, pm.getLaunchIntentForPackage(packageName),
                    component, task.taskId, false, task.taskId == front, false, look.label,
                    look.icon));
            count++;
        }
        // Then what plays or works in the background without a task, if it has a launcher icon
        final String self = userContext.getPackageName();
        for (String packageName : services) {
            if (count == maxRunning || seen.contains(packageName)
                    || packageName.equals(home) || packageName.equals(self)) {
                continue;
            }
            final Intent launch = pm.getLaunchIntentForPackage(packageName);
            if (launch == null || launch.getComponent() == null) {
                continue;
            }
            seen.add(packageName);
            final Look look = look(pm, launch.getComponent());
            entries.add(new Entry(packageName, launch, launch.getComponent(), -1, false, false,
                    true, look.label, look.icon));
            count++;
        }
        return entries;
    }

    /** Every app with a launcher icon, A to Z. Blocks, so off the main thread. */
    List<Launchable> loadAll(Context userContext, UserHandle user) {
        final PackageManager pm = userContext.getPackageManager();
        final List<Launchable> apps = new ArrayList<>();
        for (LauncherActivityInfo info : userContext.getSystemService(LauncherApps.class)
                .getActivityList(null, user)) {
            final String key = "all:" + info.getComponentName().flattenToShortString();
            final long updated = updated(pm, info.getComponentName().getPackageName());
            Look look = mLooks.get(key);
            if (look == null || look.updated != updated) {
                look = new Look(updated, info.getLabel(), info.getBadgedIcon(0));
                mLooks.put(key, look);
            }
            apps.add(new Launchable(info, look.label, look.icon));
        }
        final Collator collator = Collator.getInstance(Locale.getDefault());
        apps.sort((a, b) -> collator.compare(String.valueOf(a.label), String.valueOf(b.label)));
        return apps;
    }

    private static long updated(PackageManager pm, String packageName) {
        try {
            return pm.getPackageInfo(packageName, 0).lastUpdateTime;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /** The process an activity runs in, its package's unless it names another. */
    private static String processOf(PackageManager pm, ComponentName activity) {
        if (activity == null) {
            return null;
        }
        try {
            return pm.getActivityInfo(activity, PackageManager.MATCH_DISABLED_COMPONENTS)
                    .processName;
        } catch (PackageManager.NameNotFoundException e) {
            return activity.getPackageName();
        }
    }

    private Look look(PackageManager pm, ComponentName component) {
        final long updated = updated(pm, component.getPackageName());
        final String key = component.flattenToShortString();
        Look look = mLooks.get(key);
        if (look == null || look.updated != updated) {
            Drawable icon;
            try {
                icon = pm.getActivityIcon(component);
            } catch (PackageManager.NameNotFoundException e) {
                icon = pm.getDefaultActivityIcon();
            }
            look = new Look(updated, appLabel(pm, component.getPackageName()), icon);
            mLooks.put(key, look);
        }
        return look;
    }

    /** A drawable of its own for one more view, as views can't share one. */
    static Drawable copy(Drawable icon) {
        final Drawable.ConstantState state = icon == null ? null : icon.getConstantState();
        return state == null ? icon : state.newDrawable().mutate();
    }

    static CharSequence appLabel(PackageManager pm, String packageName) {
        if (packageName == null) {
            return "";
        }
        try {
            return pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    private static ActivityManager.RecentTaskInfo taskOf(
            List<ActivityManager.RecentTaskInfo> tasks, String packageName) {
        for (ActivityManager.RecentTaskInfo task : tasks) {
            if (packageName.equals(componentOf(task).getPackageName())) {
                return task;
            }
        }
        return null;
    }

    static ComponentName componentOf(ActivityManager.RecentTaskInfo task) {
        return task.baseActivity != null ? task.baseActivity
                : task.baseIntent == null ? null : task.baseIntent.getComponent();
    }

    static String homePackage(PackageManager pm) {
        final ResolveInfo home = pm.resolveActivity(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY);
        return home == null || home.activityInfo == null ? null : home.activityInfo.packageName;
    }
}
