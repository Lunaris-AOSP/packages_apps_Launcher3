package com.android.launcher3.smartspace;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Chronometer;
import android.widget.FrameLayout;
import android.widget.RemoteViews;

import com.android.launcher3.R;

import java.util.Locale;

final class SmartspaceClock {
    final long base;
    final boolean countDown;
    final boolean running;
    private final CharSequence mPausedText;
    private final long mPausedDuration;

    private SmartspaceClock(long base, boolean countDown, boolean running, CharSequence pausedText,
            long pausedDuration) {
        this.base = base;
        this.countDown = countDown;
        this.running = running;
        mPausedText = pausedText;
        mPausedDuration = pausedDuration;
    }

    CharSequence text(Context context) {
        CharSequence time = running || TextUtils.isEmpty(mPausedText)
                ? formatTime(base, countDown, SystemClock.elapsedRealtime()) : mPausedText;
        return running ? time : context.getString(R.string.smartspace_clock_paused, time);
    }

    static String formatTime(long base, boolean countDown, long now) {
        long millis = Math.max(0, countDown ? base - now : now - base);
        return DateUtils.formatElapsedTime(countDown ? (millis + 999) / 1000 : millis / 1000);
    }

    static SmartspaceCard fromNotification(Context context, StatusBarNotification sbn,
            SmartspaceCard previous) {
        Notification notification = sbn.getNotification();
        if (!Process.myUserHandle().equals(sbn.getUser())
                || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        String pkg = sbn.getPackageName();
        boolean clockApp = "com.google.android.deskclock".equals(pkg)
                || "com.android.deskclock".equals(pkg);
        boolean showChronometer = notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER);
        if (!clockApp && !showChronometer) return null;
        String channel = lower(notification.getChannelId());
        StringBuilder actionNames = new StringBuilder();
        StringBuilder actionLabels = new StringBuilder();
        if (notification.actions != null) {
            for (Notification.Action action : notification.actions) {
                actionLabels.append(' ').append(lower(action.title));
                try {
                    Intent intent = action.actionIntent == null
                            ? null : action.actionIntent.getIntent();
                    if (intent != null) actionNames.append(' ').append(lower(intent.getAction()));
                } catch (RuntimeException ignored) { }
            }
        }
        String names = actionNames.toString();
        String labels = actionLabels.toString();
        boolean countDown = notification.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN);
        boolean stopwatch = channel.contains("stopwatch") || names.contains("_stopwatch");
        boolean timer = !stopwatch && (channel.contains("timer") || names.contains("_timer")
                || countDown);
        if (!timer && !stopwatch && clockApp && showChronometer) stopwatch = !countDown;
        if (!timer && !stopwatch) return null;

        boolean running;
        if (names.contains("pause_") || names.contains("lap_stopwatch")) running = true;
        else if (names.contains("start_") || names.contains("resume_")) running = false;
        else if (labels.contains("pause") || labels.contains("lap")) running = true;
        else if (labels.contains("resume") || labels.contains("start")) running = false;
        else running = sbn.isOngoing();

        Snapshot snapshot = snapshot(context, notification.bigContentView,
                notification.contentView, notification.headsUpContentView);
        long now = SystemClock.elapsedRealtime();
        Long base = snapshot == null ? null : snapshot.base;
        if (base == null && showChronometer && notification.when > 0) {
            base = now + notification.when - System.currentTimeMillis();
        }
        if (base == null && timer && notification.when > System.currentTimeMillis()) {
            base = now + notification.when - System.currentTimeMillis();
        }
        SmartspaceClock oldClock = previous == null ? null : previous.clock;
        if (base == null && oldClock != null && oldClock.countDown == timer) {
            base = running && !oldClock.running
                    ? now + (timer ? oldClock.mPausedDuration : -oldClock.mPausedDuration)
                    : oldClock.base;
        }

        CharSequence title = notification.extras.getCharSequence(Notification.EXTRA_TITLE);
        if (TextUtils.isEmpty(title)) title = context.getString(timer
                ? R.string.smartspace_timer : R.string.smartspace_stopwatch);
        CharSequence subtitle = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
        SmartspaceClock clock = null;
        if (base != null) {
            CharSequence paused = snapshot == null ? null : snapshot.text;
            long duration = Math.max(0, timer ? base - now : now - base);
            if (!running && oldClock != null && !oldClock.running && oldClock.base == base) {
                paused = oldClock.mPausedText;
                duration = oldClock.mPausedDuration;
            }
            if (TextUtils.isEmpty(paused)) paused = formatTime(base, timer, now);
            clock = new SmartspaceClock(base, timer, running, paused, duration);
        }
        PendingIntent pending = notification.contentIntent;
        Intent launch = pending == null ? context.getPackageManager().getLaunchIntentForPackage(pkg)
                : null;
        return new SmartspaceCard((timer ? "clock_timer_" : "clock_stopwatch_") + sbn.getKey(),
                SmartspaceEvent.TIMERS,
                title, subtitle, null, null, SmartspaceCard.action(launch, pending), 0)
                .withClock(clock);
    }

    private static Snapshot snapshot(Context context, RemoteViews... views) {
        for (RemoteViews remote : views) {
            if (remote == null) continue;
            try {
                Context packageContext = context.createPackageContext(remote.getPackage(),
                        Context.CONTEXT_RESTRICTED);
                View root = remote.apply(packageContext, new FrameLayout(packageContext));
                Chronometer chronometer = findChronometer(root);
                if (chronometer == null) continue;
                Snapshot result = new Snapshot(chronometer.getBase(), chronometer.getText());
                chronometer.stop();
                return result;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static Chronometer findChronometer(View view) {
        if (view instanceof Chronometer) return (Chronometer) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Chronometer result = findChronometer(group.getChildAt(i));
                if (result != null) return result;
            }
        }
        return null;
    }

    private static String lower(CharSequence text) {
        return text == null ? "" : text.toString().toLowerCase(Locale.ROOT);
    }

    private static final class Snapshot {
        final long base;
        final CharSequence text;

        Snapshot(long base, CharSequence text) {
            this.base = base;
            this.text = text;
        }
    }
}
