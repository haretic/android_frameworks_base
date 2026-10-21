/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import androidx.constraintlayout.helper.widget.Flow;
import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.HardwareBgDrawable;

import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import com.android.systemui.res.R;

/**
 * ConstraintLayout implementation of the button layout created by the global actions dialog.
 */
public class GlobalActionsLayoutLite extends GlobalActionsLayout {
    private View mLastView;
    private final Set<Integer> mCurrentIds = new HashSet<>();
    private View mFooter;
    private BooleanSupplier mShowFooter;

    public GlobalActionsLayoutLite(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOnClickListener(v -> { }); // Prevent parent onClickListener from triggering
    }

    @VisibleForTesting
    @Override
    protected boolean shouldReverseListItems() {
        // Handled in XML
        return false;
    }

    @Override
    protected HardwareBgDrawable getBackgroundDrawable(int backgroundColor) {
        return null;
    }

    /** Whether the menu is the list style here, see config_powerMenuList. */
    public static boolean isListStyle(Context context) {
        final Resources res = context.getResources();
        return res.getBoolean(R.bool.config_powerMenuList)
                && res.getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override
    public void onUpdateList() {
        super.onUpdateList();
        if (isListStyle(getContext())) {
            // One action a row, the footer under them
            if (mFooter != null) {
                mFooter.setVisibility(mShowFooter.getAsBoolean() ? View.VISIBLE : View.GONE);
            }
            ((Flow) findViewById(R.id.list_flow)).setMaxElementsWrap(1);
            return;
        }
        int nElementsWrap = getResources().getInteger(
                com.android.systemui.res.R.integer.power_menu_lite_max_columns);
        // don't count the flow element or the footer
        int nChildren = getListView().getChildCount() - (mFooter != null ? 2 : 1);

        // Avoid having just one action on the last row if there are more than 2 columns because
        // it looks unbalanced. Instead, bring the column size down to balance better.
        if (nChildren == nElementsWrap + 1 && nElementsWrap > 2) {
            nElementsWrap -= 1;
        }
        Flow flow = findViewById(R.id.list_flow);
        if (mFooter != null) {
            final boolean show = mShowFooter.getAsBoolean();
            mFooter.setVisibility(show ? View.VISIBLE : View.GONE);
            if (show && isFooterBeside()) {
                // Two columns of actions leave room for the footer beside them
                nElementsWrap = Math.min(nElementsWrap, 2);
            }
        }
        flow.setMaxElementsWrap(nElementsWrap);
    }

    /**
     * Keeps {@code footer} in the card after the actions, e.g. the pinned apps: under them, or
     * beside them where R.bool.global_actions_pinned_beside says so (landscape). It is shown
     * while {@code showFooter} returns true, checked on every list update.
     */
    public void setFooter(View footer, BooleanSupplier showFooter) {
        mFooter = footer;
        mShowFooter = showFooter;
        if (footer.getId() == View.NO_ID) {
            footer.setId(View.generateViewId());
        }
        final View flow = findViewById(R.id.list_flow);
        final ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT);
        if (isFooterBeside()) {
            final ConstraintLayout.LayoutParams flowLp =
                    (ConstraintLayout.LayoutParams) flow.getLayoutParams();
            flowLp.width = ConstraintLayout.LayoutParams.WRAP_CONTENT;
            flowLp.endToEnd = ConstraintLayout.LayoutParams.UNSET;
            flowLp.endToStart = footer.getId();
            flowLp.horizontalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED;
            flow.setLayoutParams(flowLp);
            lp.startToEnd = flow.getId();
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        } else {
            lp.topToBottom = flow.getId();
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        }
        footer.setLayoutParams(lp);
        getListView().addView(footer);
    }

    private boolean isFooterBeside() {
        return getResources().getBoolean(R.bool.global_actions_pinned_beside);
    }

    @Override
    protected void addToListView(View v, boolean reverse) {
        super.addToListView(v, reverse);

        if (v.getId() == View.NO_ID || mCurrentIds.contains(v.getId())) {
            v.setId(View.generateViewId());
        }
        mCurrentIds.add(v.getId());

        Flow flow = findViewById(R.id.list_flow);
        flow.addView(v);

        if (mLastView != null) {
            mLastView.setNextFocusForwardId(v.getId());
        }
        mLastView = v;
    }

    @Override
    protected void removeAllListViews() {
        View flow = findViewById(R.id.list_flow);
        super.removeAllListViews();

        // Add flow element back after clearing the list view
        super.addToListView(flow, false);
        if (mFooter != null) {
            getListView().addView(mFooter);
        }
        mLastView = null;
        mCurrentIds.clear();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        boolean anyTruncated = false;
        ViewGroup listView = getListView();

        // Check to see if any of the GlobalActionsItems have had their messages truncated
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child instanceof GlobalActionsItem) {
                GlobalActionsItem item = (GlobalActionsItem) child;
                anyTruncated = anyTruncated || item.isTruncated();
            }
        }
        // If any of the items have been truncated, set the all to single-line marquee
        if (anyTruncated) {
            for (int i = 0; i < listView.getChildCount(); i++) {
                View child = listView.getChildAt(i);
                if (child instanceof GlobalActionsItem) {
                    GlobalActionsItem item = (GlobalActionsItem) child;
                    item.setMarquee(true);
                }
            }
        }
    }

    @VisibleForTesting
    protected float getGridItemSize() {
        return getContext().getResources().getDimension(R.dimen.global_actions_grid_item_height);
    }

    @VisibleForTesting
    protected float getAnimationDistance() {
        return getGridItemSize() / 2;
    }

    @Override
    public float getAnimationOffsetX() {
        return getAnimationDistance();
    }

    @Override
    public float getAnimationOffsetY() {
        return 0f;
    }
}
