package com.android.launcher3.search.universal;

import android.content.Context;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.graphics.Outline;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.launcher3.R;
import com.android.launcher3.views.ActivityContext;

public class UniversalSearchResults {

    public static String getSectionTitle(Context context, int source) {
        switch (source) {
            case UniversalSearchResult.SOURCE_CONVERSATION:
                return context.getString(R.string.search_section_conversations);
            case UniversalSearchResult.SOURCE_SHORTCUT:
                return context.getString(R.string.search_section_shortcuts);
            case UniversalSearchResult.SOURCE_CONTACT:
                return context.getString(R.string.search_section_contacts);
            case UniversalSearchResult.SOURCE_QS_TILE:
                return context.getString(R.string.search_section_qs_tiles);
            case UniversalSearchResult.SOURCE_CALCULATOR:
                return context.getString(R.string.search_section_calculator);
            case UniversalSearchResult.SOURCE_FILE:
                return context.getString(R.string.search_section_files);
            case UniversalSearchResult.SOURCE_EVENT:
                return context.getString(R.string.search_section_events);
            case UniversalSearchResult.SOURCE_WEB:
                return context.getString(R.string.search_section_actions);
            case UniversalSearchResult.SOURCE_SETTING:
                return context.getString(R.string.search_section_settings);
            default:
                return "";
        }
    }

    private static int getFallbackIcon(int source) {
        switch (source) {
            case UniversalSearchResult.SOURCE_CONVERSATION:
                return R.drawable.ic_search_conversation;
            case UniversalSearchResult.SOURCE_CONTACT:
                return R.drawable.ic_search_contact;
            case UniversalSearchResult.SOURCE_QS_TILE:
            case UniversalSearchResult.SOURCE_SETTING:
                return R.drawable.ic_setting;
            case UniversalSearchResult.SOURCE_CALCULATOR:
                return R.drawable.ic_search_calculator;
            case UniversalSearchResult.SOURCE_FILE:
                return R.drawable.ic_search_file;
            case UniversalSearchResult.SOURCE_EVENT:
                return R.drawable.ic_search_event;
            case UniversalSearchResult.SOURCE_WEB:
                return R.drawable.ic_search_web;
            default:
                return R.drawable.ic_search_conversation;
        }
    }

    public static void bind(ActivityContext activityContext, View view,
            UniversalSearchResult result) {
        if (result == null) {
            return;
        }
        ImageView icon = view.findViewById(R.id.search_result_icon);
        TextView title = view.findViewById(R.id.search_result_title);
        TextView subtitle = view.findViewById(R.id.search_result_subtitle);

        title.setText(result.title);
        if (TextUtils.isEmpty(result.subtitle)) {
            subtitle.setVisibility(View.GONE);
        } else {
            subtitle.setVisibility(View.VISIBLE);
            subtitle.setText(result.subtitle);
        }
        View call = view.findViewById(R.id.search_result_call);
        if (result.phoneNumber == null) {
            call.setVisibility(View.GONE);
        } else {
            call.setVisibility(View.VISIBLE);
            call.setOnClickListener(v -> {
                Intent dial = new Intent(Intent.ACTION_DIAL,
                        Uri.fromParts("tel", result.phoneNumber, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activityContext.startActivitySafely(v, dial, null);
            });
        }
        applyIcon(icon, result);
        CompoundButton toggle = view.findViewById(R.id.search_result_switch);
        if (result.toggle == null) {
            toggle.setVisibility(View.GONE);
            view.setOnClickListener(v -> launch(activityContext, v, result));
            view.setOnLongClickListener(null);
            view.setLongClickable(false);
            return;
        }
        toggle.setVisibility(View.VISIBLE);
        toggle.setChecked(result.checked);
        view.setOnClickListener(v -> {
            boolean on = !result.checked;
            if (result.toggle.set(v.getContext(), on)) {
                result.checked = on;
                toggle.setChecked(on);
            } else if (result.intent != null) {
                activityContext.startActivitySafely(v, result.intent, null);
            }
        });
        view.setOnLongClickListener(result.intent == null ? null : v -> {
            activityContext.startActivitySafely(v, result.intent, null);
            return true;
        });
    }

    private static void applyIcon(ImageView icon, UniversalSearchResult result) {
        if (result.icon == null) {
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClipToOutline(false);
            icon.setOutlineProvider(null);
            icon.setImageResource(getFallbackIcon(result.source));
            return;
        }
        icon.setImageDrawable(result.icon);
        if (!result.thumbnail) {
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setClipToOutline(false);
            icon.setOutlineProvider(null);
            return;
        }
        boolean circle = result.source == UniversalSearchResult.SOURCE_CONTACT;
        float radius = circle ? -1
                : icon.getResources().getDimension(R.dimen.search_thumbnail_radius);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int size = Math.min(view.getWidth(), view.getHeight());
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        radius < 0 ? size / 2f : radius);
            }
        });
        icon.setClipToOutline(true);
    }

    private static void launch(ActivityContext activityContext, View view,
            UniversalSearchResult result) {
        Context context = activityContext.asContext();
        if (result.intent != null) {
            activityContext.startActivitySafely(view, result.intent, null);
            return;
        }
        if (result.shortcutId == null || result.packageName == null) {
            return;
        }
        LauncherApps launcherApps = context.getSystemService(LauncherApps.class);
        if (launcherApps == null) {
            return;
        }
        try {
            launcherApps.startShortcut(result.packageName, result.shortcutId, null, null,
                    result.user);
        } catch (SecurityException | IllegalStateException e) {
            // The shortcut's app was uninstalled or disabled between query and tap.
        }
    }
}
