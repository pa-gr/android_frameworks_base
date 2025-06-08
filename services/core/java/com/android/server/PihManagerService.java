/*
 * SPDX-FileCopyrightText: 2024-2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.server;

import static android.os.Process.SYSTEM_UID;
import static com.android.internal.util.PropImitationHooks.PACKAGE_GMS;
import static com.android.internal.util.PropImitationHooks.PROCESS_GMS_UNSTABLE;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.util.Slog;

import com.android.internal.util.IKeyboxProvider;
import com.android.internal.util.IPihManager;
import com.android.internal.R;
import com.android.server.SystemService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * TODO:
 * - move gms add account activity listener here
 */
public class PihManagerService extends SystemService {

    private static final String TAG = "PihManager";
    private static final String SERVICE_NAME = "pih_manager";
    private static final String EMPTY_JSON = "{}";

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private final Object mLock = new Object();
    private String mCertifiedProps = EMPTY_JSON;
    private IKeyboxProvider mKeyboxProvider = new DefaultKeyboxProvider(getContext());

    private Integer mGmsUid;
    private boolean mGmsIsAddingAccount;

    public PihManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        Slog.v(TAG, "Starting PihManager service");
        loadCertifiedProps();
        publishBinderService(SERVICE_NAME, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            dlog("PHASE_BOOT_COMPLETED");
            registerGmsTaskListener();
        }
    }

    private void loadCertifiedProps() {
        byte[] jsonBytes;
        try {
            jsonBytes = getContext().getResources().openRawResource(
                    R.raw.certified_build_props).readAllBytes();
        } catch (IOException e) {
            Slog.e(TAG, "failed to read json!", e);
            return;
        }

        mCertifiedProps = new String(jsonBytes, StandardCharsets.UTF_8);
    }

    private void registerGmsTaskListener() {
        mGmsIsAddingAccount = isGmsAddAccountActivityOnTop();

        final TaskStackListener taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                final boolean is = isGmsAddAccountActivityOnTop();
                if (is != mGmsIsAddingAccount) {
                    dlog("GmsAddAccountActivityOnTop is:" + is + " was:" + mGmsIsAddingAccount);
                    mGmsIsAddingAccount = is;
                    restartGms();
                }
            }
        };

        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register task stack listener!", e);
        }
    }

    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && focusedTask.topActivity.equals(GMS_ADD_ACCOUNT_ACTIVITY);
        } catch (Exception e) {
            Slog.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    private void restartGms() {
        final Integer gmsUid = getGmsUid();
        if (gmsUid == null) {
            Slog.e(TAG, "Cannot restart gms without uid!");
            return;
        }

        try {
            ActivityManager.getService().killApplicationProcess(PROCESS_GMS_UNSTABLE, gmsUid);
            dlog("restartGms success");
        } catch (RemoteException e) {
            Slog.e(TAG, "restartGms failed", e);
        }
    }

    private int getGmsUid() {
        if (mGmsUid == null) {
            try {
                mGmsUid = getContext().getPackageManager().getApplicationInfo(PACKAGE_GMS, 0).uid;
            } catch (Exception e) {
                Slog.e(TAG, "Failed to get gms uid!", e);
            }
        }
        return mGmsUid;
    }

    private final IPihManager.Stub mService = new IPihManager.Stub() {
        @Override
        public String getCertifiedPropertiesJson() {
            final Integer gmsUid = getGmsUid();
            if (gmsUid != null && Binder.getCallingUid() == gmsUid && mGmsIsAddingAccount) {
                dlog("getCertifiedPropertiesJson: gms is adding account, return none!");
                return EMPTY_JSON;
            }

            return mCertifiedProps;
        }

        @Override
        public void setCertifiedPropertiesJson(String props) {
            if (Binder.getCallingUid() != SYSTEM_UID) {
                Log.e(TAG, "setCertifiedPropertiesJson: Caller must be system");
                return;
            }

            if (TextUtils.isEmpty(props)) {
                Log.e(TAG, "setCertifiedPropertiesJson: props cannot be null or empty");
                return;
            }

            try {
                new JSONObject(props);
            } catch (JSONException e) {
                Slog.e(TAG, "setCertifiedPropertiesJson: invalid json!", e);
            }

            synchronized (mLock) {
                if (mCertifiedProps.equals(props)) {
                    dlog("certified props are already equal");
                    return;
                }

                dlog("certified props set: " + props);
                mCertifiedProps = props;
                restartGms();
            }
        }

        @Override
        public void resetCertifiedProperties() {
            dlog("resetting certified props");
            synchronized (mLock) {
                loadCertifiedProps();
                restartGms();
            }
        }

        @Override
        public IKeyboxProvider getKeyboxProvider() {
            return mKeyboxProvider;
        }

        @Override
        public void setKeyboxProvider(IKeyboxProvider provider) {
            if (Binder.getCallingUid() != SYSTEM_UID) {
                Log.e(TAG, "setKeyboxProvider: Caller must be system");
                return;
            }

            if (provider == null) {
                Log.e(TAG, "cannot set null keybox provider");
                return;
            }

            try {
                dlog("setKeyboxProvider: " + provider.getName() + " hasKeybox="
                        + provider.hasKeybox());
            } catch (RemoteException e) {}

            synchronized (mLock) {
                mKeyboxProvider = provider;
                restartGms();
            }
        }

        @Override
        public void resetKeyboxProvider() {
            if (Binder.getCallingUid() != SYSTEM_UID) {
                Log.e(TAG, "resetKeyboxProvider: Caller must be system");
                return;
            }

            dlog("resetting keybox provider");
            synchronized (mLock) {
                mKeyboxProvider = new DefaultKeyboxProvider(getContext());
                restartGms();
            }
        }
    };

    private static class DefaultKeyboxProvider extends IKeyboxProvider.Stub {
        private final Map<String, String> keyboxData = new HashMap<>();

        private DefaultKeyboxProvider(Context context) {
            String[] keybox = context.getResources().getStringArray(
                    R.array.config_certifiedKeybox);

            Arrays.stream(keybox)
                    .map(entry -> entry.split(":", 2))
                    .filter(parts -> parts.length == 2)
                    .forEach(parts -> keyboxData.put(parts[0], parts[1]));

            if (!hasKeybox()) {
                Log.w(TAG, "Incomplete keybox data loaded");
            }
        }

        @Override
        public String getName() {
            return "DefaultKeyboxProvider";
        }

        @Override
        public boolean hasKeybox() {
            return Arrays.asList("EC.PRIV", "EC.CERT_1", "EC.CERT_2", "EC.CERT_3",
                    "RSA.PRIV", "RSA.CERT_1", "RSA.CERT_2", "RSA.CERT_3")
                    .stream()
                    .allMatch(keyboxData::containsKey);
        }

        @Override
        public String getEcPrivateKey() {
            return keyboxData.get("EC.PRIV");
        }

        @Override
        public String getRsaPrivateKey() {
            return keyboxData.get("RSA.PRIV");
        }

        @Override
        public String[] getEcCertificateChain() {
            return getCertificateChain("EC");
        }

        @Override
        public String[] getRsaCertificateChain() {
            return getCertificateChain("RSA");
        }

        private String[] getCertificateChain(String prefix) {
            return new String[]{
                    keyboxData.get(prefix + ".CERT_1"),
                    keyboxData.get(prefix + ".CERT_2"),
                    keyboxData.get(prefix + ".CERT_3")
            };
        }
    }

    private static void dlog(String msg) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, msg);
        }
    }
}
