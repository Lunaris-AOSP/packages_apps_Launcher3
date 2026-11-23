/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.launcher3.graphics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.FakeLauncherPrefs
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.dagger.LauncherAppComponent
import com.android.launcher3.dagger.LauncherAppSingleton
import com.android.launcher3.icons.mono.MonoIconThemeController
import com.android.launcher3.shapes.CornerStyle
import com.android.launcher3.shapes.CustomIconShape
import com.android.launcher3.shapes.ShapeCorner
import com.android.launcher3.util.AllModulesForTest
import com.android.launcher3.util.Executors.MAIN_EXECUTOR
import com.android.launcher3.util.FakePrefsModule
import com.android.launcher3.util.SandboxApplication
import com.android.launcher3.util.TestUtil
import com.google.common.truth.Truth.assertThat
import dagger.Component
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ThemeManagerTest {

    @get:Rule val context = SandboxApplication()

    lateinit var themeManager: ThemeManager

    @Before
    fun setUp() {
        context.initDaggerComponent(DaggerThemeManagerComponent.builder())
        themeManager = ThemeManager.INSTANCE[context]
    }

    @Test
    fun `folder override leaves app shape unchanged and notifies renderers`() {
        val appShape = themeManager.iconShape
        var dispatched = false
        val subscription = themeManager.iconShapeData.forEach(MAIN_EXECUTOR) { dispatched = true }
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        dispatched = false
        val shape = CustomIconShape.DEFAULT
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {
            LauncherPrefs.get(context)
                .put(
                    ThemeManager.PREF_CUSTOM_FOLDER_SHAPE to shape.serialize(),
                    ThemeManager.PREF_FOLDER_SHAPE to CustomIconShape.KEY,
                )
        }
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        subscription.close()
        assertEquals(shape.pathString, themeManager.iconState.folderMask)
        assertSame(appShape, themeManager.iconShape)
        assertTrue(dispatched)
    }

    @Test
    fun `expanded folder ignores override and shrinking restores it`() {
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {
            LauncherPrefs.get(context)
                .put(
                    ThemeManager.PREF_CUSTOM_FOLDER_SHAPE to CustomIconShape.DEFAULT.serialize(),
                    ThemeManager.PREF_FOLDER_SHAPE to CustomIconShape.KEY,
                )
        }
        val selected = themeManager.folderShapeForSpan(1, 1)
        val expanded = themeManager.folderShapeForSpan(2, 1)
        assertNotSame(selected, expanded)
        assertSame(expanded, themeManager.folderShapeForSpan(1, 2))
        assertSame(expanded, themeManager.folderShapeForSpan(2, 2))
        assertSame(expanded, themeManager.folderShapeForSpan(3, 3))
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {
            LauncherPrefs.get(context)
                .put(
                    ThemeManager.PREF_CUSTOM_FOLDER_SHAPE,
                    CustomIconShape.DEFAULT.copy(topLeft = ShapeCorner(CornerStyle.CUT, 1f))
                        .serialize(),
                )
        }
        assertSame(expanded, themeManager.folderShapeForSpan(2, 1))
        assertNotSame(selected, themeManager.folderShapeForSpan(1, 1))
        assertSame(themeManager.folderShape, themeManager.folderShapeForSpan(1, 1))
    }

    @Test
    fun `matching folders follows custom icon path and invalid override falls back`() {
        val shape = CustomIconShape.DEFAULT.copy(topLeft = ShapeCorner(CornerStyle.CUT, 1f))
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {
            LauncherPrefs.get(context)
                .put(
                    ThemeManager.PREF_CUSTOM_ICON_SHAPE to shape.serialize(),
                    ThemeManager.PREF_ICON_SHAPE to CustomIconShape.KEY,
                    ThemeManager.PREF_CUSTOM_FOLDER_SHAPE to "invalid",
                    ThemeManager.PREF_FOLDER_SHAPE to CustomIconShape.KEY,
                )
        }
        assertEquals(shape.pathString, themeManager.iconState.folderMask)
    }

    @Test
    fun `isMonoThemeEnabled get and set`() {
        themeManager.isMonoThemeEnabled = true
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        assertTrue(themeManager.isMonoThemeEnabled)
        assertThat(themeManager.iconState.themeController)
            .isInstanceOf(MonoIconThemeController::class.java)

        themeManager.isMonoThemeEnabled = false
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        assertFalse(themeManager.isMonoThemeEnabled)
        assertThat(themeManager.iconState.themeController).isNull()
    }

    @Test
    fun `callback called on theme change`() {
        themeManager.isMonoThemeEnabled = false

        var callbackCalled = false
        themeManager.addChangeListener { callbackCalled = true }
        themeManager.isMonoThemeEnabled = true
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}

        assertTrue(callbackCalled)
    }

    @Test
    fun `iconState changes with theme`() {
        themeManager.isMonoThemeEnabled = false
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        val disabledIconState = themeManager.iconState

        themeManager.isMonoThemeEnabled = true
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        assertNotEquals(disabledIconState, themeManager.iconState)

        themeManager.isMonoThemeEnabled = false
        TestUtil.runOnExecutorSync(MAIN_EXECUTOR) {}
        assertEquals(disabledIconState, themeManager.iconState)
    }
}

@LauncherAppSingleton
@Component(modules = [AllModulesForTest::class, FakePrefsModule::class])
interface ThemeManagerComponent : LauncherAppComponent {

    override fun getLauncherPrefs(): FakeLauncherPrefs

    @Component.Builder
    interface Builder : LauncherAppComponent.Builder {

        override fun build(): ThemeManagerComponent
    }
}
