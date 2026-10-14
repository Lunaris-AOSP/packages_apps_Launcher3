package com.android.launcher3.smartspace;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.launcher3.notification.NotificationListener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

final class SmartspaceClockController implements NotificationListener.NotificationsChangedListener {
    private final Context mContext;
    private final Consumer<List<SmartspaceCard>> mListener;
    private final Map<String, SmartspaceCard> mCards = new LinkedHashMap<>();
    private boolean mActive;

    SmartspaceClockController(Context context, Consumer<List<SmartspaceCard>> listener) {
        mContext = context;
        mListener = listener;
    }

    void start() {
        mActive = true;
        NotificationListener.addNotificationsChangedListener(mContext, this);
    }

    void stop() {
        mActive = false;
        NotificationListener.removeNotificationsChangedListener(this);
        mCards.clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification notification) {
        if (!mActive) return;
        if (update(notification)) mListener.accept(new ArrayList<>(mCards.values()));
    }

    private boolean update(StatusBarNotification notification) {
        SmartspaceCard card;
        try {
            card = SmartspaceClock.fromNotification(mContext, notification,
                    mCards.get(notification.getKey()));
        } catch (RuntimeException e) {
            Log.w("SmartspaceClock", "Cannot read Clock notification", e);
            return false;
        }
        if (card == null) return mCards.remove(notification.getKey()) != null;
        mCards.put(notification.getKey(), card);
        return true;
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification notification) {
        if (mActive && mCards.remove(notification.getKey()) != null) {
            mListener.accept(new ArrayList<>(mCards.values()));
        }
    }

    @Override
    public void onNotificationsFullRefresh(List<StatusBarNotification> notifications) {
        if (!mActive) return;
        Set<String> keys = new HashSet<>();
        for (StatusBarNotification notification : notifications) {
            keys.add(notification.getKey());
            update(notification);
        }
        mCards.keySet().retainAll(keys);
        mListener.accept(new ArrayList<>(mCards.values()));
    }
}
