/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.example.appfunctions.agent.data.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import com.example.appfunctions.agent.domain.device.AgentInteractionMode
import com.example.appfunctions.agent.domain.device.FormFactor
import com.example.appfunctions.agent.ui.glasses.GlassesAgentActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [AndroidDeviceCapabilityRepository].
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AndroidDeviceCapabilityRepositoryTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var mockContext: Context
    private lateinit var mockPackageManager: PackageManager
    private lateinit var mockResources: Resources
    private lateinit var configuration: Configuration
    private lateinit var repository: AndroidDeviceCapabilityRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockContext = mockk(relaxed = true)
        mockPackageManager = mockk(relaxed = true)
        mockResources = mockk(relaxed = true)
        configuration = Configuration().apply { smallestScreenWidthDp = 411 }

        every { mockContext.packageManager } returns mockPackageManager
        every { mockContext.resources } returns mockResources
        every { mockResources.configuration } returns configuration
        every { mockPackageManager.hasSystemFeature(any()) } returns false
        every {
            mockPackageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        } returns true
        every {
            mockPackageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        } returns true

        repository = AndroidDeviceCapabilityRepository(mockContext)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `detectFormFactor identifies Handset, Tablet, Wear, Automotive, TV, XR Headset, and Projected`() {
        assertEquals(FormFactor.HANDSET, repository.detectFormFactor(isProjectedSurface = false))
        assertEquals(
            FormFactor.XR_GLASSES_DISPLAY,
            repository.detectFormFactor(isProjectedSurface = true),
        )

        configuration.smallestScreenWidthDp = 720
        assertEquals(FormFactor.TABLET, repository.detectFormFactor(isProjectedSurface = false))

        every {
            mockPackageManager.hasSystemFeature("android.software.xr.api.openxr")
        } returns true
        assertEquals(
            FormFactor.XR_HEADSET,
            repository.detectFormFactor(isProjectedSurface = false),
        )

        every { mockPackageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) } returns true
        assertEquals(FormFactor.TV, repository.detectFormFactor(isProjectedSurface = false))

        every {
            mockPackageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        } returns true
        assertEquals(
            FormFactor.AUTOMOTIVE,
            repository.detectFormFactor(isProjectedSurface = false),
        )

        every { mockPackageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) } returns true
        assertEquals(FormFactor.WEAR, repository.detectFormFactor(isProjectedSurface = false))
    }

    @Test
    fun `formFactorOverride and glassesConnectedOverride update state flows and capabilities`() =
        runTest {
            assertNull(repository.formFactorOverride.value)
            assertNull(repository.glassesConnectedOverride.value)
            assertFalse(repository.isGlassesConnected.value)

            // Simulating XR Display Glasses via formFactorOverride marks glasses connected
            repository.setFormFactorOverride(FormFactor.XR_GLASSES_DISPLAY)
            assertEquals(FormFactor.XR_GLASSES_DISPLAY, repository.formFactorOverride.value)
            assertEquals(FormFactor.XR_GLASSES_DISPLAY, repository.detectFormFactor(false))
            assertTrue(repository.isGlassesConnected.value)

            val displayCaps = repository.observeCapabilities().first()
            assertEquals(FormFactor.XR_GLASSES_DISPLAY, displayCaps.formFactor)
            assertTrue(displayCaps.isGlassesConnected)
            assertTrue(displayCaps.isAdditiveSeeThroughDisplay)
            assertTrue(displayCaps.supportedModes.contains(AgentInteractionMode.LIVE_MULTIMODAL))

            // Simulating XR Audio Glasses disables visual CHAT mode and additive display
            repository.setFormFactorOverride(FormFactor.XR_GLASSES_AUDIO)
            val audioCaps = repository.observeCapabilities().first()
            assertEquals(FormFactor.XR_GLASSES_AUDIO, audioCaps.formFactor)
            assertTrue(audioCaps.isGlassesConnected)
            assertFalse(audioCaps.isAdditiveSeeThroughDisplay)
            assertFalse(audioCaps.supportedModes.contains(AgentInteractionMode.CHAT))
            assertTrue(audioCaps.supportedModes.contains(AgentInteractionMode.LIVE_VOICE))

            // Explicit glassesConnectedOverride takes precedence over formFactorOverride
            repository.setGlassesConnectedOverride(false)
            assertEquals(false, repository.glassesConnectedOverride.value)
            assertFalse(repository.isGlassesConnected.value)

            repository.setGlassesConnectedOverride(null)
            repository.setFormFactorOverride(null)
            assertFalse(repository.isGlassesConnected.value)
        }

    @Test
    fun `buildSystemInstruction produces distinct instructions for Display Glasses, Audio Glasses, XR Headset, and TV`() {
        val displayPrompt =
            repository.buildSystemInstruction(
                formFactor = FormFactor.XR_GLASSES_DISPLAY,
                mode = AgentInteractionMode.LIVE_MULTIMODAL,
            )
        assertTrue(displayPrompt.contains("update_hud_card"))
        assertTrue(displayPrompt.contains("snap_glasses_frame"))
        assertTrue(displayPrompt.contains("set_camera_streaming"))
        assertTrue(displayPrompt.contains("get_glasses_hardware_status"))

        val audioPrompt =
            repository.buildSystemInstruction(
                formFactor = FormFactor.XR_GLASSES_AUDIO,
                mode = AgentInteractionMode.LIVE_VOICE,
            )
        assertTrue(audioPrompt.contains("NO visual display"))
        assertFalse(audioPrompt.contains("update_hud_card(title"))
        assertTrue(audioPrompt.contains("snap_glasses_frame"))

        val headsetPrompt =
            repository.buildSystemInstruction(
                formFactor = FormFactor.XR_HEADSET,
                mode = AgentInteractionMode.LIVE_MULTIMODAL,
            )
        assertTrue(headsetPrompt.contains(FormFactor.XR_HEADSET.displayName))
        assertTrue(headsetPrompt.contains("searchContacts"))

        val tvPrompt =
            repository.buildSystemInstruction(
                formFactor = FormFactor.TV,
                mode = AgentInteractionMode.LIVE_VOICE,
            )
        assertTrue(tvPrompt.contains(FormFactor.TV.displayName))
        assertTrue(tvPrompt.contains("10-foot"))
    }

    @Test
    fun `launchGlassesAgent populates intent flags and extras and falls back cleanly on standard devices`() {
        val intentSlot = slot<Intent>()
        every { mockContext.startActivity(capture(intentSlot)) } returns Unit

        repository.launchGlassesAgent(
            activityContext = mockContext,
            threadId = "thread-42",
            targetPackageName = "com.example.chatapp",
        )

        verify { mockContext.startActivity(any()) }
        val captured = intentSlot.captured
        assertEquals(
            "thread-42",
            captured.getStringExtra(GlassesAgentActivity.EXTRA_THREAD_ID),
        )
        assertEquals(
            "com.example.chatapp",
            captured.getStringExtra(GlassesAgentActivity.EXTRA_TARGET_PACKAGE_NAME),
        )
        val expectedFlags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(expectedFlags, captured.flags and expectedFlags)
    }
}
