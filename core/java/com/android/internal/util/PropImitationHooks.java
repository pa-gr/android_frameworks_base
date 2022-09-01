/*
 * SPDX-FileCopyrightText: 2022-2025 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.internal.util;

import android.app.ActivityTaskManager;
import android.app.Application;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.Binder;
import android.os.Process;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.R;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * @hide
 */
public class PropImitationHooks {

    private static final String TAG = "PropImitationHooks";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final Boolean sDisableGmsProps = SystemProperties.getBoolean(
            "persist.sys.pihooks.disable.gms_props", false);

    private static final Boolean sDisableKeyAttestationBlock = SystemProperties.getBoolean(
            "persist.sys.pihooks.disable.gms_key_attestation_block", false);

    private static final String PACKAGE_ARCORE = "com.google.ar.core";
    private static final String PACKAGE_FINSKY = "com.android.vending";
    private static final String PACKAGE_GMS = "com.google.android.gms";
    private static final String PROCESS_GMS_UNSTABLE = PACKAGE_GMS + ".unstable";
    private static final String PACKAGE_NETFLIX = "com.netflix.mediaclient";
    private static final String PACKAGE_GPHOTOS = "com.google.android.apps.photos";

    private static final String PROP_SECURITY_PATCH = "persist.sys.pihooks.security_patch";
    private static final String PROP_FIRST_API_LEVEL = "persist.sys.pihooks.first_api_level";

    private static final ComponentName GMS_ADD_ACCOUNT_ACTIVITY = ComponentName.unflattenFromString(
            "com.google.android.gms/.auth.uiflows.minutemaid.MinuteMaidActivity");

    private static final Set<String> sPixelFeatures = Set.of(
        "PIXEL_2017_PRELOAD",
        "PIXEL_2018_PRELOAD",
        "PIXEL_2019_MIDYEAR_PRELOAD",
        "PIXEL_2019_PRELOAD",
        "PIXEL_2020_EXPERIENCE",
        "PIXEL_2020_MIDYEAR_EXPERIENCE",
        "PIXEL_EXPERIENCE"
    );

    private static volatile JSONObject sCertifiedProps;
    private static volatile String sStockFp, sNetflixModel;

    private static volatile String sProcessName;
    private static volatile boolean sIsPixelDevice, sIsGms, sIsFinsky, sIsPhotos;
    private static volatile Context sContext;

    public static void setProps(Context context) {
        final String packageName = context.getPackageName();
        final String processName = Application.getProcessName();

        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(processName)) {
            Log.e(TAG, "Null package or process name");
            return;
        }

        final Resources res = context.getResources();
        if (res == null) {
            Log.e(TAG, "Null resources");
            return;
        }

        sContext = context;
        sStockFp = res.getString(R.string.config_stockFingerprint);
        sNetflixModel = res.getString(R.string.config_netflixSpoofModel);

        sProcessName = processName;
        sIsPixelDevice = Build.MANUFACTURER.equals("Google") && Build.MODEL.contains("Pixel");
        sIsGms = packageName.equals(PACKAGE_GMS) && processName.equals(PROCESS_GMS_UNSTABLE);
        sIsFinsky = packageName.equals(PACKAGE_FINSKY);
        sIsPhotos = packageName.equals(PACKAGE_GPHOTOS);

