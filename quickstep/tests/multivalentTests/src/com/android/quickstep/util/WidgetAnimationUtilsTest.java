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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Matrix;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class WidgetAnimationUtilsTest {
    private static final float EPSILON = 0.001f;

    @Test
    public void portraitAppFitsWideAndTallWidgets() {
        verifyCrop(new RectF(0, 0, 1080, 2400), new RectF(100, 1600, 900, 1900));
        verifyCrop(new RectF(0, 0, 1080, 2400), new RectF(100, 600, 250, 1600));
    }

    @Test
    public void landscapeAppFitsWideAndTallWidgets() {
        verifyCrop(new RectF(0, 0, 2400, 1080), new RectF(600, 100, 1600, 250));
        verifyCrop(new RectF(0, 0, 2400, 1080), new RectF(1600, 100, 1900, 900));
    }

    @Test
    public void cropRespectsWindowInsetsAndOffset() {
        verifyCrop(new RectF(24, 80, 1056, 2300), new RectF(40, 500, 200, 1700));
        verifyCrop(new RectF(80, 24, 2300, 1056), new RectF(500, 40, 1700, 200));
    }

    @Test
    public void emptyBoundsClearPreviousTransform() {
        Matrix matrix = new Matrix();
        matrix.setScale(2f, 2f);
        RectF crop = new RectF(0, 0, 100, 100);
        assertEquals(0f, WidgetAnimationUtils.updateWindowCrop(new RectF(),
                new RectF(0, 0, 100, 100), matrix, crop), EPSILON);
        assertTrue(crop.isEmpty());
        assertTrue(matrix.isIdentity());
        assertEquals(0f, WidgetAnimationUtils.updateWindowCrop(new RectF(0, 0, 100, 100),
                new RectF(), matrix, crop), EPSILON);
        assertTrue(crop.isEmpty());
    }

    private static void verifyCrop(RectF source, RectF target) {
        Matrix matrix = new Matrix();
        RectF crop = new RectF();
        float scale = WidgetAnimationUtils.updateWindowCrop(source, target, matrix, crop);
        assertTrue(scale > 0f);
        assertTrue(crop.left >= source.left - EPSILON);
        assertTrue(crop.top >= source.top - EPSILON);
        assertTrue(crop.right <= source.right + EPSILON);
        assertTrue(crop.bottom <= source.bottom + EPSILON);
        assertEquals(source.centerX(), crop.centerX(), EPSILON);
        assertEquals(source.centerY(), crop.centerY(), EPSILON);

        RectF mapped = new RectF(crop);
        matrix.mapRect(mapped);
        assertEquals(target.left, mapped.left, EPSILON);
        assertEquals(target.top, mapped.top, EPSILON);
        assertEquals(target.right, mapped.right, EPSILON);
        assertEquals(target.bottom, mapped.bottom, EPSILON);
        assertEquals(32f, matrix.mapRadius(32f / scale), EPSILON);

        float[] values = new float[9];
        matrix.getValues(values);
        assertEquals(values[Matrix.MSCALE_X], values[Matrix.MSCALE_Y], EPSILON);
    }
}
