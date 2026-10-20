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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.TextUtils;

import org.lineageos.internal.util.PowerMenuUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The apps in the wheel taskbar, as a taskbar has them: the pinned ones (shared with the power
 * menu), then the running ones, most recent first. A task counts as running while it has a
 * running activity, so an app the system closed drops out, or loses its dot if pinned.
 */
final class WheelTaskbarApps {

    /** One app in the bar. */
    static final class Entry {
        final String packageName;
        /** Launches it when it doesn't run. */
        final Intent launch;
        final ComponentName component;
        /** -1 when it doesn't run. */
        final int taskId;
        final boolean pinned;
        final boolean front;

        Entry(String packageName, Intent launch, ComponentName component, int taskId,
                boolean pinned, boolean front) {
            this.packageName = packageName;
            this.launch = launch;
            this.component = component;
            this.taskId = taskId;
            this.pinned = pinned;
            this.front = front;
        }

        boolean running() {
            return taskId >= 0;
        }

        /** The same app in the same state, so a refresh can leave its button alone. */
        boolean same(Entry other) {
            return other != null && packageName.equals(other.packageName)
                    && taskId == other.taskId && front == other.front && pinned == other.pinned;
        }
    }

    private WheelTaskbarApps() {
    }

    /**
     * The pinned apps, then up to {@code maxRunning} more running ones. {@code front} is the
     * task in front, -1 if none (home).
     */
    static List<Entry> load(Context userContext, int userId, int maxRunning, int front) {
        final PackageManager pm = userContext.getPackageManager();
        final String home = homePackage(pm);
        final List<ActivityManager.RecentTaskInfo> tasks = ActivityTaskManager.getInstance()
                .getRecentTasks(Math.max(maxRunning, 8) * 3,
                        ActivityManager.RECENT_IGNORE_UNAVAILABLE, userId);
        final List<ActivityManager.RecentTaskInfo> running = new ArrayList<>();
        for (ActivityManager.RecentTaskInfo task : tasks) {
            final ComponentName component = componentOf(task);
            if (task.isRunning && component != null
                    && !component.getPackageName().equals(home)) {
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
                entries.add(new Entry(packageName, launch, launch.getComponent(),
                        task == null ? -1 : task.taskId, true,
                        task != null && task.taskId == front));
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
            entries.add(new Entry(packageName, pm.getLaunchIntentForPackage(packageName),
                    component, task.taskId, false, task.taskId == front));
            count++;
        }
        return entries;
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
