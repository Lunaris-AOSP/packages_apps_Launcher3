package com.android.launcher3.smartspace;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.BaseTemplateData.SubItemInfo;
import android.app.smartspace.uitemplatedata.TapAction;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Process;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;
import android.widget.RemoteViews;

import com.android.launcher3.Launcher;
import com.android.launcher3.R;

final class SmartspaceCard {
    final String id;
    final SmartspaceEvent event;
    final CharSequence title;
    final CharSequence subtitle;
    final CharSequence details;
    final Icon icon;
    final Drawable drawable;
    final View.OnClickListener action;
    final SmartspaceTarget target;
    final String actionId;
    final long expiresAt;
    final SmartspaceClock clock;
    final RemoteViews remoteViews;

    SmartspaceCard(String id, SmartspaceEvent event, CharSequence title, CharSequence subtitle,
            Icon icon, Drawable drawable, View.OnClickListener action, long expiresAt) {
        this(id, event, title, subtitle, null, icon, drawable, action, expiresAt, null, null, null);
    }

    SmartspaceCard(String id, SmartspaceEvent event, CharSequence title, CharSequence subtitle,
            CharSequence details, Icon icon, Drawable drawable, View.OnClickListener action,
            long expiresAt) {
        this(id, event, title, subtitle, details, icon, drawable, action, expiresAt, null, null, null);
    }

    SmartspaceCard withClock(SmartspaceClock clock) {
        return new SmartspaceCard(id, event, title, subtitle, details, icon, drawable, action,
                expiresAt, target, actionId, clock);
    }

    private SmartspaceCard(String id, SmartspaceEvent event, CharSequence title,
            CharSequence subtitle, CharSequence details, Icon icon, Drawable drawable,
            View.OnClickListener action, long expiresAt, SmartspaceTarget target,
            String actionId, SmartspaceClock clock) {
        this.id = id;
        this.event = event;
        this.title = title;
        this.subtitle = subtitle;
        this.details = details;
        this.icon = icon;
        this.drawable = drawable;
        this.action = action;
        this.expiresAt = expiresAt;
        this.target = target;
        this.actionId = actionId;
        this.clock = clock;
        this.remoteViews = target == null ? null : target.getRemoteViews();
    }

    static SmartspaceCard fromTarget(Context context, SmartspaceTarget target) {
        SmartspaceEvent event = SmartspaceEvent.forFeature(target.getFeatureType());
        if (!event.isEnabled(context) || !Process.myUserHandle().equals(target.getUserHandle())
                || target.getFeatureType() == SmartspaceTarget.FEATURE_STEP_DATE) return null;
        long now = System.currentTimeMillis();
        if (target.getCreationTimeMillis() > now
                || (target.getExpiryTimeMillis() > 0 && target.getExpiryTimeMillis() <= now)) {
            return null;
        }
        SmartspaceAction header = target.getHeaderAction();
        SmartspaceAction base = target.getBaseAction();
        SmartspaceAction display = header != null && hasDisplayContent(header) ? header : base;
        CharSequence title = display == null ? null : display.getTitle();
        CharSequence subtitle = display == null ? null : display.getSubtitle();
        CharSequence details = null;
        Icon icon = display == null ? null : display.getIcon();
        if (TextUtils.isEmpty(title) && base != null) title = base.getTitle();
        if (TextUtils.isEmpty(subtitle) && base != null) subtitle = base.getSubtitle();
        if (icon == null && base != null) icon = base.getIcon();
        SmartspaceAction click = hasAction(header) ? header : base;
        Intent intent = click == null ? null : click.getIntent();
        PendingIntent pending = click == null ? null : click.getPendingIntent();
        String actionId = click == null ? null : click.getId();
        BaseTemplateData template = target.getTemplateData();
        if (template != null) {
            SubItemInfo primary = allowedItem(context, template.getPrimaryItem(), event);
            SubItemInfo secondary = allowedItem(context, template.getSubtitleItem(), event);
            if (primary != null && primary.getText() != null) {
                CharSequence templateTitle = primary.getText().getText();
                if (!TextUtils.isEmpty(templateTitle)) title = templateTitle;
            }
            if (secondary != null && secondary.getText() != null) {
                CharSequence templateSubtitle = secondary.getText().getText();
                if (!TextUtils.isEmpty(templateSubtitle)) subtitle = templateSubtitle;
            }
            SubItemInfo subtitleSupplemental = allowedItem(context,
                    template.getSubtitleSupplementalItem(), event);
            SubItemInfo supplementalLine = allowedItem(context,
                    template.getSupplementalLineItem(), event);
            SubItemInfo supplementalAlarm = allowedItem(context,
                    template.getSupplementalAlarmItem(), event);
            Icon templateIcon = getTemplateIcon(primary, secondary, subtitleSupplemental,
                    supplementalLine, supplementalAlarm);
            if (templateIcon != null) {
                icon = templateIcon;
            }
            CharSequence templateDetails = joinDetails(subtitleSupplemental, supplementalLine,
                    supplementalAlarm);
            if (!TextUtils.isEmpty(templateDetails)) {
                details = templateDetails;
            }
            TapAction tap = getTemplateTapAction(primary, secondary, subtitleSupplemental,
                    supplementalLine, supplementalAlarm);
            if (tap != null && (tap.getUserHandle() == null
                    || Process.myUserHandle().equals(tap.getUserHandle()))) {
                intent = tap.getIntent();
                pending = tap.getPendingIntent();
                actionId = tap.getId() == null ? null : tap.getId().toString();
            }
        }
        SmartspaceContent content = SmartspaceContent.read(context, target, event);
        if (icon == null || (content.icon != null
                && (event == SmartspaceEvent.PACKAGES || event == SmartspaceEvent.DOORBELL))) {
            icon = content.icon;
        }
        if (intent == null && pending == null) {
            if (content.tap != null) {
                intent = content.tap.getIntent();
                pending = content.tap.getPendingIntent();
                actionId = content.tap.getId() == null ? null : content.tap.getId().toString();
            } else if (content.action != null) {
                intent = content.action.getIntent();
                pending = content.action.getPendingIntent();
                actionId = content.action.getId();
            }
        }
        content.remove(title);
        content.remove(subtitle);
        if (TextUtils.isEmpty(title) && !TextUtils.isEmpty(subtitle)) {
            title = subtitle;
            subtitle = null;
        }
        if (TextUtils.isEmpty(title)) title = content.takeFirst();
        if (TextUtils.isEmpty(title) && (icon != null || target.getRemoteViews() != null)) {
            title = context.getText(event.title);
        }
        if (TextUtils.isEmpty(subtitle)) subtitle = content.takeFirst();
        content.add(details);
        content.remove(title);
        content.remove(subtitle);
        details = TextUtils.join(" · ", content.lines);
        if (TextUtils.isEmpty(title)) return null;
        return new SmartspaceCard(target.getSmartspaceTargetId(), event, title, subtitle, details,
                icon, null, action(intent, pending), target.getExpiryTimeMillis(), target, actionId,
                null);
    }

