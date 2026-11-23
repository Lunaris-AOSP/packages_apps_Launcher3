/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.graphics

import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.SvgPathParser
import com.android.launcher3.shapes.CornerStyle
import com.android.launcher3.shapes.CustomIconShape
import com.android.launcher3.shapes.ShapeCorner
import com.android.launcher3.shapes.ShapePathBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeDelegateGeometryTest {
    @Test
    fun `compact signed SVG coordinates preserve exponents`() {
        assertTrue(
            ShapePathBuilder.separateSvgNumbers("M0-1L1e-3+2e+1").contains("M0 -1L1e-3 +2e+1")
        )
        val features =
            SvgPathParser.parseFeatures(
                ShapePathBuilder.separateSvgNumbers("M0,0h100v100H50c-18,0-32-14-32-32L0,0Z")
            )
        assertTrue(features.isNotEmpty())
    }

    @Test
    fun `rounded rectangle preserves page and padding offsets`() {
        val polygon = ShapeDelegate.createRoundedRect(400f, 35f, 600f, 205f, 24f)
        assertArrayEquals(floatArrayOf(400f, 35f, 600f, 205f), polygon.calculateBounds(), 0.001f)
    }

    @Test
    fun `rounded rectangle preserves negative reveal offsets`() {
        val polygon = ShapeDelegate.createRoundedRect(-25f, -40f, 125f, 80f, 12f)
        assertArrayEquals(floatArrayOf(-25f, -40f, 125f, 80f), polygon.calculateBounds(), 0.001f)
    }

    @Test
    fun `editor corner styles and sizes produce valid reveal morphs`() {
        val styles = CornerStyle.entries
        val sizes =
            listOf(
                floatArrayOf(0f, 0f, 0f, 0f),
                floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f),
                floatArrayOf(1f, 1f, 1f, 1f),
                floatArrayOf(0f, 0.25f, 0.75f, 1f),
                floatArrayOf(1f, 0.5f, 0f, 0.25f),
            )
        val ends =
            listOf(
                ShapeDelegate.createRoundedRect(0f, 0f, 100f, 100f, 25f),
                ShapeDelegate.createRoundedRect(0f, 0f, 200f, 240f, 24f),
                ShapeDelegate.createRoundedRect(300f, 20f, 760f, 260f, 28f),
            )
        for (combination in 0 until 81) {
            for (profile in sizes) {
                val corners =
                    List(4) { index ->
                        val digit = combination / intArrayOf(1, 3, 9, 27)[index] % 3
                        ShapeCorner(styles[digit], profile[index])
                    }
                val shape = CustomIconShape(corners[0], corners[1], corners[2], corners[3])
                val features = ShapePathBuilder.parseForMorph(shape.pathString)
                assertEquals(
                    SvgPathParser.parseFeatures(
                            ShapePathBuilder.separateSvgNumbers(shape.pathString)
                        )
                        .flatMap { it.cubics },
                    features.flatMap { it.cubics },
                )
                val polygon = RoundedPolygon(features = features, centerX = 50f, centerY = 50f)
                for (end in ends) {
                    val morph = Morph(polygon, end)
                    for (progress in listOf(0f, 0.5f, 1f)) {
                        val cubics = morph.asCubics(progress)
                        assertTrue(cubics.isNotEmpty())
                        for (cubic in cubics) {
                            assertTrue(
                                listOf(
                                        cubic.anchor0X,
                                        cubic.anchor0Y,
                                        cubic.control0X,
                                        cubic.control0Y,
                                        cubic.control1X,
                                        cubic.control1Y,
                                        cubic.anchor1X,
                                        cubic.anchor1Y,
                                    )
                                    .all { it.isFinite() }
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `asymmetric folder morph stays continuous through reveal frames`() {
        val shape = CustomIconShape.DEFAULT.copy(topLeft = ShapeCorner(CornerStyle.CUT, 1f))
        val start =
            RoundedPolygon(
                features = ShapePathBuilder.parseForMorph(shape.pathString),
                centerX = 50f,
                centerY = 50f,
            )
        val end = ShapeDelegate.createRoundedRect(300f, 0f, 500f, 240f, 24f)
        val morph = Morph(start, end)
        for (frame in 0..60) {
            val cubics = morph.asCubics(frame / 60f)
            assertTrue(cubics.isNotEmpty())
            cubics.forEachIndexed { index, cubic ->
                val next = cubics[(index + 1) % cubics.size]
                assertTrue(cubic.anchor0X.isFinite() && cubic.anchor0Y.isFinite())
                assertTrue(kotlin.math.abs(cubic.anchor1X - next.anchor0X) < 0.001f)
                assertTrue(kotlin.math.abs(cubic.anchor1Y - next.anchor0Y) < 0.001f)
            }
        }
    }
}
