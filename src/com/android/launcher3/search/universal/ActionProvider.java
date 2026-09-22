package com.android.launcher3.search.universal;

import android.app.SearchManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Process;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ActionProvider implements SearchProvider {

    private static final int MAX_APPS = 3;

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_WEB;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_ACTIONS.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.trim().isEmpty()) {
            return out;
        }
        Intent web = new Intent(Intent.ACTION_WEB_SEARCH)
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        out.add(new UniversalSearchResult(
                UniversalSearchResult.SOURCE_WEB, "web",
                context.getString(R.string.search_action_web, query), null,
                web, Process.myUserHandle(), 10));

        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_SEARCH);
        List<ResolveInfo> handlers;
        try {
            handlers = pm.queryIntentActivities(probe, 0);
        } catch (RuntimeException e) {
            return out;
        }
        int added = 0;
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : handlers) {
            if (added >= MAX_APPS || out.size() >= max) {
                break;
            }
            if (info.activityInfo == null || !info.activityInfo.exported) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            if (pm.getLaunchIntentForPackage(pkg) == null) {
                continue;
            }
            CharSequence label = info.loadLabel(pm);
            if (label == null || pkg.contentEquals(label)) {
                continue;
            }
            Intent intent = new Intent(Intent.ACTION_SEARCH)
                    .setClassName(pkg, info.activityInfo.name)
                    .putExtra(SearchManager.QUERY, query)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_WEB, pkg,
                    context.getString(R.string.search_action_in_app, label), null,
                    intent, Process.myUserHandle(), 5);
            result.packageName = pkg;
            try {
                result.icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                result.icon = null;
            }
            out.add(result);
            added++;
        }
        return out;
    }
}
