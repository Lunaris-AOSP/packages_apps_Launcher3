/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.compose.ui.platform.AbstractComposeView;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

public final class LauncherViewOverscroll implements Application.ActivityLifecycleCallbacks {
    private final FragmentManager.FragmentLifecycleCallbacks mFragmentCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentStarted(@NonNull FragmentManager manager,
                        @NonNull Fragment fragment) {
                    if (fragment instanceof DialogFragment) {
                        android.app.Dialog dialog = ((DialogFragment) fragment).getDialog();
                        if (dialog != null && dialog.getWindow() != null) {
                            observe(dialog.getWindow().getDecorView());
                        }
                    }
                }
            };

    public static void install(Application application) {
        application.registerActivityLifecycleCallbacks(new LauncherViewOverscroll());
    }

    private static void observe(View root) {
        if (root.getTag(R.id.launcher_spring_scroll_observer) == null) {
            RootObserver observer = new RootObserver(root);
            root.setTag(R.id.launcher_spring_scroll_observer, observer);
        }
    }

    private static void installInTree(View view) {
        if (view instanceof AbstractComposeView) return;
        if (view.getOverScrollMode() != View.OVER_SCROLL_NEVER) {
            if (view instanceof RecyclerView) {
                RecyclerView list = (RecyclerView) view;
                if (list.getEdgeEffectFactory().getClass() == RecyclerView.EdgeEffectFactory.class) {
                    list.setEdgeEffectFactory(new SpringEdgeEffectFactory());
                }
            } else if (view instanceof NestedScrollView) {
                SpringEdgeEffectFactory.install((NestedScrollView) view);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) installInTree(group.getChildAt(i));
        }
    }

    private static final class RootObserver implements View.OnAttachStateChangeListener,
            ViewTreeObserver.OnGlobalLayoutListener {
        private final View mRoot;
        @Nullable private ViewTreeObserver mTreeObserver;

        RootObserver(View root) {
            mRoot = root;
            root.addOnAttachStateChangeListener(this);
            installInTree(root);
            if (root.isAttachedToWindow()) onViewAttachedToWindow(root);
        }

        @Override
        public void onGlobalLayout() { installInTree(mRoot); }

        @Override
        public void onViewAttachedToWindow(View view) {
            mTreeObserver = view.getViewTreeObserver();
            mTreeObserver.addOnGlobalLayoutListener(this);
            installInTree(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            if (mTreeObserver != null && mTreeObserver.isAlive()) {
                mTreeObserver.removeOnGlobalLayoutListener(this);
            }
            mTreeObserver = null;
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
        if (activity instanceof FragmentActivity) {
            ((FragmentActivity) activity).getSupportFragmentManager()
                    .registerFragmentLifecycleCallbacks(mFragmentCallbacks, true);
        }
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        observe(activity.getWindow().getDecorView());
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
        if (activity instanceof FragmentActivity) {
            ((FragmentActivity) activity).getSupportFragmentManager()
                    .unregisterFragmentLifecycleCallbacks(mFragmentCallbacks);
        }
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {}
}
