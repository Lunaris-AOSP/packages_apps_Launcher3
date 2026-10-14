package com.android.launcher3.smartspace;

import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.app.AlarmManager;
import android.app.smartspace.SmartspaceConfig;
import android.app.smartspace.SmartspaceManager;
import android.app.smartspace.SmartspaceSession;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.SmartspaceTargetEvent;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;

import com.android.launcher3.Launcher;
import com.android.launcher3.R;
import com.android.launcher3.quickspace.QuickspaceController;
import com.android.launcher3.quickspace.receivers.QuickSpaceActionReceiver;
import com.android.launcher3.search.universal.CalendarProvider;
import com.android.launcher3.util.MSMHProxy;
import com.android.launcher3.util.MediaSessionManagerHelper;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

final class SmartspaceController implements QuickspaceController.OnDataListener,
        MediaSessionManagerHelper.MediaMetadataListener {
    private static final String TAG = "LauncherSmartspace";
    private static final String FLASHLIGHT_ENABLED = "flashlight_enabled";
    private static final long EVENT_WINDOW = DateUtils.HOUR_IN_MILLIS;
    private static final SmartspaceEvent[] LOCAL_ORDER = {
            SmartspaceEvent.FLASHLIGHT, SmartspaceEvent.CALENDAR, SmartspaceEvent.ALARMS,
            SmartspaceEvent.MUSIC, SmartspaceEvent.BATTERY, SmartspaceEvent.WEATHER
    };

    private final Context mContext;
    private final Consumer<List<SmartspaceCard>> mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final EnumMap<SmartspaceEvent, SmartspaceCard> mLocal =
            new EnumMap<>(SmartspaceEvent.class);
    private final List<SmartspaceTarget> mTargets = new ArrayList<>();
    private final Runnable mPublish = this::publish;
    private final Runnable mQueryCalendar = this::queryCalendar;
    private SmartspaceSession mSession;
    private SmartspaceSession.OnTargetsAvailableListener mSessionListener;
    private QuickspaceController mWeather;
    private CancellationSignal mCalendarQuery;
    private boolean mActive;
    private boolean mMediaRegistered;
    private SmartspaceBluetoothController mBluetooth;
    private SmartspaceClockController mClock;
    private List<SmartspaceCard> mBluetoothCards = List.of();
    private List<SmartspaceCard> mClockCards = List.of();
    private boolean mReceiverRegistered;
    private boolean mObserverRegistered;
    private int mGeneration;

    SmartspaceController(Context context, Consumer<List<SmartspaceCard>> listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!mActive) return;
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                updateBattery(intent);
            } else {
                updateAlarm();
                scheduleCalendarQuery();
            }
            publish();
        }
    };

    private final ContentObserver mObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            if (!mActive) return;
            updateFlashlight();
            if (uri == null || CalendarContract.AUTHORITY.equals(uri.getAuthority())) {
                scheduleCalendarQuery();
            }
            publish();
        }
    };

    void setActive(boolean active) {
        if (mActive == active) return;
        if (active) start();
        else stop();
    }

    private void start() {
        if (mActive) return;
        mActive = true;
        mGeneration++;
        connectSession();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_TIME_TICK);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_LOCALE_CHANGED);
        if (SmartspaceEvent.ALARMS.isEnabled(mContext)) {
            filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED);
        }
        if (SmartspaceEvent.BATTERY.isEnabled(mContext)) {
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        }
        Intent battery = mContext.registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
        mReceiverRegistered = true;
        if (battery != null && Intent.ACTION_BATTERY_CHANGED.equals(battery.getAction())) {
            updateBattery(battery);
        }
        if (SmartspaceEvent.FLASHLIGHT.isEnabled(mContext)) {
            mContext.getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(FLASHLIGHT_ENABLED), false, mObserver);
            mObserverRegistered = true;
            updateFlashlight();
        }
        if (SmartspaceEvent.CALENDAR.isEnabled(mContext) && CalendarProvider.hasPermission(mContext)) {
            mContext.getContentResolver().registerContentObserver(
                    CalendarContract.CONTENT_URI, true, mObserver);
            mObserverRegistered = true;
            scheduleCalendarQuery();
        }
        if (SmartspaceEvent.BLUETOOTH.isEnabled(mContext)) {
            mBluetooth = new SmartspaceBluetoothController(mContext, cards -> {
                mBluetoothCards = cards;
                publish();
            });
            mBluetooth.start();
        }
        if (SmartspaceEvent.TIMERS.isEnabled(mContext)) {
            mClock = new SmartspaceClockController(mContext, cards -> {
                mClockCards = cards;
                publish();
            });
            mClock.start();
        }
        if (SmartspaceEvent.WEATHER.isEnabled(mContext)) {
            mWeather = new QuickspaceController(mContext, true);
            mWeather.addListener(this);
        }
        if (SmartspaceEvent.MUSIC.isEnabled(mContext)) {
            try {
                MSMHProxy.INSTANCE(mContext).addMediaMetadataListener(this);
                mMediaRegistered = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot observe media", e);
            }
        }
        updateAlarm();
        publish();
    }

    private void stop() {
        if (!mActive) return;
        mActive = false;
        mGeneration++;
        if (mCalendarQuery != null) {
            mCalendarQuery.cancel();
            mCalendarQuery = null;
        }
        mHandler.removeCallbacksAndMessages(null);
        if (mReceiverRegistered) mContext.unregisterReceiver(mReceiver);
        mReceiverRegistered = false;
        if (mObserverRegistered) mContext.getContentResolver().unregisterContentObserver(mObserver);
        mObserverRegistered = false;
        if (mBluetooth != null) mBluetooth.stop();
        mBluetooth = null;
        mBluetoothCards = List.of();
        if (mClock != null) mClock.stop();
        mClock = null;
        mClockCards = List.of();
        if (mMediaRegistered) MSMHProxy.INSTANCE(mContext).removeMediaMetadataListener(this);
        mMediaRegistered = false;
        if (mWeather != null) {
            mWeather.removeListener(this);
            mWeather.onDestroy();
            mWeather = null;
        }
        disconnectSession();
        mTargets.clear();
        mLocal.clear();
    }

    private void connectSession() {
        boolean enabled = false;
        for (SmartspaceEvent event : SmartspaceEvent.values()) {
            enabled |= event != SmartspaceEvent.BATTERY && event.isEnabled(mContext);
        }
        if (!enabled) return;

        SmartspaceManager manager = mContext.getSystemService(SmartspaceManager.class);
        if (manager == null) return;
        try {
            SmartspaceSession session = manager.createSmartspaceSession(
                    new SmartspaceConfig.Builder(mContext, "home")
                            .setSmartspaceTargetCount(20).build());
            mSession = session;
            mSessionListener = targets -> {
                if (!mActive || mSession != session) return;
                mTargets.clear();
                mTargets.addAll(targets);
                publish();
            };
            session.addOnTargetsAvailableListener(MAIN_EXECUTOR, mSessionListener);
            session.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(
                    SmartspaceTargetEvent.EVENT_UI_SURFACE_SHOWN).build());
            session.requestSmartspaceUpdate();
        } catch (RuntimeException e) {
            Log.w(TAG, "Smartspace service unavailable; using local events", e);
            disconnectSession();
        }
    }

    private void disconnectSession() {
        SmartspaceSession session = mSession;
        mSession = null;
        if (session == null) return;
        try {
            if (mSessionListener != null) {
                session.removeOnTargetsAvailableListener(mSessionListener);
            }
            session.notifySmartspaceEvent(new SmartspaceTargetEvent.Builder(
                    SmartspaceTargetEvent.EVENT_UI_SURFACE_HIDDEN).build());
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot notify Smartspace service", e);
        } finally {
            mSessionListener = null;
            try {
                session.close();
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot close Smartspace session", e);
            }
        }
    }

    void notifyEvent(SmartspaceCard card, int type) {
        if (mSession == null || card == null || card.target == null) return;
        try {
            SmartspaceTargetEvent.Builder event = new SmartspaceTargetEvent.Builder(type)
                    .setSmartspaceTarget(card.target);
            if (card.actionId != null) event.setSmartspaceActionId(card.actionId);
            mSession.notifySmartspaceEvent(event.build());
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot send Smartspace event", e);
        }
    }

    private void publish() {
        if (!mActive) return;
        long now = System.currentTimeMillis();
        long nextUpdate = Long.MAX_VALUE;
        List<SmartspaceCard> cards = new ArrayList<>(mClockCards);
        cards.addAll(mBluetoothCards);
        Set<SmartspaceEvent> provided = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (SmartspaceTarget target : mTargets) {
            int feature = target.getFeatureType();
            if (feature == SmartspaceTarget.FEATURE_FLASHLIGHT) continue;
            if (feature == SmartspaceTarget.FEATURE_TIMER && mClockCards.stream()
                    .anyMatch(card -> card.id.startsWith("clock_timer_"))) continue;
            if (feature == SmartspaceTarget.FEATURE_STOPWATCH && mClockCards.stream()
                    .anyMatch(card -> card.id.startsWith("clock_stopwatch_"))) continue;
            if (!mBluetoothCards.isEmpty()
                    && feature == SmartspaceTarget.FEATURE_PAIRED_DEVICE_STATE) continue;
            SmartspaceCard card;
            try {
                card = SmartspaceCard.fromTarget(mContext, target);
            } catch (RuntimeException e) {
                Log.w(TAG, "Cannot read Smartspace target", e);
                continue;
            }
            if (card != null && ids.add(card.id)) {
                cards.add(card);
                if (card.event != SmartspaceEvent.WEATHER
                        || target.getFeatureType() == SmartspaceTarget.FEATURE_WEATHER) {
                    provided.add(card.event);
                }
            }
            if (target.getCreationTimeMillis() > now) {
                nextUpdate = Math.min(nextUpdate, target.getCreationTimeMillis());
            }
            if (target.getExpiryTimeMillis() > now) {
                nextUpdate = Math.min(nextUpdate, target.getExpiryTimeMillis());
            }
        }
        for (SmartspaceEvent event : LOCAL_ORDER) {
            SmartspaceCard card = mLocal.get(event);
            if (card == null) continue;
            if (!provided.contains(card.event) && card.event.isEnabled(mContext)
                    && (card.expiresAt <= 0 || card.expiresAt > now)) {
                cards.add(card);
                if (card.expiresAt > now) nextUpdate = Math.min(nextUpdate, card.expiresAt);
            }
        }
        mListener.accept(cards);
        mHandler.removeCallbacks(mPublish);
        if (nextUpdate != Long.MAX_VALUE) {
            mHandler.postDelayed(mPublish, Math.max(1, nextUpdate - now));
        }
    }

    @Override
    public void onDataUpdated() {
        if (!mActive || mWeather == null) return;
        String text = mWeather.getWeatherTemp();
        if (mWeather.isWeatherAvailable() && !TextUtils.isEmpty(text)) {
            mLocal.put(SmartspaceEvent.WEATHER, new SmartspaceCard("local_weather",
                    SmartspaceEvent.WEATHER, text, null, null, mWeather.getWeatherIcon(),
                    QuickSpaceActionReceiver.getWeatherAction(), 0));
        } else {
            mLocal.remove(SmartspaceEvent.WEATHER);
        }
        publish();
    }

    @Override
    public void onMediaMetadataChanged() {
        if (!mActive) return;
        MediaSessionManagerHelper media = MSMHProxy.INSTANCE(mContext);
        MediaMetadata metadata = media.getCurrentMediaMetadata();
        CharSequence title = metadata == null ? null
                : metadata.getText(MediaMetadata.METADATA_KEY_TITLE);
        CharSequence artist = metadata == null ? null
                : metadata.getText(MediaMetadata.METADATA_KEY_ARTIST);
        if (media.isMediaPlaying() && !TextUtils.isEmpty(title)) {
            Bitmap artwork = media.getMediaBitmap();
            Icon icon = artwork == null ? null : Icon.createWithBitmap(artwork);
            Drawable drawable = icon == null ? media.getMediaAppIcon() : null;
            String musicId = "local_music_" + Objects.hash(title, artist);
            mLocal.put(SmartspaceEvent.MUSIC, new SmartspaceCard(musicId, SmartspaceEvent.MUSIC,
                    title, artist, icon, drawable,
                    view -> media.launchMediaApp(), 0));
        } else {
            mLocal.remove(SmartspaceEvent.MUSIC);
        }
        publish();
    }

    @Override
    public void onPlaybackStateChanged() {
        onMediaMetadataChanged();
    }

    private void updateAlarm() {
        if (!SmartspaceEvent.ALARMS.isEnabled(mContext)) return;
        AlarmManager manager = mContext.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo alarm = manager == null ? null : manager.getNextAlarmClock();
        long now = System.currentTimeMillis();
        if (alarm != null && alarm.getTriggerTime() > now
                && alarm.getTriggerTime() <= now + 12 * DateUtils.HOUR_IN_MILLIS) {
            String time = DateFormat.getTimeFormat(mContext).format(new Date(alarm.getTriggerTime()));
            mLocal.put(SmartspaceEvent.ALARMS,
                    new SmartspaceCard("local_alarm_" + alarm.getTriggerTime(),
                            SmartspaceEvent.ALARMS,
                            mContext.getText(R.string.smartspace_next_alarm), time, null, null,
                            SmartspaceCard.action(new Intent(AlarmClock.ACTION_SHOW_ALARMS),
                                    alarm.getShowIntent()), alarm.getTriggerTime()));
        } else {
            mLocal.remove(SmartspaceEvent.ALARMS);
        }
    }

    private void updateFlashlight() {
        if (!SmartspaceEvent.FLASHLIGHT.isEnabled(mContext)) return;
        if (Settings.Secure.getInt(mContext.getContentResolver(), FLASHLIGHT_ENABLED, 0) != 0) {
            mLocal.put(SmartspaceEvent.FLASHLIGHT,
                    new SmartspaceCard("local_flashlight", SmartspaceEvent.FLASHLIGHT,
                            mContext.getText(R.string.smartspace_flashlight_on),
                            mContext.getText(R.string.smartspace_flashlight_summary),
                            Icon.createWithResource("android", com.android.internal.R.drawable.ic_qs_flashlight),
                            null,
                            view -> Launcher.getLauncher(view.getContext()).turnOffFlashlight(), 0));
        } else {
            mLocal.remove(SmartspaceEvent.FLASHLIGHT);
        }
    }

    private void updateBattery(Intent intent) {
        if (!SmartspaceEvent.BATTERY.isEnabled(mContext)) return;
        int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        int percent = scale > 0 && level >= 0 ? Math.min(100, level * 100 / scale) : -1;
        if (percent >= 0 && (charging || percent <= 20)) {
            mLocal.put(SmartspaceEvent.BATTERY, new SmartspaceCard("local_battery_" + charging,
                    SmartspaceEvent.BATTERY,
                    mContext.getString(charging ? R.string.smartspace_charging
                            : R.string.smartspace_low_battery, percent),
                    mContext.getText(R.string.smartspace_battery_settings), null, null,
                    SmartspaceCard.action(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY), null), 0));
        } else {
            mLocal.remove(SmartspaceEvent.BATTERY);
        }
    }

    private void scheduleCalendarQuery() {
        if (!SmartspaceEvent.CALENDAR.isEnabled(mContext)) return;
        mHandler.removeCallbacks(mQueryCalendar);
        mHandler.postDelayed(mQueryCalendar, 150);
    }

    private void queryCalendar() {
        if (!mActive) return;
        if (!CalendarProvider.hasPermission(mContext)) {
            mLocal.remove(SmartspaceEvent.CALENDAR);
            publish();
            return;
        }
        if (mCalendarQuery != null) mCalendarQuery.cancel();
        CancellationSignal cancellation = new CancellationSignal();
        mCalendarQuery = cancellation;
        int generation = mGeneration;
        UI_HELPER_EXECUTOR.execute(() -> {
            SmartspaceCard card = readCalendar(cancellation);
            mHandler.post(() -> {
                if (!mActive || generation != mGeneration || cancellation.isCanceled()) return;
                mCalendarQuery = null;
                if (card == null) mLocal.remove(SmartspaceEvent.CALENDAR);
                else mLocal.put(SmartspaceEvent.CALENDAR, card);
                publish();
            });
        });
    }

    private SmartspaceCard readCalendar(CancellationSignal cancellation) {
        long now = System.currentTimeMillis();
        Uri.Builder uri = CalendarContract.Instances.CONTENT_URI.buildUpon();
        ContentUris.appendId(uri, now);
        ContentUris.appendId(uri, now + EVENT_WINDOW);
        String[] projection = {CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END};
        String selection = CalendarContract.Instances.VISIBLE + "=1 AND "
                + CalendarContract.Instances.ALL_DAY + "=0 AND "
                + CalendarContract.Instances.SELF_ATTENDEE_STATUS + "!="
                + CalendarContract.Attendees.ATTENDEE_STATUS_DECLINED + " AND "
                + CalendarContract.Instances.STATUS + "!=" + CalendarContract.Events.STATUS_CANCELED;
        try (Cursor cursor = mContext.getContentResolver().query(uri.build(), projection,
                selection, null, CalendarContract.Instances.BEGIN + " ASC", cancellation)) {
            if (cursor == null) return null;
            while (cursor.moveToNext()) {
                long end = cursor.getLong(3);
                if (end <= now) continue;
                long id = cursor.getLong(0);
                String title = cursor.getString(1);
                CharSequence time = DateUtils.getRelativeTimeSpanString(cursor.getLong(2), now,
                        DateUtils.MINUTE_IN_MILLIS);
                Intent intent = new Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI, id));
                return new SmartspaceCard("local_calendar_" + id + "_" + cursor.getLong(2),
                        SmartspaceEvent.CALENDAR,
                        TextUtils.isEmpty(title) ? mContext.getText(R.string.smartspace_event_untitled)
                                : title, time, null, null, SmartspaceCard.action(intent, null), end);
            }
        } catch (RuntimeException e) {
            if (!cancellation.isCanceled()) Log.w(TAG, "Cannot read calendar", e);
        }
        return null;
    }

}
