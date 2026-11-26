package com.android.launcher3.smartspace;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

import com.android.launcher3.LauncherPrefs;
import com.android.launcher3.R;
import com.android.launcher3.quickspace.QuickSpaceView;

public class AtAGlanceView extends FrameLayout
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private QuickSpaceView mQuickspace;
    private SmartspaceView mSmartspace;
    private boolean mBlackForeground;
    private boolean mResumed;
    private boolean mVisible;
    private boolean mDestroyed;

    public AtAGlanceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mDestroyed) return;
        LauncherPrefs.getPrefs(getContext()).registerOnSharedPreferenceChangeListener(this);
        updateMode();
    }

    @Override
    protected void onDetachedFromWindow() {
        LauncherPrefs.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        releaseContent();
        super.onDetachedFromWindow();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        mVisible = isVisible;
        updateListening();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (key == null || LauncherPrefs.USE_SMARTSPACE.getSharedPrefKey().equals(key)
                || LauncherPrefs.AT_A_GLANCE_BLACK.getSharedPrefKey().equals(key)) {
            updateMode();
        }
        if (key == null || key.startsWith("pref_smartspace_")) {
            if (mSmartspace != null) mSmartspace.refreshSettings();
        }
    }

    private void updateMode() {
        if (mDestroyed) return;
        boolean smartspace = LauncherPrefs.USE_SMARTSPACE.get(getContext());
        boolean blackForeground = LauncherPrefs.AT_A_GLANCE_BLACK.get(getContext());
        if (blackForeground == mBlackForeground
                && (smartspace ? mSmartspace != null : mQuickspace != null)) {
            return;
        }
        releaseContent();
        mBlackForeground = blackForeground;
        Context context = blackForeground
                ? new ContextThemeWrapper(getContext(), R.style.AtAGlanceBlack) : getContext();
        View content;
        if (smartspace) {
            mSmartspace = new SmartspaceView(context);
            content = mSmartspace;
        } else {
            mQuickspace = (QuickSpaceView) LayoutInflater.from(context)
                    .inflate(R.layout.quickspace_view, this, false);
            content = mQuickspace;
        }
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        updateListening();
    }

    private void updateListening() {
        boolean active = mResumed && mVisible && isAttachedToWindow() && !mDestroyed;
        if (mSmartspace != null) mSmartspace.setActive(active);
        if (mQuickspace != null) {
            if (active) mQuickspace.onResume();
            else mQuickspace.onPause();
        }
    }

    private void releaseContent() {
        if (mSmartspace != null) mSmartspace.setActive(false);
        if (mQuickspace != null) mQuickspace.onDestroy();
        removeAllViews();
        mSmartspace = null;
        mQuickspace = null;
    }

    public void onResume() {
        mResumed = true;
        updateListening();
    }

    public void onPause() {
        mResumed = false;
        updateListening();
    }

    public void onDestroy() {
        mDestroyed = true;
        LauncherPrefs.getPrefs(getContext()).unregisterOnSharedPreferenceChangeListener(this);
        releaseContent();
    }
}
