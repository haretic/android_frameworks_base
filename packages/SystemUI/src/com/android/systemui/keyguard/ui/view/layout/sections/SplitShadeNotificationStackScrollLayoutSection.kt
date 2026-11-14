/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.keyguard.ui.view.layout.sections

import android.content.Context
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.END
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.constraintlayout.widget.ConstraintSet.TOP
import com.android.systemui.res.R
import com.android.systemui.shade.NotificationPanelView
import com.android.systemui.shade.ShadeDisplayAware
import com.android.systemui.statusbar.notification.stack.ui.view.SharedNotificationContainer
import com.android.systemui.statusbar.notification.stack.ui.viewbinder.SharedNotificationContainerBinder
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.SharedNotificationContainerViewModel
import com.android.systemui.wheel.WheelLandscapeLockscreen
import javax.inject.Inject

/** Large-screen format for notifications, shown as two columns on the device */
class SplitShadeNotificationStackScrollLayoutSection
@Inject
constructor(
    @ShadeDisplayAware context: Context,
    notificationPanelView: NotificationPanelView,
    sharedNotificationContainer: SharedNotificationContainer,
    sharedNotificationContainerViewModel: SharedNotificationContainerViewModel,
    sharedNotificationContainerBinder: SharedNotificationContainerBinder,
    private val smartspaceViewModel:
        com.android.systemui.keyguard.ui.viewmodel.KeyguardSmartspaceViewModel,
) :
    NotificationStackScrollLayoutSection(
        context,
        notificationPanelView,
        sharedNotificationContainer,
        sharedNotificationContainerViewModel,
        sharedNotificationContainerBinder,
    ) {
    override fun applyConstraints(constraintSet: ConstraintSet) {
        if (WheelLandscapeLockscreen.enabled(context)) {
            WheelLandscapeLockscreen.guideline(constraintSet)
            constraintSet.apply {
                clear(R.id.nssl_placeholder, TOP)
                clear(R.id.nssl_placeholder, ConstraintSet.BOTTOM)
                if (smartspaceViewModel.isSmartspaceEnabled) {
                    connect(
                        R.id.nssl_placeholder,
                        TOP,
                        com.android.systemui.shared.R.id.bc_smartspace_view,
                        ConstraintSet.BOTTOM,
                        WheelLandscapeLockscreen.px(context, 8),
                    )
                } else {
                    connect(
                        R.id.nssl_placeholder,
                        TOP,
                        PARENT_ID,
                        TOP,
                        WheelLandscapeLockscreen.top(context),
                    )
                }
                connect(R.id.nssl_placeholder, START, R.id.split_shade_guideline, END)
                connect(R.id.nssl_placeholder, END, PARENT_ID, END)
                connect(
                    R.id.nssl_placeholder,
                    ConstraintSet.BOTTOM,
                    PARENT_ID,
                    ConstraintSet.BOTTOM,
                    WheelLandscapeLockscreen.bottom(context),
                )
            }
            return
        }
        constraintSet.apply {
            connect(
                R.id.nssl_placeholder,
                TOP,
                PARENT_ID,
                TOP,
                context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin),
            )
            connect(R.id.nssl_placeholder, START, PARENT_ID, START)
            connect(R.id.nssl_placeholder, END, PARENT_ID, END)

            addNotificationPlaceholderBarrier(this)
        }
    }
}
