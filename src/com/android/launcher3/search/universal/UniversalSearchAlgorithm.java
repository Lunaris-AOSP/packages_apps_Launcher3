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
    private static final long DEBOUNCE_MS = 60;
    // Past this, show what has arrived and fold slower providers in when they finish.
    private static final long PROVIDER_TIMEOUT_MS = 150;

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
    private final Handler mWorker;
    private final boolean mAppLibrary;
    private final List<SearchProvider> mProviders;
    private final AtomicInteger mRequest = new AtomicInteger();

    public UniversalSearchAlgorithm(Context context, LooperExecutor uiExecutor) {
        mContext = context.getApplicationContext();
        mAppState = LauncherAppState.getInstance(context);
        mResultHandler = new Handler(uiExecutor.getLooper());
        mWorker = new Handler(Executors.UI_HELPER_EXECUTOR.getLooper());
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
            mWorker.removeCallbacksAndMessages(null);
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
            mWorker.postDelayed(() -> startProviders(token, query, appItems, callback),
                    DEBOUNCE_MS);
        });
    }

    private void startProviders(int token, String query, ArrayList<AdapterItem> appItems,
            SearchCallback<AdapterItem> callback) {
        if (token != mRequest.get()) {
            return;
        }
        List<SearchProvider> enabled = new ArrayList<>();
        for (SearchProvider provider : mProviders) {
            if (provider.isEnabled(mContext)) {
                enabled.add(provider);
            }
        }
        Pass pass = new Pass(token, query, appItems, callback, enabled.size());
        for (int i = 0; i < enabled.size(); i++) {
            int slot = i;
            SearchProvider provider = enabled.get(i);
            Executors.THREAD_POOL_EXECUTOR.execute(() -> {
                List<UniversalSearchResult> results = token == mRequest.get()
                        ? queryProvider(provider, query) : List.of();
                mWorker.post(() -> pass.onProviderDone(slot, results));
            });
        }
        mWorker.postDelayed(pass::onTimeout, PROVIDER_TIMEOUT_MS);
    }

    private List<UniversalSearchResult> queryProvider(SearchProvider provider, String query) {
        try {
            return provider.query(mContext, query, MAX_PER_SOURCE * 2);
        } catch (RuntimeException e) {
            // A misbehaving provider must not take the whole search down.
            return List.of();
        }
    }

    private boolean hasEnabledProvider() {
        for (SearchProvider provider : mProviders) {
            if (provider.isEnabled(mContext)) {
                return true;
            }
        }
        return false;
    }

    private List<AdapterItem> group(List<UniversalSearchResult> all) {
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

    /** Only touched on {@link #mWorker}. */
    private class Pass {
        private final int mToken;
        private final String mQuery;
        private final ArrayList<AdapterItem> mAppItems;
        private final SearchCallback<AdapterItem> mCallback;
        private final List<UniversalSearchResult>[] mResults;
        private int mPending;
        private boolean mTimedOut;

        @SuppressWarnings("unchecked")
        Pass(int token, String query, ArrayList<AdapterItem> appItems,
                SearchCallback<AdapterItem> callback, int providers) {
            mToken = token;
            mQuery = query;
            mAppItems = appItems;
            mCallback = callback;
            mResults = new List[providers];
            mPending = providers;
            if (providers == 0) {
                publishResults();
            }
        }

        void onProviderDone(int slot, List<UniversalSearchResult> results) {
            mResults[slot] = results;
            mPending--;
            if (mPending == 0 || mTimedOut) {
                publishResults();
            }
        }

        void onTimeout() {
            if (mPending > 0) {
                mTimedOut = true;
                publishResults();
            }
        }

        private void publishResults() {
            if (mToken != mRequest.get()) {
                return;
            }
            List<UniversalSearchResult> all = new ArrayList<>();
            for (List<UniversalSearchResult> results : mResults) {
                if (results != null) {
                    all.addAll(results);
                }
            }
            ArrayList<AdapterItem> merged = new ArrayList<>(mAppItems);
            merged.addAll(group(all));
            publish(mToken, mQuery, withEmptyMessage(merged, mQuery), mCallback);
        }
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
