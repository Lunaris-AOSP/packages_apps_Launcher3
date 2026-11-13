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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import android.app.ActivityManager.RunningTaskInfo;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.window.IRemoteTransition;
import android.window.IRemoteTransitionFinishedCallback;
import android.window.RemoteTransitionStub;
import android.window.TransitionInfo;
import android.window.WindowContainerTransaction;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class NothingTransitionState {
    public static final long HANDOFF_DURATION_MS = NothingAppAnimation.LAUNCH_DURATION_MS / 2;
    private static final long EXPIRY_MS = NothingAppAnimation.LAUNCH_DURATION_MS * 2;
    private static long sNextId;
    private static Snapshot sSnapshot;
    private static final ConcurrentHashMap<Integer, HandoffTarget> sOpeningTargets =
            new ConcurrentHashMap<>();

    private NothingTransitionState() { }

    public static synchronized long begin(RemoteAnimationTarget target) {
        long id = ++sNextId;
        HandoffTarget opening = target == null ? null : sOpeningTargets.get(target.taskId);
        if (opening != null && target.taskInfo != null
                && opening.task.userId == target.taskInfo.userId
                && opening.task.displayId == target.taskInfo.displayId) {
            opening.animationId = id;
        }
        return id;
    }

    public static synchronized void update(long id, RemoteAnimationTarget target,
            RectF launcherBounds, Matrix matrix, Rect crop, float radius, float alpha) {
        if (target.taskInfo == null
                || target.taskInfo.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                || target.taskInfo.parentTaskId != -1
                || target.rotationChange != 0 || crop.isEmpty() || launcherBounds.isEmpty()
                || !Float.isFinite(launcherBounds.width())
                || !Float.isFinite(launcherBounds.height())) return;
        if (sSnapshot == null || sSnapshot.id != id) {
            sSnapshot = new Snapshot(id, target);
        }
        sSnapshot.bounds.set(launcherBounds);
        sSnapshot.matrix.set(matrix);
        sSnapshot.crop.set(crop);
        sSnapshot.radius = radius;
        sSnapshot.alpha = alpha;
        sSnapshot.updatedAt = SystemClock.uptimeMillis();
    }

    public static synchronized Snapshot take(RemoteAnimationTarget target) {
        if (sSnapshot == null) return null;
        if (SystemClock.uptimeMillis() - sSnapshot.updatedAt > EXPIRY_MS) {
            sSnapshot = null;
            return null;
        }
        if (target.taskInfo == null
                || target.taskInfo.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                || target.rotationChange != 0 || target.taskId != sSnapshot.taskId
                || target.taskInfo.userId != sSnapshot.userId
                || target.taskInfo.displayId != sSnapshot.displayId) return null;
        Snapshot snapshot = new Snapshot(sSnapshot);
        sSnapshot = null;
        return snapshot;
    }

    public static synchronized void clear(long id) {
        if (sSnapshot != null && sSnapshot.id == id) sSnapshot = null;
    }

    private static synchronized Snapshot peek(RunningTaskInfo task) {
        if (sSnapshot == null || task == null || task.taskId != sSnapshot.taskId
                || task.userId != sSnapshot.userId || task.displayId != sSnapshot.displayId
                || SystemClock.uptimeMillis() - sSnapshot.updatedAt > EXPIRY_MS) return null;
        return new Snapshot(sSnapshot);
    }

    private static synchronized Snapshot peek(RunningTaskInfo task, long animationId) {
        if (animationId == 0 || sSnapshot == null || sSnapshot.id != animationId) return null;
        return peek(task);
    }

    public static IRemoteTransition wrap(IRemoteTransition delegate) {
        return new RemoteTransitionStub() {
            private final ConcurrentHashMap<IBinder, List<HandoffTarget>> mTargets =
                    new ConcurrentHashMap<>();

            @Override
            public void startAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction transaction,
                    IRemoteTransitionFinishedCallback finish) throws RemoteException {
                List<HandoffTarget> targets = new ArrayList<>();
                for (TransitionInfo.Change change : info.getChanges()) {
                    RunningTaskInfo task = change.getTaskInfo();
                    if (task == null || task.parentTaskId != -1
                            || task.getWindowingMode() != WINDOWING_MODE_FULLSCREEN
                            || change.getStartRotation() != change.getEndRotation()
                            || change.getStartDisplayId() != change.getEndDisplayId()) continue;
                    SurfaceControl surface = change.getLeash();
                    if (!surface.isValid()) continue;
                    HandoffTarget target = new HandoffTarget(task,
                            new SurfaceControl(surface, "Nothing transition handoff"));
                    targets.add(target);
                    if (change.getMode() == android.view.WindowManager.TRANSIT_OPEN
                            || change.getMode() == android.view.WindowManager.TRANSIT_TO_FRONT) {
                        sOpeningTargets.put(task.taskId, target);
                    }
                    if (peek(task) != null && (change.getMode() == android.view.WindowManager.TRANSIT_CLOSE
                            || change.getMode() == android.view.WindowManager.TRANSIT_TO_BACK)) {
                        transaction.setMatrix(surface, 1f, 0f, 0f, 1f)
                                .setCrop(surface, null).setCornerRadius(surface, 0f)
                                .setAlpha(surface, 1f);
                    }
                }
                mTargets.put(token, targets);
                try {
                    delegate.startAnimation(token, info, transaction,
                            new IRemoteTransitionFinishedCallback.Stub() {
                                @Override
                                public void onTransitionFinished(WindowContainerTransaction wct,
                                        SurfaceControl.Transaction finishTransaction)
                                        throws RemoteException {
                                    List<HandoffTarget> held = mTargets.remove(token);
                                    boolean ownsTransaction = finishTransaction == null;
                                    SurfaceControl.Transaction result = ownsTransaction
                                            ? new SurfaceControl.Transaction() : finishTransaction;
                                    try {
                                        if (held != null) {
                                            float[] values = new float[9];
                                            for (HandoffTarget target : held) {
                                                Snapshot state = peek(target.task, target.animationId);
                                                if (state == null || !target.surface.isValid()) continue;
                                                state.matrix.getValues(values);
                                                float scale = (float) Math.hypot(
                                                        values[Matrix.MSCALE_X], values[Matrix.MSKEW_Y]);
                                                result.setMatrix(target.surface, state.matrix, values)
                                                        .setWindowCrop(target.surface, state.crop)
                                                        .setCornerRadius(target.surface,
                                                                state.radius / Math.max(0.001f, scale))
                                                        .setAlpha(target.surface, 1f);
                                            }
                                        }
                                        finish.onTransitionFinished(wct, result);
                                    } finally {
                                        release(held);
                                        if (ownsTransaction) result.close();
                                    }
                                }
                            });
                } catch (RemoteException | RuntimeException e) {
                    release(mTargets.remove(token));
                    throw e;
                }
            }

            @Override
            public void mergeAnimation(IBinder token, TransitionInfo info,
                    SurfaceControl.Transaction transaction, IBinder mergeTarget,
                    IRemoteTransitionFinishedCallback finish) throws RemoteException {
                try {
                    delegate.mergeAnimation(token, info, transaction, mergeTarget, finish);
                } finally {
                    release(mTargets.remove(mergeTarget));
                }
            }

            @Override
            public void onTransitionConsumed(IBinder token, boolean aborted) throws RemoteException {
                try {
                    delegate.onTransitionConsumed(token, aborted);
                } finally {
                    release(mTargets.remove(token));
                }
            }
        };
    }

    private static void release(List<HandoffTarget> targets) {
        if (targets == null) return;
        for (HandoffTarget target : targets) {
            sOpeningTargets.remove(target.task.taskId, target);
            target.surface.release();
        }
    }

    private static final class HandoffTarget {
        final RunningTaskInfo task;
        final SurfaceControl surface;
        volatile long animationId;

        HandoffTarget(RunningTaskInfo taskInfo, SurfaceControl leash) {
            task = taskInfo;
            surface = leash;
        }
    }

    public static final class Snapshot {
        private final long id;
        private final int taskId;
        private final int userId;
        private final int displayId;
        public final RectF bounds = new RectF();
        public final Matrix matrix = new Matrix();
        public final Rect crop = new Rect();
        public float radius;
        public float alpha;
        private long updatedAt;

        private Snapshot(long animationId, RemoteAnimationTarget target) {
            id = animationId;
            taskId = target.taskId;
            userId = target.taskInfo.userId;
            displayId = target.taskInfo.displayId;
        }

        private Snapshot(Snapshot source) {
            id = source.id;
            taskId = source.taskId;
            userId = source.userId;
            displayId = source.displayId;
            bounds.set(source.bounds);
            matrix.set(source.matrix);
            crop.set(source.crop);
            radius = source.radius;
            alpha = source.alpha;
            updatedAt = source.updatedAt;
        }
    }
}
