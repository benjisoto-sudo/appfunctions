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
package com.example.appfunctions.agent.domain.device

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Supported Android device form factors for the AppFunctions Testing Agent.
 */
enum class FormFactor(val displayName: String) {
    HANDSET("Handset"),
    TABLET("Tablet"),
    WEAR("Wear OS"),
    AUTOMOTIVE("Android Auto / Automotive"),
    TV("Android TV"),
    XR_HEADSET("Android XR Headset"),
    XR_GLASSES_AUDIO("Android XR Audio Glasses"),
    XR_GLASSES_DISPLAY("Android XR Display Glasses"),
    ;

    /** Maps this granular device [FormFactor] to the UI layout [com.example.appfunctions.agent.ui.layout.FormFactor]. */
    fun toLayoutFormFactor(): com.example.appfunctions.agent.ui.layout.FormFactor =
        when (this) {
            HANDSET, TABLET -> com.example.appfunctions.agent.ui.layout.FormFactor.MOBILE
            TV -> com.example.appfunctions.agent.ui.layout.FormFactor.TV
            WEAR -> com.example.appfunctions.agent.ui.layout.FormFactor.WEAR
            AUTOMOTIVE -> com.example.appfunctions.agent.ui.layout.FormFactor.AUTO
            XR_HEADSET, XR_GLASSES_AUDIO, XR_GLASSES_DISPLAY ->
                com.example.appfunctions.agent.ui.layout.FormFactor.XR
        }
}

/**
 * Supported interaction modalities for testing AppFunctions across form factors.
 */
enum class AgentInteractionMode {
    CHAT,
    LIVE_VOICE,
    LIVE_MULTIMODAL,
}

/**
 * Snapshot of current device hardware, form factor, and connected XR eyewear state.
 */
data class DeviceCapabilities(
    val formFactor: FormFactor = FormFactor.HANDSET,
    val isGlassesConnected: Boolean = false,
    val hasMicrophone: Boolean = true,
    val hasOutwardCamera: Boolean = true,
    val isAdditiveSeeThroughDisplay: Boolean = false,
    val supportedModes: Set<AgentInteractionMode> =
        setOf(
            AgentInteractionMode.CHAT,
            AgentInteractionMode.LIVE_VOICE,
            AgentInteractionMode.LIVE_MULTIMODAL,
        ),
)

/**
 * Parameter specification for a device-local hardware or HUD tool (such as XR glasses controls).
 */
data class LocalToolParam(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    val enumValues: List<String>? = null,
)

/**
 * Specification for a device-local tool exposed alongside system
 * [androidx.appfunctions.metadata.AppFunctionMetadata].
 */
data class LocalDeviceToolSpec(
    val name: String,
    val description: String,
    val params: List<LocalToolParam> = emptyList(),
    val deduplicatePerTurn: Boolean = true,
)

/**
 * Result returned by a device-local tool execution.
 */
data class LocalToolResult(
    val ok: Boolean,
    val message: String,
    val payload: Map<String, String> = emptyMap(),
) {
    fun toJsonString(): String {
        val obj =
            buildJsonObject {
                put("status", JsonPrimitive(if (ok) "success" else "error"))
                put("message", JsonPrimitive(message))
                payload.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
            }
        return obj.toString()
    }
}

/**
 * Registry for device-local hardware & UI tools (e.g. Android XR Glasses camera snapshot,
 * camera streaming, hardware status, Glimmer HUD card update, open-ear speaker volume,
 * pause, and end session) that operate alongside discovered system AppFunctions in a
 * Gemini Live session.
 *
 * Enforces turn-level deduplication (`deduplicatePerTurn`) so streaming models do not invoke
 * duplicate side-effect calls within a single turn before [onTurnBoundary] is called.
 */
