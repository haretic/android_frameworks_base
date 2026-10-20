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
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;

import com.android.internal.policy.SystemBarUtils;
import com.android.systemui.res.R;

/**
 * CPU, temperature, battery and RAM with small graphs of about the last 10 minutes, shown
 * with the power menu: a card above the menu in portrait, a thin strip along the top of the
 * screen in landscape (layout-land). Each metric is a label, a big value, a graph and, in the
 * card, a short line under it.
 *
 * {@link PowerMenuStatsSampler} keeps the history in the background; while this view is
 * attached it updates every second. It never takes the focus, so the wheel and the D-pad
 * still move between the menu's buttons only.
 */
public class PowerMenuStatsPanel extends LinearLayout implements PowerMenuStatsSampler.Listener {

    private Cell mCpu;
    private Cell mTemp;
    private Cell mBattery;
    private Cell mRam;
    private final int mGoodColor;
    private final int mOkColor;
    private final int mBadColor;
    private float[] mBuffer = new float[PowerMenuStatsSampler.HISTORY_SIZE];
    private boolean mAttached;

    public PowerMenuStatsPanel(Context context, AttributeSet attrs) {
        super(context, attrs);
        mGoodColor = context.getColor(R.color.global_actions_stats_good);
        mOkColor = context.getColor(R.color.global_actions_stats_ok);
        mBadColor = context.getColor(R.color.global_actions_stats_bad);
    }

    /** Starts the background history, called once when SystemUI starts. */
    public static void startSampling(Context context) {
        PowerMenuStatsSampler.getInstance(context).start();
    }

    /**
     * Adds the panel to the power menu's {@code container} next to {@code menu}, if it is
     * switched on: chained above the menu and as wide as it in portrait, along the top edge
     * in landscape.
     */
    public static void addTo(ConstraintLayout container, View menu) {
        final Context context = container.getContext();
        if (!PowerMenuStatsSampler.isEnabled(context)) {
            return;
        }
        final Resources res = context.getResources();
        final View panel = LayoutInflater.from(context).inflate(
                R.layout.global_actions_stats, container, false);
        final ConstraintLayout.LayoutParams lp =
                new ConstraintLayout.LayoutParams(0, ConstraintLayout.LayoutParams.WRAP_CONTENT);
        if (res.getBoolean(R.bool.global_actions_stats_strip)) {
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        } else {
            // A packed chain keeps the pair centred; the margin keeps the card out from under
            // the status bar when the pair is taller than the screen
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.topMargin = SystemBarUtils.getStatusBarHeight(context);
            lp.bottomToTop = menu.getId();
            lp.startToStart = menu.getId();
            lp.endToEnd = menu.getId();
            lp.verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED;
            lp.bottomMargin = res.getDimensionPixelSize(R.dimen.global_actions_stats_menu_gap);
            final ConstraintLayout.LayoutParams menuLp =
                    (ConstraintLayout.LayoutParams) menu.getLayoutParams();
            menuLp.topToTop = ConstraintLayout.LayoutParams.UNSET;
            menuLp.topToBottom = panel.getId();
            menu.setLayoutParams(menuLp);
        }
        container.addView(panel, lp);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCpu = new Cell(findViewById(R.id.global_actions_stats_cpu),
                R.string.global_actions_stats_cpu, R.color.global_actions_stats_cpu);
        if (mCpu.extra != null) {
            // One line per cluster
            mCpu.extra.setSingleLine(false);
            mCpu.extra.setMaxLines(2);
        }
        mTemp = new Cell(findViewById(R.id.global_actions_stats_temp),
                R.string.global_actions_stats_temp, R.color.global_actions_stats_temp);
        mBattery = new Cell(findViewById(R.id.global_actions_stats_bat),
                R.string.global_actions_stats_bat, R.color.global_actions_stats_bat);
        mRam = new Cell(findViewById(R.id.global_actions_stats_ram),
                R.string.global_actions_stats_ram, R.color.global_actions_stats_ram);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        // Placeholders until the first reading, so nothing jumps
        showNumbers(PowerMenuStatsReader.empty(), Float.NaN);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateListening(true);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // Still counts as attached during this call
        updateListening(false);
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        updateListening(isAttachedToWindow());
    }

