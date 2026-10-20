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

import java.util.Locale;

/**
 * The numbers of the power menu stats panel as text, and how worrying each one is: a big
 * value per metric and a short line under it. Plain Java, so it can be tested without
 * Android.
 */
final class PowerMenuStatsFormat {

    /** Plain value, the metric's own color. */
    static final int LEVEL_NORMAL = 0;
    /** Green, e.g. charging. */
    static final int LEVEL_GOOD = 1;
    /** Amber, e.g. warm. */
    static final int LEVEL_OK = 2;
    /** Red, e.g. hot or almost empty. */
    static final int LEVEL_BAD = 3;

    static final String UNKNOWN = "--";

    private PowerMenuStatsFormat() {
    }

    /** "34%" */
    static String cpuValue(int percent) {
        return percent < 0 ? UNKNOWN : percent + "%";
    }

    /** "1.8 GHz", empty if the clock is unknown. */
    static String cpuExtra(int mhz) {
        return mhz > 0 ? String.format(Locale.US, "%.1f GHz", mhz / 1000f) : "";
    }

    /**
     * "big 2.2 GHz" over "little off", one line per cluster; cpuExtra(mhz) without two
     * clusters.
     */
    static String cpuClusters(int bigMhz, int littleMhz, int mhz) {
        if (bigMhz < 0 || littleMhz < 0) {
            return cpuExtra(mhz);
        }
        return "big " + clusterClock(bigMhz) + "\nlittle " + clusterClock(littleMhz);
    }

    private static String clusterClock(int mhz) {
        return mhz == PowerMenuStatsReader.OFFLINE ? "off" : cpuExtra(mhz);
    }

    /** "48°C" */
    static String tempValue(float celsius) {
        return Float.isNaN(celsius) ? UNKNOWN : Math.round(celsius) + "°C";
    }

    static int tempLevel(float celsius) {
        return Float.isNaN(celsius) ? LEVEL_NORMAL
                : celsius >= 75 ? LEVEL_BAD : celsius >= 65 ? LEVEL_OK : LEVEL_NORMAL;
    }

    /** "max 61°C", the highest point of the graph, empty if there is none. */
    static String tempExtra(float maxCelsius) {
        return Float.isNaN(maxCelsius) ? "" : "max " + Math.round(maxCelsius) + "°C";
    }

    /** "97%" */
    static String batteryValue(int percent) {
        return percent < 0 ? UNKNOWN : percent + "%";
    }

    static int batteryLevel(int percent, boolean charging) {
        return percent < 0 ? LEVEL_NORMAL
                : charging ? LEVEL_GOOD : percent <= 15 ? LEVEL_BAD : LEVEL_NORMAL;
    }

    /**
     * "+4.6 W" while charging, "-1.9 W" on battery, empty if the power is unknown. Plugged in
     * but not charging, the charger runs the r1: "USB power", or "USB, -0.4 W" with what the
     * battery adds.
     */
    static String batteryExtra(float watts, boolean charging, boolean plugged) {
        if (plugged && !charging) {
            return Float.isNaN(watts) || watts < 0.05f ? "USB power"
                    : String.format(Locale.US, "USB, -%.1f W", watts);
        }
        return Float.isNaN(watts) ? ""
                : String.format(Locale.US, "%s%.1f W", charging ? "+" : "-", watts);
    }

    /** "1.9 G" used */
    static String ramValue(int usedMb) {
        return usedMb < 0 ? UNKNOWN : gigabytes(usedMb) + " G";
    }

    static int ramLevel(int percent) {
        return percent >= 90 ? LEVEL_OK : LEVEL_NORMAL;
    }

    /** "of 3.5 G", empty if unknown. */
    static String ramExtra(int totalMb) {
        return totalMb <= 0 ? "" : "of " + gigabytes(totalMb) + " G";
    }

    private static String gigabytes(int mb) {
        return String.format(Locale.US, "%.1f", mb / 1024f);
    }

    /**
     * The scale of a graph as {min, max}. A NaN {@code min} or {@code max} follows the
     * values, keeping at least {@code minRange} between the two.
     */
    static float[] scale(float[] values, int count, float min, float max, float minRange) {
        float low = Float.NaN;
        float high = Float.NaN;
        for (int i = 0; i < count; i++) {
            final float v = values[i];
            if (!Float.isNaN(v)) {
                low = Float.isNaN(low) || v < low ? v : low;
                high = Float.isNaN(high) || v > high ? v : high;
            }
        }
        final float scaleMin = !Float.isNaN(min) ? min
                : Float.isNaN(low) ? 0f : (float) Math.floor(low - minRange / 5);
        final float scaleMax = !Float.isNaN(max) ? max
                : Math.max(scaleMin + minRange,
                        Float.isNaN(high) ? 0f : high + (high - scaleMin) / 10);
        return new float[] { scaleMin, scaleMax };
    }

    /** The highest value, NaN if there is none. */
    static float max(float[] values, int count) {
        float high = Float.NaN;
        for (int i = 0; i < count; i++) {
            final float v = values[i];
            if (!Float.isNaN(v) && (Float.isNaN(high) || v > high)) {
                high = v;
            }
        }
        return high;
    }
}