        /* Set Certified Properties for GMSCore
         * Set Stock Fingerprint for ARCore
         * Set custom model for Netflix
         */
        if (sIsGms) {
            setCertifiedPropsForGms();
        } else if (!sStockFp.isEmpty() && packageName.equals(PACKAGE_ARCORE)) {
            dlog("Setting stock fingerprint for: " + packageName);
            setPropValue("FINGERPRINT", sStockFp);
        } else if (!sNetflixModel.isEmpty() && packageName.equals(PACKAGE_NETFLIX)) {
            dlog("Setting model to " + sNetflixModel + " for Netflix");
            setPropValue("MODEL", sNetflixModel);
        }
    }

    private static void setPropValue(String key, Object value, Boolean isVersionField) {
        if (TextUtils.isEmpty(key) || value == null) {
            Log.e(TAG, "invalid key/value pair: " + key + ":" + value);
            return;
        }
        try {
            dlog("Setting prop " + key + " to " + value.toString());
            Class clazz = isVersionField ? Build.VERSION.class : Build.class;
            Field field = clazz.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set prop " + key, e);
        }
    }

    private static void setPropValue(String key, Object value) {
        setPropValue(key, value, false);
    }

    private static void loadCertifiedProps() {
        byte[] jsonBytes;
        try {
            jsonBytes = sContext.getResources().openRawResource(
                    R.raw.certified_build_props).readAllBytes();
        } catch (IOException e) {
            Log.e(TAG, "loadCertifiedProps: failed to read json!", e);
            return;
        }

        String jsonString = new String(jsonBytes, StandardCharsets.UTF_8);
        if (TextUtils.isEmpty(jsonString)) {
            dlog("loadCertifiedProps: json is empty, bailing");
            return;
        }

        try {
            sCertifiedProps = new JSONObject(jsonString);
        } catch (JSONException e) {
            Log.e(TAG, "loadCertifiedProps: failed to parse json!", e);
        }
    }

    private static void setCertifiedPropsForGms() {
        if (sDisableGmsProps) {
            dlog("GMS prop imitation is disabled by user");
            return;
        }

        loadCertifiedProps();

        if (sCertifiedProps == null || sCertifiedProps.length() == 0) {
            dlog("Certified props are not set");
            return;
        }

        final boolean was = isGmsAddAccountActivityOnTop();
        final TaskStackListener taskStackListener = new TaskStackListener() {
            @Override
            public void onTaskStackChanged() {
                final boolean is = isGmsAddAccountActivityOnTop();
                if (is ^ was) {
                    dlog("GmsAddAccountActivityOnTop is:" + is + " was:" + was +
                            ", killing myself!"); // process will restart automatically later
                    Process.killProcess(Process.myPid());
                }
            }
        };
        if (!was) {
            dlog("Spoofing build for GMS");
            setCertifiedProps();
        } else {
            dlog("Skip spoofing build for GMS, because GmsAddAccountActivityOnTop");
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(taskStackListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to register task stack listener!", e);
        }
    }

    private static void setCertifiedProps() {
        sCertifiedProps.keySet().forEach(key -> {
            try {
                Object value = sCertifiedProps.get(key);
                if (key.equals("VERSION")) {
                    JSONObject versionProps = (JSONObject) value;
                    versionProps.keySet().forEach(versionKey -> {
                        try {
                            setPropValue(versionKey, versionProps.get(versionKey), true);
                        } catch (JSONException e) {
                            Log.e(TAG, "failed to get value for key: " + versionKey, e);
                        }
                    });
                } else {
                    setPropValue(key, value);
                }
            } catch (JSONException e) {
                Log.e(TAG, "failed to get value for key: " + key, e);
            }
        });
        setSystemProperty(PROP_SECURITY_PATCH, Build.VERSION.SECURITY_PATCH);
        setSystemProperty(PROP_FIRST_API_LEVEL,
                Integer.toString(Build.VERSION.DEVICE_INITIAL_SDK_INT));
    }

    private static void setSystemProperty(String name, String value) {
        try {
            SystemProperties.set(name, value);
            dlog("Set system prop " + name + "=" + value);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set system prop " + name + "=" + value, e);
        }
    }

    private static boolean isGmsAddAccountActivityOnTop() {
        try {
            final ActivityTaskManager.RootTaskInfo focusedTask =
                    ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return focusedTask != null && focusedTask.topActivity != null
                    && focusedTask.topActivity.equals(GMS_ADD_ACCOUNT_ACTIVITY);
        } catch (Exception e) {
            Log.e(TAG, "Unable to get top activity!", e);
        }
        return false;
    }

    public static boolean shouldBypassTaskPermission(Context context) {
        if (sDisableGmsProps) {
            return false;
        }

        // GMS doesn't have MANAGE_ACTIVITY_TASKS permission
        final int callingUid = Binder.getCallingUid();
        final int gmsUid;
        try {
            gmsUid = context.getPackageManager().getApplicationInfo(PACKAGE_GMS, 0).uid;
            // dlog("shouldBypassTaskPermission: gmsUid:" + gmsUid + " callingUid:" + callingUid);
        } catch (Exception e) {
            Log.e(TAG, "shouldBypassTaskPermission: unable to get gms uid", e);
            return false;
        }
        return gmsUid == callingUid;
    }

    private static boolean isCallerSafetyNet() {
        return sIsGms && Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(elem -> elem.getClassName().contains("DroidGuard"));
    }

    public static void onEngineGetCertificateChain() {
        if (sDisableKeyAttestationBlock) {
            dlog("Key attestation blocking is disabled by user");
            return;
        }

        // Check stack for SafetyNet or Play Integrity
        if (isCallerSafetyNet() || sIsFinsky) {
            dlog("Blocked key attestation sIsGms=" + sIsGms + " sIsFinsky=" + sIsFinsky);
            throw new UnsupportedOperationException();
        }
    }

    public static boolean hasSystemFeature(String name, boolean has) {
        if (sIsPhotos && !sIsPixelDevice && has
                && sPixelFeatures.stream().anyMatch(name::contains)) {
            dlog("Blocked system feature " + name + " for Google Photos");
            has = false;
        }
        return has;
    }

    public static void dlog(String msg) {
        if (DEBUG) Log.d(TAG, "[" + sProcessName + "] " + msg);
    }
}
