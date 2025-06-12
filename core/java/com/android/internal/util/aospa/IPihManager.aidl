/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util.aospa;

import com.android.internal.util.aospa.IKeyboxProvider;

/** @hide */
interface IPihManager {

    String getCertifiedPropertiesJson();

    void setCertifiedPropertiesJson(in String props);

    void resetCertifiedProperties();

    IKeyboxProvider getKeyboxProvider();

    void setKeyboxProvider(in IKeyboxProvider provider);

    void resetKeyboxProvider();
}
