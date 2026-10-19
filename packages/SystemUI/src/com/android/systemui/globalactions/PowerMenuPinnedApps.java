/*
 * Copyright (C) 2026 The andr36oid Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.globalactions;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.res.R;

import org.lineageos.internal.util.PowerMenuUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * The apps pinned to the power menu (LineageParts, Power menu), inside the menu's card: a
 * row under the buttons in portrait, a small grid beside them in landscape. Pinned apps that
 * were uninstalled or disabled since are left out; with none left the section isn't shown.
 */
public class PowerMenuPinnedApps extends LinearLayout {

    /** More don't fit the card on a small screen. */
    static final int MAX_APPS = 4;

    private ViewGroup mGrid;

    public PowerMenuPinnedApps(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /**
     * The section for the user's pinned apps, or null without any. A tap dismisses the menu
     * through {@code dismiss} and starts the app, behind the bouncer when the device is locked.
     */
    static PowerMenuPinnedApps create(Context context, ViewGroup parent, UserHandle user,
            ActivityStarter activityStarter, Runnable dismiss) {
        final PackageManager pm = context.createContextAsUser(user, 0).getPackageManager();
        final List<ResolveInfo> apps = resolve(context, pm, user);
        if (apps.isEmpty()) {
            return null;
        }
        final PowerMenuPinnedApps section = (PowerMenuPinnedApps) LayoutInflater.from(context)
                .inflate(R.layout.global_actions_pinned, parent, false);
        section.setApps(apps, pm, activityStarter, dismiss);
        return section;
    }

    private static List<ResolveInfo> resolve(Context context, PackageManager pm,
            UserHandle user) {
        final List<ResolveInfo> apps = new ArrayList<>();
        final String pinned = PowerMenuUtils.getPinnedApps(context, user.getIdentifier());
        if (TextUtils.isEmpty(pinned)) {
            return apps;
        }
        for (String packageName : pinned.split("\\|")) {
            if (apps.size() == MAX_APPS) {
                break;
            }
            if (packageName.isEmpty()) {
                continue;
            }
            final Intent intent = pm.getLaunchIntentForPackage(packageName);
            final ResolveInfo info = intent == null ? null : pm.resolveActivity(intent, 0);
            if (info != null && info.activityInfo != null) {
                apps.add(info);
            }
        }
        return apps;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mGrid = findViewById(R.id.global_actions_pinned_grid);
    }

    private void setApps(List<ResolveInfo> apps, PackageManager pm,
            ActivityStarter activityStarter, Runnable dismiss) {
        final Context context = getContext();
        final LayoutInflater inflater = LayoutInflater.from(context);
        final int columns = context.getResources().getInteger(
                R.integer.global_actions_pinned_columns);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                row.setOrientation(HORIZONTAL);
                mGrid.addView(row, new LayoutParams(LayoutParams.WRAP_CONTENT,
                        LayoutParams.WRAP_CONTENT));
            }
            final ResolveInfo info = apps.get(i);
            final View tile = inflater.inflate(R.layout.global_actions_pinned_item, row, false);
            final CharSequence label = info.loadLabel(pm);
            ((ImageView) tile.findViewById(R.id.global_actions_pinned_icon))
                    .setImageDrawable(info.loadIcon(pm));
            ((TextView) tile.findViewById(R.id.global_actions_pinned_label)).setText(label);
            tile.setContentDescription(label);
            final Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            tile.setOnClickListener(v -> {
                dismiss.run();
                activityStarter.postStartActivityDismissingKeyguard(launch, 0);
            });
            row.addView(tile);
        }
    }
}
