/*
 * Copyright (C) 2024-2026 Lunaris AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.util

import android.animation.ValueAnimator
import android.content.Context
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.copy
import androidx.compose.animation.core.spring
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

private const val ScrollEpsilon = 0.5f

@Composable
fun LauncherOverscroll(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val physics = LauncherScrollPreferences.get(context)
    val factory = remember(context, physics) {
        if (physics.enabled) SpringOverscrollFactory(context, physics) else null
    }
    CompositionLocalProvider(LocalOverscrollFactory provides factory, content = content)
}

fun ComposeView.setLauncherContent(content: @Composable () -> Unit) {
    setContent { LauncherOverscroll(content) }
}

private data class SpringOverscrollFactory(
    val context: Context,
    val physics: SpringScrollPhysics,
) : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = LauncherOverscrollEffect(context, physics)
}

internal class LauncherOverscrollEffect(
    private val context: Context,
    private val physics: SpringScrollPhysics,
) : OverscrollEffect {
    private var animation by mutableStateOf(AnimationState(0f))
    private var flingJob: Job? = null
    private var returning = false
    private var returnHeld = false
    private var vertical = true
    private var width = 0f
    private var height = 0f
    private val extent get() = if (vertical) height else width
    private val offset get() = animation.value
    private val hasOverscroll get() = offset != 0f || animation.isRunning

    override val isInProgress get() = hasOverscroll && !returning

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode,
        PointerInputModifierNode {
        override fun MeasureScope.measure(
            measurable: Measurable,
            constraints: Constraints,
        ): MeasureResult {
            val placeable = measurable.measure(constraints)
            width = placeable.width.toFloat()
            height = placeable.height.toFloat()
            return layout(placeable.width, placeable.height) {
                placeable.placeWithLayer(0, 0) {
                    translationX = if (vertical) 0f else offset
                    translationY = if (vertical) offset else 0f
                }
            }
        }

        override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
            if (pass == PointerEventPass.Initial && returning &&
                pointerEvent.changes.any { it.pressed && !it.previousPressed } &&
                pointerEvent.changes.none { it.previousPressed }
            ) {
                returnHeld = true
                flingJob?.cancel()
                flingJob = null
                animation = AnimationState(offset)
            }
            if (pass == PointerEventPass.Final && pointerEvent.changes.none { it.pressed }) {
                resumeReturn()
            }
        }

        override fun onCancelPointerInput() {
            if (isAttached) resumeReturn()
        }

        override fun onDetach() {
            flingJob?.cancel()
            flingJob = null
            animation = AnimationState(0f)
            returning = false
            returnHeld = false
        }
    }

    private fun resumeReturn() {
        if (!returnHeld) return
        returnHeld = false
        if (!returning) return
        node.node.coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val job = coroutineContext.job
            flingJob = job
            try {
                animateReturn(0f, AnimationConstants.UnspecifiedTime)
            } finally {
                if (flingJob === job) flingJob = null
            }
        }
    }

    private suspend fun animateReturn(velocity: Float, lastFrameTime: Long) {
        returning = true
        val bounce = animation.copy(lastFrameTimeNanos = lastFrameTime, velocity = velocity)
        animation = bounce
        bounce.animateTo(
            targetValue = 0f,
            animationSpec = spring(
                stiffness = physics.stiffness,
                dampingRatio = physics.dampingRatio,
                visibilityThreshold = ScrollEpsilon,
            ),
            sequentialAnimation = lastFrameTime != AnimationConstants.UnspecifiedTime,
        )
        returning = false
    }

    private fun vector(value: Float) = if (vertical) Offset(0f, value) else Offset(value, 0f)
    private fun Offset.axis() = if (vertical) y else x
    private fun Velocity.axis() = if (vertical) y else x

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        if (!physics.enabled || !ValueAnimator.areAnimatorsEnabled()) {
            flingJob?.cancel()
            animation = AnimationState(0f)
            returning = false
            returnHeld = false
            return performScroll(delta)
        }
        if (!hasOverscroll && delta != Offset.Zero) vertical = abs(delta.y) >= abs(delta.x)
        if (source == NestedScrollSource.UserInput && delta != Offset.Zero) {
            returning = false
            returnHeld = false
            flingJob?.cancel()
            flingJob = null
            if (animation.isRunning) animation = AnimationState(offset)
        }

        val currentOffset = offset
        val relaxed = if (currentOffset * delta.axis() < 0f) {
            sign(delta.axis()) * minOf(abs(delta.axis()), abs(currentOffset))
        } else 0f
        if (relaxed != 0f) animation = AnimationState(currentOffset + relaxed)
        val remaining = delta - vector(relaxed)
        val consumed = performScroll(remaining)
        if (offset != 0f && abs(consumed.axis()) > ScrollEpsilon) {
            animation = AnimationState(0f)
        }
        val unconsumed = (remaining - consumed).axis()
        val pulled = if (
            abs(unconsumed) > ScrollEpsilon &&
            source == NestedScrollSource.UserInput && extent > 0f
        ) {
            val raw = (physics.distance(offset, extent) + unconsumed)
                .coerceIn(-extent * 4f, extent * 4f)
            animation = AnimationState(physics.offset(raw, extent))
            unconsumed
        } else 0f
        return vector(relaxed + pulled) + consumed
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) = coroutineScope {
        if (!hasOverscroll && velocity != Velocity.Zero) vertical = abs(velocity.y) >= abs(velocity.x)
        flingJob?.cancel()
        val job = coroutineContext.job
        flingJob = job
        try {
            if (!physics.enabled || !ValueAnimator.areAnimatorsEnabled()) {
                animation = AnimationState(0f)
                returning = false
                returnHeld = false
                performFling(velocity)
                return@coroutineScope
            }
            val held = if (abs(offset) > ScrollEpsilon && offset * velocity.axis() > 0f) {
                velocity.axis()
            } else 0f
            val offered = if (vertical) velocity.copy(y = velocity.y - held)
                else velocity.copy(x = velocity.x - held)
            var lastFrameTime = AnimationConstants.UnspecifiedTime
            val frameTimeJob = launch {
                while (true) withFrameNanos { lastFrameTime = it }
            }
            val remaining = try {
                offered - performFling(offered)
            } finally {
                frameTimeJob.cancel()
            }
            ensureActive()
            if (!physics.enabled || !ValueAnimator.areAnimatorsEnabled()) {
                animation = AnimationState(0f)
                returning = false
                returnHeld = false
                return@coroutineScope
            }
            val edgeVelocity = held + remaining.axis()
            if (extent > 0f &&
                (abs(edgeVelocity) > 1f ||
                    (abs(offset) > ScrollEpsilon && offset * velocity.axis() >= 0f))
            ) {
                ScrollEdgeHaptics.perform(context, edgeVelocity)
            }
            animateReturn(
                physics.springVelocity(edgeVelocity, extent, offset),
                lastFrameTime,
            )
        } finally {
            if (flingJob === job) flingJob = null
        }
    }
}
