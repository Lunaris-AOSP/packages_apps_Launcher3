package com.android.launcher3.icons.pack;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.Nullable;

import static com.android.launcher3.util.Executors.MODEL_EXECUTOR;

import com.android.launcher3.LauncherModel;
import com.android.launcher3.icons.ThemedIconSettings;
import com.android.launcher3.util.ComponentKey;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.android.launcher3.customization.IconDatabase;
import com.android.launcher3.util.AppReloader;

public class IconPackManager extends BroadcastReceiver {
    private static final String TAG = "IconPackManager";

    private static final String[] ICON_INTENT_ACTIONS = new String[] {
            "com.fede.launcher.THEME_ICONPACK",
            "com.anddoes.launcher.THEME",
            "com.novalauncher.THEME",
            "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme",
            "org.adw.launcher.THEMES",
            "net.oneplus.launcher.icons.ACTION_PICK_ICON",
            "org.adw.launcher.icons.ACTION_PICK_ICON"
    };

    private static final Intent[] ICON_INTENTS = new Intent[ICON_INTENT_ACTIONS.length];
    private static IconPackManager sInstance;

    static {
        for (int i = 0; i < ICON_INTENT_ACTIONS.length; i++) {
            ICON_INTENTS[i] = new Intent(ICON_INTENT_ACTIONS[i]);
        }
    }

    public static synchronized IconPackManager get(Context context) {
        if (sInstance == null) {
            sInstance = new IconPackManager(context);
        }
        return sInstance;
    }

    private final Context mContext;
    private final Map<String, IconPack> mProviders = new HashMap<>();
    private final Handler mHandler = new Handler(MODEL_EXECUTOR.getLooper());

    private IconPackManager(Context context) {
        mContext = context;
        reloadProviders();

        IntentFilter filter = new IntentFilter();
        filter.addDataScheme("package");
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);

