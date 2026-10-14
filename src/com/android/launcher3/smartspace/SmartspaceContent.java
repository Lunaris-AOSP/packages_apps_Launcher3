package com.android.launcher3.smartspace;

import android.app.smartspace.SmartspaceAction;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.CarouselTemplateData;
import android.app.smartspace.uitemplatedata.CombinedCardsTemplateData;
import android.app.smartspace.uitemplatedata.HeadToHeadTemplateData;
import android.app.smartspace.uitemplatedata.SubCardTemplateData;
import android.app.smartspace.uitemplatedata.SubImageTemplateData;
import android.app.smartspace.uitemplatedata.SubListTemplateData;
import android.app.smartspace.uitemplatedata.TapAction;
import android.app.smartspace.uitemplatedata.Text;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;

import java.util.LinkedHashSet;
import java.util.Set;

final class SmartspaceContent {
    final Set<String> lines = new LinkedHashSet<>();
    Icon icon;
    TapAction tap;
    SmartspaceAction action;

    static SmartspaceContent read(Context context, SmartspaceTarget target, SmartspaceEvent event) {
        SmartspaceContent content = new SmartspaceContent();
        content.readTemplate(context, target.getTemplateData(), event, 0);
        content.readAction(target.getBaseAction(), target.getTemplateData() == null);
        if (target.getActionChips() != null) {
            for (SmartspaceAction chip : target.getActionChips()) content.readAction(chip, true);
        }
        if (target.getIconGrid() != null) {
            for (SmartspaceAction item : target.getIconGrid()) content.readAction(item, true);
        }
        return content;
    }

    private void readTemplate(Context context, BaseTemplateData data, SmartspaceEvent event,
            int depth) {
        if (data == null || depth > 3) return;
        if (depth > 0) {
            if (data.getPrimaryItem() != null
                    && SmartspaceCard.allowedItem(context, data.getPrimaryItem(), event) == null) {
                return;
            }
            readItem(context, data.getPrimaryItem(), event);
            readItem(context, data.getSubtitleItem(), event);
            readItem(context, data.getSubtitleSupplementalItem(), event);
            readItem(context, data.getSupplementalLineItem(), event);
            readItem(context, data.getSupplementalAlarmItem(), event);
        }
        if (data instanceof HeadToHeadTemplateData) {
            HeadToHeadTemplateData head = (HeadToHeadTemplateData) data;
            add(head.getHeadToHeadTitle());
            addScore(text(head.getHeadToHeadFirstCompetitorText()),
                    text(head.getHeadToHeadSecondCompetitorText()));
            setIcon(head.getHeadToHeadFirstCompetitorIcon());
            setIcon(head.getHeadToHeadSecondCompetitorIcon());
            setTap(head.getHeadToHeadAction());
        } else if (data instanceof SubListTemplateData) {
            SubListTemplateData list = (SubListTemplateData) data;
            for (Text item : list.getSubListTexts()) add(item);
            setIcon(list.getSubListIcon());
            setTap(list.getSubListAction());
        } else if (data instanceof SubCardTemplateData) {
            SubCardTemplateData card = (SubCardTemplateData) data;
            add(card.getSubCardText());
            setIcon(card.getSubCardIcon());
            setTap(card.getSubCardAction());
        } else if (data instanceof SubImageTemplateData) {
            SubImageTemplateData images = (SubImageTemplateData) data;
            for (Text item : images.getSubImageTexts()) add(item);
            for (android.app.smartspace.uitemplatedata.Icon item : images.getSubImages()) {
                setIcon(item);
            }
            setTap(images.getSubImageAction());
        } else if (data instanceof CarouselTemplateData) {
            CarouselTemplateData carousel = (CarouselTemplateData) data;
            for (CarouselTemplateData.CarouselItem item : carousel.getCarouselItems()) {
                add(item.getUpperText());
                add(item.getLowerText());
                setIcon(item.getImage());
                setTap(item.getTapAction());
            }
            setTap(carousel.getCarouselAction());
        } else if (data instanceof CombinedCardsTemplateData) {
            for (BaseTemplateData child : ((CombinedCardsTemplateData) data)
                    .getCombinedCardDataList()) {
                readTemplate(context, child, event, depth + 1);
            }
        }
    }

    private void readItem(Context context, BaseTemplateData.SubItemInfo item, SmartspaceEvent event) {
        item = SmartspaceCard.allowedItem(context, item, event);
        if (item == null) return;
        add(item.getText());
        setIcon(item.getIcon());
        setTap(item.getTapAction());
    }

    private void readAction(SmartspaceAction value, boolean includeExtrasText) {
        if (value == null) return;
        add(value.getTitle());
        add(value.getSubtitle());
        if (icon == null) icon = value.getIcon();
        if (action == null && (value.getIntent() != null || value.getPendingIntent() != null)) {
            action = value;
        }
        Bundle extras = value.getExtras();
        if (extras == null) return;
        if (includeExtrasText) {
            add(extras.getCharSequence("matchTimeSummary"));
            addScore(extras.getCharSequence("firstCompetitorScore"),
                    extras.getCharSequence("secondCompetitorScore"));
            add(extras.getCharSequence("cardPrompt"));
            add(extras.getCharSequence("loyaltyProgramName"));
            CharSequence[] items = extras.getCharSequenceArray("listItems");
            if (items != null && items.length > 0) {
                for (CharSequence item : items) add(item);
            } else {
                add(extras.getCharSequence("emptyListString"));
            }
        }
        for (String key : new String[] {"imageBitmap", "appIcon", "firstCompetitorLogo",
                "secondCompetitorLogo", "loadingScreenIcon"}) {
            if (icon != null) break;
            Object image = extras.get(key);
            if (image instanceof Bitmap && !((Bitmap) image).isRecycled()) {
                icon = Icon.createWithBitmap((Bitmap) image);
            } else if (image instanceof Icon) {
                icon = (Icon) image;
            }
        }
        if (icon == null) {
            String image = extras.getString("imageUri");
            if (!TextUtils.isEmpty(image)) {
                Uri uri = Uri.parse(image);
                if ("content".equals(uri.getScheme()) || "android.resource".equals(uri.getScheme())) {
                    icon = Icon.createWithContentUri(uri);
                }
            }
        }
    }

    private void addScore(CharSequence first, CharSequence second) {
        if (!TextUtils.isEmpty(first) && !TextUtils.isEmpty(second)) add(first + " – " + second);
        else {
            add(first);
            add(second);
        }
    }

    private void setIcon(android.app.smartspace.uitemplatedata.Icon value) {
        if (icon == null && value != null) icon = value.getIcon();
    }

    private void setTap(TapAction value) {
        if (tap == null && value != null
                && (value.getIntent() != null || value.getPendingIntent() != null)
                && (value.getUserHandle() == null
                || Process.myUserHandle().equals(value.getUserHandle()))) tap = value;
    }

    private static CharSequence text(Text value) {
        return value == null ? null : value.getText();
    }

    private void add(Text value) {
        add(text(value));
    }

    void add(CharSequence value) {
        if (!TextUtils.isEmpty(value)) lines.add(value.toString());
    }

    CharSequence takeFirst() {
        if (lines.isEmpty()) return null;
        String first = lines.iterator().next();
        lines.remove(first);
        return first;
    }

    void remove(CharSequence value) {
        if (value != null) lines.remove(value.toString());
    }
}
