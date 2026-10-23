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
import android.os.Build
import android.util.Log
import androidx.xr.projected.ProjectedContext
import com.example.appfunctions.agent.domain.device.AgentInteractionMode
import com.example.appfunctions.agent.domain.device.DeviceCapabilities
import com.example.appfunctions.agent.domain.device.DeviceCapabilityRepository
import com.example.appfunctions.agent.domain.device.FormFactor
import com.example.appfunctions.agent.ui.glasses.GlassesAgentActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Default implementation of [DeviceCapabilityRepository] using Android system features and
 * Jetpack XR [ProjectedContext] to support Handset, Tablet, Wear OS, Auto, TV, and Android XR Glasses.
 */
@Singleton
class AndroidDeviceCapabilityRepository
    @Inject
    constructor(
        @ApplicationContext private val appContext: Context,
    ) : DeviceCapabilityRepository {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val glassesOverride = MutableStateFlow<Boolean?>(null)
        private val _formFactorOverride = MutableStateFlow<FormFactor?>(null)

        override val glassesConnectedOverride: StateFlow<Boolean?> = glassesOverride.asStateFlow()
        override val formFactorOverride: StateFlow<FormFactor?> = _formFactorOverride.asStateFlow()

        private val projectedDeviceFlow: Flow<Boolean> =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    ProjectedContext.isProjectedDeviceConnected(appContext, Dispatchers.Main)
                } else {
                    flowOf(false)
                }
            }.getOrElse { e ->
                Log.w(TAG, "ProjectedContext connectivity flow unavailable; defaulting to false", e)
                flowOf(false)
            }

        override val isGlassesConnected: StateFlow<Boolean> =
            combine(
                projectedDeviceFlow,
                glassesOverride,
                _formFactorOverride,
            ) { projectedConnected, overrideVal, formFactorVal ->
                when {
                    overrideVal != null -> overrideVal
                    formFactorVal == FormFactor.XR_GLASSES_DISPLAY ||
                        formFactorVal == FormFactor.XR_GLASSES_AUDIO -> true
                    else -> projectedConnected
                }
            }.stateIn(scope, SharingStarted.Eagerly, false)

        override fun observeCapabilities(isProjectedSurface: Boolean): Flow<DeviceCapabilities> {
            return combine(
                isGlassesConnected,
                _formFactorOverride,
            ) { glassesConnected, _ ->
                val formFactor = detectFormFactor(isProjectedSurface)
                val pm = appContext.packageManager
                val hasMic = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
                val hasCamera = pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
                val isSeeThrough =
                    formFactor == FormFactor.XR_GLASSES_DISPLAY || isProjectedSurface

                val supportedModes =
                    buildSet {
                        if (formFactor != FormFactor.XR_GLASSES_AUDIO &&
                            formFactor != FormFactor.AUTOMOTIVE
                        ) {
                            add(AgentInteractionMode.CHAT)
                        }
                        if (hasMic) {
                            add(AgentInteractionMode.LIVE_VOICE)
                        }
                        if (hasMic && (hasCamera || glassesConnected || isProjectedSurface)) {
                            add(AgentInteractionMode.LIVE_MULTIMODAL)
                        }
                    }

                DeviceCapabilities(
                    formFactor = formFactor,
                    isGlassesConnected = glassesConnected,
                    hasMicrophone = hasMic,
                    hasOutwardCamera = hasCamera || glassesConnected || isProjectedSurface,
                    isAdditiveSeeThroughDisplay = isSeeThrough,
                    supportedModes = supportedModes,
                )
            }
        }

        override fun detectFormFactor(isProjectedSurface: Boolean): FormFactor {
            _formFactorOverride.value?.let { return it }
            if (isProjectedSurface) {
                return FormFactor.XR_GLASSES_DISPLAY
            }
            val pm = appContext.packageManager
            return when {
                pm.hasSystemFeature(PackageManager.FEATURE_WATCH) -> FormFactor.WEAR
                pm.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) -> FormFactor.AUTOMOTIVE
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) -> FormFactor.TV
                pm.hasSystemFeature("android.software.xr.api.openxr") ||
                    pm.hasSystemFeature("android.hardware.xr.input") -> FormFactor.XR_HEADSET
                appContext.resources.configuration.smallestScreenWidthDp >= 600 -> FormFactor.TABLET
                else -> FormFactor.HANDSET
            }
        }

        override fun resolveHardwareContext(
            activityContext: Context,
            isProjectedSurface: Boolean,
        ): Context {
            if (!isProjectedSurface) return activityContext
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ProjectedContext.createProjectedDeviceContext(activityContext)
                } else {
                    activityContext
                }
            }.getOrElse { e ->
                Log.w(TAG, "Falling back to Activity context for projected hardware access", e)
                activityContext
            }
        }

        override fun resolveHostContext(activityContext: Context): Context {
            return runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ProjectedContext.createHostDeviceContext(activityContext)
                } else {
                    activityContext.applicationContext
                }
            }.getOrElse {
                activityContext.applicationContext
            }
        }

        override fun launchGlassesAgent(
            activityContext: Context,
            threadId: String?,
            targetPackageName: String?,
        ) {
            val intent =
                Intent(activityContext, GlassesAgentActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                    if (threadId != null) {
                        putExtra(GlassesAgentActivity.EXTRA_THREAD_ID, threadId)
                    }
                    if (targetPackageName != null) {
                        putExtra(GlassesAgentActivity.EXTRA_TARGET_PACKAGE_NAME, targetPackageName)
                    }
                }
            val projectedOptionsBundle =
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                        ProjectedContext.createProjectedActivityOptions(activityContext).toBundle()
                    } else {
                        null
                    }
                }.getOrNull()

            if (projectedOptionsBundle != null) {
                runCatching {
                    activityContext.startActivity(intent, projectedOptionsBundle)
                }.onFailure {
                    activityContext.startActivity(intent)
                }
            } else {
                activityContext.startActivity(intent)
            }
        }

        override fun setGlassesConnectedOverride(connected: Boolean?) {
            glassesOverride.value = connected
        }

        override fun setFormFactorOverride(formFactor: FormFactor?) {
            _formFactorOverride.value = formFactor
        }

        override fun buildSystemInstruction(
            formFactor: FormFactor,
            mode: AgentInteractionMode,
            activeTaskContext: String,
        ): String {
            val currentDate = LocalDate.now().toString()
            return when (formFactor) {
                FormFactor.XR_GLASSES_DISPLAY -> {
                    """
                    You are an interactive, real-time AI Assistant running on Android XR Display Smart Glasses (${formFactor.displayName}).
                    Today's date is $currentDate. Current workflow context: $activeTaskContext.

                    HOW YOUR HARDWARE, APP FUNCTIONS & SENSORS WORK:
                    1. ANDROID APP FUNCTIONS (CONNECTED APPS):
                       - You have direct access to Android AppFunctions exposed by installed apps on the user's phone (such as ChatApp's `searchContacts`, `send`, and `makeCall`, and location services).
                       - When the user asks to send a message or call someone in ChatApp, ALWAYS call `searchContacts` first to resolve the recipient's `endpointValue`, then call `send` or `makeCall`.
                    2. VISION (ON-DEMAND POV CAMERA & 1 FPS STREAMING):
                       - You receive 1 initial POV camera frame from the user's glasses when the session starts.
                       - Whenever the user asks "what am I looking at?", "check this", or moves to a new object/area, MUST call `snap_glasses_frame` to capture a fresh frame before answering.
                       - If the user asks you to continuously watch an activity or stop watching, call `set_camera_streaming(enabled)`.
                    3. GLANCEABLE HEADS-UP DISPLAY (HUD):
                       - Whenever you execute an AppFunction (like sending a message or searching contacts) or surface a key answer, call `update_hud_card(title, primaryText, subtitleHint)` so the user sees a clean, high-contrast summary on their see-through display while hearing your voice.
                    4. HANDS-FREE HARDWARE CONTROLS & TELEMETRY:
                       - If the user asks about glasses battery, camera, or connection state, call `get_glasses_hardware_status`.
                       - If the user asks to adjust volume ("louder", "quieter", "mute", "max volume"), call `adjust_glasses_volume`.
                       - If the user asks to pause ("hold on", "wait a second", "pause"), call `pause_session`.
                       - If the user asks to quit or finish ("end session", "exit app", "stop"), call `end_session` and speak a warm 1-sentence goodbye.

                    CRITICAL TOOL & STATE ACCURACY RULES (NO HALLUCINATIONS):
                    1. NEVER claim in speech that you sent a message, made a call, updated the HUD, or changed a setting UNLESS you invoke the corresponding AppFunction or device tool in the SAME turn.
                    2. Keep every spoken turn concise (1 to 2 sentences maximum). Users wearing smart glasses need brisk, natural, interruptible conversation.
                    """.trimIndent()
                }
                FormFactor.XR_GLASSES_AUDIO -> {
                    """
                    You are an interactive, real-time AI Assistant running on eyes-free Android XR Audio Smart Glasses (${formFactor.displayName}).
                    Today's date is $currentDate. Current workflow context: $activeTaskContext.

                    HOW YOUR AUDIO-ONLY GLASSES, APP FUNCTIONS & SENSORS WORK:
                    1. EYES-FREE AUDIO-ONLY MODE (NO VISUAL DISPLAY):
                       - These smart glasses have open-ear speakers, microphones, and an outward POV camera, but NO visual display or HUD card.
                       - Convey all answers clearly through concise spoken audio without relying on visual cards.
                    2. ANDROID APP FUNCTIONS (CONNECTED APPS):
                       - When the user asks to send a message or call someone in ChatApp, ALWAYS call `searchContacts` first to resolve the recipient's `endpointValue`, then call `send` or `makeCall`.
                    3. VISION & HARDWARE CONTROLS:
                       - Call `snap_glasses_frame` whenever the user asks what they are looking at, or `set_camera_streaming` for continuous watching.
                       - Use `get_glasses_hardware_status`, `adjust_glasses_volume`, `pause_session`, and `end_session` for hands-free hardware control.
                    4. CRITICAL ACCURACY:
                       - NEVER claim an action succeeded without invoking its AppFunction or device tool in the SAME turn. Keep spoken turns to 1-2 sentences maximum.
                    """.trimIndent()
                }
                FormFactor.XR_HEADSET -> {
                    """
                    You are an on-device spatial AI Assistant running on ${formFactor.displayName} in ${mode.name} mode.
                    Today's date is $currentDate. Context: $activeTaskContext.
                    Support rich spatial and multimodal workflows while keeping spoken or written responses clear and concise.
                    When interacting with apps like ChatApp, always call `searchContacts` first to resolve a valid `endpointValue` before calling `send` or `makeCall`.
                    Never claim an action succeeded without invoking its AppFunction tool first.
                    """.trimIndent()
                }
                FormFactor.TV -> {
                    """
                    You are an on-device Android assistant running on ${formFactor.displayName} (10-foot living-room experience).
                    Today's date is $currentDate. Context: $activeTaskContext.
                    Keep responses concise and easy to read or hear from across the room.
                    When interacting with apps like ChatApp, always call `searchContacts` first to find the `endpointValue` before calling `send` or `makeCall`.
                    Never claim an action succeeded without invoking its AppFunction tool first.
                    """.trimIndent()
                }
                FormFactor.AUTOMOTIVE,
                FormFactor.WEAR,
                -> {
                    """
                    You are an on-device Android assistant running in hands-free voice mode on ${formFactor.displayName}.
                    Today's date is $currentDate. Context: $activeTaskContext.
                    Keep spoken responses ultra-concise (1 sentence) for eyes-free safety.
                    When interacting with apps like ChatApp, always call `searchContacts` first to find the `endpointValue` before calling `send` or `makeCall`.
                    Never claim an action succeeded without invoking its AppFunction tool first.
                    """.trimIndent()
                }
                FormFactor.HANDSET,
                FormFactor.TABLET,
                -> {
                    if (mode == AgentInteractionMode.CHAT) {
                        "You are an assistant running on Android (${formFactor.displayName}). Be concise, direct and helpful. Today's date is $currentDate."
                    } else {
                        """
                        You are an on-device Android assistant running in Gemini Live voice mode on ${formFactor.displayName}.
                        Today's date is $currentDate. Context: $activeTaskContext.
                        Speak concisely and naturally (1 to 2 sentences).
                        When the user asks to interact with an installed app (such as searching contacts, sending messages, or making calls in ChatApp), invoke your available AppFunction tools immediately.
                        For ChatApp, always call `searchContacts` first to obtain a valid `endpointValue` before calling `send` or `makeCall`.
                        """.trimIndent()
                    }
                }
            }
        }

        companion object {
            private const val TAG = "AndroidDeviceCapability"
        }
    }
