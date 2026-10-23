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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.appfunctions.agent.BuildConfig
import com.example.appfunctions.agent.data.SettingsRepository
import com.example.appfunctions.agent.data.live.GeminiLiveProviderImpl
import com.example.appfunctions.agent.domain.appfunction.GetAppFunctionStatesUseCase
import com.example.appfunctions.agent.domain.appfunction.GetAppFunctionsUseCase
import com.example.appfunctions.agent.domain.device.AgentInteractionMode
import com.example.appfunctions.agent.domain.device.DeviceCapabilityRepository
import com.example.appfunctions.agent.domain.device.DeviceLocalToolRegistry
import com.example.appfunctions.agent.domain.live.LiveSessionState
import com.example.appfunctions.agent.domain.live.LiveVoiceOrchestrator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel managing the UI state for Gemini Live bidirectional voice & multimodal sessions
 * across phone, tablet, and Android XR glasses.
 */
@HiltViewModel
class LiveVoiceViewModel
    @Inject
    constructor(
        private val orchestrator: LiveVoiceOrchestrator,
        private val getAppFunctionsUseCase: GetAppFunctionsUseCase,
        private val getAppFunctionStatesUseCase: GetAppFunctionStatesUseCase,
        private val settingsRepository: SettingsRepository,
        private val deviceCapabilityRepository: DeviceCapabilityRepository,
    ) : ViewModel() {
        val sessionState: StateFlow<LiveSessionState> = orchestrator.sessionState
        val transcriptLog: StateFlow<List<String>> = orchestrator.transcriptLog
        val audioLevel: StateFlow<Float> = orchestrator.audioLevel
        val hudCardState: StateFlow<Pair<String, String>?> = orchestrator.hudCardState

        /**
         * Returns true if the audio output player is actively playing or within the 1200ms buffer tail.
         */
        fun isAudioPlaying(): Boolean = orchestrator.isAudioPlaying()

        /**
         * Toggles microphone mute state on the underlying audio capturer.
         */
        fun setMuted(muted: Boolean) {
            orchestrator.setMuted(muted)
        }

        /**
         * Starts a Gemini Live full-duplex session configured with all enabled AppFunction tools
         * across connected apps (such as ChatApp and built-in tools) and optional device-local tools.
         */
        fun startSession(
            apiKey: String? = null,
            modelName: String = GeminiLiveProviderImpl.DEFAULT_LIVE_MODEL,
            targetPackageName: String? = null,
            systemInstruction: String? = null,
            hardwareContext: Context? = null,
            hostContext: Context? = null,
            localToolRegistry: DeviceLocalToolRegistry? = null,
            onSetupComplete: (suspend () -> Unit)? = null,
        ) {
            viewModelScope.launch {
                val resolvedApiKey =
                    apiKey?.ifBlank { null }
                        ?: settingsRepository.geminiApiKey.firstOrNull()?.ifBlank { null }
                        ?: BuildConfig.GEMINI_API_KEY.ifBlank { "" }

                val disconnectedApps = settingsRepository.disconnectedApps.firstOrNull() ?: emptySet()
                val toolsMap = getAppFunctionsUseCase().firstOrNull() ?: emptyMap()
                val allTools = toolsMap.values.flatten()
                val states = getAppFunctionStatesUseCase(allTools.map { it.name })
                val stateMap = states.associateBy { it.functionName }
                val tools =
                    allTools
                        .filter { metadata ->
                            val isEnabled = stateMap[metadata.name]?.isEnabled ?: true
                            isEnabled &&
                                metadata.packageName !in disconnectedApps &&
                                (targetPackageName == null || metadata.packageName == targetPackageName)
                        }
                        .distinctBy { it.id }

                val resolvedSystemInstruction =
                    systemInstruction
                        ?: deviceCapabilityRepository.buildSystemInstruction(
                            formFactor = deviceCapabilityRepository.detectFormFactor(),
                            mode = AgentInteractionMode.LIVE_VOICE,
                        )

                orchestrator.startVoiceSession(
                    apiKey = resolvedApiKey,
                    modelName = modelName,
                    tools = tools,
                    systemInstruction = resolvedSystemInstruction,
                    hardwareContext = hardwareContext,
                    localToolRegistry = localToolRegistry,
                    onSetupComplete = onSetupComplete,
                )
            }
        }

        /**
         * Sends a direct text prompt over the active Gemini Live session (ideal for emulator testing).
         */
        fun sendTextPrompt(prompt: String) {
            if (prompt.isNotBlank()) {
                orchestrator.sendTextPrompt(prompt)
            }
        }

        /**
         * Sends a single JPEG snapshot frame upstream to Gemini Live (for `snap_glasses_frame`).
         */
        suspend fun sendVideoFrame(jpegBase64: String) {
            orchestrator.sendVideoFrame(jpegBase64)
        }

        /**
         * Updates the active HUD card state (for `update_hud_card` on XR Display Glasses).
         */
        fun updateHudCard(
            title: String,
            subtitle: String,
        ) {
            orchestrator.updateHudCard(title, subtitle)
        }

        /**
         * Ends the active voice session and releases microphone/speaker resources.
         */
        fun stopSession() {
            orchestrator.stopVoiceSession()
        }

        override fun onCleared() {
            super.onCleared()
            orchestrator.stopVoiceSession()
        }
    }
