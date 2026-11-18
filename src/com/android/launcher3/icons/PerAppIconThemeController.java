/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.icons;

import android.content.Context;
import android.graphics.drawable.AdaptiveIconDrawable;

import androidx.annotation.Nullable;

import com.android.launcher3.customization.IconDatabase;
import com.android.launcher3.graphics.ThemeManager;
import com.android.launcher3.graphics.theme.MonoIconThemeFactory;
import com.android.launcher3.icons.mono.MonoThemedBitmap;
import com.android.launcher3.icons.mono.ThemedIconDelegate;

public final class PerAppIconThemeController implements IconThemeController {

    private final Context mContext;
    private final ThemeManager mThemeManager;

    public PerAppIconThemeController(Context context, ThemeManager themeManager) {
        mContext = context;
        mThemeManager = themeManager;
    }

    public static IconThemeController getThemedController(ThemeManager themeManager) {
        IconThemeController controller = themeManager.getThemeController();
        return controller != null
                ? controller : MonoIconThemeFactory.INSTANCE.getMONO_THEME_CONTROLLER();
    }

    @Nullable
    private IconThemeController getController(@Nullable SourceHint sourceHint) {
        if (sourceHint == null) {
            return mThemeManager.getThemeController();
        }
        return IconDatabase.shouldUseThemedIcon(mContext, sourceHint.getKey(),
                mThemeManager.isIconThemeEnabled())
                ? getThemedController(mThemeManager) : null;
    }

    @Override
    public String getThemeID() {
        return getThemedController(mThemeManager).getThemeID();
    }

    @Override
    public ThemedBitmap createThemedBitmap(AdaptiveIconDrawable icon, BitmapInfo info,
            BaseIconFactory factory, @Nullable SourceHint sourceHint) {
        IconThemeController controller = getController(sourceHint);
        return controller == null ? ThemedBitmap.NOT_SUPPORTED
                : withConfiguredColors(
                        controller.createThemedBitmap(icon, info, factory, sourceHint));
    }

    @Override
    public ThemedBitmap decode(byte[] bytes, BitmapInfo info, BaseIconFactory factory,
            SourceHint sourceHint) {
        IconThemeController controller = getController(sourceHint);
        return controller == null ? ThemedBitmap.NOT_SUPPORTED
                : withConfiguredColors(controller.decode(bytes, info, factory, sourceHint));
    }

    private static ThemedBitmap withConfiguredColors(ThemedBitmap bitmap) {
        if (bitmap instanceof MonoThemedBitmap mono) {
            return new MonoThemedBitmap(mono.getMono(), ThemedIconDelegate::getColors,
                    mono.getLuminanceDelta() == null ? null : Double.NaN);
        }
        return bitmap;
    }

    @Nullable
    @Override
    public AdaptiveIconDrawable createThemedAdaptiveIcon(Context context,
            AdaptiveIconDrawable originalIcon, @Nullable BitmapInfo info) {
        return getThemedController(mThemeManager)
                .createThemedAdaptiveIcon(context, originalIcon, info);
    }
}