    private static Icon getTemplateIcon(SubItemInfo... items) {
        for (SubItemInfo item : items) {
            if (item != null && item.getIcon() != null) {
                android.app.smartspace.uitemplatedata.Icon templateIcon = item.getIcon();
                Icon icon = templateIcon.getIcon();
                if (icon != null) return icon;
            }
        }
        return null;
    }

    private static CharSequence joinDetails(SubItemInfo... items) {
        StringBuilder details = new StringBuilder();
        for (SubItemInfo item : items) {
            if (item == null || item.getText() == null
                    || TextUtils.isEmpty(item.getText().getText())) continue;
            if (details.length() > 0) details.append(" · ");
            details.append(item.getText().getText());
        }
        return details.length() == 0 ? null : details.toString();
    }

    private static boolean hasDisplayContent(SmartspaceAction action) {
        return !TextUtils.isEmpty(action.getTitle()) || !TextUtils.isEmpty(action.getSubtitle())
                || action.getIcon() != null;
    }

    private static TapAction getTemplateTapAction(SubItemInfo... items) {
        for (SubItemInfo item : items) {
            if (item == null || item.getTapAction() == null) continue;
            TapAction tap = item.getTapAction();
            if ((tap.getIntent() != null || tap.getPendingIntent() != null)
                    && (tap.getUserHandle() == null
                    || Process.myUserHandle().equals(tap.getUserHandle()))) {
                return tap;
            }
        }
        return null;
    }

    static SubItemInfo allowedItem(Context context, SubItemInfo item,
            SmartspaceEvent targetEvent) {
        if (item == null || item.getLoggingInfo() == null) return item;
        // Fast Pair publishes accessory battery/charging rows as part of the paired-device card.
        // Do not hide those rows just because the separate phone-battery preference is disabled.
        if (targetEvent == SmartspaceEvent.BLUETOOTH) return item;
        int feature = item.getLoggingInfo().getFeatureType();
        return feature == SmartspaceTarget.FEATURE_UNDEFINED
                || feature == SmartspaceTarget.FEATURE_STEP_DATE
                || SmartspaceEvent.forFeature(feature).isEnabled(context) ? item : null;
    }

    private static boolean hasAction(SmartspaceAction action) {
        return action != null && (action.getPendingIntent() != null || action.getIntent() != null);
    }

    static View.OnClickListener action(Intent intent, PendingIntent pending) {
        if (intent == null && pending == null) return null;
        return view -> {
            try {
                if (pending != null) {
                    ActivityOptions options = ActivityOptions.makeBasic()
                            .setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                    pending.send(view.getContext(), 0, null, null, null, null, options.toBundle());
                } else {
                    Launcher.getLauncher(view.getContext()).startActivitySafely(view,
                            new Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), null);
                }
            } catch (PendingIntent.CanceledException | RuntimeException e) {
                Toast.makeText(view.getContext(), R.string.smartspace_action_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
        };
    }
}
