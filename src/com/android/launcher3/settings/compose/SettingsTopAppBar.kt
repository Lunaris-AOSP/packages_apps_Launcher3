/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.android.launcher3.settings.compose

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsTopAppBar(
    title: String,
    onBack: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val expandedHeight =
        with(LocalDensity.current) {
            maxOf(
                192.dp,
                104.dp + MaterialTheme.typography.headlineLargeEmphasized.lineHeight.toDp() * 2,
            )
        }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            TwoRowsTopAppBar(
                modifier = Modifier.widthIn(max = 840.dp).fillMaxWidth(),
                title = { expanded ->
                    Text(
                        title,
                        modifier =
                            Modifier.semantics { heading() }
                                .then(
                                    if (expanded)
                                        Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                                    else Modifier
                                ),
                        style =
                            if (expanded) MaterialTheme.typography.headlineLargeEmphasized
                            else MaterialTheme.typography.titleLargeEmphasized,
                        maxLines = if (expanded) 2 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = onBack,
                        shapes = IconButtonDefaults.shapes(),
                        modifier = Modifier.padding(start = 12.dp, end = 8.dp).size(48.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            stringResource(
                                androidx.appcompat.R.string.abc_action_bar_up_description
                            ),
                        )
                    }
                },
                actions = actions,
                collapsedHeight = 72.dp,
                expandedHeight = expandedHeight,
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                scrollBehavior = scrollBehavior,
            )
        }
    }
}
