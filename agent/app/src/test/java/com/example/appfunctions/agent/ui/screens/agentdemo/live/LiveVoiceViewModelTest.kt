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
package com.example.appfunctions.agent.ui.screens.agentdemo.live

import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionPackageMetadata
import com.example.appfunctions.agent.data.SettingsRepository
import com.example.appfunctions.agent.data.live.GeminiLiveProviderImpl
import com.example.appfunctions.agent.domain.appfunction.GetAppFunctionStatesUseCase
import com.example.appfunctions.agent.domain.appfunction.GetAppFunctionsUseCase
import com.example.appfunctions.agent.domain.device.AgentInteractionMode
import com.example.appfunctions.agent.domain.device.DeviceCapabilityRepository
import com.example.appfunctions.agent.domain.device.FormFactor
import com.example.appfunctions.agent.domain.live.LiveSessionState
import com.example.appfunctions.agent.domain.live.LiveVoiceOrchestrator
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [LiveVoiceViewModel]. */
class LiveVoiceViewModelTest {
    private val orchestrator = mockk<LiveVoiceOrchestrator>(relaxed = true)
    private val getAppFunctionsUseCase = mockk<GetAppFunctionsUseCase>(relaxed = true)
    private val getAppFunctionStatesUseCase = mockk<GetAppFunctionStatesUseCase>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val deviceCapabilityRepository = mockk<DeviceCapabilityRepository>(relaxed = true)

    @Test
    fun `startSession delegates to orchestrator with ChatApp tools and form factor system instruction`() =
        runTest {
            val pkgMetadata = mockk<AppFunctionPackageMetadata>(relaxed = true)
            val chatAppTool = mockk<AppFunctionMetadata>(relaxed = true)
            every { chatAppTool.id } returns "com.example.chatapp.functions.ChatFunctions#send"
            every { chatAppTool.packageName } returns "com.example.chatapp"
            coEvery { getAppFunctionStatesUseCase(any()) } returns emptyList()

            every { orchestrator.sessionState } returns MutableStateFlow(LiveSessionState.Idle)
            every { orchestrator.transcriptLog } returns MutableStateFlow(emptyList())
            every { orchestrator.audioLevel } returns MutableStateFlow(0f)
            every { orchestrator.hudCardState } returns MutableStateFlow(null)
            every { getAppFunctionsUseCase() } returns
                flowOf(mapOf(pkgMetadata to listOf(chatAppTool)))
            every { settingsRepository.disconnectedApps } returns flowOf(emptySet())
            every { deviceCapabilityRepository.detectFormFactor(any()) } returns FormFactor.HANDSET
            every {
                deviceCapabilityRepository.buildSystemInstruction(
                    formFactor = FormFactor.HANDSET,
                    mode = AgentInteractionMode.LIVE_VOICE,
                    activeTaskContext = any(),
                )
            } returns "Handset Live Voice Instruction"

            val viewModel =
                LiveVoiceViewModel(
                    orchestrator,
                    getAppFunctionsUseCase,
                    getAppFunctionStatesUseCase,
                    settingsRepository,
                    deviceCapabilityRepository,
                )
            viewModel.startSession(apiKey = "key-123")

            verify(timeout = 1000) {
                orchestrator.startVoiceSession(
                    apiKey = "key-123",
                    modelName = GeminiLiveProviderImpl.DEFAULT_LIVE_MODEL,
                    tools = listOf(chatAppTool),
                    systemInstruction = "Handset Live Voice Instruction",
                    hardwareContext = null,
                    localToolRegistry = null,
                    onSetupComplete = null,
                )
            }
            assertEquals(LiveSessionState.Idle, viewModel.sessionState.value)
        }

    @Test
    fun `startSession filters tools by targetPackageName and disconnectedApps`() =
        runTest {
            val pkgMetadata = mockk<AppFunctionPackageMetadata>(relaxed = true)
            val chatAppTool = mockk<AppFunctionMetadata>(relaxed = true)
            every { chatAppTool.id } returns "com.example.chatapp#send"
            every { chatAppTool.packageName } returns "com.example.chatapp"

            val otherAppTool = mockk<AppFunctionMetadata>(relaxed = true)
            every { otherAppTool.id } returns "com.example.other#action"
            every { otherAppTool.packageName } returns "com.example.other"
            coEvery { getAppFunctionStatesUseCase(any()) } returns emptyList()

            every { orchestrator.sessionState } returns MutableStateFlow(LiveSessionState.Idle)
            every { orchestrator.transcriptLog } returns MutableStateFlow(emptyList())
            every { orchestrator.audioLevel } returns MutableStateFlow(0f)
            every { orchestrator.hudCardState } returns MutableStateFlow(null)
            every { getAppFunctionsUseCase() } returns
                flowOf(mapOf(pkgMetadata to listOf(chatAppTool, otherAppTool)))
            every { settingsRepository.disconnectedApps } returns flowOf(emptySet())

            val viewModel =
                LiveVoiceViewModel(
                    orchestrator,
                    getAppFunctionsUseCase,
                    getAppFunctionStatesUseCase,
                    settingsRepository,
                    deviceCapabilityRepository,
                )
            viewModel.startSession(
                apiKey = "key-123",
                targetPackageName = "com.example.chatapp",
                systemInstruction = "Custom Instruction",
            )

            verify(timeout = 1000) {
                orchestrator.startVoiceSession(
                    apiKey = "key-123",
                    modelName = GeminiLiveProviderImpl.DEFAULT_LIVE_MODEL,
                    tools = listOf(chatAppTool),
                    systemInstruction = "Custom Instruction",
                    hardwareContext = null,
                    localToolRegistry = null,
                    onSetupComplete = null,
                )
            }
        }

    @Test
    fun `sendTextPrompt forwards non-blank prompt to orchestrator`() {
        val viewModel =
            LiveVoiceViewModel(
                orchestrator,
                getAppFunctionsUseCase,
                getAppFunctionStatesUseCase,
                settingsRepository,
                deviceCapabilityRepository,
            )

        viewModel.sendTextPrompt("   ")
        verify(exactly = 0) { orchestrator.sendTextPrompt(any()) }

        viewModel.sendTextPrompt("Search contacts for Alice")
        verify(exactly = 1) { orchestrator.sendTextPrompt("Search contacts for Alice") }
    }

    @Test
    fun `stopSession stops active voice session on orchestrator`() {
        every { orchestrator.sessionState } returns MutableStateFlow(LiveSessionState.Listening)
        every { orchestrator.transcriptLog } returns MutableStateFlow(emptyList())
        every { orchestrator.audioLevel } returns MutableStateFlow(0f)
        every { orchestrator.hudCardState } returns MutableStateFlow(null)

        val viewModel =
            LiveVoiceViewModel(
                orchestrator,
                getAppFunctionsUseCase,
                getAppFunctionStatesUseCase,
                settingsRepository,
                deviceCapabilityRepository,
            )
        viewModel.stopSession()

        verify { orchestrator.stopVoiceSession() }
    }
}
