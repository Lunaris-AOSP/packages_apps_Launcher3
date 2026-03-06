/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.quickstep.util;

import android.graphics.Matrix;
import android.graphics.RectF;

public final class WidgetAnimationUtils {
    private WidgetAnimationUtils() { }

    public static float updateWindowCrop(RectF source, RectF target, Matrix matrix, RectF crop) {
        if (source.isEmpty() || target.isEmpty()) {
            crop.setEmpty();
            matrix.reset();
            return 0f;
        }
        float scale = Math.max(target.width() / source.width(), target.height() / source.height());
        crop.set(source);
        crop.inset((source.width() - target.width() / scale) / 2f,
                (source.height() - target.height() / scale) / 2f);
        matrix.setScale(scale, scale);
        matrix.postTranslate(target.left - crop.left * scale, target.top - crop.top * scale);
        return scale;
    }
}
