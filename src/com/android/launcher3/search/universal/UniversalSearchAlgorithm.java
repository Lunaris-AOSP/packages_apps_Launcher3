package com.android.launcher3.search.universal;

import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_EMPTY_SEARCH;

import android.content.Context;
import android.os.Handler;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.search.SearchAlgorithm;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.LooperExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class UniversalSearchAlgorithm implements SearchAlgorithm<AdapterItem> {

    private static final int MAX_PER_SOURCE = 4;

    private static final int[] SOURCE_ORDER = {
            UniversalSearchResult.SOURCE_CALCULATOR,
            UniversalSearchResult.SOURCE_CONVERSATION,
            UniversalSearchResult.SOURCE_SHORTCUT,
            UniversalSearchResult.SOURCE_CONTACT,
            UniversalSearchResult.SOURCE_QS_TILE,
            UniversalSearchResult.SOURCE_SETTING,
            UniversalSearchResult.SOURCE_FILE,
            UniversalSearchResult.SOURCE_EVENT,
            UniversalSearchResult.SOURCE_WEB,
    };

    private final Context mContext;
    private final LauncherAppState mAppState;
    private final Handler mResultHandler;
    private final boolean mAppLibrary;
    private final List<SearchProvider> mProviders;
    private final AtomicInteger mRequest = new AtomicInteger();

    public UniversalSearchAlgorithm(Context context, LooperExecutor uiExecutor) {
        mContext = context.getApplicationContext();
        mAppState = LauncherAppState.getInstance(context);
        mResultHandler = new Handler(uiExecutor.getLooper());
        mAppLibrary = false;
        mProviders = List.of(new CalculatorProvider(), new ShortcutProvider(),
                new ContactProvider(), new QsTileProvider(), new SettingProvider(),
                new MediaProvider(), new CalendarProvider(), new ActionProvider());
    }

    @Override
    public void cancel(boolean interruptActiveRequests) {
        if (interruptActiveRequests) {
            mRequest.incrementAndGet();
            mResultHandler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void doSearch(String query, SearchCallback<AdapterItem> callback) {
        final int token = mRequest.incrementAndGet();
        mAppState.getModel().enqueueModelUpdateTask((taskController, dataModel, apps) -> {
            ArrayList<AdapterItem> appItems =
                    DefaultAppSearchAlgorithm.getTitleMatchResult(mContext, apps.data, query);
            boolean anyProvider = hasEnabledProvider();
            if (!anyProvider) {
                publish(token, query, withEmptyMessage(appItems, query), callback);
                return;
            }
            publish(token, query, new ArrayList<>(appItems), callback);
            Executors.UI_HELPER_EXECUTOR.execute(() -> {
                if (token != mRequest.get()) {
                    return;
                }
                ArrayList<AdapterItem> merged = new ArrayList<>(appItems);
                merged.addAll(collect(query));
                publish(token, query, withEmptyMessage(merged, query), callback);
            });
        });
    }

    private boolean hasEnabledProvider() {
        for (SearchProvider provider : mProviders) {
            if (provider.isEnabled(mContext)) {
                return true;
            }
        }
        return false;
    }

    private List<AdapterItem> collect(String query) {
        List<UniversalSearchResult> all = new ArrayList<>();
        for (SearchProvider provider : mProviders) {
            if (!provider.isEnabled(mContext)) {
                continue;
            }
            try {
                all.addAll(provider.query(mContext, query, MAX_PER_SOURCE * 2));
            } catch (RuntimeException e) {
                // A misbehaving provider must not take the whole search down.
            }
        }
        List<AdapterItem> items = new ArrayList<>();
        for (int source : SOURCE_ORDER) {
            List<UniversalSearchResult> forSource = new ArrayList<>();
            for (UniversalSearchResult result : all) {
                if (result.source == source) {
                    forSource.add(result);
                }
            }
            if (forSource.isEmpty()) {
                continue;
            }
            forSource.sort(Comparator.comparingInt((UniversalSearchResult r) -> -r.score)
                    .thenComparing(r -> r.title.toString()));
            items.add(AdapterItem.asSearchSection(source));
            for (int i = 0; i < forSource.size() && i < MAX_PER_SOURCE; i++) {
                items.add(AdapterItem.asSearchResult(forSource.get(i)));
            }
        }
        return items;
    }

    private void publish(int token, String query, ArrayList<AdapterItem> items,
            SearchCallback<AdapterItem> callback) {
        if (token != mRequest.get()) {
            return;
        }
        mResultHandler.post(() -> {
            if (token == mRequest.get()) {
                callback.onSearchResult(query, items);
            }
        });
    }

    private ArrayList<AdapterItem> withEmptyMessage(ArrayList<AdapterItem> items, String query) {
        if (!items.isEmpty() || (mAppLibrary && query.isEmpty())) {
            return items;
        }
        AdapterItem item = new AdapterItem(VIEW_TYPE_EMPTY_SEARCH);
        AppInfo placeHolder = new AppInfo();
        placeHolder.title = query;
        item.itemInfo = placeHolder;
        items.add(item);
        return items;
    }
}
