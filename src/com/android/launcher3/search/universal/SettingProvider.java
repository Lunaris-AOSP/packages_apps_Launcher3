package com.android.launcher3.search.universal;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.search.StringMatcherUtility;

import java.util.ArrayList;
import java.util.List;

public class SettingProvider implements SearchProvider {

    private static final Uri INDEXABLES_RAW =
            Uri.parse("content://com.android.settings/settings/indexables_raw");
    private static final String COLUMN_TITLE = "title";
    private static final String COLUMN_SCREEN_TITLE = "screenTitle";
    private static final String COLUMN_KEY = "key";
    private static final String COLUMN_ACTION = "intentAction";
    private static final String COLUMN_TARGET_PACKAGE = "intentTargetPackage";
    private static final String COLUMN_TARGET_CLASS = "intentTargetClass";

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_SETTING;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_SETTINGS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.isEmpty()) {
            return out;
        }
        StringMatcherUtility.StringMatcher matcher =
                StringMatcherUtility.StringMatcher.getInstance();
        String lower = query.toLowerCase();
        try (Cursor c = context.getContentResolver().query(
                INDEXABLES_RAW, null, null, null, null)) {
            if (c == null) {
                return out;
            }
            int titleIndex = c.getColumnIndex(COLUMN_TITLE);
            int screenIndex = c.getColumnIndex(COLUMN_SCREEN_TITLE);
            int keyIndex = c.getColumnIndex(COLUMN_KEY);
            int actionIndex = c.getColumnIndex(COLUMN_ACTION);
            int pkgIndex = c.getColumnIndex(COLUMN_TARGET_PACKAGE);
            int classIndex = c.getColumnIndex(COLUMN_TARGET_CLASS);
            if (titleIndex < 0) {
                return out;
            }
            while (c.moveToNext() && out.size() < max) {
                String title = c.getString(titleIndex);
                if (TextUtils.isEmpty(title)
                        || !StringMatcherUtility.matches(lower, title, matcher)) {
                    continue;
                }
                Intent intent = buildIntent(c, actionIndex, pkgIndex, classIndex);
                if (intent == null) {
                    continue;
                }
                out.add(new UniversalSearchResult(
                        UniversalSearchResult.SOURCE_SETTING,
                        keyIndex < 0 ? title : c.getString(keyIndex),
                        title,
                        screenIndex < 0 ? null : c.getString(screenIndex),
                        intent,
                        Process.myUserHandle(),
                        ShortcutProvider.score(lower, title)));
            }
        } catch (SecurityException | IllegalArgumentException e) {
            return out;
        }
        return out;
    }

    private static Intent buildIntent(Cursor c, int actionIndex, int pkgIndex, int classIndex) {
        String action = actionIndex < 0 ? null : c.getString(actionIndex);
        String pkg = pkgIndex < 0 ? null : c.getString(pkgIndex);
        String cls = classIndex < 0 ? null : c.getString(classIndex);
        Intent intent = null;
        if (!TextUtils.isEmpty(action)) {
            intent = new Intent(action);
        } else if (!TextUtils.isEmpty(pkg) && !TextUtils.isEmpty(cls)) {
            intent = new Intent().setComponent(new ComponentName(pkg, cls));
        }
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return intent;
    }
}
