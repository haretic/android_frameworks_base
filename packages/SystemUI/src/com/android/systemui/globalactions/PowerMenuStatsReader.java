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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Reads the numbers the power menu stats panel shows. It uses the same sysfs and procfs files
 * as the andr36oid performance overlay, minus the frame rate, which means nothing while the
 * menu is up. The clock is the fastest cluster's, so a busy big cluster shows on a big.LITTLE
 * SoC. The battery comes from BatteryManager through {@link #setBattery}, since SystemUI can't
 * list /sys/class/power_supply.
 *
 * Plain Java on purpose, so it can be tested on the host against a fake file tree.
 * Not thread safe: use it from one thread.
 */
final class PowerMenuStatsReader {

    /** -1 or NaN where a value couldn't be read. */
    int cpuPercent = -1;
    int cpuMhz = -1;
    float tempC = Float.NaN;
    int batteryPercent = -1;
    boolean charging;
    float batteryVolts = Float.NaN;
    /** Always positive, the direction is in {@link #charging}. */
    float batteryAmps = Float.NaN;
    /** Always positive, the direction is in {@link #charging}. */
    float batteryWatts = Float.NaN;
    int ramUsedMb = -1;
    int ramTotalMb = -1;
    int ramPercent = -1;

    private final String mRoot;
    private final String[] mCpuFreqPaths;
    private final String mTempPath;

    private long mLastCpuBusy = -1;
    private long mLastCpuTotal;

    PowerMenuStatsReader() {
        this("");
    }

    /** @param root prefix for every path, "" on the device, a fake tree in tests */
    PowerMenuStatsReader(String root) {
        mRoot = root;
        mCpuFreqPaths = findCpuFreqs();
        mTempPath = findTemperature();
    }

    /** A reader with nothing read yet, all values unknown. Looks up no files. */
    static PowerMenuStatsReader empty() {
        return new PowerMenuStatsReader("", new String[0], null);
    }

    /** A copy of the current values, safe to hand to another thread. */
    PowerMenuStatsReader snapshot() {
        final PowerMenuStatsReader copy =
                new PowerMenuStatsReader(mRoot, mCpuFreqPaths, mTempPath);
        copy.cpuPercent = cpuPercent;
        copy.cpuMhz = cpuMhz;
        copy.tempC = tempC;
        copy.batteryPercent = batteryPercent;
        copy.charging = charging;
        copy.batteryVolts = batteryVolts;
        copy.batteryAmps = batteryAmps;
        copy.batteryWatts = batteryWatts;
        copy.ramUsedMb = ramUsedMb;
        copy.ramTotalMb = ramTotalMb;
        copy.ramPercent = ramPercent;
        return copy;
    }

    // For empty() and snapshot(): takes the paths, looks nothing up
    private PowerMenuStatsReader(String root, String[] cpuFreqPaths, String tempPath) {
        mRoot = root;
        mCpuFreqPaths = cpuFreqPaths;
        mTempPath = tempPath;
    }

    /** Forget the last CPU sample, e.g. after the screen was off. */
    void reset() {
        mLastCpuBusy = -1;
        cpuPercent = -1;
    }

    void sample() {
        sampleCpu(read(mRoot + "/proc/stat"));
        int mhz = -1;
        for (String path : mCpuFreqPaths) {
            mhz = Math.max(mhz, readInt(path, 1000));
        }
        cpuMhz = positiveOrMinusOne(mhz);
        final int milliC = mTempPath == null ? Integer.MIN_VALUE : readInt(mTempPath, 1);
        tempC = milliC == Integer.MIN_VALUE || milliC < 0 ? Float.NaN : milliC / 1000f;
        sampleRam(read(mRoot + "/proc/meminfo"));
    }

    /** Busy share of all cores since the last sample, from the first line of /proc/stat. */
    void sampleCpu(String stat) {
        if (stat == null || !stat.startsWith("cpu ")) {
            cpuPercent = -1;
            return;
        }
        final int end = stat.indexOf('\n');
        final String[] fields = (end < 0 ? stat : stat.substring(0, end)).trim().split("\\s+");
        long total = 0;
        long idle = 0;
        try {
            // user nice system idle iowait irq softirq steal
            for (int i = 1; i < fields.length && i <= 8; i++) {
                final long value = Long.parseLong(fields[i]);
                total += value;
                if (i == 4 || i == 5) {
                    idle += value;
                }
            }
        } catch (NumberFormatException e) {
            cpuPercent = -1;
            return;
        }
        final long busy = total - idle;
        if (mLastCpuBusy >= 0 && total > mLastCpuTotal) {
            cpuPercent = (int) (100 * (busy - mLastCpuBusy) / (total - mLastCpuTotal));
            cpuPercent = Math.max(0, Math.min(100, cpuPercent));
        }
        mLastCpuBusy = busy;
        mLastCpuTotal = total;
    }

    /**
     * The battery as BatteryManager reports it: level in percent (-1 unknown), voltage in mV
     * and current in uA (Integer.MIN_VALUE unknown, either sign).
     */
    void setBattery(int percent, boolean isCharging, int milliVolts, int microAmps) {
        batteryPercent = percent < 0 ? -1 : percent;
        charging = isCharging;
        batteryAmps = microAmps == Integer.MIN_VALUE ? Float.NaN : Math.abs(microAmps / 1e6f);
        batteryVolts = milliVolts == Integer.MIN_VALUE || milliVolts <= 0
                ? Float.NaN : milliVolts / 1e3f;
        batteryWatts = Float.isNaN(batteryAmps) || Float.isNaN(batteryVolts)
                ? Float.NaN : batteryAmps * batteryVolts;
    }

    /** Used is MemTotal - MemAvailable, like the performance overlay. */
    void sampleRam(String meminfo) {
        final long total = meminfoKb(meminfo, "MemTotal:");
        final long available = meminfoKb(meminfo, "MemAvailable:");
        if (total > 0 && available >= 0 && available <= total) {
            ramTotalMb = (int) (total / 1024);
            ramUsedMb = (int) ((total - available) / 1024);
            ramPercent = (int) (100 * (total - available) / total);
        } else {
            ramTotalMb = ramUsedMb = ramPercent = -1;
        }
    }

    static long meminfoKb(String meminfo, String key) {
        if (meminfo == null) {
            return -1;
        }
        final int start = meminfo.indexOf(key);
        if (start < 0) {
            return -1;
        }
        final int end = meminfo.indexOf('\n', start);
        final String line = meminfo.substring(start + key.length(),
                end < 0 ? meminfo.length() : end).trim();
        try {
            return Long.parseLong(line.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** scaling_cur_freq of every cpufreq policy, one per cluster. */
    private String[] findCpuFreqs() {
        final File[] policies = new File(mRoot + "/sys/devices/system/cpu/cpufreq").listFiles(
                (dir, name) -> name.startsWith("policy"));
        if (policies == null) {
            return new String[0];
        }
        final String[] paths = new String[policies.length];
        for (int i = 0; i < policies.length; i++) {
            paths[i] = policies[i].getPath() + "/scaling_cur_freq";
        }
        return paths;
    }

    /** The SoC's thermal zone, or the first one there is. */
    private String findTemperature() {
        final File[] zones = new File(mRoot + "/sys/class/thermal").listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null || zones.length == 0) {
            return null;
        }
        java.util.Arrays.sort(zones);
        for (File zone : zones) {
            final String type = read(zone.getPath() + "/type");
            if (type != null && type.contains("soc")) {
                return zone.getPath() + "/temp";
            }
        }
        return zones[0].getPath() + "/temp";
    }

    private static int positiveOrMinusOne(int value) {
        return value > 0 ? value : -1;
    }

    /** The number in the file divided by {@code divisor}, MIN_VALUE if unreadable. */
    private static int readInt(String path, int divisor) {
        final String text = read(path);
        if (text == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return (int) (Long.parseLong(text) / divisor);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException | java.nio.file.InvalidPathException e) {
            return null;
        }
    }
}
