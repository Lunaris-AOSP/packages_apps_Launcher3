/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.android.launcher3.settings.compose

import android.content.*
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.R
import com.android.launcher3.customization.IconDatabase
import com.android.launcher3.icons.IconProvider
import com.android.launcher3.lineage.trust.TrustAppsActivity
import com.android.launcher3.lineage.trust.db.TrustComponent
import com.android.launcher3.settings.ComposeSettingsActivity
import com.android.launcher3.settings.PinnedAppsActivity
import com.android.launcher3.util.PackageManagerHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SettingsAppScreens {
    @JvmStatic
    fun showPinnedApps(activity: ComposeSettingsActivity) {
        activity.setContent { HomeSettingsTheme { PinnedAppsScreen(activity::finish) } }
    }

    @JvmStatic
    fun showIconPacks(activity: ComposeSettingsActivity) {
        activity.setContent { HomeSettingsTheme { IconPacksScreen(activity::finish) } }
    }
}

private data class AppChoice(val key: String, val label: String, val icon: Drawable?)

@Composable
private fun AppListScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.(String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets =
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = {
            SettingsTopAppBar(
                title = title,
                onBack = onBack,
                actions = actions,
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Box(
            Modifier.fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .then(settingsEntryMotion()),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                Modifier.widthIn(max = 840.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item("search") {
                    OutlinedTextField(
                        query,
                        { query = it },
                        singleLine = true,
                        shape = CircleShape,
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
                                            R.string.widgets_full_sheet_cancel_button_description
                                        ),
                                    )
                                }
                        },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    )
                }
                content(query.trim())
            }
        }
    }
}

@Composable
private fun packageRevision(): Int {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var revision by remember { mutableIntStateOf(0) }
    DisposableEffect(context, lifecycle) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    revision++
                }
            }
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addDataScheme("package")
            }
        context.registerReceiver(receiver, filter)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) revision++
        }
        lifecycle.addObserver(observer)
        onDispose {
            context.unregisterReceiver(receiver)
            lifecycle.removeObserver(observer)
        }
    }
    return revision
}

@Composable
private fun PinnedAppsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { LauncherPrefs.getPrefs(context) }
    val revision = packageRevision()
    var pinned by remember {
        mutableStateOf(
            prefs.getStringSet(PinnedAppsActivity.PREF_PINNED_APPS_LIST, emptySet())!!.toSet()
        )
    }
    LaunchedEffect(revision) {
        pinned =
            prefs
                .getStringSet(PinnedAppsActivity.PREF_PINNED_APPS_LIST, emptySet())
                .orEmpty()
                .toSet()
    }
    val apps by
        produceState<List<AppChoice>?>(null, revision) {
            value =
                withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    pm.queryIntentActivities(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                            0,
                        )
                        .map {
                            AppChoice(
                                ComponentName(it.activityInfo.packageName, it.activityInfo.name)
                                    .flattenToString(),
                                it.loadLabel(pm).toString(),
                                it.loadIcon(pm),
                            )
                        }
                        .distinctBy { it.key }
                        .sortedBy { it.label.lowercase() }
                }
        }
    AppListScaffold(stringResource(R.string.pref_pinned_apps_list_title), onBack) { query ->
        val filtered = apps?.filter { it.label.contains(query, true) }
        if (filtered == null) item("loading") { AppLoading() }
        else {
            if (filtered.isEmpty()) item("empty") { EmptyApps(query) }
            items(filtered, key = { it.key }) { app ->
                val checked = app.key in pinned
                AppChoiceRow(
                    app,
                    checked,
                    Modifier.animateItem().toggleable(checked, role = Role.Checkbox) {
                        pinned = if (it) pinned + app.key else pinned - app.key
                        prefs
                            .edit()
                            .putStringSet(PinnedAppsActivity.PREF_PINNED_APPS_LIST, pinned.toSet())
                            .apply()
                    },
                ) {
                    Checkbox(checked, onCheckedChange = null)
                }
            }
        }
    }
}