    /** Live updates only while attached and visible. */
    private void updateListening(boolean attached) {
        final boolean listen = attached && isShown();
        if (listen == mAttached) {
            return;
        }
        mAttached = listen;
        final PowerMenuStatsSampler sampler = PowerMenuStatsSampler.getInstance(getContext());
        if (listen) {
            sampler.attach(this);
        } else {
            sampler.detach(this);
        }
    }

    @Override
    public void onStatsUpdated(PowerMenuStatsReader s, PowerMenuStatsHistory history) {
        if (!mAttached) {
            return;
        }
        final int capacity = history.getCapacity();
        // CPU and RAM in percent; temperature on a window of at least 10 degrees around
        // the values; power from 0 with at least 2 W of range, so noise stays flat
        showGraph(mCpu, history, PowerMenuStatsSampler.SERIES_CPU, capacity, 0f, 100f, 0f);
        final float maxTemp = showGraph(mTemp, history, PowerMenuStatsSampler.SERIES_TEMP,
                capacity, Float.NaN, Float.NaN, 10f);
        showGraph(mBattery, history, PowerMenuStatsSampler.SERIES_POWER, capacity,
                0f, Float.NaN, 2f);
        showGraph(mRam, history, PowerMenuStatsSampler.SERIES_RAM, capacity, 0f, 100f, 0f);
        showNumbers(s, maxTemp);
    }

    /** Draws one series, see PowerMenuStatsFormat.scale. Returns the highest value. */
    private float showGraph(Cell cell, PowerMenuStatsHistory history, int series,
            int capacity, float min, float max, float minRange) {
        if (mBuffer.length < capacity) {
            mBuffer = new float[capacity];
        }
        final int count = history.copy(series, mBuffer);
        final float[] scale = PowerMenuStatsFormat.scale(mBuffer, count, min, max, minRange);
        cell.graph.setValues(mBuffer, count, capacity, scale[0], scale[1]);
        return PowerMenuStatsFormat.max(mBuffer, count);
    }

    private void showNumbers(PowerMenuStatsReader s, float maxTemp) {
        // 34%, big 2.2 GHz over little 1.6 GHz (only the portrait card has the extra line)
        mCpu.show(PowerMenuStatsFormat.cpuValue(s.cpuPercent),
                PowerMenuStatsFormat.cpuClusters(s.bigMhz, s.littleMhz, s.cpuMhz),
                PowerMenuStatsFormat.LEVEL_NORMAL);
        // 48°C, max 61°C: the hottest sample in the graph's window (about 10 minutes)
        mTemp.show(PowerMenuStatsFormat.tempValue(s.tempC),
                PowerMenuStatsFormat.tempExtra(maxTemp), PowerMenuStatsFormat.tempLevel(s.tempC));
        // 97%, +4.6 W while charging
        mBattery.show(PowerMenuStatsFormat.batteryValue(s.batteryPercent),
                PowerMenuStatsFormat.batteryExtra(s.batteryWatts, s.charging, s.plugged),
                PowerMenuStatsFormat.batteryLevel(s.batteryPercent, s.charging));
        // 1.9 G, of 3.5 G
        mRam.show(PowerMenuStatsFormat.ramValue(s.ramUsedMb),
                PowerMenuStatsFormat.ramExtra(s.ramTotalMb),
                PowerMenuStatsFormat.ramLevel(s.ramPercent));
    }

    private int colorOf(int level, int normal) {
        switch (level) {
            case PowerMenuStatsFormat.LEVEL_GOOD:
                return mGoodColor;
            case PowerMenuStatsFormat.LEVEL_OK:
                return mOkColor;
            case PowerMenuStatsFormat.LEVEL_BAD:
                return mBadColor;
            default:
                return normal;
        }
    }

    private final class Cell {
        final TextView value;
        /** Only in the portrait card. */
        final TextView extra;
        final PowerMenuSparklineView graph;
        final int color;

        Cell(View root, int label, int colorRes) {
            ((TextView) root.findViewById(R.id.global_actions_stats_label)).setText(label);
            value = root.findViewById(R.id.global_actions_stats_value);
            extra = root.findViewById(R.id.global_actions_stats_extra);
            graph = root.findViewById(R.id.global_actions_stats_graph);
            color = getContext().getColor(colorRes);
        }

        /** The graph takes the level's color while the value is worth a look. */
        void show(String text, String extraText, int level) {
            value.setText(text);
            if (extra != null) {
                extra.setText(extraText);
            }
            graph.setColor(colorOf(level, color));
        }
    }
}
