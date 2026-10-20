/*
 * Copyright (C) 2019-2024 The LineageOS Project
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
package com.android.launcher3.lineage.trust;

import static com.android.launcher3.lineage.trust.db.TrustComponent.Kind.HIDDEN;
import static com.android.launcher3.lineage.trust.db.TrustComponent.Kind.PROTECTED;

import android.os.Bundle;
import com.android.launcher3.AppFilter;
import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.Utilities;
import com.android.launcher3.lineage.trust.db.TrustComponent;
import com.android.launcher3.lineage.trust.db.TrustDatabaseHelper;
import com.android.launcher3.settings.ComposeSettingsActivity;
import com.android.launcher3.settings.compose.TrustAppsCompose;
import java.util.List;

public class TrustAppsActivity extends ComposeSettingsActivity implements
        TrustAppsAdapter.Listener, LoadTrustComponentsTask.Callback, UpdateItemTask.UpdateCallback {
    private static final String KEY_TRUST_ONBOARDING = "pref_trust_onboarding";
    private TrustDatabaseHelper mDbHelper;
    private TrustAppsCompose mUi;
    private LoadTrustComponentsTask mLoadTask;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mUi = new TrustAppsCompose(this, Utilities.hasSecureKeyguard(this));
        mDbHelper = TrustDatabaseHelper.getInstance(this);
        if (!LauncherPrefs.getPrefs(this).getBoolean(KEY_TRUST_ONBOARDING, false)) {
            LauncherPrefs.getPrefs(this).edit().putBoolean(KEY_TRUST_ONBOARDING, true).apply();
            mUi.showHelp();
        }
        mLoadTask = new LoadTrustComponentsTask(mDbHelper, getPackageManager(),
                new AppFilter(this), this);
        mLoadTask.execute();
    }

    @Override
    public void onHiddenItemChanged(TrustComponent component) {
        new UpdateItemTask(mDbHelper, this, HIDDEN).execute(component);
    }

    @Override
    public void onProtectedItemChanged(TrustComponent component) {
        new UpdateItemTask(mDbHelper, this, PROTECTED).execute(component);
    }

    @Override
    public void onUpdated(boolean result) {
        if (result) LauncherAppState.INSTANCE.get(this).getModel().reloadIfActive();
    }

    @Override
    public void onLoadListProgress(int progress) { }

    @Override
    public void onLoadCompleted(List<TrustComponent> result) {
        if (!isFinishing() && !isDestroyed()) mUi.setItems(result);
    }

    @Override
    protected void onDestroy() {
        if (mLoadTask != null) mLoadTask.cancel(true);
        super.onDestroy();
    }
}
