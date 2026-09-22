package com.android.launcher3.search.universal;

import android.content.Context;

import java.util.List;

public interface SearchProvider {

    int getSource();

    boolean isEnabled(Context context);

    List<UniversalSearchResult> query(Context context, String query, int max);
}
