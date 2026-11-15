/*
 * Copyright (C) 2026 The haretic Project
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

package com.android.server.pm;

import android.Manifest;
import android.annotation.Nullable;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;

import java.util.Set;

/**
 * Packages that come with haretic at a version the system picked and that update with the
 * system only. No app replaces one (see {@link InstallPackageHelper}), and an app that installs
 * packages does not see one either: a store that finds no such package installed has no update
 * for it to offer, in its lists or in a notification.
 */
final class PinnedPackages {
    /** Magisk's app, which haretic's rooted builds install and keep at their version */
    private static final String MAGISK_PACKAGE = "com.topjohnwu.magisk";

    private PinnedPackages() {
    }

    static boolean isPinned(@Nullable String packageName) {
        return MAGISK_PACKAGE.equals(packageName);
    }

    /**
     * Whether an app is one that installs packages for the user: a store, a browser, a file
     * manager. The system's own installer, which is privileged, shows what is there.
     */
    static boolean installsPackages(@Nullable PackageStateInternal state) {
        if (state == null || state.isPrivileged()) {
            return false;
        }
        final AndroidPackage pkg = state.getPkg();
        if (pkg == null) {
            return false;
        }
        final Set<String> requested = pkg.getRequestedPermissions();
        return requested.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES)
                || requested.contains(Manifest.permission.INSTALL_PACKAGES);
    }
}
