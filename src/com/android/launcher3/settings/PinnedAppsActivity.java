package com.android.launcher3.settings;

import android.os.Bundle;
import com.android.launcher3.settings.compose.SettingsAppScreens;

public class PinnedAppsActivity extends ComposeSettingsActivity {
    public static final String PREF_PINNED_APPS_LIST = "pref_pinned_apps_list";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsAppScreens.showPinnedApps(this);
    }
}
