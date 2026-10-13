package com.android.launcher3.search.universal;

import android.app.appsearch.AppSearchManager;
import android.app.appsearch.AppSearchResult;
import android.app.appsearch.GenericDocument;
import android.app.appsearch.GlobalSearchSession;
import android.app.appsearch.SearchResult;
import android.app.appsearch.SearchResults;
import android.app.appsearch.SearchSpec;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;

import com.android.launcher3.LauncherPrefs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/** Readable because the home role holds READ_HOME_APP_SEARCH_DATA. */
public class AppSearchProvider implements SearchProvider {

    private static final long TIMEOUT_MS = 800;
    private static final String PLATFORM_PACKAGE = "android";
    private static final String[] TITLE_PROPERTIES =
            {"name", "title", "subject", "displayName", "label"};
    private static final String[] SUBTITLE_PROPERTIES =
            {"description", "snippet", "text", "body", "summary"};
    private static final String[] LINK_PROPERTIES = {"url", "uri", "deepLink", "webUrl"};

    @Override
    public int getSource() {
        return UniversalSearchResult.SOURCE_APP_CONTENT;
    }

    @Override
    public boolean isEnabled(Context context) {
        return LauncherPrefs.SEARCH_APP_CONTENT.get(context);
    }

    @Override
    public List<UniversalSearchResult> query(Context context, String query, int max) {
        List<UniversalSearchResult> out = new ArrayList<>();
        if (query.trim().length() < 2) {
            return out;
        }
        List<SearchResult> page;
        try (GlobalSearchSession session = session(context)) {
            if (session == null) {
                return out;
            }
            SearchSpec spec = new SearchSpec.Builder()
                    .setTermMatch(SearchSpec.TERM_MATCH_PREFIX)
                    .setResultCountPerPage(max * 2)
                    .build();
            try (SearchResults results = session.search(query.trim(), spec)) {
                CompletableFuture<List<SearchResult>> future = new CompletableFuture<>();
                results.getNextPage(Runnable::run, result -> future.complete(
                        result.isSuccess() ? result.getResultValue() : null));
                page = await(future);
            }
        }
        if (page == null) {
            return out;
        }
        PackageManager pm = context.getPackageManager();
        Map<String, CharSequence> labels = new HashMap<>();
        String lower = query.trim().toLowerCase();
        for (SearchResult hit : page) {
            if (out.size() >= max) {
                break;
            }
            String pkg = hit.getPackageName();
            if (PLATFORM_PACKAGE.equals(pkg) || !SearchProvider.isPackageSearchable(context, pkg)) {
                continue;
            }
            GenericDocument doc = hit.getGenericDocument();
            String title = firstString(doc, TITLE_PROPERTIES);
            Intent intent = intentFor(pm, pkg, firstString(doc, LINK_PROPERTIES));
            if (TextUtils.isEmpty(title) || intent == null) {
                continue;
            }
            CharSequence app = labels.computeIfAbsent(pkg, p -> {
                try {
                    return pm.getApplicationLabel(pm.getApplicationInfo(p, 0));
                } catch (PackageManager.NameNotFoundException e) {
                    return p;
                }
            });
            String detail = firstString(doc, SUBTITLE_PROPERTIES);
            UniversalSearchResult result = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_APP_CONTENT,
                    pkg + "/" + doc.getNamespace() + "/" + doc.getId(),
                    title,
                    TextUtils.isEmpty(detail) ? app : app + " · " + detail,
                    intent, Process.myUserHandle(),
                    Math.max(FuzzyMatcher.score(lower, title), FuzzyMatcher.WORD / 2));
            result.packageName = pkg;
            try {
                result.icon = pm.getApplicationIcon(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                result.icon = null;
            }
            out.add(result);
        }
        return out;
    }

    private static String firstString(GenericDocument doc, String[] properties) {
        for (String property : properties) {
            try {
                String value = doc.getPropertyString(property);
                if (!TextUtils.isEmpty(value)) {
                    return value;
                }
            } catch (RuntimeException e) {
            }
        }
        return null;
    }

    private static Intent intentFor(PackageManager pm, String pkg, String link) {
        if (!TextUtils.isEmpty(link)) {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(pkg)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(view, 0) != null) {
                return view;
            }
        }
        Intent launch = pm.getLaunchIntentForPackage(pkg);
        return launch == null ? null : launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static GlobalSearchSession session(Context context) {
        AppSearchManager manager = context.getSystemService(AppSearchManager.class);
        if (manager == null) {
            return null;
        }
        CompletableFuture<GlobalSearchSession> future = new CompletableFuture<>();
        manager.createGlobalSearchSession(Runnable::run,
                (AppSearchResult<GlobalSearchSession> result) -> {
                    GlobalSearchSession session = result.isSuccess()
                            ? result.getResultValue() : null;
                    if (!future.complete(session) && session != null) {
                        session.close();
                    }
                });
        return await(future);
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
        }
        if (!future.cancel(false) && !future.isCompletedExceptionally()) {
            return future.getNow(null);
        }
        return null;
    }
}
