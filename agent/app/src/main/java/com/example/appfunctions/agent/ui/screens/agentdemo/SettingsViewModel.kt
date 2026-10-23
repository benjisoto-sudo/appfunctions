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
package com.example.appfunctions.agent.ui.screens.agentdemo

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.appfunctions.agent.data.LlmProviderName
import com.example.appfunctions.agent.data.ServiceTier
import com.example.appfunctions.agent.data.SettingsRepository
import com.example.appfunctions.agent.domain.device.DeviceCapabilityRepository
import com.example.appfunctions.agent.domain.device.FormFactor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** ViewModel for the Settings screen, managing API keys, provider selection, and XR form factor testing. */
@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val deviceCapabilityRepository: DeviceCapabilityRepository,
    ) : ViewModel() {
        val geminiApiKeyState = TextFieldState()

        val glassesConnectedOverride: StateFlow<Boolean?> =
            deviceCapabilityRepository.glassesConnectedOverride

        val formFactorOverride: StateFlow<FormFactor?> =
            deviceCapabilityRepository.formFactorOverride

        val uiState: StateFlow<SettingsUiState> =
            combine(
                settingsRepository.selectedProvider,
                settingsRepository.serviceTier,
            ) { provider, serviceTier ->
                SettingsUiState(selectedProvider = provider, serviceTier = serviceTier)
            }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = SettingsUiState(),
                )

        init {
            viewModelScope.launch {
                val geminiKey = settingsRepository.geminiApiKey.firstOrNull()
                if (!geminiKey.isNullOrEmpty()) {
                    geminiApiKeyState.setTextAndPlaceCursorAtEnd(geminiKey)
                }
            }
        }

        fun saveSettings() {
            viewModelScope.launch {
                withContext(NonCancellable) {
                    settingsRepository.setGeminiApiKey(geminiApiKeyState.text.toString())
                }
            }
        }

        fun setSelectedProvider(provider: LlmProviderName) {
            viewModelScope.launch { settingsRepository.setSelectedProvider(provider) }
        }

        fun setServiceTier(tier: ServiceTier) {
            viewModelScope.launch { settingsRepository.setServiceTier(tier) }
        }

        fun setGlassesSimulation(enabled: Boolean) {
            deviceCapabilityRepository.setGlassesConnectedOverride(if (enabled) true else null)
        }

        fun setFormFactorOverride(formFactor: FormFactor?) {
            deviceCapabilityRepository.setFormFactorOverride(formFactor)
        }
    }
