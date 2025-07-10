/*
 * SPDX-FileCopyrightText: 2024-2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.aospa;

import android.app.Application;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

/**
 * Manager class for handling keybox providers.
 * @hide
 */
public final class KeyProviderManager {

    private static final String TAG = "KeyProviderManager";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private KeyProviderManager() {
    }

    public static IKeyboxProvider getProvider() {
        IPihManager pihManager = PropImitationHooks.getPihManager();
        if (pihManager == null) {
            Log.d(TAG, "Failed to get pih manager service.");
            return null;
        }

        try {
            return pihManager.getKeyboxProvider();
        } catch (RemoteException e) {
            Log.e(TAG, "getKeyboxProvider() failed", e);
            return null;
        }
    }

    public static boolean isKeyboxAvailable() {
        if (!PropImitationHooks.sEnableKeyboxImitation) {
            dlog("Key attestation spoofing is disabled");
            return false;
        }

        // Sanity check
        String processName = Application.getProcessName();
        if (TextUtils.isEmpty(processName)) {
            return false;
        }

        IKeyboxProvider provider = getProvider();
        if (provider == null) {
            dlog("No keybox provider available");
            return false;
        }

        // dlog("Using keybox provider: " + provider.getName());

        try {
            if (!provider.hasKeybox()) {
                dlog("Keybox provider is invalid");
                return false;
            }
            // Check if the current process matches a spoofing target
            for (String packageName : provider.getSpoofingTargets()) {
                if (processName.contains(packageName)) {
                    dlog("Keybox provider spoofing target match: " + packageName);
                    return true;
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "isKeyboxAvailable() failed", e);
            return false;
        }

        // Check for user-defined packages to spoof
        String packages = PropImitationHooks.getKeyboxSpoofingTargets();
        if (packages != null) {
            for (String packageName : packages.split(",")) {
                if (!packageName.isEmpty() && processName.contains(packageName)) {
                    dlog("User setting keybox spoofing target match: " + packageName);
                    return true;
                }
            }
        }

        return false;
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
