/* SPDX-License-Identifier: Apache-2.0 */
package com.android.systemui.wheel;

import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.android.internal.widget.NotificationActionListLayout;
import com.android.systemui.res.R;

public final class WheelNotificationLayout {
    private WheelNotificationLayout() {}

    public static void apply(View view, boolean enabled, int columns) {
        if (view == null) return;
        if (view instanceof NotificationActionListLayout actions) {
            actions.setButtonColumns(enabled ? columns : 0);
        }
        if (view instanceof TextView text
                && (view.getId() == com.android.internal.R.id.title
                        || view.getId() == com.android.internal.R.id.text
                        || view.getId() == com.android.internal.R.id.big_text
                        || view.getId() == com.android.internal.R.id.message_text)) {
            if (enabled) {
                if (view.getTag(R.id.wheel_original_text_size) == null) {
                    view.setTag(
                            R.id.wheel_original_text_size,
                            TypedValue.deriveDimension(
                                    TypedValue.COMPLEX_UNIT_SP,
                                    text.getTextSize(),
                                    view.getResources().getDisplayMetrics()));
                }
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            } else if (view.getTag(R.id.wheel_original_text_size) instanceof Float original) {
                text.setTextSize(TypedValue.COMPLEX_UNIT_SP, original);
                view.setTag(R.id.wheel_original_text_size, null);
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++)
                apply(group.getChildAt(i), enabled, columns);
        }
    }

    /**
     * How much taller the larger text makes a view, in px: each line of text the wheel layout
     * enlarged, at its height now less its height at the original size.
     */
    public static int extraHeight(View view) {
        if (view == null || view.getVisibility() == View.GONE) return 0;
        if (view instanceof TextView text
                && view.getTag(R.id.wheel_original_text_size) instanceof Float original) {
            final float now = TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP,
                    text.getTextSize(), view.getResources().getDisplayMetrics());
            if (now <= original) return 0;
            final int lines = Math.max(1, Math.min(text.getMaxLines(),
                    text.getLineCount() > 0 ? text.getLineCount() : 1));
            return (int) Math.ceil(text.getLineHeight() * (1 - original / now) * lines);
        }
        int extra = 0;
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++)
                extra += extraHeight(group.getChildAt(i));
        }
        return extra;
    }
}
