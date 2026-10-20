/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.android.launcher3.settings.compose

import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.preference.*
import androidx.recyclerview.widget.RecyclerView
import com.android.launcher3.R
import com.android.launcher3.settings.*
import com.android.launcher3.settings.preferences.ColorPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal data class SettingsRow(
    val preference: Preference,
    val id: String,
    val first: Boolean = false,
    val last: Boolean = false,
    val heading: Boolean = false,
)

private fun rows(group: PreferenceGroup, query: String = ""): List<SettingsRow> {
    val result = mutableListOf<SettingsRow>()
    fun visit(parent: PreferenceGroup, path: String) {
        val children =
            (0 until parent.preferenceCount).map(parent::getPreference).filter { it.isVisible }
        children.forEachIndexed { index, pref ->
            val id = pref.key ?: "$path/$index"
            if (pref is PreferenceCategory) {
                if (query.isBlank()) result += SettingsRow(pref, id, heading = true)
                visit(pref, id)
            } else if (pref is PreferenceScreen && query.isNotBlank() && pref.preferenceCount > 0) {
                visit(pref, id)
            } else if (
                query.isBlank() ||
                    "${pref.title ?: ""} ${pref.summary ?: ""}".contains(query, ignoreCase = true)
            ) {
                result +=
                    SettingsRow(
                        pref,
                        id,
                        first = index == 0 || children[index - 1] is PreferenceCategory,
                        last =
                            index == children.lastIndex || children[index + 1] is PreferenceCategory,
                    )
            }
        }
    }
    visit(group, "root")
    return result
}

private fun screenForKey(group: PreferenceGroup, key: String): PreferenceScreen? {
    for (index in 0 until group.preferenceCount) {
        val pref = group.getPreference(index)
        if (pref.key == key) return group as? PreferenceScreen
        if (pref is PreferenceGroup) {
            val found = screenForKey(pref, key)
            if (found != null) return found
            if (pref.findPreference<Preference>(key) != null) return group as? PreferenceScreen
        }
    }
    return null
}

