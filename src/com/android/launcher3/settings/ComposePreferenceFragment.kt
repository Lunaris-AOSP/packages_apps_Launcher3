/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceScreen
import com.android.launcher3.R
import com.android.launcher3.settings.compose.HomeSettingsScreen
import com.android.launcher3.settings.compose.HomeSettingsTheme

abstract class ComposePreferenceFragment : Fragment() {
    private lateinit var manager: PreferenceManager
    private var attached = false
    internal var dialogPreference by mutableStateOf<Preference?>(null)
    internal var requestedScreen by mutableStateOf<String?>(null)

    fun getPreferenceManager(): PreferenceManager = manager

    fun getPreferenceScreen(): PreferenceScreen = checkNotNull(manager.preferenceScreen)

    fun <T : Preference> findPreference(key: CharSequence): T? = manager.findPreference(key)

    fun setPreferencesFromResource(resource: Int, rootKey: String?) {
        setPreferenceScreen(manager.inflateFromResource(requireContext(), resource, null))
        requestedScreen = rootKey
    }

    fun setPreferenceScreen(screen: PreferenceScreen) {
        if (manager.setPreferences(screen) && attached) screen.onAttached()
    }

    abstract fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requireContext().theme.applyStyle(R.style.HomeSettings_PreferenceTheme, false)
        manager = PreferenceManager(requireContext())
        manager.onDisplayPreferenceDialogListener =
            PreferenceManager.OnDisplayPreferenceDialogListener { dialogPreference = it }
        manager.onNavigateToScreenListener =
            PreferenceManager.OnNavigateToScreenListener { requestedScreen = it.key }
        manager.onPreferenceTreeClickListener =
            PreferenceManager.OnPreferenceTreeClickListener {
                val name = it.fragment
                if (name != null && !parentFragmentManager.isStateSaved) {
                    val fragment =
                        parentFragmentManager.fragmentFactory.instantiate(
                            requireContext().classLoader,
                            name,
                        )
                    fragment.arguments = it.extras
                    if (fragment is DialogFragment) fragment.show(parentFragmentManager, it.key)
                    else
                        parentFragmentManager
                            .beginTransaction()
                            .replace(R.id.content_frame, fragment)
                            .addToBackStack(it.key)
                            .commit()
                    true
                } else false
            }
        val root =
            arguments?.getString(SettingsActivity.EXTRA_FRAGMENT_ROOT_KEY)
                ?: requireActivity().intent.getStringExtra(SettingsActivity.EXTRA_FRAGMENT_ROOT_KEY)
        onCreatePreferences(savedInstanceState, root)
        if (savedInstanceState != null) requestedScreen = null
        savedInstanceState?.getBundle(STATE_PREFERENCES)?.let {
            getPreferenceScreen().restoreHierarchyState(it)
        }
        getPreferenceScreen().onAttached()
        attached = true
        savedInstanceState?.getString(STATE_DIALOG)?.let { dialogPreference = findPreference(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        state: Bundle?,
    ): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { HomeSettingsTheme { HomeSettingsScreen(this@ComposePreferenceFragment) } }
        }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val preferences = Bundle()
        getPreferenceScreen().saveHierarchyState(preferences)
        outState.putBundle(STATE_PREFERENCES, preferences)
        outState.putString(STATE_DIALOG, dialogPreference?.key)
    }

    override fun onDestroy() {
        getPreferenceScreen().onDetached()
        attached = false
        manager.onDisplayPreferenceDialogListener = null
        manager.onNavigateToScreenListener = null
        manager.onPreferenceTreeClickListener = null
        super.onDestroy()
    }

    private companion object {
        const val STATE_PREFERENCES = "compose_preferences"
        const val STATE_DIALOG = "compose_dialog"
    }
}
