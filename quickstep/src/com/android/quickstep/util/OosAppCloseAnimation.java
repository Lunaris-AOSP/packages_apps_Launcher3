/*
 * Copyright (C) 2024-2026 Lunaris AOSP
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

package com.android.quickstep.util;


public final class OosAppCloseAnimation {

    public static final long WORKSPACE_REVEAL_DURATION_MS = 500;

    public static final float SHAPE_PROGRESS_START = 0.65f;

    private static final float ICON_ALPHA_START = 0.35f;

    private OosAppCloseAnimation() { }

    public static float getIconAlpha(float progress) {
        float alpha = Math.max(0f, Math.min(1f,
                (progress - ICON_ALPHA_START) / (SHAPE_PROGRESS_START - ICON_ALPHA_START)));
        return alpha * alpha * (3f - 2f * alpha);
    }

    public static float getWindowAlpha(float progress) {
        return 1f - getIconAlpha(progress);
    }
}