@Composable
private fun IconPacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision = packageRevision()
    var selected by remember { mutableStateOf(IconDatabase.getGlobal(context)) }
    val packs by
        produceState<List<AppChoice>?>(null, revision) {
            selected = IconDatabase.getGlobal(context)
            value =
                withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    listOf(
                        AppChoice(
                            IconDatabase.VALUE_DEFAULT,
                            context.getString(R.string.icon_pack_default_label),
                            null,
                        )
                    ) +
                        iconPackActions
                            .flatMap {
                                pm.queryIntentActivities(
                                    Intent(it),
                                    android.content.pm.PackageManager.GET_META_DATA,
                                )
                            }
                            .map {
                                AppChoice(
                                    it.activityInfo.packageName,
                                    it.loadLabel(pm).toString(),
                                    it.loadIcon(pm),
                                )
                            }
                            .distinctBy { it.key }
                            .sortedBy { it.label.lowercase() }
                }
        }
    val preview by
        produceState<List<Drawable>>(emptyList(), selected, revision) {
            value =
                withContext(Dispatchers.IO) {
                    val pm = context.packageManager
                    pm.queryIntentActivities(
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),
                            0,
                        )
                        .sortedBy { it.loadLabel(pm).toString() }
                        .take(8)
                        .map {
                            IconProvider.INSTANCE.get(context)
                                .getIcon(
                                    it.activityInfo,
                                    context.resources.displayMetrics.densityDpi,
                                )
                        }
                }
        }
    AppListScaffold(
        stringResource(R.string.icon_pack_title),
        onBack,
        actions = {
            IconButton(
                shapes = IconButtonDefaults.shapes(),
                onClick = {
                    val intent =
                        PackageManagerHelper.getMarketSearchIntent(
                            context,
                            context.getString(R.string.icon_pack_title),
                        )
                    try {
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(context, R.string.icon_pack_no_market, Toast.LENGTH_SHORT)
                            .show()
                    }
                },
            ) {
                Icon(Icons.Rounded.Add, stringResource(R.string.icon_pack_add))
            }
        },
    ) { query ->
        if (query.isEmpty() && preview.isNotEmpty())
            item("preview") {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(bottom = 16.dp),
                ) {
                    LazyRow(
                        contentPadding = PaddingValues(24.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(preview) { AppIcon(it) }
                    }
                }
            }
        val filtered = packs?.filter { it.label.contains(query, true) }
        if (filtered == null) item("loading") { AppLoading() }
        else {
            if (filtered.isEmpty()) item("empty") { EmptyApps(query) }
            items(filtered, key = { it.key }) { app ->
                AppChoiceRow(
                    app,
                    selected == app.key,
                    Modifier.animateItem()
                        .selectable(
                            selected == app.key,
                            role = Role.RadioButton,
                            onClick = {
                                IconDatabase.setGlobal(context, app.key)
                                selected = app.key
                            },
                        ),
                ) {
                    val intent =
                        remember(app.key, revision) {
                            context.packageManager.getLaunchIntentForPackage(app.key)
                        }
                    if (intent != null)
                        IconButton(
                            shapes = IconButtonDefaults.shapes(),
                            onClick = {
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {}
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                stringResource(R.string.search_action_open, app.label),
                            )
                        }
                    RadioButton(selected == app.key, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun AppChoiceRow(
    app: AppChoice,
    selected: Boolean,
    modifier: Modifier,
    trailing: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color =
            if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = 88.dp)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (app.icon != null) AppIcon(app.icon)
            else Icon(Icons.Rounded.Palette, null, Modifier.size(48.dp))
            Text(
                app.label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            trailing()
        }
    }
}

@Composable
private fun AppIcon(drawable: Drawable) {
    val bitmap = remember(drawable) { drawable.toBitmap(128, 128).asImageBitmap() }
    Image(bitmap, null, Modifier.size(48.dp))
}

@Composable
private fun AppLoading() {
    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
        LoadingIndicator()
    }
}

@Composable
private fun EmptyApps(query: String) {
    Text(
        stringResource(R.string.all_apps_no_search_results, query),
        Modifier.padding(24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val iconPackActions =
    listOf(
        "com.fede.launcher.THEME_ICONPACK",
        "com.anddoes.launcher.THEME",
        "com.novalauncher.THEME",
        "com.teslacoilsw.launcher.THEME",
        "com.gau.go.launcherex.theme",
        "org.adw.launcher.THEMES",
        "org.adw.launcher.icons.ACTION_PICK_ICON",
        "net.oneplus.launcher.icons.ACTION_PICK_ICON",
    )

class TrustAppsCompose(private val activity: TrustAppsActivity, private val secure: Boolean) {
    private data class Entry(
        val component: TrustComponent,
        val hidden: Boolean,
        val protected: Boolean,
    )

    private var entries by mutableStateOf<List<Entry>?>(null)
    private var help by mutableStateOf(false)

    init {
        activity.setContent { HomeSettingsTheme { Content() } }
    }

    fun setItems(items: List<TrustComponent>) {
        entries = items.distinctBy { it.packageName }.map { Entry(it, it.isHidden, it.isProtected) }
    }

    fun showHelp() {
        help = true
    }

    @Composable
    private fun Content() {
        var helpVisible by rememberSaveable { mutableStateOf(help) }
        LaunchedEffect(help) {
            if (help) {
                helpVisible = true
                help = false
            }
        }
        AppListScaffold(
            stringResource(R.string.trust_apps_manager_name),
            activity::finish,
            actions = {
                IconButton(shapes = IconButtonDefaults.shapes(), onClick = { helpVisible = true }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.HelpOutline,
                        stringResource(R.string.trust_apps_help),
                    )
                }
            },
        ) { query ->
            val filtered = entries?.filter { it.component.label.contains(query, true) }
            if (filtered == null) item("loading") { AppLoading() }
            else {
                if (filtered.isEmpty()) item("empty") { EmptyApps(query) }
                items(filtered, key = { it.component.packageName }) { entry ->
                    val component = entry.component
                    AppChoiceRow(
                        AppChoice(component.packageName, component.label, component.icon),
                        entry.hidden || entry.protected,
                        Modifier.animateItem(),
                    ) {
                        IconToggleButton(
                            shapes = IconButtonDefaults.toggleableShapes(),
                            checked = entry.hidden,
                            onCheckedChange = {
                                component.invertVisibility()
                                activity.onHiddenItemChanged(component)
                                setItems(entries.orEmpty().map { it.component })
                            },
                        ) {
                            Icon(
                                painterResource(
                                    if (entry.hidden) R.drawable.ic_hidden_locked
                                    else R.drawable.ic_hidden_unlocked
                                ),
                                stringResource(R.string.trust_apps_info_hidden),
                            )
                        }
                        if (secure)
                            IconToggleButton(
                                shapes = IconButtonDefaults.toggleableShapes(),
                                checked = entry.protected,
                                onCheckedChange = {
                                    component.invertProtection()
                                    activity.onProtectedItemChanged(component)
                                    setItems(entries.orEmpty().map { it.component })
                                },
                            ) {
                                Icon(
                                    painterResource(
                                        if (entry.protected) R.drawable.ic_protected_locked
                                        else R.drawable.ic_protected_unlocked
                                    ),
                                    stringResource(R.string.trust_apps_info_protected),
                                )
                            }
                    }
                }
            }
        }
        if (helpVisible)
            AlertDialog(
                onDismissRequest = { helpVisible = false },
                title = {
                    Text(
                        stringResource(R.string.trust_apps_help),
                        style = MaterialTheme.typography.headlineSmallEmphasized,
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(stringResource(R.string.trust_apps_info_hidden))
                        Text(stringResource(R.string.trust_apps_info_protected))
                        if (!secure) Text(stringResource(R.string.trust_apps_no_lock_error))
                    }
                },
                confirmButton = {
                    TextButton(onClick = { helpVisible = false }) {
                        Text(stringResource(android.R.string.ok))
                    }
                },
            )
    }
}
