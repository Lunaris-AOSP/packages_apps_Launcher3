/*
 * Copyright (C) 2026 Lunaris AOSP
 * Copyright (C) 2025 AxionOS Project
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.ui.text.ExperimentalTextApi::class,
)

package com.android.launcher3.settings.compose

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.DeviceFontFamilyName
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.unit.dp

@Composable
fun HomeSettingsTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    val typography =
        remember(context) {
            val base = Typography()
            val brand =
                configuredFont(
                    context,
                    "config_headlineFontFamily",
                    "config_headlineFontFamilyMedium",
                )
            val plain =
                configuredFont(context, "config_bodyFontFamily", "config_bodyFontFamilyMedium")
            fun adapt(
                style: TextStyle,
                family: FontFamily?,
                token: String,
                emphasized: Boolean,
            ): TextStyle =
                style.copy(
                    fontFamily =
                        family
                            ?: FontFamily(
                                Font(
                                    DeviceFontFamilyName(
                                        "variable-$token" + if (emphasized) "-emphasized" else ""
                                    )
                                )
                            ),
                    fontWeight = if (emphasized) FontWeight.SemiBold else style.fontWeight,
                    hyphens = Hyphens.Auto,
                )
            base.copy(
                displayLarge = adapt(base.displayLarge, brand, "display-large", false),
                displayLargeEmphasized =
                    adapt(base.displayLargeEmphasized, brand, "display-large", true),
                displayMedium = adapt(base.displayMedium, brand, "display-medium", false),
                displayMediumEmphasized =
                    adapt(base.displayMediumEmphasized, brand, "display-medium", true),
                displaySmall = adapt(base.displaySmall, brand, "display-small", false),
                displaySmallEmphasized =
                    adapt(base.displaySmallEmphasized, brand, "display-small", true),
                headlineLarge = adapt(base.headlineLarge, brand, "headline-large", false),
                headlineLargeEmphasized =
                    adapt(base.headlineLargeEmphasized, brand, "headline-large", true),
                headlineMedium = adapt(base.headlineMedium, brand, "headline-medium", false),
                headlineMediumEmphasized =
                    adapt(base.headlineMediumEmphasized, brand, "headline-medium", true),
                headlineSmall = adapt(base.headlineSmall, brand, "headline-small", false),
                headlineSmallEmphasized =
                    adapt(base.headlineSmallEmphasized, brand, "headline-small", true),
                titleLarge = adapt(base.titleLarge, brand, "title-large", false),
                titleLargeEmphasized = adapt(base.titleLargeEmphasized, brand, "title-large", true),
                titleMedium = adapt(base.titleMedium, brand, "title-medium", false),
                titleMediumEmphasized =
                    adapt(base.titleMediumEmphasized, brand, "title-medium", true),
                titleSmall = adapt(base.titleSmall, brand, "title-small", false),
                titleSmallEmphasized = adapt(base.titleSmallEmphasized, brand, "title-small", true),
                bodyLarge = adapt(base.bodyLarge, plain, "body-large", false),
                bodyLargeEmphasized = adapt(base.bodyLargeEmphasized, plain, "body-large", true),
                bodyMedium = adapt(base.bodyMedium, plain, "body-medium", false),
                bodyMediumEmphasized = adapt(base.bodyMediumEmphasized, plain, "body-medium", true),
                bodySmall = adapt(base.bodySmall, plain, "body-small", false),
                bodySmallEmphasized = adapt(base.bodySmallEmphasized, plain, "body-small", true),
                labelLarge = adapt(base.labelLarge, plain, "label-large", false),
                labelLargeEmphasized = adapt(base.labelLargeEmphasized, plain, "label-large", true),
                labelMedium = adapt(base.labelMedium, plain, "label-medium", false),
                labelMediumEmphasized =
                    adapt(base.labelMediumEmphasized, plain, "label-medium", true),
                labelSmall = adapt(base.labelSmall, plain, "label-small", false),
                labelSmallEmphasized = adapt(base.labelSmallEmphasized, plain, "label-small", true),
            )
        }
    MaterialExpressiveTheme(
        colorScheme = colors,
        typography = typography,
        motionScheme = MotionScheme.expressive(),
        shapes =
            Shapes(
                extraSmall = RoundedCornerShape(8.dp),
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(28.dp),
                extraLarge = RoundedCornerShape(32.dp),
            ),
        content = content,
    )
}

private fun configuredFont(context: Context, normalKey: String, mediumKey: String): FontFamily? {
    fun config(name: String): String {
        val id = context.resources.getIdentifier(name, "string", "android")
        return if (id != 0) context.getString(id) else ""
    }
    val normal = config(normalKey)
    val medium = config(mediumKey)
    if (
        normal.isEmpty() ||
            medium.isEmpty() ||
            normal == "sans-serif" && medium == "sans-serif-medium"
    )
        return null
    return FontFamily(
        Font(DeviceFontFamilyName(normal), FontWeight.Normal),
        Font(DeviceFontFamilyName(medium), FontWeight.Medium),
        Font(DeviceFontFamilyName(medium), FontWeight.SemiBold),
    )
}
