/*
 * SPDX-FileCopyrightText: 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.wheel;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.View;

import com.android.systemui.res.R;

import java.text.NumberFormat;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * What a running app takes, on the taskbar's card for it: its share of the processor over the
 * last moment and the memory it holds, all its processes together, as two meters. Measured
 * only while the card shows.
 */
final class WheelAppLoadView extends View {
    /** The first reading comes soon, the card is looked at briefly; then once a second. */
    private static final long FIRST_MS = 350, EVERY_MS = 1000;
    /** Memory moves slowly and costs more to read: every third reading. */
    private static final int MEMORY_EVERY = 3;

    private final Executor mBackground;
    private final Handler mHandler;
    private final ActivityManager mActivityManager;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private final int mText, mValueText, mTrack, mAccent;
    private final long mTotalMemory;
    private final String mCpuLabel, mMemoryLabel;

    private String mPackage;
    /** Counts up with every app watched: a reading for an earlier one is dropped. */
    private volatile int mWatch;
    private float mCpu, mMemory;
    private String mCpuValue = "", mMemoryValue = "";

    WheelAppLoadView(Context context, Executor background, Handler handler) {
        super(context);
        mBackground = background;
        mHandler = handler;
        mActivityManager = context.getSystemService(ActivityManager.class);
        mDensity = context.getResources().getDisplayMetrics().density;
        mText = context.getColor(R.color.wheel_taskbar_text_secondary);
        mValueText = context.getColor(R.color.wheel_taskbar_text);
        mTrack = context.getColor(R.color.wheel_taskbar_divider);
        mAccent = context.getColor(android.R.color.system_accent1_300);
        mCpuLabel = context.getString(R.string.wheel_taskbar_load_cpu);
        mMemoryLabel = context.getString(R.string.wheel_taskbar_load_memory);
        final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        mActivityManager.getMemoryInfo(memory);
        mTotalMemory = Math.max(1, memory.totalMem);
        mPaint.setTypeface(Typeface.create(context.getString(
                com.android.internal.R.string.config_bodyFontFamily), Typeface.NORMAL));
        mPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                context.getResources().getDisplayMetrics()));
        mPaint.setFontFeatureSettings("tnum");
        setVisibility(GONE);
    }

    /** Shows the load of this package's processes, or with null nothing. */
    void watch(String packageName) {
        if (packageName != null && packageName.equals(mPackage)) {
            return;
        }
        mPackage = packageName;
        final int watch = ++mWatch;
        setVisibility(GONE);
        if (packageName != null) {
            mBackground.execute(() -> read(watch, packageName, 0, 0, 0, null));
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        watch(null);
    }

    /** Background: one reading, compared with the one before, then the next in a while. */
    private void read(int watch, String packageName, int count, long cpuBefore, long before,
            String memoryBefore) {
        if (watch != mWatch) {
            return;
        }
        final List<ActivityManager.RunningAppProcessInfo> running =
                mActivityManager.getRunningAppProcesses();
        int[] pids = new int[0];
        if (running != null) {
            pids = running.stream().filter(process -> process.pkgList != null
                    && List.of(process.pkgList).contains(packageName))
                    .mapToInt(process -> process.pid).toArray();
        }
        if (pids.length == 0) {
            // Not running, or gone since: no numbers
            mHandler.post(() -> {
                if (watch == mWatch) {
                    setVisibility(GONE);
                }
            });
            return;
        }
        long cpu = 0;
        long memory = -1;
        final long now = SystemClock.uptimeMillis();
        try {
            for (long time : ActivityManager.getService().getProcessCpuTimes(pids)) {
                cpu += time;
            }
            if (count % MEMORY_EVERY == 0) {
                memory = 0;
                for (long pss : ActivityManager.getService().getProcessPss(pids)) {
                    memory += pss * 1024;
                }
            }
        } catch (RemoteException e) {
            return;
        }
        final String memoryValue = memory < 0 ? memoryBefore
                : Formatter.formatShortFileSize(getContext(), memory);
        if (count > 0 && now > before) {
            // Of all cores: a process that keeps one of eight busy shows an eighth
            final float share = Math.max(0, Math.min(1, (float) (cpu - cpuBefore)
                    / ((now - before) * Runtime.getRuntime().availableProcessors())));
            final float held = memory < 0 ? -1 : Math.min(1, (float) memory / mTotalMemory);
            mHandler.post(() -> show(watch, share, held, memoryValue));
        }
        final long total = cpu;
        mHandler.postDelayed(() -> {
            if (watch == mWatch) {
                mBackground.execute(() -> read(watch, packageName, count + 1, total, now,
                        memoryValue));
            }
        }, count == 0 ? FIRST_MS : EVERY_MS);
    }

    private void show(int watch, float cpu, float memory, String memoryValue) {
        if (watch != mWatch) {
            return;
        }
        mCpu = cpu;
        final NumberFormat percent = NumberFormat.getPercentInstance();
        mCpuValue = percent.format(Math.round(cpu * 100) / 100f);
        if (memory >= 0) {
            mMemory = memory;
        }
        mMemoryValue = memoryValue == null ? "" : memoryValue;
        setVisibility(VISIBLE);
        invalidate();
    }

    private float rowHeight() {
        return mPaint.getFontSpacing() + 2 * mDensity;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // As wide as the card's other lines make it, and no narrower than the meters need
        final int wanted = (int) (132 * mDensity);
        setMeasuredDimension(resolveSize(wanted, widthMeasureSpec),
                (int) Math.ceil(2 * rowHeight() + 3 * mDensity));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float labels = Math.max(mPaint.measureText(mCpuLabel),
                mPaint.measureText(mMemoryLabel));
        final float values = Math.max(mPaint.measureText(mCpuValue),
                mPaint.measureText(mMemoryValue));
        row(canvas, 3 * mDensity, mCpuLabel, mCpu, mCpuValue, labels, values);
        row(canvas, 3 * mDensity + rowHeight(), mMemoryLabel, mMemory, mMemoryValue, labels,
                values);
    }

    private void row(Canvas canvas, float top, String label, float share, String value,
            float labels, float values) {
        final boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        final float w = getWidth();
        final float gap = 7 * mDensity;
        final float baseline = top + (rowHeight() - mPaint.descent() - mPaint.ascent()) / 2;
        mPaint.setColor(mText);
        mPaint.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText(label, rtl ? w : 0, baseline, mPaint);
        mPaint.setColor(mValueText);
        mPaint.setTextAlign(rtl ? Paint.Align.LEFT : Paint.Align.RIGHT);
        canvas.drawText(value, rtl ? 0 : w, baseline, mPaint);
        final float from = (rtl ? values : labels) + gap;
        final float to = w - (rtl ? labels : values) - gap;
        if (to - from < 8 * mDensity) {
            return;
        }
        final float middle = top + rowHeight() / 2;
        final float half = 1.5f * mDensity;
        mPaint.setColor(mTrack);
        canvas.drawRoundRect(from, middle - half, to, middle + half, half, half, mPaint);
        if (share > 0) {
            // Anything in use shows, however little
            final float length = Math.max(2 * half, (to - from) * share);
            mPaint.setColor(mAccent);
            canvas.drawRoundRect(rtl ? to - length : from, middle - half,
                    rtl ? to : from + length, middle + half, half, half, mPaint);
        }
    }
}
