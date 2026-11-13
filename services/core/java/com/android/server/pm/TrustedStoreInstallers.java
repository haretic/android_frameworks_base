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

import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.util.ArrayMap;

import java.util.HexFormat;

/**
 * The app stores haretic lets install without the confirmation dialog, as an installer holding
 * INSTALL_PACKAGES may. None of them asks for that permission, so the user had to confirm every
 * install, from a quiet notification whenever the store was not on screen. Each store counts
 * only when signed by the certificate its releases carry: a package that merely has the name
 * gets nothing.
 */
final class TrustedStoreInstallers {
    private static final ArrayMap<String, byte[]> SIGNERS = new ArrayMap<>();

    static {
        // Aurora Store, F-Droid's build (the one haretic ships)
        add("com.aurora.store",
                "5c83c7672b929955dc0a1db89a5e6ae4389e2eae7ec939956041694e5815f532");
        // Droid-ify (shipped)
        add("com.looker.droidify",
                "b4f1c8a81e4b9f6e02638e93bead56656502c21fb95c8d3ec93d0cdd1f6e0d3f");
        // F-Droid and F-Droid Basic
        add("org.fdroid.fdroid",
                "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab");
        add("org.fdroid.basic",
                "ef7f822b7653e2011cb8c5a7fc99e8d2ade1717e539ca56d6456295e3bc07312");
        // Obtainium, from GitHub and from F-Droid (the same developer-signed build)
        add("dev.imranr.obtainium",
                "b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362");
        add("dev.imranr.obtainium.fdroid",
                "b353601f6a1d5fd6603ae2f50be80cf301367b86b6ab8b1f66243da96cd57362");
    }

    private TrustedStoreInstallers() {
    }

    private static void add(String packageName, String sha256) {
        SIGNERS.put(packageName, HexFormat.of().parseHex(sha256));
    }

    /** Whether the installer of a session is one of the stores, running as that store's uid. */
    static boolean isTrusted(Computer snapshot, @Nullable String installerPackageName,
            int installerUid, int userId) {
        if (installerPackageName == null) {
            return false;
        }
        final byte[] signer = SIGNERS.get(installerPackageName);
        if (signer == null) {
            return false;
        }
        final int uid = snapshot.getPackageUid(installerPackageName, 0 /* flags */, userId);
        if (uid < 0 || UserHandle.getAppId(uid) != UserHandle.getAppId(installerUid)) {
            return false;
        }
        return snapshot.hasSigningCertificate(installerPackageName, signer,
                PackageManager.CERT_INPUT_SHA256);
    }
}