class DeviceLocalToolRegistry(
    private val onToolExecuted: ((toolName: String, summary: String) -> Unit)? = null,
) {
    private data class RegisteredLocalTool(
        val spec: LocalDeviceToolSpec,
        val handler: suspend (JsonObject) -> LocalToolResult,
    )

    private val tools = ConcurrentHashMap<String, RegisteredLocalTool>()
    private val executedInCurrentTurn = ConcurrentHashMap.newKeySet<String>()

    fun register(
        spec: LocalDeviceToolSpec,
        handler: suspend (JsonObject) -> LocalToolResult,
    ) {
        tools[spec.name] = RegisteredLocalTool(spec, handler)
    }

    fun clear() {
        tools.clear()
        executedInCurrentTurn.clear()
    }

    fun onTurnBoundary() {
        executedInCurrentTurn.clear()
    }

    fun hasTool(name: String): Boolean = tools.containsKey(name)

    fun toGeminiFunctionDeclarations(): List<JsonObject> {
        return tools.entries
            .sortedBy { it.key }
            .map { (_, reg) ->
                val spec = reg.spec
                buildJsonObject {
                    put("name", JsonPrimitive(spec.name))
                    put("description", JsonPrimitive(spec.description))
                    put(
                        "parameters",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    spec.params.forEach { param ->
                                        put(
                                            param.name,
                                            buildJsonObject {
                                                put("type", JsonPrimitive(param.type.lowercase()))
                                                put("description", JsonPrimitive(param.description))
                                                if (!param.enumValues.isNullOrEmpty()) {
                                                    put(
                                                        "enum",
                                                        buildJsonArray {
                                                            param.enumValues.forEach {
                                                                add(JsonPrimitive(it))
                                                            }
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                            val requiredList = spec.params.filter { it.required }.map { it.name }
                            if (requiredList.isNotEmpty()) {
                                put(
                                    "required",
                                    buildJsonArray {
                                        requiredList.forEach { add(JsonPrimitive(it)) }
                                    },
                                )
                            }
                        },
                    )
                }
            }
    }

    suspend fun execute(
        name: String,
        args: JsonObject?,
    ): LocalToolResult {
        val reg =
            tools[name]
                ?: return LocalToolResult(ok = false, message = "Unknown device tool '$name'.")
        val safeArgs = args ?: JsonObject(emptyMap())
        val dedupKey = "$name:$safeArgs"
        if (reg.spec.deduplicatePerTurn && !executedInCurrentTurn.add(dedupKey)) {
            return LocalToolResult(
                ok = true,
                message = "Already executed '$name' in this turn; do not repeat.",
            )
        }
        return try {
            val result = reg.handler(safeArgs)
            onToolExecuted?.invoke(name, result.message)
            result
        } catch (e: Exception) {
            LocalToolResult(ok = false, message = "Error executing '$name': ${e.message}")
        }
    }

    /**
     * Registers the 7 standard Android XR Glasses hardware & Glimmer HUD tools from the
     * Android XR In-App Agent Developer Guide (`DEVELOPER_GUIDE.md`).
     */
    fun registerStandardGlassesTools(
        onSnapFrame: suspend (reason: String) -> Boolean,
        onUpdateHudCard: (title: String, primaryText: String, subtitleHint: String) -> Unit,
        onAdjustVolume: (direction: String, steps: Int) -> String,
        onPauseSession: (reason: String) -> Unit,
        onEndSession: (reason: String) -> Unit,
        onSetCameraStreaming: (enabled: Boolean) -> String = { "Camera streaming: $it" },
        onGetHardwareStatus: () -> Map<String, String> = { emptyMap() },
        onTogglePauseState: ((Boolean, String) -> Unit)? = null,
    ) {
        register(
            LocalDeviceToolSpec(
                name = "snap_glasses_frame",
                description =
                    "Captures a fresh POV camera frame from the user's smart glasses when the " +
                        "user asks what they are looking at, points at an object, or moves to a " +
                        "new environment.",
                params =
                    listOf(
                        LocalToolParam(
                            name = "reason",
                            type = "string",
                            description =
                                "Why a fresh camera snapshot is needed " +
                                    "(e.g. 'user_asked_what_is_this').",
                            required = false,
                        ),
                    ),
                deduplicatePerTurn = true,
            ),
        ) { args ->
            val reason = args["reason"]?.jsonPrimitive?.content ?: "visual_grounding"
            val sent = onSnapFrame(reason)
            LocalToolResult(
                ok = sent,
                message =
                    if (sent) {
                        "Captured fresh POV camera frame from glasses ($reason)."
                    } else {
                        "Glasses camera frame not ready yet; answer based on current context."
                    },
            )
        }

        register(
            LocalDeviceToolSpec(
                name = "set_camera_streaming",
                description =
                    "Enables or disables continuous 1 FPS POV camera frame streaming from the " +
                        "smart glasses when the user asks you to continuously watch an activity " +
                        "or stop watching.",
                params =
                    listOf(
                        LocalToolParam(
                            name = "enabled",
                            type = "boolean",
                            description =
                                "True to enable continuous 1 FPS camera streaming, false to " +
                                    "return to on-demand snapshots.",
                            required = true,
                        ),
                    ),
                deduplicatePerTurn = true,
            ),
        ) { args ->
            val enabled =
                args["enabled"]?.jsonPrimitive?.booleanOrNull
                    ?: args["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: false
            val status = onSetCameraStreaming(enabled)
            LocalToolResult(
                ok = true,
                message = status,
                payload = mapOf("cameraStreaming" to enabled.toString()),
            )
        }

        register(
            LocalDeviceToolSpec(
                name = "get_glasses_hardware_status",
                description =
                    "Returns the current smart glasses hardware status (connection state, " +
                        "camera streaming mode, microphone pause state, and display availability).",
                params = emptyList(),
                deduplicatePerTurn = true,
            ),
        ) { _ ->
            val statusMap = onGetHardwareStatus()
            val summary =
                if (statusMap.isEmpty()) {
                    "Glasses hardware status: active."
                } else {
                    statusMap.entries.joinToString(", ") { "${it.key}=${it.value}" }
                }
            LocalToolResult(
                ok = true,
                message = summary,
                payload = statusMap,
            )
        }

        register(
            LocalDeviceToolSpec(
                name = "update_hud_card",
                description =
                    "Updates the primary glanceable card on the user's see-through glasses " +
                        "display. Call this whenever you surface a key step, message summary, " +
                        "contact match, or answer.",
                params =
                    listOf(
                        LocalToolParam(
                            name = "title",
                            type = "string",
                            description = "Short header chip label (max 25 chars)",
                            required = true,
                        ),
                        LocalToolParam(
                            name = "primaryText",
                            type = "string",
                            description = "Main high-contrast HUD line (max 60 chars)",
                            required = true,
                        ),
                        LocalToolParam(
                            name = "subtitleHint",
                            type = "string",
                            description = "Secondary supporting detail or voice prompt hint",
                            required = true,
                        ),
                    ),
                deduplicatePerTurn = true,
            ),
        ) { args ->
            val title = args["title"]?.jsonPrimitive?.content ?: "Agent HUD"
            val primaryText = args["primaryText"]?.jsonPrimitive?.content ?: ""
            val subtitleHint = args["subtitleHint"]?.jsonPrimitive?.content ?: ""
            onUpdateHudCard(title, primaryText, subtitleHint)
            LocalToolResult(
                ok = true,
                message = "Updated glasses HUD card: '$title' -> '$primaryText'.",
            )
        }

        register(
            LocalDeviceToolSpec(
                name = "adjust_glasses_volume",
                description =
                    "Adjusts the open-ear speaker volume on the smart glasses when the user " +
                        "says 'speak louder', 'lower volume', 'mute', 'unmute', or 'max volume'.",
                params =
                    listOf(
                        LocalToolParam(
                            name = "direction",
                            type = "string",
                            description = "Volume adjustment direction",
                            required = true,
                            enumValues = listOf("UP", "DOWN", "MUTE", "UNMUTE", "MAX"),
                        ),
                        LocalToolParam(
                            name = "steps",
                            type = "integer",
                            description = "Number of volume steps (1 to 5, default 2)",
                            required = false,
                        ),
                    ),
                deduplicatePerTurn = true,
            ),
        ) { args ->
            val rawDirection =
                args["direction"]?.jsonPrimitive?.content
                    ?: args["level"]?.jsonPrimitive?.content
                    ?: "UP"
            val direction = rawDirection.uppercase()
            val steps = (args["steps"]?.jsonPrimitive?.intOrNull ?: 2).coerceIn(1, 5)
            val status = onAdjustVolume(direction, steps)
            LocalToolResult(ok = true, message = status)
        }

        register(
            LocalDeviceToolSpec(
                name = "pause_session",
                description =
                    "Pauses or resumes the live microphone and agent session when the user " +
                        "says 'pause', 'hold on a second', 'wait', or 'resume'.",
                params =
                    listOf(
                        LocalToolParam(
                            name = "paused",
                            type = "boolean",
                            description = "True to pause the session (default), false to resume.",
                            required = false,
                        ),
                        LocalToolParam(
                            name = "reason",
                            type = "string",
                            description = "Reason for pausing or resuming",
                            required = false,
                        ),
                    ),
                deduplicatePerTurn = true,
            ),
        ) { args ->
            val paused =
                args["paused"]?.jsonPrimitive?.booleanOrNull
                    ?: args["paused"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                    ?: true
            val reason = args["reason"]?.jsonPrimitive?.content ?: "user_requested_pause"
            if (onTogglePauseState != null) {
                onTogglePauseState(paused, reason)
            } else {
                onPauseSession(reason)
            }
            LocalToolResult(
                ok = true,
                message =
                    if (paused) {
                        "Session paused ($reason). User can tap the glasses temple touchpad to resume."
                    } else {
                        "Session resumed ($reason)."
                    },
                payload = mapOf("paused" to paused.toString()),
            )
        }

        register(
            LocalDeviceToolSpec(
                name = "end_session",
                description =
                    "Gracefully closes the glasses session after speaking a brief farewell when " +
                        "the user says 'end session', 'quit', 'stop', or 'exit app'.",
                params =
                    listOf(
                        LocalToolParam(
                            name = "reason",
                            type = "string",
                            description = "Reason for ending the session",
                            required = false,
                        ),
                    ),
                deduplicatePerTurn = true,
            ),
        ) { args ->
            val reason = args["reason"]?.jsonPrimitive?.content ?: "user_requested_exit"
            onEndSession(reason)
            LocalToolResult(
                ok = true,
                message = "Ending session after farewell audio finishes ($reason).",
            )
        }
    }
}

/**
 * Repository providing reactive form-factor detection, Android XR ProjectedContext connectivity,
 * and cross-device system instructions for the AppFunctions Testing Agent.
 */
interface DeviceCapabilityRepository {
    /** Reactive stream of whether an Android XR Projected device (AI Glasses) is connected. */
    val isGlassesConnected: StateFlow<Boolean>

    /** Reactive stream of developer-configured form factor override (`null` for auto-detect). */
    val formFactorOverride: StateFlow<FormFactor?>

    /** Reactive stream of developer-configured glasses connectivity override (`null` for auto). */
    val glassesConnectedOverride: StateFlow<Boolean?>

    /** Observes the full [DeviceCapabilities] of the host and any connected projected eyewear. */
    fun observeCapabilities(isProjectedSurface: Boolean = false): Flow<DeviceCapabilities>

    /** Detects the current [FormFactor] for the given surface. */
    fun detectFormFactor(isProjectedSurface: Boolean = false): FormFactor

    /** Resolves the hardware [Context] for audio/camera capture (`ProjectedContext` when on glasses). */
    fun resolveHardwareContext(
        activityContext: Context,
        isProjectedSurface: Boolean,
    ): Context

    /** Resolves the host phone [Context] for reading shared credentials/DataStore from a projected activity. */
    fun resolveHostContext(activityContext: Context): Context

    /** Projects `GlassesAgentActivity` onto the connected Android XR glasses display. */
    fun launchGlassesAgent(
        activityContext: Context,
        threadId: String? = null,
        targetPackageName: String? = null,
    )

    /** Allows overriding glasses connection state for local testing/debugging. */
    fun setGlassesConnectedOverride(connected: Boolean?)

    /** Allows overriding detected [FormFactor] for local testing/debugging across devices. */
    fun setFormFactorOverride(formFactor: FormFactor?)

    /** Builds a form-factor-aware system instruction for Gemini Chat or Gemini Live. */
    fun buildSystemInstruction(
        formFactor: FormFactor,
        mode: AgentInteractionMode,
        activeTaskContext: String = "Testing and executing Android AppFunctions across connected apps",
    ): String
}