@Composable
internal fun HomeSettingsScreen(fragment: ComposePreferenceFragment) {
    val root = fragment.getPreferenceScreen()
    val context = fragment.requireContext()
    val highlight =
        fragment.arguments?.getString(SettingsActivity.EXTRA_FRAGMENT_HIGHLIGHT_KEY)
            ?: fragment
                .requireActivity()
                .intent
                .getStringExtra(SettingsActivity.EXTRA_FRAGMENT_HIGHLIGHT_KEY)
    val initialScreen = fragment.requestedScreen ?: highlight?.let { screenForKey(root, it)?.key }
    var screenKey by rememberSaveable { mutableStateOf(initialScreen.orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    val screen = root.findPreference<PreferenceScreen>(screenKey) ?: root
    val overview = fragment is SettingsActivity.LauncherSettingsFragment
    val searchIndex by
        produceState<List<SettingsSearchResult>?>(null, context, overview) {
            if (overview) {
                value = withContext(Dispatchers.IO) { settingsSearchIndex(context) }
            }
        }
    val holder = rememberSaveableStateHolder()
    val title = screen.title?.toString() ?: fragment.requireActivity().title.toString()
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val goBack: () -> Unit = {
        when {
            query.isNotEmpty() -> query = ""
            screenKey.isNotEmpty() -> screenKey = ""
            fragment.parentFragmentManager.backStackEntryCount > 0 ->
                fragment.parentFragmentManager.popBackStack()
            else -> fragment.requireActivity().finish()
        }
    }
    BackHandler(query.isNotEmpty() || screenKey.isNotEmpty(), goBack)

    LaunchedEffect(screenKey) {
        scroll.state.heightOffset = 0f
        scroll.state.contentOffset = 0f
    }

    LaunchedEffect(fragment.requestedScreen) {
        fragment.requestedScreen?.let {
            screenKey = it
            query = ""
            fragment.requestedScreen = null
        }
    }
    DisposableEffect(fragment, root) {
        var observing = true
        val adapters = mutableListOf<PreferenceGroupAdapter>()
        fun observe(group: PreferenceGroup) {
            if (group is PreferenceScreen) {
                adapters +=
                    object : PreferenceGroupAdapter(group) {
                        override fun onPreferenceChange(preference: Preference) {
                            super.onPreferenceChange(preference)
                            if (observing) revision++
                        }
                    }
            }
            for (index in 0 until group.preferenceCount) {
                (group.getPreference(index) as? PreferenceGroup)?.let(::observe)
            }
        }
        observe(root)
        val observer =
            object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() {
                    revision++
                }

                override fun onItemRangeChanged(start: Int, count: Int) {
                    revision++
                }

                override fun onItemRangeChanged(start: Int, count: Int, payload: Any?) {
                    revision++
                }

                override fun onItemRangeInserted(start: Int, count: Int) {
                    revision++
                }

                override fun onItemRangeRemoved(start: Int, count: Int) {
                    revision++
                }
            }
        adapters.forEach { it.registerAdapterDataObserver(observer) }
        val lifecycle = fragment.viewLifecycleOwner.lifecycle
        val preferences = fragment.getPreferenceManager().sharedPreferences
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                fragment.view?.post { revision++ }
            }
        preferences?.registerOnSharedPreferenceChangeListener(listener)
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            observing = false
            adapters.forEach { it.unregisterAdapterDataObserver(observer) }
            preferences?.unregisterOnSharedPreferenceChangeListener(listener)
            lifecycle.removeObserver(lifecycleObserver)
        }
    }

    fun open(pref: Preference) {
        try {
            when {
                pref is ColorPreference ||
                    pref is SearchProviderPreference ||
                    pref is SearchSectionOrderPreference ||
                    pref is NotificationDotsPreference && pref.fragment != null ||
                    pref.key == "pref_clear_home_screen" ||
                    pref.key == "pref_restart" -> fragment.dialogPreference = pref
                else -> pref.performClick()
            }
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.activity_not_found, Toast.LENGTH_SHORT).show()
        }
        revision++
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets =
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = { SettingsTopAppBar(title = title, onBack = goBack, scrollBehavior = scroll) },
    ) { padding ->
        val spatial =
            MaterialTheme.motionScheme.defaultSpatialSpec<androidx.compose.ui.unit.IntOffset>()
        val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
        AnimatedContent(
            targetState = screenKey,
            modifier =
                Modifier.padding(padding)
                    .consumeWindowInsets(padding)
                    .fillMaxSize()
                    .then(settingsEntryMotion()),
            transitionSpec = {
                val sign = if (targetState.isEmpty()) -direction else direction
                (slideInHorizontally(spatial) { sign * it / 5 } + fadeIn(effects)) togetherWith
                    (slideOutHorizontally(spatial) { -sign * it / 8 } + fadeOut(effects))
            },
            label = "settings_screen",
        ) { key ->
            holder.SaveableStateProvider(key) {
                val current = root.findPreference<PreferenceScreen>(key) ?: root
                val listState = rememberLazyListState()
                val entries = remember(current, query, revision) { rows(current, query.trim()) }
                val searchResults =
                    remember(searchIndex, query) {
                        if (overview) searchSettings(searchIndex.orEmpty(), query) else emptyList()
                    }
                LaunchedEffect(query) { if (query.isNotBlank()) listState.scrollToItem(0) }
                var highlighted by rememberSaveable { mutableStateOf(false) }
                var showHighlight by remember { mutableStateOf(false) }
                LaunchedEffect(highlight, current, entries.size) {
                    if (highlight != null && !highlighted) {
                        val index = entries.indexOfFirst { it.preference.key == highlight }
                        if (index >= 0) {
                            listState.scrollToItem(
                                index + if (overview && query.isEmpty()) 2 else 1
                            )
                            highlighted = true
                            showHighlight = true
                            delay(1800)
                            showHighlight = false
                        }
                    }
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.widthIn(max = 840.dp).fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        item("search") {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                                shape = CircleShape,
                                singleLine = true,
                                placeholder = { Text(stringResource(R.string.label_search)) },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                                trailingIcon = {
                                    if (query.isNotEmpty())
                                        IconButton(
                                            shapes = IconButtonDefaults.shapes(),
                                            onClick = { query = "" },
                                        ) {
                                            Icon(
                                                Icons.Rounded.Close,
                                                stringResource(
                                                    R.string
                                                        .widgets_full_sheet_cancel_button_description
                                                ),
                                            )
                                        }
                                },
                                colors =
                                    OutlinedTextFieldDefaults.colors(
                                        unfocusedContainerColor =
                                            MaterialTheme.colorScheme.surfaceContainer,
                                        unfocusedBorderColor =
                                            androidx.compose.ui.graphics.Color.Transparent,
                                    ),
                            )
                        }
                        if (overview && query.isEmpty())
                            item("preview") { HomeOverview(Modifier.padding(bottom = 20.dp)) }
                        if (overview && query.isNotBlank() && searchIndex == null) {
                            item("search_loading") {
                                Box(
                                    Modifier.fillMaxWidth().padding(32.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadingIndicator()
                                }
                            }
                        }
                        itemsIndexed(
                            searchResults,
                            key = { _, result -> result.id },
                            contentType = { _, _ -> "search_result" },
                        ) { _, result ->
                            SettingsSearchResultRow(
                                result,
                                onClick = {
                                    try {
                                        result.open(context)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(
                                                context,
                                                R.string.activity_not_found,
                                                Toast.LENGTH_SHORT,
                                            )
                                            .show()
                                    }
                                },
                                modifier =
                                    Modifier.padding(bottom = 6.dp)
                                        .animateItem(
                                            fadeInSpec = effects,
                                            fadeOutSpec = effects,
                                            placementSpec = spatial,
                                        ),
                            )
                        }
                        itemsIndexed(
                            entries,
                            key = { _, row -> row.id },
                            contentType = { _, row ->
                                if (row.heading) "heading" else row.preference.javaClass.name
                            },
                        ) { _, row ->
                            if (row.heading) {
                                Text(
                                    row.preference.title?.toString().orEmpty(),
                                    Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp)
                                        .semantics { heading() },
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                )
                            } else {
                                SettingsPreference(
                                    row = row,
                                    overview = overview,
                                    highlighted = showHighlight && row.preference.key == highlight,
                                    revision = revision,
                                    onClick = { open(row.preference) },
                                    onChanged = { revision++ },
                                    modifier =
                                        Modifier.animateItem(
                                            fadeInSpec = effects,
                                            fadeOutSpec = effects,
                                            placementSpec = spatial,
                                        ),
                                )
                            }
                        }
                        if (
                            entries.isEmpty() &&
                                searchResults.isEmpty() &&
                                (!overview || query.isBlank() || searchIndex != null)
                        )
                            item("empty") {
                                Column(
                                    Modifier.fillMaxWidth().padding(40.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Text(
                                        entries.size.toString(),
                                        style = MaterialTheme.typography.headlineLargeEmphasized,
                                    )
                                    Text(
                                        stringResource(R.string.all_apps_search_results),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                    }
                }
            }
        }
    }
    fragment.dialogPreference?.let { preference ->
        SettingsDialog(
            preference,
            onDismiss = {
                fragment.dialogPreference = null
                revision++
            },
        )
    }
}
