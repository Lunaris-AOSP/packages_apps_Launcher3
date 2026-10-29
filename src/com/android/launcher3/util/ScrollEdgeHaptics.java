/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.util;

import android.content.Context;
import android.widget.EdgeEffect;

public final class ScrollEdgeHaptics {
    private ScrollEdgeHaptics() {}

    public static void perform(Context context, float velocity) {
        EdgeEffect edge = new EdgeEffect(context);
        edge.onAbsorb(Math.max(1, Math.round(Math.abs(velocity))));
        edge.finish();
    }
}
