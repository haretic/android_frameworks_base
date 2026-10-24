/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/**
 * The wheel UI's dialog buttons (config_wheelDialogs): an alert dialog's buttons as a list, one a
 * row and the full width, the positive one first, so the wheel walks them top to bottom. Works on
 * the framework's alert dialogs and AndroidX's alike, both having their buttons as button1 to 3.
 *
 * @hide
 */
public final class WheelDialogButtons {

    private WheelDialogButtons() {}

    /** Puts the dialog's buttons in a list, where the device asks for it. */
    public static void apply(View decor) {
        final Context context = decor.getContext();
        if (!context.getResources().getBoolean(com.android.internal.R.bool.config_wheelDialogs)) {
            return;
        }
        final View positive = decor.findViewById(android.R.id.button1);
        final View negative = decor.findViewById(android.R.id.button2);
        final View neutral = decor.findViewById(android.R.id.button3);
        // Once moved, the buttons' parent is the list, which a dialog shown again keeps
        if (!(positive instanceof Button) || !(positive.getParent() instanceof ViewGroup bar)
                || bar.getId() == com.android.internal.R.id.wheel_dialog_buttons
                || !(bar.getParent() instanceof ViewGroup parent)) {
            return;
        }
        // The list goes beside the bar, or beside the scroll view that wraps it, as the alert
        // dialogs have it (framework and AndroidX): a scroll view takes one child only
        View anchor = bar;
        ViewGroup panel = parent;
        for (int up = 0; up < 2 && isScroller(panel)
                && panel.getParent() instanceof ViewGroup outer; up++) {
            anchor = panel;
            panel = outer;
        }
        // The bar stays, empty and gone, for what the dialog still asks of it
        final LinearLayout list = new LinearLayout(context);
        list.setId(com.android.internal.R.id.wheel_dialog_buttons);
        list.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(context, 16);
        list.setPadding(pad, dp(context, 4), pad, dp(context, 12));
        int index = 0;
        for (View button : new View[] {positive, negative, neutral}) {
            if (!(button instanceof Button) || button.getParent() != bar) {
                continue;
            }
            bar.removeView(button);
            style((Button) button, button == positive);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52));
            lp.topMargin = index++ == 0 ? 0 : dp(context, 8);
            list.addView(button, lp);
        }
        bar.setVisibility(View.GONE);
        anchor.setVisibility(View.GONE);
        panel.addView(list, panel.indexOfChild(anchor) + 1, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** ScrollView, HorizontalScrollView, AndroidX's NestedScrollView: one child each. */
    private static boolean isScroller(ViewGroup group) {
        return group instanceof ScrollView || group instanceof HorizontalScrollView
                || group.getClass().getName().endsWith("ScrollView");
    }

    private static void style(Button button, boolean primary) {
        final Context context = button.getContext();
        final int fill = context.getColor(primary
                ? com.android.internal.R.color.materialColorPrimary
                : com.android.internal.R.color.materialColorSecondaryContainer);
        final int text = context.getColor(primary
                ? com.android.internal.R.color.materialColorOnPrimary
                : com.android.internal.R.color.materialColorOnSecondaryContainer);
        final GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(context, 26));
        shape.setColor(fill);
        final GradientDrawable mask = new GradientDrawable();
        mask.setCornerRadius(dp(context, 26));
        mask.setColor(0xffffffff);
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf((text & 0x00ffffff) | 0x33000000), shape, mask));
        // AndroidX's buttons tint their background, and lift it as they're pressed
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        button.setTextColor(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(context, 16), 0, dp(context, 16), 0);
    }

    private static int dp(Context context, float value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, context.getResources().getDisplayMetrics());
    }
}
