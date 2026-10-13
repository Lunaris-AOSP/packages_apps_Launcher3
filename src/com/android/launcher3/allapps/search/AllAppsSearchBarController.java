/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.launcher3.allapps.search;

import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.SuggestionSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.android.launcher3.ExtendedEditText;
import com.android.launcher3.Utilities;
import com.android.launcher3.allapps.BaseAllAppsAdapter;
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem;
import com.android.launcher3.search.SearchAlgorithm;
import com.android.launcher3.search.SearchCallback;
import com.android.launcher3.search.universal.SearchHistory;
import com.android.launcher3.search.universal.UniversalSearchResults;
import com.android.launcher3.views.ActivityContext;

import java.util.List;

/**
 * An interface to a search box that AllApps can command.
 */
public class AllAppsSearchBarController
        implements TextWatcher, OnEditorActionListener, ExtendedEditText.OnBackKeyListener {

    private static final String TAG = "AllAppsSearchBarController";
    protected ActivityContext mLauncher;
    protected SearchCallback<AdapterItem> mCallback;
    protected ExtendedEditText mInput;
    protected String mQuery;
    private String[] mTextConversions;
    private String mResultQuery;
    private boolean mResetting;

    protected SearchAlgorithm<AdapterItem> mSearchAlgorithm;

    public void setVisibility(int visibility) {
        mInput.setVisibility(visibility);
    }

    /**
     * Sets the references to the apps model and the search result callback.
     */
    public final void initialize(
            SearchAlgorithm<AdapterItem> searchAlgorithm, ExtendedEditText input,
            ActivityContext launcher, SearchCallback<AdapterItem> callback) {
        mCallback = new SearchCallback<>() {
            @Override
            public void onSearchResult(String query, java.util.ArrayList<AdapterItem> items) {
                if (!mResetting && query.equals(mQuery)
                        && query.equals(mInput.getText().toString())) {
                    mResultQuery = query;
                    callback.onSearchResult(query, items);
                }
            }

            @Override
            public void clearSearchResult() {
                mResultQuery = null;
                callback.clearSearchResult();
            }
        };
        mLauncher = launcher;

        mInput = input;
        mInput.addTextChangedListener(this);
        mInput.setOnEditorActionListener(this);
        mInput.setOnBackKeyListener(this);
        mSearchAlgorithm = searchAlgorithm;
        if (mSearchAlgorithm.hasEmptyQueryResults()) {
            mInput.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus && !mResetting && mInput.getEditableText().length() == 0) {
                    mQuery = "";
                    mSearchAlgorithm.cancel(false);
                    mSearchAlgorithm.doSearch("", mTextConversions, mCallback);
                }
            });
        }
    }

    @Override
    public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {
        // Do nothing
    }

    @Override
    public void onTextChanged(CharSequence s, int start, int before, int count) {
        mTextConversions = extractTextConversions(s);
    }

    /**
     * Extract text conversions from composing text and send them for search.
     */
    public static String[] extractTextConversions(CharSequence text) {
        if (text instanceof SpannableStringBuilder) {
            SpannableStringBuilder spanned = (SpannableStringBuilder) text;
            SuggestionSpan[] suggestionSpans =
                spanned.getSpans(0, text.length(), SuggestionSpan.class);
            if (suggestionSpans != null && suggestionSpans.length > 0) {
                spanned.removeSpan(suggestionSpans[0]);
                return suggestionSpans[0].getSuggestions();
            }
        }
        return null;
    }

    @Override
    public void afterTextChanged(final Editable s) {
        mQuery = s.toString();
        if (mQuery.trim().isEmpty() && (mResetting || !mInput.hasFocus()
                || !mSearchAlgorithm.hasEmptyQueryResults())) {
            mSearchAlgorithm.cancel(true);
            mCallback.clearSearchResult();
        } else {
            mSearchAlgorithm.cancel(false);
            mSearchAlgorithm.doSearch(mQuery, mTextConversions, mCallback);
        }
    }

    public void refreshSearchResult() {
        if (mQuery == null || (mQuery.trim().isEmpty() && (!mInput.hasFocus()
                || !mSearchAlgorithm.hasEmptyQueryResults()))) {
            return;
        }
        // If play store continues auto updating an app, we want to show partial result.
        mSearchAlgorithm.cancel(false);
        mSearchAlgorithm.doSearch(mQuery, mTextConversions, mCallback);
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {

        if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO || (
                actionId == EditorInfo.IME_NULL && event != null
                        && event.getAction() == KeyEvent.ACTION_DOWN
                        && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER))) {
            if (actionId == EditorInfo.IME_NULL) {
                Log.i(TAG, "User pressed ENTER key");
            } else {
                Log.i(TAG, "User tapped ime search button");
            }
            // Skip if the query is empty
            String query = v.getText().toString();
            if (query.trim().isEmpty()) {
                return false;
            }

            // selectFocusedView should return SearchTargetEvent that is passed onto onClick
            return launchTopResult();
        }
        return false;
    }

    private boolean launchTopResult() {
        if (TextUtils.isEmpty(mQuery) || mQuery.trim().isEmpty()
                || !mQuery.equals(mResultQuery)) {
            return false;
        }
        List<AdapterItem> items = mLauncher.getAppsView().getSearchResultList().getAdapterItems();
        for (AdapterItem item : items) {
            if (item.searchResult != null && !item.searchResult.isPlaceholder()) {
                return UniversalSearchResults.launch(mLauncher, mInput, item.searchResult);
            }
            if (item.itemInfo != null && BaseAllAppsAdapter.isIconViewType(item.viewType)) {
                SearchHistory.recordLaunch(mInput.getContext(), SearchHistory.appKey(
                        item.itemInfo.getTargetComponent(), item.itemInfo.user));
                return UniversalSearchResults.launchItem(mLauncher, mInput, item.itemInfo);
            }
        }
        return false;
    }

    @Override
    public boolean onBackKey() {
        // Only hide the search field if there is no query
        String query = Utilities.trim(mInput.getEditableText().toString());
        if (!query.isEmpty()) {
            reset();
            return true;
        }
        return false;
    }

    /**
     * Resets the search bar state.
     */
    public void cancelSearch() {
        mSearchAlgorithm.cancel(true);
        mResultQuery = null;
    }

    public void reset() {
        mResetting = true;
        cancelSearch();
        mCallback.clearSearchResult();
        mInput.reset();
        mInput.clearFocus();
        mInput.hideKeyboard();
        mQuery = null;
        mResultQuery = null;
        mResetting = false;
    }

    /**
     * Focuses the search field to handle key events.
     */
    public void focusSearchField() {
        mInput.showKeyboard();
    }

    /**
     * Returns whether the search field is focused.
     */
    public boolean isSearchFieldFocused() {
        return mInput.isFocused();
    }
}
