package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

public class UniversalSearchResult {

    public static final int SOURCE_APP = 0;
    public static final int SOURCE_SHORTCUT = 1;
    public static final int SOURCE_CONVERSATION = 2;
    public static final int SOURCE_CONTACT = 3;
    public static final int SOURCE_SETTING = 4;
    public static final int SOURCE_QS_TILE = 5;
    public static final int SOURCE_CALCULATOR = 6;
    public static final int SOURCE_FILE = 7;
    public static final int SOURCE_EVENT = 8;
    public static final int SOURCE_WEB = 9;

    public final int source;
    public final String id;
    public final CharSequence title;
    public final CharSequence subtitle;
    public final Intent intent;
    public final UserHandle user;
    public final int score;

    public Drawable icon;
    public String packageName;
    public String shortcutId;
    public String phoneNumber;
    public boolean thumbnail;
    public Toggle toggle;
    public boolean checked;

    public interface Toggle {
        boolean set(Context context, boolean on);
    }

    public UniversalSearchResult(int source, String id, CharSequence title, CharSequence subtitle,
            Intent intent, UserHandle user, int score) {
        this.source = source;
        this.id = id;
        this.title = title;
        this.subtitle = subtitle;
        this.intent = intent;
        this.user = user;
        this.score = score;
    }
}
