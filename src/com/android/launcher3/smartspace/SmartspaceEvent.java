package com.android.launcher3.smartspace;

import static android.app.smartspace.SmartspaceTarget.*;

import android.content.Context;

import com.android.launcher3.ConstantItem;
import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;

public enum SmartspaceEvent {
    WEATHER(LauncherPrefs.SMARTSPACE_WEATHER, R.string.smartspace_weather,
            FEATURE_WEATHER, FEATURE_WEATHER_ALERT, FEATURE_SEVERE_WEATHER_ALERT),
    CALENDAR(LauncherPrefs.SMARTSPACE_CALENDAR, R.string.smartspace_calendar, FEATURE_CALENDAR),
    MUSIC(LauncherPrefs.SMARTSPACE_MUSIC, R.string.smartspace_music,
            FEATURE_MEDIA, FEATURE_MEDIA_RESUME, FEATURE_MEDIA_HEADS_UP),
    ALARMS(LauncherPrefs.SMARTSPACE_ALARMS, R.string.smartspace_alarms,
            FEATURE_ALARM, FEATURE_UPCOMING_ALARM, FEATURE_HOLIDAY_ALARM, FEATURE_BEDTIME_ROUTINE),
    FLASHLIGHT(LauncherPrefs.SMARTSPACE_FLASHLIGHT, R.string.smartspace_flashlight, FEATURE_FLASHLIGHT),
    BLUETOOTH(LauncherPrefs.SMARTSPACE_BLUETOOTH, R.string.smartspace_bluetooth,
            FEATURE_PAIRED_DEVICE_STATE),
    BATTERY(LauncherPrefs.SMARTSPACE_BATTERY, R.string.smartspace_battery),
    TIMERS(LauncherPrefs.SMARTSPACE_TIMERS, R.string.smartspace_timers,
            FEATURE_TIMER, FEATURE_STOPWATCH, FEATURE_CROSS_DEVICE_TIMER),
    REMINDERS(LauncherPrefs.SMARTSPACE_REMINDERS, R.string.smartspace_reminders, FEATURE_REMINDER),
    TRAVEL(LauncherPrefs.SMARTSPACE_TRAVEL, R.string.smartspace_travel,
            FEATURE_COMMUTE_TIME, FEATURE_FLIGHT, FEATURE_ETA_MONITORING,
            FEATURE_DRIVING_MODE, FEATURE_TIME_TO_LEAVE),
    SPORTS(LauncherPrefs.SMARTSPACE_SPORTS, R.string.smartspace_sports, FEATURE_SPORTS),
    PACKAGES(LauncherPrefs.SMARTSPACE_PACKAGES, R.string.smartspace_packages, FEATURE_PACKAGE_TRACKING),
    FINANCE(LauncherPrefs.SMARTSPACE_FINANCE, R.string.smartspace_finance, FEATURE_STOCK_PRICE_CHANGE),
    SHOPPING(LauncherPrefs.SMARTSPACE_SHOPPING, R.string.smartspace_shopping,
            FEATURE_SHOPPING_LIST, FEATURE_LOYALTY_CARD, FEATURE_GAS_STATION_PAYMENT),
    FITNESS(LauncherPrefs.SMARTSPACE_FITNESS, R.string.smartspace_fitness,
            FEATURE_FITNESS_TRACKING, FEATURE_SLEEP_SUMMARY, FEATURE_STEP_COUNTING),
    DOORBELL(LauncherPrefs.SMARTSPACE_DOORBELL, R.string.smartspace_doorbell, FEATURE_DOORBELL),
    OTHER(LauncherPrefs.SMARTSPACE_OTHER, R.string.smartspace_other);

    public final ConstantItem<Boolean> preference;
    public final int title;
    private final int[] mFeatures;

    SmartspaceEvent(ConstantItem<Boolean> preference, int title, int... features) {
        this.preference = preference;
        this.title = title;
        mFeatures = features;
    }

    public boolean isEnabled(Context context) {
        return preference.get(context);
    }

    public static SmartspaceEvent forFeature(int feature) {
        for (SmartspaceEvent event : values()) {
            for (int type : event.mFeatures) {
                if (feature == type) return event;
            }
        }
        return OTHER;
    }
}
