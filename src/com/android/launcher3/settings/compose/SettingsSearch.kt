/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.settings.compose

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.android.launcher3.R
import com.android.launcher3.settings.*
import com.android.launcher3.smartspace.SmartspaceEvent
import org.xmlpull.v1.XmlPullParser

internal data class SettingsSearchResult(
    val key: String,
    val title: String,
    val summary: String,
    val breadcrumb: String,
    val category: String,
    val activity: Class<*>,
    val screenKey: String?,
    val keywords: String,
) {
    val id: String = "${activity.name}/$key"

    fun open(context: Context) {
        context.startActivity(
            Intent(context, activity)
                .putExtra(SettingsActivity.EXTRA_FRAGMENT_HIGHLIGHT_KEY, key)
                .apply { screenKey?.let { putExtra(SettingsActivity.EXTRA_FRAGMENT_ROOT_KEY, it) } }
        )
    }
}

private data class SearchDestination(
    val resource: Int,
    val activity: Class<*>,
    val category: String,
    val title: Int,
)

private data class SearchGroup(val depth: Int, val title: String, val screenKey: String?)

internal fun settingsSearchIndex(context: Context): List<SettingsSearchResult> {
    val destinations =
        listOf(
            SearchDestination(
                R.xml.launcher_icons_preferences,
                SettingsIcons::class.java,
                "icons",
                R.string.icons_category_title,
            ),
            SearchDestination(
                R.xml.launcher_home_screen_preferences,
                SettingsHomescreen::class.java,
                "home_screen",
                R.string.home_category_title,
            ),
            SearchDestination(
                R.xml.launcher_app_drawer_preferences,
                SettingsAppDrawer::class.java,
                "app_drawer",
                R.string.app_drawer_category_title,
            ),
            SearchDestination(
                R.xml.launcher_recents_preferences,
                SettingsRecents::class.java,
                "recents",
                R.string.recents_category_title,
            ),
            SearchDestination(
                R.xml.launcher_misc_preferences,
                SettingsMisc::class.java,
                "miscellaneous",
                R.string.misc_category_title,
            ),
            SearchDestination(
                R.xml.launcher_overscroll_preferences,
                SettingsOverscroll::class.java,
                "miscellaneous",
                R.string.overscroll_settings_title,
            ),
        )
    val results = mutableListOf<SettingsSearchResult>()
    val android = "http://schemas.android.com/apk/res/android"
    for (destination in destinations) {
        context.resources.getXml(destination.resource).use { parser ->
            val groups = mutableListOf<SearchGroup>()
            fun text(attribute: String): String {
                val resource = parser.getAttributeResourceValue(android, attribute, 0)
                return if (resource != 0) context.getString(resource)
                else parser.getAttributeValue(android, attribute).orEmpty()
            }
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                groups.removeAll { it.depth >= parser.depth }
                val tag = parser.name.substringAfterLast('.')
                if (tag == "intent" || tag == "extra") continue
                val title = text("title")
                val key = parser.getAttributeValue(android, "key").orEmpty()
                val isScreen = tag == "PreferenceScreen"
                if (
                    parser.depth > 1 &&
                        key.isNotEmpty() &&
                        title.isNotEmpty() &&
                        tag != "PreferenceCategory"
                ) {
                    val summary = text("summary").takeUnless { it.contains("%s") }.orEmpty()
                    val entries = parser.getAttributeResourceValue(android, "entries", 0)
                    val labels =
                        if (entries != 0) context.resources.getTextArray(entries).joinToString(" ")
                        else ""
                    val breadcrumb =
                        (listOf(context.getString(destination.title)) +
                                groups.map { it.title }.filter { it.isNotEmpty() })
                            .joinToString(" › ")
                    results +=
                        SettingsSearchResult(
                            key,
                            title,
                            summary,
                            breadcrumb,
                            destination.category,
                            destination.activity,
                            groups.lastOrNull { it.screenKey != null }?.screenKey,
                            "$title $summary $breadcrumb $labels ${text("summaryOn")} ${text("summaryOff")}",
                        )
                }
                if (tag == "PreferenceCategory" || isScreen) {
                    groups +=
                        SearchGroup(parser.depth, title, key.takeIf { isScreen && it.isNotEmpty() })
                }
            }
        }
    }
    val smartspaceTitle = context.getString(R.string.smartspace_events_title)
    val homeTitle = context.getString(R.string.home_category_title)
    SmartspaceEvent.values().forEach { event ->
        val title = context.getString(event.title)
        val summary = context.getString(R.string.smartspace_events_summary)
        results +=
            SettingsSearchResult(
                event.preference.sharedPrefKey,
                title,
                summary,
                "$homeTitle › $smartspaceTitle",
                "home_screen",
                SettingsHomescreen::class.java,
                "smartspace_events",
                "$title $summary $homeTitle $smartspaceTitle",
            )
    }
    return results.distinctBy { it.id }
}

internal fun searchSettings(
    index: List<SettingsSearchResult>,
    query: String,
): List<SettingsSearchResult> {
    val terms =
        query
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map {
                it.lowercase().let { term -> if (term.length > 3) term.removeSuffix("s") else term }
            }
    if (terms.isEmpty()) return emptyList()
    return index
        .filter { result -> terms.all { result.keywords.contains(it, ignoreCase = true) } }
        .sortedBy { result ->
            when {
                result.title.startsWith(query.trim(), ignoreCase = true) -> 0
                terms.all { result.title.contains(it, ignoreCase = true) } -> 1
                else -> 2
            }
        }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SettingsSearchResultRow(
    result: SettingsSearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Icon(
                    settingsCategoryIcon(result.category),
                    null,
                    Modifier.padding(12.dp).size(24.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(result.title, style = MaterialTheme.typography.titleMediumEmphasized)
                if (result.summary.isNotEmpty()) {
                    Text(
                        result.summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    result.breadcrumb,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
