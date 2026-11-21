/*
 * SPDX-FileCopyrightText: Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */
package com.android.launcher3.views;

import static com.android.launcher3.states.StateAnimationConfig.ANIM_WORKSPACE_FADE;
import static com.android.launcher3.util.Executors.MAIN_EXECUTOR;
import static com.android.launcher3.util.Executors.UI_HELPER_EXECUTOR;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.FloatProperty;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceControlViewHost.SurfacePackage;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;

import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherState;
import com.android.launcher3.anim.PendingAnimation;
import com.android.launcher3.statemanager.StateManager.StateHandler;
import com.android.launcher3.statemanager.StateManager.StateListener;
import com.android.launcher3.states.StateAnimationConfig;

public final class HomeClockView extends SurfaceView
        implements SurfaceHolder.Callback, StateListener<LauncherState>,
        StateHandler<LauncherState> {
    private static final String TAG = "HomeClockView";
    private static final String ENABLED_SETTING = "lock_screen_home_clock_enabled";
    private static final String STYLE_SETTING = "lock_screen_custom_clock_style";
    private static final Uri PROVIDER = Uri.parse("content://com.android.systemui.homeclock");

    private static final FloatProperty<HomeClockView> STATE_ALPHA =
            new FloatProperty<HomeClockView>("stateAlpha") {
                @Override
                public Float get(HomeClockView view) {
                    return view.mStateAlpha;
                }

                @Override
                public void setValue(HomeClockView view, float alpha) {
                    view.mStateAlpha = Math.max(0f, Math.min(1f, alpha));
                    view.updateClockAlpha();
                }
            };

    private final Launcher mLauncher;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final int[] mScreenPosition = new int[2];
    private final Runnable mRetry = this::requestRender;
    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange) {
            mRetryCount = 0;
            update();
        }
    };
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                mUnlockDuration = 0;
            }
            mRetryCount = 0;
            update();
        }
    };

    private boolean mAttached;
    private boolean mSurfaceReady;
    private boolean mEnabled;
    private boolean mRequested;
    private int mGeneration;
    private int mRequestWidth;
    private int mRequestHeight;
    private int mRequestX;
    private int mRequestY;
    private int mRetryCount;
    private Connection mConnection;
    private float mDrawerProgress = 1f;
    private boolean mStateTransitionRunning;
    private boolean mStateAlphaAnimating;
    private float mStateAlpha = 1f;
    private ViewTreeObserver mObservedTree;
    private final ViewTreeObserver.OnPreDrawListener mPreDraw = () -> {
        if (mEnabled) updateClockAlpha();
        return true;
    };
    private boolean mContentVisible = true;
    private boolean mUnlocking;
    private long mUnlockStartTime;
    private long mUnlockDuration;

    public HomeClockView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLauncher = Launcher.getLauncher(context);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);
        setZOrderOnTop(false);
        setFocusable(false);
        setClickable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mAttached = true;
        for (String key : new String[] {ENABLED_SETTING, STYLE_SETTING}) {
            getContext().getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(key), false, mSettingsObserver);
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_PREFERRED_ACTIVITY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        getContext().registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        mLauncher.getStateManager().addStateListener(this);
        mObservedTree = getViewTreeObserver();
        mObservedTree.addOnPreDrawListener(mPreDraw);
        update();
    }

    @Override
    protected void onDetachedFromWindow() {
        mAttached = false;
        mStateTransitionRunning = false;
        mStateAlphaAnimating = false;
        if (mObservedTree != null && mObservedTree.isAlive()) {
            mObservedTree.removeOnPreDrawListener(mPreDraw);
        }
        mObservedTree = null;
        mUnlockDuration = 0;
        releaseClock();
        mLauncher.getStateManager().removeStateListener(this);
        getContext().getContentResolver().unregisterContentObserver(mSettingsObserver);
        getContext().unregisterReceiver(mReceiver);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (mAttached) update();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (mAttached && hasWindowFocus) {
            mRetryCount = 0;
            update();
        }
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (mAttached) requestRender();
    }

    private void update() {
        if (!mAttached) return;
        ResolveInfo home = getContext().getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        mEnabled = home != null && home.activityInfo != null
                && getContext().getPackageName().equals(home.activityInfo.packageName)
                && Settings.Secure.getInt(getContext().getContentResolver(), ENABLED_SETTING, 0) != 0
                && Settings.Secure.getInt(getContext().getContentResolver(), STYLE_SETTING, 0) > 0;
        if (!mEnabled) mUnlockDuration = 0;
        updateVisibility(mLauncher.getStateManager().getState());
    }

    public void onUnlockAnimation(boolean unlocked, long duration, long startDelay) {
        float scale = ValueAnimator.getDurationScale();
        if (duration <= 0 || scale <= 0) {
            mUnlockDuration = 0;
            return;
        }
        mUnlocking = unlocked;
        mUnlockStartTime = SystemClock.uptimeMillis() + Math.round(startDelay * scale);
        mUnlockDuration = Math.max(1L, Math.round(duration * scale));
        sendUnlockTiming();
    }

    private boolean addUnlockTiming(Bundle request) {
        if (mUnlockDuration <= 0 || SystemClock.uptimeMillis()
                > mUnlockStartTime + mUnlockDuration + 2000) return false;
        request.putBoolean("unlocked", mUnlocking);
        request.putLong("start_time", mUnlockStartTime);
        request.putLong("duration", mUnlockDuration);
        return true;
    }

    private void sendUnlockTiming() {
        Connection connection = mConnection;
        if (connection == null) return;
        Bundle request = new Bundle();
        request.putBinder("lifecycle", connection.lifetime);
        request.putBinder("renderer", connection.renderer);
        if (addUnlockTiming(request)) {
            UI_HELPER_EXECUTOR.execute(() -> connection.setUnlockTiming(request));
        }
    }

    private void updateVisibility(LauncherState state) {
        if (!mStateTransitionRunning) mStateAlpha = stateAlpha(state);
        setDrawerProgress(mLauncher.getAllAppsController().getProgress());
        if (!mEnabled) releaseClock(true);
        setVisibility(mEnabled ? VISIBLE : INVISIBLE);
        if (canRender()) requestRender();
        else releaseClock();
    }

    public void setDrawerProgress(float progress) {
        if (!Float.isFinite(progress)) return;
        mDrawerProgress = Math.max(0f, Math.min(1f, progress));
        updateClockAlpha();
    }

    private void updateClockAlpha() {
        View workspace = mLauncher.getWorkspace();
        View dragLayer = mLauncher.getDragLayer();
        float contentAlpha = (workspace == null ? 1f : workspace.getAlpha())
                * (dragLayer == null ? 1f : dragLayer.getAlpha());
        float alpha = mDrawerProgress * mDrawerProgress * (3f - 2f * mDrawerProgress)
                * mStateAlpha * Math.max(0f, Math.min(1f, contentAlpha));
        if (Float.compare(getAlpha(), alpha) != 0) setAlpha(alpha);
        boolean visible = Math.round(alpha * 255f) > 0 || mStateTransitionRunning;
        if (mContentVisible != visible) {
            mContentVisible = visible;
            sendContentVisibility();
        }
    }

    private void sendContentVisibility() {
        Connection connection = mConnection;
        if (connection == null) return;
        boolean visible = mContentVisible;
        UI_HELPER_EXECUTOR.execute(() -> connection.setContentVisible(visible));
    }

    private static float stateAlpha(LauncherState state) {
        return state == LauncherState.NORMAL || state == LauncherState.ALL_APPS ? 1f : 0f;
    }

    @Override
    public void setState(LauncherState state) {
        mStateAlphaAnimating = false;
        mStateAlpha = stateAlpha(state);
        updateClockAlpha();
    }

    @Override
    public void setStateWithAnimation(LauncherState state,
            StateAnimationConfig config, PendingAnimation animation) {
        mStateAlphaAnimating = true;
        animation.setFloat(this, STATE_ALPHA, stateAlpha(state),
                config.getInterpolator(ANIM_WORKSPACE_FADE,
                        state.getWorkspacePageAlphaProvider(mLauncher).interpolator));
        animation.addEndListener(success -> {
            mStateAlphaAnimating = false;
            if (!success) {
                mStateTransitionRunning = false;
                setState(mLauncher.getStateManager().getCurrentStableState());
            }
        });
    }

    @Override
    public void onStateTransitionStart(LauncherState toState) {
        mStateTransitionRunning = true;
        if (!mStateAlphaAnimating) setState(toState);
        updateVisibility(toState);
    }

    @Override
    public void onStateTransitionComplete(LauncherState finalState) {
        mStateTransitionRunning = false;
        updateVisibility(finalState);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mSurfaceReady = true;
        requestRender();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        requestRender();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mSurfaceReady = false;
        releaseClock();
    }

    private boolean canRender() {
        return mAttached && mSurfaceReady && mEnabled && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE && getDisplay() != null
                && getWidth() > 0 && getHeight() > 0 && getHostToken() != null;
    }

    private void requestRender() {
        if (!canRender()) return;
        getLocationOnScreen(mScreenPosition);
        if (mRequested && mRequestWidth == getWidth() && mRequestHeight == getHeight()
                && mRequestX == mScreenPosition[0] && mRequestY == mScreenPosition[1]) return;
        releaseClock();
        mRequested = true;
        mRequestWidth = getWidth();
        mRequestHeight = getHeight();
        mRequestX = mScreenPosition[0];
        mRequestY = mScreenPosition[1];
        int generation = mGeneration;
        IBinder lifetime = new Binder();
        Bundle request = new Bundle();
        request.putBinder("lifecycle", lifetime);
        request.putBinder("host_token", getHostToken());
        request.putInt("display_id", getDisplay().getDisplayId());
        request.putInt("width", mRequestWidth);
        request.putInt("height", mRequestHeight);
        request.putInt("screen_x", mRequestX);
        request.putInt("screen_y", mRequestY);
        request.putBoolean("visible", mContentVisible);
        addUnlockTiming(request);
        UI_HELPER_EXECUTOR.execute(() -> {
            ContentProviderClient client = null;
            SurfacePackage surface = null;
            Connection connection = null;
            try {
                client = getContext().getContentResolver().acquireUnstableContentProviderClient(PROVIDER);
                Bundle result = client == null ? null : client.call("render", null, request);
                surface = result == null ? null
                        : result.getParcelable("surface_package", SurfacePackage.class);
                IBinder renderer = result == null ? null : result.getBinder("renderer");
                if (surface != null && renderer != null) {
                    Connection ready = new Connection(client, lifetime, renderer);
                    connection = ready;
                    ready.death = () -> MAIN_EXECUTOR.execute(() -> {
                        if (mConnection == ready || mGeneration == generation) {
                            releaseClock();
                            mRetryCount = 0;
                            scheduleRetry();
                        }
                    });
                    renderer.linkToDeath(ready.death, 0);
                    SurfacePackage readySurface = surface;
                    MAIN_EXECUTOR.execute(() -> attach(generation, ready, readySurface));
                    return;
                }
            } catch (Exception e) {
                Log.w(TAG, "Cannot connect the home clock", e);
            }
            if (surface != null) surface.release();
            if (connection != null) connection.close();
            else if (client != null) new Connection(client, lifetime, null).close();
            MAIN_EXECUTOR.execute(() -> {
                if (mGeneration == generation) {
                    mRequested = false;
                    scheduleRetry();
                }
            });
        });
    }

    private void attach(int generation, Connection connection, SurfacePackage surface) {
        if (mGeneration != generation || !canRender()) {
            surface.release();
            boolean cancelUnlock = !mEnabled
                    || mLauncher.getStateManager().getState() != LauncherState.NORMAL;
            UI_HELPER_EXECUTOR.execute(() -> connection.close(cancelUnlock));
            return;
        }
        mConnection = connection;
        try {
            setChildSurfacePackage(surface);
            Runnable attached = () -> {
                if (mConnection == connection && canRender()) {
                    UI_HELPER_EXECUTOR.execute(() -> {
                        boolean confirmed = connection.confirmAttached();
                        MAIN_EXECUTOR.execute(() -> {
                            if (mConnection != connection) return;
                            if (confirmed) {
                                mRetryCount = 0;
                                sendContentVisibility();
                                sendUnlockTiming();
                            } else {
                                releaseClock();
                                scheduleRetry();
                            }
                        });
                    });
                }
            };
            if (isHardwareAccelerated()) {
                getViewTreeObserver().registerFrameCommitCallback(attached);
                invalidate();
            } else {
                post(attached);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot attach the home clock", e);
            surface.release();
            releaseClock();
            scheduleRetry();
        }
    }

    private void scheduleRetry() {
        if (!canRender() || mRetryCount >= 3) return;
        mHandler.removeCallbacks(mRetry);
        mHandler.postDelayed(mRetry, 250L << mRetryCount++);
    }

    private void releaseClock() {
        releaseClock(false);
    }

    private void releaseClock(boolean cancelUnlock) {
        mGeneration++;
        mRequested = false;
        mHandler.removeCallbacks(mRetry);
        clearChildSurfacePackage();
        Connection connection = mConnection;
        mConnection = null;
        if (connection != null) {
            UI_HELPER_EXECUTOR.execute(() -> connection.close(cancelUnlock));
        }
    }

    private static final class Connection {
        final ContentProviderClient client;
        final IBinder lifetime;
        final IBinder renderer;
        IBinder.DeathRecipient death;
        boolean closed;

        Connection(ContentProviderClient client, IBinder lifetime, IBinder renderer) {
            this.client = client;
            this.lifetime = lifetime;
            this.renderer = renderer;
        }

        void setUnlockTiming(Bundle request) {
            if (closed) return;
            try {
                client.call("unlock", null, request);
            } catch (Exception e) {
                Log.w(TAG, "Cannot synchronize the home clock unlock", e);
            }
        }

        void setContentVisible(boolean visible) {
            if (closed) return;
            try {
                Bundle request = new Bundle();
                request.putBinder("lifecycle", lifetime);
                request.putBinder("renderer", renderer);
                request.putBoolean("visible", visible);
                client.call("visibility", null, request);
            } catch (Exception e) {
                Log.w(TAG, "Cannot update the home clock visibility", e);
            }
        }

        boolean confirmAttached() {
            if (closed) return false;
            try {
                Bundle request = new Bundle();
                request.putBinder("lifecycle", lifetime);
                request.putBinder("renderer", renderer);
                Bundle result = client.call("attached", null, request);
                return result != null && result.getBoolean("attached");
            } catch (Exception e) {
                Log.w(TAG, "Cannot confirm the home clock attachment", e);
                return false;
            }
        }

        void close() {
            close(false);
        }

        void close(boolean cancelUnlock) {
            if (closed) return;
            closed = true;
            try {
                Bundle request = new Bundle();
                request.putBinder("lifecycle", lifetime);
                request.putBoolean("cancel_unlock", cancelUnlock);
                client.call("release", null, request);
            } catch (Exception e) {
                Log.w(TAG, "Cannot release the home clock", e);
            } finally {
                try {
                    if (renderer != null && death != null) renderer.unlinkToDeath(death, 0);
                } catch (RuntimeException e) {
                    Log.w(TAG, "Cannot unlink the home clock renderer", e);
                } finally {
                    client.close();
                }
            }
        }
    }
}
