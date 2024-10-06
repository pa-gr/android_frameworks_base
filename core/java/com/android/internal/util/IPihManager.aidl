/*
 * SPDX-FileCopyrightText: 2024 Paranoid Android
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.internal.util;

import com.android.internal.util.IKeyboxProvider;

/** @hide */
interface IPihManager {

    String getCertifiedPropertiesJson();

    void setCertifiedPropertiesJson(in String props);

    void resetCertifiedProperties();

    IKeyboxProvider getKeyboxProvider();

    void setKeyboxProvider(in IKeyboxProvider provider);

    void resetKeyboxProvider();
}
