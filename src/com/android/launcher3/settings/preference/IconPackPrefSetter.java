package com.android.launcher3.settings.preference;

import android.content.Context;

import com.android.launcher3.R;
import com.android.launcher3.customization.IconDatabase;
import com.android.launcher3.icons.pack.IconPackManager;
import com.android.launcher3.util.ComponentKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

public class IconPackPrefSetter implements ReloadingListPreference.OnReloadListener {
    private final Context mContext;
    private final ComponentKey mKey;

    public IconPackPrefSetter(Context context) {
        this(context, null);
    }

    public IconPackPrefSetter(Context context, ComponentKey key) {
        mContext = context;
        mKey = key;
    }

    @Override
    public Runnable listUpdater(ReloadingListPreference pref) {
        IconPackManager ipm = IconPackManager.get(mContext);
        Map<String, CharSequence> packList = ipm.getProviderNames();
        if (mKey != null) {
            String selectedPack = IconDatabase.getSelectionForComponent(mContext, mKey);
            for (String pkg : new HashSet<>(packList.keySet())) {
                if (!pkg.equals(selectedPack)
                        && !ipm.packContainsActivity(pkg, mKey.componentName)) {
                    packList.remove(pkg);
                }
            }
        }

        CharSequence[] keys = new String[packList.size() + (mKey == null ? 1 : 3)];
        CharSequence[] values = new String[keys.length];
        int i = 0;

        if (mKey != null) {
            keys[i] = mContext.getString(R.string.app_info_icon_style_global);
            values[i++] = IconDatabase.VALUE_GLOBAL;
        }

        keys[i] = mContext.getResources().getString(R.string.icon_pack_default_label);
        values[i++] = IconDatabase.VALUE_DEFAULT;

        if (mKey != null) {
            keys[i] = mContext.getString(R.string.app_info_icon_style_themed);
            values[i++] = IconDatabase.VALUE_THEMED;
        }

        // List of available icon packs
        List<Map.Entry<String, CharSequence>> packs = new ArrayList<>(packList.entrySet());
        Collections.sort(packs, (o1, o2) ->
                normalizeTitle(o1.getValue()).compareTo(normalizeTitle(o2.getValue())));
        for (Map.Entry<String, CharSequence> entry : packs) {
            keys[i] = entry.getValue();
            values[i++] = entry.getKey();
        }

        return () -> pref.setEntriesWithValues(keys, values);
    }

    private String normalizeTitle(CharSequence title) {
        return title.toString().toLowerCase();
    }
}
