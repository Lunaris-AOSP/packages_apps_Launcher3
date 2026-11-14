/*
 * Copyright (C) 2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.quickstep.util;

public final class CosUnlockAnimationSpec {
    private static final float ITEM_BOUNCE = 0.18f;
    static final float MIN_VISIBLE_CHANGE = 1f / 256f;

    final float startScale;
    final float columnSpacing;
    final float rowSpacing;
    private final float rowWeight;
    private final float itemResponse;
    final Spring dockTranslation;
    final Spring dockAlpha = new Spring(0f, 0.5f);

    public CosUnlockAnimationSpec(float density, boolean twoPanels, boolean tablet) {
        startScale = twoPanels || tablet ? 4f : 6f;
        columnSpacing = (twoPanels ? 300f : 400f) * density;
        rowSpacing = (twoPanels ? 400f : 500f) * density;
        rowWeight = twoPanels || tablet ? 1f : 2f;
        itemResponse = twoPanels || tablet ? 0.32f : 0.3f;
        dockTranslation = new Spring(twoPanels || tablet ? 0.33f : 0.35f,
                twoPanels || tablet ? 0.43f : 0.48f);
    }

    Item item(float cellX, float cellY, int spanX, int spanY, int columns,
            float centerX, float centerY, float height,
            float windowWidth, float windowHeight) {
        float column = cellX + spanX / 2f - columns / 2f;
        float row = cellY + spanY / 2f + 0.5f;
        float response = itemResponse + 0.02f * ((Math.abs(row) + 1f) * rowWeight
                + Math.abs(column));
        float fromX = windowWidth / 2f + column * columnSpacing;
        float fromY = -0.7f * windowHeight + height * startScale / 2f
                + (row - (spanY + 1f) / 2f) * rowSpacing;
        float translationX = fromX - centerX;
        float translationY = fromY - centerY;
        float minimumVisibleChange = Math.min(
                1f / Math.max(Math.abs(translationX), Math.abs(translationY)),
                Math.min(MIN_VISIBLE_CHANGE / Math.abs(1f - startScale), MIN_VISIBLE_CHANGE));
        return new Item(translationX, translationY, minimumVisibleChange,
                new Spring(ITEM_BOUNCE, response));
    }

    static final class Item {
        final float translationX;
        final float translationY;
        final float minimumVisibleChange;
        final Spring spring;

        Item(float translationX, float translationY, float minimumVisibleChange, Spring spring) {
            this.translationX = translationX;
            this.translationY = translationY;
            this.minimumVisibleChange = minimumVisibleChange;
            this.spring = spring;
        }
    }

    static final class Spring {
        final float response;
        private final double omega;
        private final double damping;
        private final double decay;
        private final double frequency;

        Spring(float bounce, float response) {
            this.response = response;
            omega = Math.sqrt((float) Math.pow(2 * Math.PI / response, 2));
            damping = 1f - bounce;
            decay = damping * omega;
            frequency = omega * Math.sqrt(Math.max(0, 1 - damping * damping));
        }

        float value(double seconds) {
            if (seconds <= 0) return 0f;
            double displacement = frequency == 0
                    ? (1 + omega * seconds) * Math.exp(-omega * seconds)
                    : Math.exp(-decay * seconds) * (Math.cos(frequency * seconds)
                            + decay / frequency * Math.sin(frequency * seconds));
            return (float) (1 - displacement);
        }

        boolean isAtEquilibrium(double seconds, float minimumVisibleChange) {
            double threshold = minimumVisibleChange * 0.75f;
            float velocity = (float) (frequency == 0
                    ? omega * omega * seconds * Math.exp(-omega * seconds)
                    : omega * omega / frequency * Math.exp(-decay * seconds)
                            * Math.sin(frequency * seconds));
            return Math.abs(1f - value(seconds)) < threshold
                    && Math.abs(velocity) < threshold * 62.5;
        }

        double maxDuration(float minimumVisibleChange) {
            double threshold = minimumVisibleChange * 0.75f;
            double seconds = 0;
            while (seconds < 4) {
                double envelope = frequency == 0
                        ? (1 + omega * seconds) * Math.exp(-omega * seconds)
                        : Math.exp(-decay * seconds) / Math.sqrt(1 - damping * damping);
                double velocity = frequency == 0
                        ? omega * omega * seconds * Math.exp(-omega * seconds)
                        : omega * omega / frequency * Math.exp(-decay * seconds);
                if (envelope <= threshold && velocity <= threshold * 62.5) break;
                seconds += 0.016;
            }
            return seconds;
        }
    }
}