        // Called when any app has been installed, enabled, disabled, updated or deleted.
        context.getApplicationContext().registerReceiver(this, filter, null, mHandler);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getData() != null) {
            String pkg = intent.getData().getEncodedSchemeSpecificPart();
            if (pkg != null) {
                Log.d(TAG, "Received intent action " + intent.getAction() + " for " + pkg);
                AppReloader appReloader = AppReloader.get(mContext);

                // Create a list of apps that are using the changed package icon pack,
                // either through the global setting or with an override.
                Set<ComponentKey> updateKeys = appReloader.withIconPack(pkg);

                // Remove the changed package from the providers to reload the application info.
                synchronized (this) {
                    mProviders.remove(pkg);
                    reloadProviders();
                }

                // Ensure all icons are up-to-date after this icon pack change.
                // Calendar and clock information will automatically be reloaded by this call.
                appReloader.reload(updateKeys);
            }
        }
    }

    private void reloadProviders() {
        PackageManager pm = mContext.getPackageManager();
        Set<ResolveInfo> info = new HashSet<>();
        for (Intent intent : ICON_INTENTS) {
            info.addAll(pm.queryIntentActivities(intent, PackageManager.GET_META_DATA));
        }

        // Remove unavailable packs
        for (String packageName : mProviders.keySet().toArray(new String[0])) {
            boolean foundPackageName = false;
            for (ResolveInfo ri : info) {
                if (ri.activityInfo.packageName.equals(packageName)) {
                    foundPackageName = true;
                    break;
                }
            }
            if (!foundPackageName) {
                mProviders.remove(packageName);
            }
        }

        // Add new packs
        for (ResolveInfo ri : info) {
            String packageName = ri.activityInfo.packageName;
            if (!mProviders.containsKey(packageName)) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                CharSequence label = ai.loadLabel(pm);
                mProviders.put(packageName, new IconPack(ai, label));
            }
        }

        String global = IconDatabase.getGlobal(mContext);
        if (!global.isEmpty() && !mProviders.containsKey(global)) {
            // The global icon pack has been removed, so reset it.
            // This constraint ensures that the global icon pack is always available,
            // even if the launcher did not receive the uninstall intent.
            Log.e(TAG, "Resetting global icon pack because provider " + global + " was removed");
            IconDatabase.resetGlobal(mContext);
        }
    }

    public synchronized Map<String, CharSequence> getProviderNames() {
        Map<String, CharSequence> providerTitles = new HashMap<>();
        for (Map.Entry<String, IconPack> pack : mProviders.entrySet()) {
            providerTitles.put(pack.getKey(), pack.getValue().getTitle());
        }
        return providerTitles;
    }

    public synchronized boolean isThemedPack(String packPackage) {
        IconPack pack = mProviders.get(packPackage);
        if (pack == null) return false;
        try {
            return pack.isThemed(mContext.getPackageManager());
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    public synchronized boolean supportsThemedIcon(ComponentKey key) {
        String packPackage = IconDatabase.getByComponent(mContext, key);
        IconPack pack = mProviders.get(packPackage);
        if (pack == null) return false;
        PackageManager pm = mContext.getPackageManager();
        try {
            if (pack.isThemed(pm)) return true;
            String drawable = IconDatabase.getExplicitDrawableForComponent(mContext, key);
            if (drawable == null || pack.getDrawableId(pm, drawable) == 0) {
                IconPack.Data data = pack.getData(pm);
                drawable = getDefaultDrawableName(pack, data, key.componentName);
            }
            return pack.isThemedDrawable(pm, drawable);
        } catch (PackageManager.NameNotFoundException | XmlPullParserException | IOException e) {
            return false;
        }
    }

    private String getDefaultDrawableName(IconPack pack, IconPack.Data data, ComponentName component)
            throws PackageManager.NameNotFoundException {
        String prefix = data.calendarPrefix.get(component);
        if (prefix != null) {
            String calendar = prefix + Calendar.getInstance().get(Calendar.DAY_OF_MONTH);
            if (pack.getDrawableId(mContext.getPackageManager(), calendar) != 0) return calendar;
        }
        return data.drawables.get(component);
    }

    @Nullable
    public Drawable applyThemedColors(Context context, String packPackage,
            @Nullable Drawable icon) {
        if (icon == null || packPackage == null || packPackage.isEmpty()) return icon;
        AdaptiveIconDrawable adaptive = icon instanceof AdaptiveIconDrawable
                ? (AdaptiveIconDrawable) icon : null;
        Drawable mono = adaptive == null ? null : adaptive.getMonochrome();
        if (mono == null && isThemedPack(packPackage)) {
            mono = adaptive == null ? icon : adaptive.getForeground();
        }
        if (mono == null) return icon;

        Drawable.ConstantState state = mono.getConstantState();
        Drawable foreground = state == null ? mono : state.newDrawable(context.getResources());
        foreground = new InsetDrawable(foreground.mutate(),
                ThemedIconSettings.getIconInsetFraction(context));
        foreground.setTint(ThemedIconSettings.getForegroundColor(context));
        return new AdaptiveIconDrawable(
                new ColorDrawable(ThemedIconSettings.getBackgroundColor(context)),
                foreground, mono);
    }

    public synchronized boolean packContainsActivity(String packPackage, ComponentName componentName) {
        try {
            IconPack pack = mProviders.get(packPackage);
            if (pack == null) return false;
            IconPack.Data data = pack.getData(mContext.getPackageManager());
            return pack.getDrawableId(mContext.getPackageManager(),
                    getDefaultDrawableName(pack, data, componentName)) != 0;
        } catch (PackageManager.NameNotFoundException | XmlPullParserException | IOException ignored) {
            return false;
        }
    }

    /**
     * Creates a resolver that can load the icon once.
     * This resolver should not be stored, as the resolution strategy could change when
     * the selected icon pack in use is uninstalled or updated.
     * @param key Component for which an icon should be extracted.
     * @return Resolver that loads the icon, or null if there is no resolution strategy.
     */
    public synchronized IconResolver resolve(ComponentKey key) {
        String packPackage = IconDatabase.getByComponent(mContext, key);
        IconPack pack = mProviders.get(packPackage);
        if (pack != null) {
            // The icon provider package is available.
            try {
                IconPack.Data data = pack.getData(mContext.getPackageManager());
                String explicitDrawable =
                        IconDatabase.getExplicitDrawableForComponent(mContext, key);
                if (explicitDrawable != null) {
                    int drawableId = mContext.getPackageManager()
                            .getResourcesForApplication(packPackage)
                            .getIdentifier(explicitDrawable, "drawable", packPackage);
                    if (drawableId != 0) {
                        return new IconResolverExternal(mContext.getPackageManager(),
                                pack.getAi(), drawableId,
                                null,
                                data.clockMetadata.get(drawableId));
                    }
                }
                String drawableName = getDefaultDrawableName(pack, data, key.componentName);
                int drawableId = pack.getDrawableId(mContext.getPackageManager(), drawableName);
                if (drawableId != 0) {
                    return new IconResolverExternal(mContext.getPackageManager(), pack.getAi(),
                            drawableId, data.calendarPrefix.get(key.componentName),
                            data.clockMetadata.get(drawableId));
                }
                if (data.hasMasking()) {
                    return new IconResolverMasked(mContext, data, pack.getAi(), key.hashCode());
                }
            } catch (PackageManager.NameNotFoundException | XmlPullParserException | IOException ignored) {
            }
        } else if (!packPackage.isEmpty()) {
            // The provider is not available, save for next time.
            IconDatabase.resetForComponent(mContext, key);
        }
        return null;
    }

    public synchronized List<IconPack.IconEntry> getAllIconEntries(String packPackage) {
        IconPack pack = mProviders.get(packPackage);
        if (pack == null) return java.util.Collections.emptyList();
        try {
            return pack.getAllIconEntries(mContext.getPackageManager());
        } catch (PackageManager.NameNotFoundException | XmlPullParserException | IOException e) {
            Log.e(TAG, "Failed to enumerate icons in pack " + packPackage, e);
            return java.util.Collections.emptyList();
        }
    }
}
