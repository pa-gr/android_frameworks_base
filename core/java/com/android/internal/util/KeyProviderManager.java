/*
 * SPDX-FileCopyrightText: 2024-2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util;

import android.os.RemoteException;
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
            dlog("Key attestation spoofing is disabled by user");
            return false;
        }

        IKeyboxProvider provider = getProvider();
        if (provider == null) {
            return false;
        }

        try {
            return provider.hasKeybox();
        } catch (RemoteException e) {
            Log.e(TAG, "isKeyboxAvailable() failed", e);
            return false;
        }
    }

    private static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, msg);
    }
}
