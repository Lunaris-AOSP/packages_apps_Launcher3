package com.android.launcher3.icons.pack;

import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class that handles the metadata and data of any icon pack.
 */
public class IconPack {
    private final ApplicationInfo mAi;
    private final CharSequence mPackageLabel;
    private Data mData;
    private Resources mRes;
    private List<IconEntry> mAllEntries;
    private Boolean mIsThemed;
    private final Map<String, Boolean> mThemedDrawables = new HashMap<>();

    IconPack(ApplicationInfo ai, CharSequence label) {
        mAi = ai;
        mPackageLabel = label;
    }

    ApplicationInfo getAi() {
        return mAi;
    }

    String getPackage() {
        return mAi.packageName;
    }

    CharSequence getTitle() {
        return mPackageLabel;
    }

    Data getData(PackageManager pm)
            throws PackageManager.NameNotFoundException, XmlPullParserException, IOException {
        if (mData == null) {
            mData = IconPackParser.parsePackage(pm, getResources(pm), getPackage());
        }
        return mData;
    }

    int getDrawableId(PackageManager pm, ComponentName name)
            throws PackageManager.NameNotFoundException, IOException, XmlPullParserException {
        Data data = getData(pm);
        Resources res = getResources(pm);
        String pkg = getPackage();
        return res.getIdentifier(data.drawables.get(name), "drawable", pkg);
    }

    int getDrawableId(PackageManager pm, String name)
            throws PackageManager.NameNotFoundException {
        return name == null ? 0 : getResources(pm).getIdentifier(name, "drawable", getPackage());
    }

    public List<IconEntry> getAllIconEntries(PackageManager pm)
            throws PackageManager.NameNotFoundException, XmlPullParserException, IOException {
        if (mAllEntries == null) {
            mAllEntries = IconPackParser.parseAllEntries(pm, getResources(pm), getPackage());
        }
        return mAllEntries;
    }

    boolean isThemed(PackageManager pm) throws PackageManager.NameNotFoundException {
        if (mIsThemed == null) {
            mIsThemed = getResources(pm).getIdentifier(
                    "grayscale_icon_map", "xml", getPackage()) != 0;
        }
        return mIsThemed;
    }

    boolean isThemedDrawable(PackageManager pm, String drawableName)
            throws PackageManager.NameNotFoundException {
        if (drawableName == null) return false;
        Boolean themed = mThemedDrawables.get(drawableName);
        if (themed == null) {
            Resources res = getResources(pm);
            int id = res.getIdentifier(drawableName, "drawable", getPackage());
            themed = false;
            if (id != 0) {
                try {
                    Drawable icon = res.getDrawable(id, null);
                    themed = icon instanceof AdaptiveIconDrawable
                            && ((AdaptiveIconDrawable) icon).getMonochrome() != null;
                } catch (Resources.NotFoundException ignored) {
                }
            }
            mThemedDrawables.put(drawableName, themed);
        }
        return themed;
    }

    private Resources getResources(PackageManager pm) throws PackageManager.NameNotFoundException {
        if (mRes == null) {
            mRes = pm.getResourcesForApplication(getPackage());
        }
        return mRes;
    }

    public static class IconEntry {
        public final String drawableName;
        public final int resId;

        IconEntry(String drawableName, int resId) {
            this.drawableName = drawableName;
            this.resId = resId;
        }
    }

    static class Data {
        final Map<ComponentName, String> drawables = new HashMap<>();
        final Map<ComponentName, String> calendarPrefix = new HashMap<>();
        final SparseArray<Clock> clockMetadata = new SparseArray<>();
        final List<Integer> iconBacks = new ArrayList<>();
        final List<Integer> iconMasks = new ArrayList<>();
        final List<Integer> iconUpons = new ArrayList<>();
        float scale = 1f;

        boolean hasMasking() {
            return !iconBacks.isEmpty() || !iconMasks.isEmpty() || !iconUpons.isEmpty();
        }
    }

    static class Clock {
        final int hourLayerIndex;
        final int minuteLayerIndex;
        final int secondLayerIndex;
        final int defaultHour;
        final int defaultMinute;
        final int defaultSecond;

        Clock(int hourLayerIndex, int minuteLayerIndex, int secondLayerIndex,
              int defaultHour, int defaultMinute, int defaultSecond) {
            this.hourLayerIndex = hourLayerIndex;
            this.minuteLayerIndex = minuteLayerIndex;
            this.secondLayerIndex = secondLayerIndex;
            this.defaultHour = defaultHour;
            this.defaultMinute = defaultMinute;
            this.defaultSecond = defaultSecond;
        }
    }
}
