/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.settings.compose

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.android.launcher3.R
import com.android.launcher3.widget.WidgetManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class SearchWidgetPreviewAssets(
    val remoteViews: RemoteViews? = null,
    val image: ImageBitmap? = null,
    val icon: ImageBitmap? = null,
)

@Composable
internal fun DockSearchWidgetPreview(info: AppWidgetProviderInfo?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    BoxWithConstraints(
        modifier.fillMaxWidth().height(112.dp),
        contentAlignment = Alignment.Center,
    ) {
        val width = with(density) { maxWidth.roundToPx().coerceAtLeast(1) }
        val height = with(density) { 96.dp.roundToPx() }
        val assets by
            produceState<SearchWidgetPreviewAssets?>(
                null,
                context,
                info,
                width,
                height,
                configuration,
            ) {
                if (info != null) {
                    val loaded =
                        withContext(Dispatchers.IO) {
                            loadSearchWidgetPreview(context, info, width, height)
                        }
                    val layoutImage =
                        if (loaded.remoteViews != null || info.previewLayout != 0) {
                            renderSearchWidgetPreview(
                                context,
                                info,
                                loaded.remoteViews,
                                width,
                                height,
                            )
                        } else null
                    value = loaded.copy(image = layoutImage ?: loaded.image)
                }
            }
        val image = assets?.image
        if (image != null) {
            Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.height(56.dp).padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val icon = assets?.icon
                    when {
                        icon != null -> Image(icon, null, Modifier.size(24.dp))
                        info == null ->
                            Icon(
                                painterResource(R.drawable.ic_super_g_color),
                                null,
                                Modifier.size(24.dp),
                                tint = androidx.compose.ui.graphics.Color.Unspecified,
                            )
                        else -> Icon(Icons.Rounded.Search, null)
                    }
                    Text(
                        stringResource(R.string.label_search),
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (info == null) Icon(Icons.Rounded.Mic, null)
                }
            }
        }
    }
}

private fun loadSearchWidgetPreview(
    context: Context,
    info: AppWidgetProviderInfo,
    width: Int,
    height: Int,
): SearchWidgetPreviewAssets {
    val remoteViews =
        try {
            val category =
                when {
                    info.generatedPreviewCategories and
                        AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX != 0 ->
                        AppWidgetProviderInfo.WIDGET_CATEGORY_SEARCHBOX
                    info.generatedPreviewCategories and
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN != 0 ->
                        AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN
                    else -> 0
                }
            if (category != 0) WidgetManagerHelper(context).loadGeneratedPreview(info, category)
            else null
        } catch (e: RuntimeException) {
            Log.w("DockSearchWidgetPreview", "Unable to load generated preview", e)
            null
        }
    val image =
        try {
            info.loadPreviewImage(context, 0)?.let { drawable ->
                val sourceWidth = drawable.intrinsicWidth.coerceAtLeast(1)
                val sourceHeight = drawable.intrinsicHeight.coerceAtLeast(1)
                val scale = minOf(width.toFloat() / sourceWidth, height.toFloat() / sourceHeight)
                drawable
                    .toBitmap(
                        (sourceWidth * scale).toInt().coerceAtLeast(1),
                        (sourceHeight * scale).toInt().coerceAtLeast(1),
                    )
                    .asImageBitmap()
            }
        } catch (e: RuntimeException) {
            Log.w("DockSearchWidgetPreview", "Unable to load preview image", e)
            null
        }
    val icon =
        try {
            info.loadIcon(context, 0)?.toBitmap(64, 64)?.asImageBitmap()
        } catch (e: RuntimeException) {
            Log.w("DockSearchWidgetPreview", "Unable to load provider icon", e)
            null
        }
    return SearchWidgetPreviewAssets(remoteViews, image, icon)
}

private fun renderSearchWidgetPreview(
    context: Context,
    info: AppWidgetProviderInfo,
    remoteViews: RemoteViews?,
    width: Int,
    height: Int,
): ImageBitmap? =
    try {
        val provider =
            info.clone().apply {
                if (remoteViews == null) initialLayout = previewLayout
                initialKeyguardLayout = 0
            }
        var failed = false
        val host =
            object : AppWidgetHostView(context) {
                    override fun getErrorView(): View {
                        failed = true
                        return View(context)
                    }
                }
                .apply {
                    setAppWidget(AppWidgetManager.INVALID_APPWIDGET_ID, provider)
                    updateAppWidget(remoteViews)
                    measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                    )
                    layout(0, 0, measuredWidth, measuredHeight)
                }
        if (failed) null
        else
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                .also { host.draw(Canvas(it)) }
                .asImageBitmap()
    } catch (e: RuntimeException) {
        Log.w("DockSearchWidgetPreview", "Unable to render preview layout", e)
        null
    }
