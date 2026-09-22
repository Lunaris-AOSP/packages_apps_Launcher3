package com.android.launcher3.search.universal;

import static com.android.launcher3.allapps.BaseAllAppsAdapter.VIEW_TYPE_EMPTY_SEARCH;

import android.content.Context;
import android.os.Handler;

import com.android.launcher3.LauncherAppState;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.allapps.search.DefaultAppSearchAlgorithm;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.search.SearchAlgorithm;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.util.Executors;
import com.android.launcher3.util.LooperExecutor;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class UniversalSearchAlgorithm implements SearchAlgorithm<AdapterItem> {

    private static final int MAX_PER_PROVIDER = 12;
    private static final int MAX_GRID_APPS = 5;
    private static final long DEBOUNCE_MS = 60;
    // Past this, show what has arrived and fold slower providers in when they finish.
    private static final long PROVIDER_TIMEOUT_MS = 150;
    private static final int NO_FILTER = -1;

    private final Context mContext;
    private final LauncherAppState mAppState;
    private final Handler mResultHandler;
    private final Handler mWorker;
    private final boolean mAppLibrary;
    private final List<SearchProvider> mProviders;
    private final AtomicInteger mRequest = new AtomicInteger();
    private final Collator mCollator = Collator.getInstance();

    // Only touched on mWorker.
    private int mFilter = NO_FILTER;
    private final Set<Integer> mExpanded = new HashSet<>();
    private Pass mLastPass;

    private Consumer<String> mQueryHandler = q -> { };

    public UniversalSearchAlgorithm(Context context, LooperExecutor uiExecutor) {
        mContext = context.getApplicationContext();
        mAppState = LauncherAppState.getInstance(context);
        mResultHandler = new Handler(uiExecutor.getLooper());
        mWorker = new Handler(Executors.UI_HELPER_EXECUTOR.getLooper());
        mAppLibrary = false;
        mProviders = List.of(new CalculatorProvider(), new QuickActionProvider(),
                new ShortcutProvider(),
                new ContactProvider(), new QsTileProvider(), new SettingProvider(),
                new MediaProvider(), new CalendarProvider(), new AppSearchProvider(),
                new ActionProvider(), new WebSuggestionProvider());
    }

    public void setQueryHandler(Consumer<String> handler) {
        mQueryHandler = handler;
    }

    @Override
    public boolean hasEmptyQueryResults() {
        return true;
    }

    @Override
    public void cancel(boolean interruptActiveRequests) {
        if (interruptActiveRequests) {
            mRequest.incrementAndGet();
            mResultHandler.removeCallbacksAndMessages(null);
            mWorker.removeCallbacksAndMessages(null);
            mWorker.post(this::resetRefinements);
        }
    }

    private void resetRefinements() {
        mFilter = NO_FILTER;
        mExpanded.clear();
        mLastPass = null;
    }

    @Override
    public void doSearch(String query, SearchCallback<AdapterItem> callback) {
        final int token = mRequest.incrementAndGet();
        SearchHistory.setCurrentQuery(query);
        mAppState.getModel().enqueueModelUpdateTask((taskController, dataModel, apps) -> {
            AppMatches appMatches = matchApps(apps.data, query);
            if (query.trim().isEmpty()) {
                mWorker.post(() -> publishEmptyQuery(token, query, appMatches.items, callback));
                return;
            }
            if (!hasEnabledProvider()) {
                publish(token, query, withEmptyMessage(appMatches.items, query), callback);
                return;
            }
            publish(token, query, new ArrayList<>(appMatches.items), callback);
            mWorker.postDelayed(() -> startProviders(token, query, appMatches, callback),
                    DEBOUNCE_MS);
        });
    }

    private record AppMatches(ArrayList<AdapterItem> items, int topScore) {}

    private AppMatches matchApps(List<AppInfo> apps, String query) {
        String lower = query.trim().toLowerCase(Locale.getDefault());
        if (lower.isEmpty()) {
            return new AppMatches(mAppLibrary
                    ? DefaultAppSearchAlgorithm.getTitleMatchResult(mContext, apps, "")
                    : new ArrayList<>(), 0);
        }
        record Scored(AppInfo app, int match, int score) {}
        List<Scored> scored = new ArrayList<>();
        boolean anyWordMatch = false;
        for (AppInfo app : apps) {
            if (app.title == null) {
                continue;
            }
            int match = FuzzyMatcher.score(lower, app.title.toString());
            if (match == 0) {
                continue;
            }
            anyWordMatch |= match >= FuzzyMatcher.WORD;
            scored.add(new Scored(app, match, match + SearchHistory.boost(mContext,
                    SearchHistory.appKey(app.getTargetComponent(), app.user))));
        }
        if (anyWordMatch) {
            // Loose matches are for when nothing matched properly, not to pad real results.
            scored.removeIf(s -> s.match < FuzzyMatcher.WORD);
        }
        scored.sort((a, b) -> a.score != b.score ? b.score - a.score
                : mCollator.compare(a.app.title.toString(), b.app.title.toString()));
        ArrayList<AdapterItem> items = new ArrayList<>();
        int limit = mAppLibrary ? Integer.MAX_VALUE : MAX_GRID_APPS;
        for (int i = 0; i < scored.size() && i < limit; i++) {
            AppInfo app = scored.get(i).app;
            items.add(AdapterItem.asApp(app));
        }
        return new AppMatches(items, scored.isEmpty() ? 0 : scored.get(0).score);
    }

    private void publishEmptyQuery(int token, String query, ArrayList<AdapterItem> appItems,
            SearchCallback<AdapterItem> callback) {
        ArrayList<AdapterItem> items = new ArrayList<>(historyItems());
        items.addAll(appItems);
        if (items.isEmpty() && !mAppLibrary) {
            return;
        }
        publish(token, query, items, callback);
    }

    private List<AdapterItem> historyItems() {
        List<String> queries = SearchHistory.recentQueries(mContext);
        List<AdapterItem> items = new ArrayList<>();
        if (queries.isEmpty()) {
            return items;
        }
        items.add(AdapterItem.asSearchSection(UniversalSearchResult.SOURCE_HISTORY));
        for (String recent : queries) {
            UniversalSearchResult row = new UniversalSearchResult(
                    UniversalSearchResult.SOURCE_HISTORY, "q:" + recent, recent, null, null,
                    null, 0);
            row.iconRes = R.drawable.ic_search_history;
            row.onTap = () -> mQueryHandler.accept(recent);
            items.add(AdapterItem.asSearchResult(row));
        }
        UniversalSearchResult clear = new UniversalSearchResult(
                UniversalSearchResult.SOURCE_HISTORY, "clear",
                mContext.getString(R.string.search_history_clear), null, null, null, 0);
        clear.iconRes = R.drawable.ic_search_clear;
        clear.onTap = () -> {
            SearchHistory.clear(mContext);
            mQueryHandler.accept("");
        };
        items.add(AdapterItem.asSearchResult(clear));
        return items;
    }

    private void startProviders(int token, String query, AppMatches appMatches,
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
        Pass pass = new Pass(token, query, appMatches, callback, enabled.size());
        mLastPass = pass;
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
        List<UniversalSearchResult> results;
        try {
            results = provider.query(mContext, query, MAX_PER_PROVIDER);
        } catch (RuntimeException e) {
            // A misbehaving provider must not take the whole search down.
            return List.of();
        }
        for (UniversalSearchResult result : results) {
            result.query = query;
            if (!result.isPlaceholder()) {
                result.score += SearchHistory.boost(mContext, SearchHistory.resultKey(result));
            }
        }
        return results;
    }

    private boolean hasEnabledProvider() {
        for (SearchProvider provider : mProviders) {
            if (provider.isEnabled(mContext)) {
                return true;
            }
        }
        return false;
    }

    private void refine(Runnable change) {
        mWorker.post(() -> {
            change.run();
            if (mLastPass != null) {
                mLastPass.publishResults();
            }
        });
    }

    private ArrayList<AdapterItem> layout(String query, AppMatches appMatches,
            List<UniversalSearchResult> all) {
        ArrayList<AdapterItem> appItems = appMatches.items;
        if (appItems.isEmpty() && query.trim().length() >= 2
                && LauncherPrefs.SEARCH_ACTIONS.get(mContext)) {
            UniversalSearchResult store = ActionProvider.storeSearch(mContext, query.trim());
            if (store != null) {
                store.query = query;
                all.add(store);
            }
        }
        Map<Integer, List<UniversalSearchResult>> bySource = new LinkedHashMap<>();
        for (int source : UniversalSearchResults.sectionOrder(mContext)) {
            List<UniversalSearchResult> forSource = new ArrayList<>();
            for (UniversalSearchResult result : all) {
                if (result.source == source) {
                    forSource.add(result);
                }
            }
            if (!forSource.isEmpty()) {
                // Stable, so equal scores keep the provider's own order (e.g. newest first).
                forSource.sort((a, b) -> b.score - a.score);
                bySource.put(source, forSource);
            }
        }

        List<Integer> present = new ArrayList<>();
        if (!appItems.isEmpty()) {
            present.add(UniversalSearchResult.SOURCE_APP);
        }
        present.addAll(bySource.keySet());
        if (mFilter != NO_FILTER && !present.contains(mFilter)) {
            mFilter = NO_FILTER;
        }

        ArrayList<AdapterItem> items = new ArrayList<>();
        if (present.size() >= 2 || mFilter != NO_FILTER) {
            List<Integer> chips = new ArrayList<>();
            chips.add(NO_FILTER);
            chips.addAll(present);
            items.add(AdapterItem.asSearchFilters(new UniversalSearchResults.Filters(
                    chips, mFilter, source -> refine(() -> mFilter = source))));
        }
        if (mFilter == UniversalSearchResult.SOURCE_APP) {
            items.addAll(appItems);
            return items;
        }
        if (mFilter != NO_FILTER) {
            items.add(AdapterItem.asSearchSection(mFilter));
            for (UniversalSearchResult result : bySource.get(mFilter)) {
                items.add(AdapterItem.asSearchResult(result));
            }
            return items;
        }

        UniversalSearchResult best = bestMatch(query, bySource);
        boolean bestFirst = best != null && best.score > appMatches.topScore;
        if (bestFirst) {
            items.add(AdapterItem.asSearchTopResult(best));
        }
        items.addAll(appItems);
        if (best != null && !bestFirst) {
            items.add(AdapterItem.asSearchTopResult(best));
        }
        int cap = LauncherPrefs.SEARCH_MAX_PER_SECTION.get(mContext);
        for (Map.Entry<Integer, List<UniversalSearchResult>> section : bySource.entrySet()) {
            List<UniversalSearchResult> results = new ArrayList<>(section.getValue());
            results.remove(best);
            if (results.isEmpty()) {
                continue;
            }
            int source = section.getKey();
            boolean expanded = mExpanded.contains(source);
            items.add(AdapterItem.asSearchSection(source));
            for (int i = 0; i < results.size() && (expanded || i < cap); i++) {
                items.add(AdapterItem.asSearchResult(results.get(i)));
            }
            if (!expanded && results.size() > cap) {
                UniversalSearchResult more = new UniversalSearchResult(source, "more",
                        mContext.getString(R.string.search_show_more, results.size() - cap),
                        null, null, null, 0);
                more.iconRes = R.drawable.ic_search_expand;
                more.onTap = () -> refine(() -> mExpanded.add(source));
                items.add(AdapterItem.asSearchResult(more));
            }
        }
        return items;
    }

    private static UniversalSearchResult bestMatch(String query,
            Map<Integer, List<UniversalSearchResult>> bySource) {
        if (query.trim().length() < 2) {
            return null;
        }
        UniversalSearchResult best = null;
        for (Map.Entry<Integer, List<UniversalSearchResult>> section : bySource.entrySet()) {
            int source = section.getKey();
            if (source == UniversalSearchResult.SOURCE_WEB
                    || source == UniversalSearchResult.SOURCE_WEB_SUGGESTION) {
                continue;
            }
            UniversalSearchResult top = section.getValue().get(0);
            if (!top.isPlaceholder() && top.score >= FuzzyMatcher.PREFIX
                    && (best == null || top.score > best.score)) {
                best = top;
            }
        }
        return best;
    }

    /** Only touched on {@link #mWorker}. */
    private class Pass {
        private final int mToken;
        private final String mQuery;
        private final AppMatches mAppMatches;
        private final SearchCallback<AdapterItem> mCallback;
        private final List<UniversalSearchResult>[] mResults;
        private int mPending;
        private boolean mTimedOut;

        @SuppressWarnings("unchecked")
        Pass(int token, String query, AppMatches appMatches,
                SearchCallback<AdapterItem> callback, int providers) {
            mToken = token;
            mQuery = query;
            mAppMatches = appMatches;
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

        void publishResults() {
            if (mToken != mRequest.get()) {
                return;
            }
            List<UniversalSearchResult> all = new ArrayList<>();
            for (List<UniversalSearchResult> results : mResults) {
                if (results != null) {
                    all.addAll(results);
                }
            }
            publish(mToken, mQuery, withEmptyMessage(layout(mQuery, mAppMatches, all), mQuery),
                    mCallback);
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
