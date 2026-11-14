/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.wheel

import android.content.Context
import android.content.res.Configuration
import android.graphics.Insets
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.plugins.keyguard.ui.clocks.ClockViewIds
import com.android.systemui.res.R
import com.android.systemui.shared.R as sharedR
import kotlin.math.roundToInt

/** Geometry shared by the stock keyguard sections on the r1's short landscape display. */
object WheelLandscapeLockscreen {
    @JvmStatic
    fun enabled(context: Context): Boolean =
        WheelLayoutSettings.lockscreen(context) &&
            context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    fun px(context: Context, dp: Int): Int =
        (dp * context.resources.displayMetrics.density).roundToInt()

    private fun insets(context: Context): Insets =
        context
            .getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics
            ?.windowInsets
            ?.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or
                    WindowInsets.Type.mandatorySystemGestures()
            ) ?: Insets.NONE

    fun top(context: Context): Int = insets(context).top + px(context, 8)

    fun bottom(context: Context): Int = insets(context).bottom + px(context, 8)

    fun startInset(context: Context): Int =
        if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            insets(context).right
        else insets(context).left

    fun endInset(context: Context): Int =
        if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            insets(context).left
        else insets(context).right

    @JvmStatic
    fun clockTextSize(context: Context, requested: Int, large: Boolean): Int =
        if (enabled(context)) minOf(requested, px(context, if (large) 64 else 48)) else requested

    fun guideline(constraints: ConstraintSet) {
        constraints.create(R.id.split_shade_guideline, ConstraintSet.VERTICAL)
        constraints.setGuidelinePercent(R.id.split_shade_guideline, 0.5f)
    }

    fun clockAndDate(context: Context, constraints: ConstraintSet, includeDate: Boolean = true) {
        guideline(constraints)
        val padding = px(context, 16)
        constraints.apply {
            for (clock in
                intArrayOf(
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                )) {
                clear(clock, ConstraintSet.TOP)
                clear(clock, ConstraintSet.BOTTOM)
                clear(clock, ConstraintSet.START)
                clear(clock, ConstraintSet.END)
                constrainWidth(clock, ConstraintSet.WRAP_CONTENT)
                constrainMaxWidth(
                    clock,
                    (context.resources.displayMetrics.widthPixels / 2 -
                            startInset(context) -
                            padding * 2)
                        .coerceAtLeast(1),
                )
                constrainHeight(
                    clock,
                    if (clock == ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE)
                        ConstraintSet.WRAP_CONTENT
                    else px(context, 72),
                )
                connect(
                    clock,
                    ConstraintSet.TOP,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.TOP,
                    top(context),
                )
                connect(
                    clock,
                    ConstraintSet.START,
                    ConstraintSet.PARENT_ID,
                    ConstraintSet.START,
                    startInset(context) + padding,
                )
                connect(
                    clock,
                    ConstraintSet.END,
                    R.id.split_shade_guideline,
                    ConstraintSet.START,
                    padding,
                )
                setHorizontalBias(clock, 0f)
            }
            if (includeDate)
                for ((date, clock) in
                    listOf(
                        sharedR.id.date_smartspace_view to ClockViewIds.LOCKSCREEN_CLOCK_VIEW_SMALL,
                        sharedR.id.date_smartspace_view_large to
                            ClockViewIds.LOCKSCREEN_CLOCK_VIEW_LARGE,
                    )) {
                    clear(date, ConstraintSet.TOP)
                    clear(date, ConstraintSet.BOTTOM)
                    constrainWidth(date, ConstraintSet.MATCH_CONSTRAINT)
                    connect(date, ConstraintSet.TOP, clock, ConstraintSet.BOTTOM, px(context, 4))
                    connect(
                        date,
                        ConstraintSet.START,
                        ConstraintSet.PARENT_ID,
                        ConstraintSet.START,
                        startInset(context) + padding,
                    )
                    connect(
                        date,
                        ConstraintSet.END,
                        R.id.split_shade_guideline,
                        ConstraintSet.START,
                        padding,
                    )
                }
        }
    }

    fun cards(context: Context, constraints: ConstraintSet) {
        guideline(constraints)
        constraints.apply {
            val card = sharedR.id.bc_smartspace_view
            clear(card, ConstraintSet.TOP)
            clear(card, ConstraintSet.BOTTOM)
            constrainWidth(card, ConstraintSet.MATCH_CONSTRAINT)
            connect(
                card,
                ConstraintSet.TOP,
                ConstraintSet.PARENT_ID,
                ConstraintSet.TOP,
                top(context),
            )
            connect(
                card,
                ConstraintSet.START,
                R.id.split_shade_guideline,
                ConstraintSet.END,
                px(context, 8),
            )
            connect(
                card,
                ConstraintSet.END,
                ConstraintSet.PARENT_ID,
                ConstraintSet.END,
                endInset(context) + px(context, 16),
            )
        }
    }
}
