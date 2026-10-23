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
package com.example.appfunctions.agent.domain.live

import android.content.Context
import android.util.Log
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.example.appfunctions.agent.data.GeminiToolConverter
import com.example.appfunctions.agent.data.audio.AudioInputCapturer
import com.example.appfunctions.agent.data.audio.AudioOutputPlayer
import com.example.appfunctions.agent.data.live.LiveFunctionResponse
import com.example.appfunctions.agent.domain.appfunction.AppFunctionExceptionFormatter
import com.example.appfunctions.agent.domain.appfunction.ConvertAppFunctionDataToJsonUseCase
import com.example.appfunctions.agent.domain.appfunction.ConvertInputToAppFunctionDataUseCase
import com.example.appfunctions.agent.domain.appfunction.ExecuteAppFunctionResult
import com.example.appfunctions.agent.domain.appfunction.ExecuteAppFunctionUseCase
import com.example.appfunctions.agent.domain.device.DeviceLocalToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates bidirectional real-time audio/multimodal streaming and voice-triggered AppFunction
 * and device-local tool execution across Android IPC boundaries and form factors (phone, XR glasses, etc.).
 */
@Singleton
class LiveVoiceOrchestrator
    @Inject
    constructor(
        private val liveLlmProvider: LiveLlmProvider,
        private val audioInputCapturer: AudioInputCapturer,
        private val audioOutputPlayer: AudioOutputPlayer,
        private val executeAppFunctionUseCase: ExecuteAppFunctionUseCase,
        private val convertInputToAppFunctionDataUseCase: ConvertInputToAppFunctionDataUseCase,
        private val convertAppFunctionDataToJsonUseCase: ConvertAppFunctionDataToJsonUseCase,
        private val toolConverter: GeminiToolConverter,
    ) {
        private val orchestratorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _sessionState = MutableStateFlow<LiveSessionState>(LiveSessionState.Idle)
        val sessionState: StateFlow<LiveSessionState> = _sessionState.asStateFlow()

        private val _transcriptLog = MutableStateFlow<List<String>>(emptyList())
        val transcriptLog: StateFlow<List<String>> = _transcriptLog.asStateFlow()

        private val _audioLevel = MutableStateFlow(0f)
        val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

        private val _hudCardState = MutableStateFlow<Pair<String, String>?>(null)
        val hudCardState: StateFlow<Pair<String, String>?> = _hudCardState.asStateFlow()

        private var sessionJob: Job? = null
        private var captureJob: Job? = null

        @Volatile
        private var pinnedHudUntilMs: Long = 0L

        /**
         * Returns true if the audio output player is actively playing or within the 1200ms buffer tail.
         */
        fun isAudioPlaying(): Boolean = audioOutputPlayer.isPlaying()

        /**
         * Sets the microphone mute state on the underlying audio capturer.
         */
        fun setMuted(muted: Boolean) {
            audioInputCapturer.isMuted = muted
            if (muted) {
                _audioLevel.value = 0f
                audioOutputPlayer.flush()
            }
        }

        /**
         * Opens a voice session, streaming audio and executing AppFunction and device-local tool requests.
         *
         * @param apiKey Gemini API key.
         * @param modelName Live model identifier (defaults to `models/gemini-3.1-flash-live-preview`).
         * @param tools Discovered [AppFunctionMetadata] tools from connected apps (e.g., ChatApp).
         * @param systemInstruction Form-factor-specific system instruction.
         * @param hardwareContext Optional hardware [Context] (e.g., `ProjectedContext` on XR glasses).
         * @param localToolRegistry Optional [DeviceLocalToolRegistry] for device-local hardware tools.
         * @param onSetupComplete Optional callback invoked when the server confirms session setup.
         */
        fun startVoiceSession(
            apiKey: String,
            modelName: String,
            tools: List<AppFunctionMetadata>,
            systemInstruction: String? = null,
            hardwareContext: Context? = null,
            localToolRegistry: DeviceLocalToolRegistry? = null,
            onSetupComplete: (suspend () -> Unit)? = null,
        ) {
            stopVoiceSession(playStopEarcon = false)
            _sessionState.value = LiveSessionState.Connecting
            _transcriptLog.value = emptyList()
            _hudCardState.value = null
            pinnedHudUntilMs = 0L
            audioInputCapturer.isMuted = false

            sessionJob =
                orchestratorScope.launch {
                    try {
                        val serverFlow =
                            liveLlmProvider.startSession(
                                apiKey = apiKey,
                                modelName = modelName,
                                tools = tools,
                                systemInstruction =
                                    systemInstruction
                                        ?: DEFAULT_LIVE_SYSTEM_INSTRUCTION,
                                localToolDeclarations =
                                    localToolRegistry?.toGeminiFunctionDeclarations() ?: emptyList(),
                            )

                        _sessionState.value = LiveSessionState.Listening
                        audioOutputPlayer.start(hardwareContext)
                        audioOutputPlayer.playSessionStartEarcon()
                        audioOutputPlayer.onAudioLevel = { level -> _audioLevel.value = level }

                        // Launch upstream audio stream job
                        captureJob =
                            launch {
                                audioInputCapturer.startCapturing(
                                    hardwareContext = hardwareContext,
                                    onAudioLevel = { level -> _audioLevel.value = level },
                                ).collect { base64Chunk ->
                                    liveLlmProvider.sendAudioChunk(base64Chunk)
                                }
                            }

                        val userTurnBuffer = StringBuilder()
                        val geminiTurnBuffer = StringBuilder()
                        var receivedAudioInTurn = false

                        // Collect down-stream frames
                        serverFlow.collect { serverMsg ->
                            if (serverMsg.setupComplete != null) {
                                if (onSetupComplete != null) {
                                    launch {
                                        try {
                                            onSetupComplete.invoke()
                                        } catch (e: Exception) {
                                            Log.w(TAG, "onSetupComplete callback failed: ${e.message}")
                                        }
                                    }
                                }
                            }

                            val serverContent = serverMsg.serverContent

                            // 1. Handle Barge-In Interruption (<15ms flush)
                            if (serverContent?.interrupted == true) {
                                audioOutputPlayer.flush()
                                localToolRegistry?.onTurnBoundary()
                                flushTurnBuffers(userTurnBuffer, geminiTurnBuffer, receivedAudioInTurn)
                                receivedAudioInTurn = false
                                _sessionState.value = LiveSessionState.Listening
                            }

                            // 2. Accumulate input/output audio transcriptions
                            serverContent?.inputTranscription?.text?.let { chunk ->
                                if (chunk.isNotBlank()) {
                                    userTurnBuffer.append(chunk)
                                }
                            }
                            serverContent?.outputTranscription?.text?.let { chunk ->
                                if (chunk.isNotBlank()) {
                                    geminiTurnBuffer.append(chunk)
                                    if (System.currentTimeMillis() >= pinnedHudUntilMs) {
                                        _hudCardState.value =
                                            "Gemini" to geminiTurnBuffer.toString().trim().takeLast(90)
                                    }
                                }
                            }

                            // 3. Process model turn parts (PCM audio or text transcripts)
                            serverContent?.modelTurn?.parts?.forEach { part ->
                                part.inlineData?.let { mediaChunk ->
                                    _sessionState.value = LiveSessionState.Speaking
                                    receivedAudioInTurn = true
                                    audioOutputPlayer.playChunk(mediaChunk.data)
                                }
                                part.text?.let { transcript ->
                                    if (transcript.isNotBlank()) {
                                        geminiTurnBuffer.append(transcript)
                                        if (System.currentTimeMillis() >= pinnedHudUntilMs) {
                                            _hudCardState.value =
                                                "Gemini" to geminiTurnBuffer.toString().trim().takeLast(90)
                                        }
                                    }
                                }
                            }

                            // 4. Handle Turn Completion
                            if (serverContent?.turnComplete == true) {
                                flushTurnBuffers(userTurnBuffer, geminiTurnBuffer, receivedAudioInTurn)
                                receivedAudioInTurn = false
                                localToolRegistry?.onTurnBoundary()
                                _sessionState.value = LiveSessionState.Listening
                            }

                            // 5. Process Tool Calls (both DeviceLocalToolRegistry and System AppFunctions)
                            val functionCalls = serverMsg.toolCall?.functionCalls.orEmpty()
                            if (functionCalls.isNotEmpty()) {
                                flushTurnBuffers(userTurnBuffer, geminiTurnBuffer, receivedAudioInTurn)
                                receivedAudioInTurn = false
                                audioOutputPlayer.flush()

                                val responses = mutableListOf<LiveFunctionResponse>()
                                val rawJsonByCallId = mutableMapOf<String, String>()

                                for (call in functionCalls) {
                                    _sessionState.value = LiveSessionState.ExecutingTool(call.name)
                                    Log.d(TAG, "Intercepted Live tool call: ${call.name} (id=${call.id})")

                                    val resultJson =
                                        if (localToolRegistry != null && localToolRegistry.hasTool(call.name)) {
                                            localToolRegistry.execute(call.name, call.args).toJsonString()
                                        } else {
                                            audioOutputPlayer.playToolExecutionEarcon()
                                            val shortName = call.name.substringAfterLast('_')
                                            val compactArgs = (call.args?.toString() ?: "{}").take(60)
                                            _hudCardState.value =
                                                "Tool: $shortName" to "Args: $compactArgs\nRunning @AppFunction..."
                                            pinnedHudUntilMs = System.currentTimeMillis() + 6000L
                                            val targetTool =
                                                tools.find {
                                                    val convertedName = toolConverter.getToolName(it)
                                                    convertedName == call.name ||
                                                        convertedName.endsWith(call.name) ||
                                                        it.id == call.name
                                                }

                                            if (targetTool != null) {
                                                val output = executeToolCall(targetTool, call.args)
                                                val compactOutput = output.replace("\n", " ").take(80)
                                                _hudCardState.value =
                                                    "Tool: $shortName" to "Args: $compactArgs\nResult: $compactOutput"
                                                pinnedHudUntilMs = System.currentTimeMillis() + 6000L
                                                output
                                            } else {
                                                val errJson =
                                                    buildJsonObject {
                                                        put(
                                                            "error",
                                                            JsonPrimitive(
                                                                "Function ${call.name} not found in available AppFunctions",
                                                            ),
                                                        )
                                                    }.toString()
                                                _hudCardState.value =
                                                    "Error: $shortName" to "Not found in available AppFunctions"
                                                pinnedHudUntilMs = System.currentTimeMillis() + 6000L
                                                errJson
                                            }
                                        }

                                    _transcriptLog.value =
                                        _transcriptLog.value +
                                        ("Executed ${call.name} -> $resultJson")

                                    rawJsonByCallId[call.id] = resultJson
                                    responses.add(
                                        LiveFunctionResponse(
                                            name = call.name,
                                            id = call.id,
                                            response = parseToolResultToJsonObject(resultJson),
                                        ),
                                    )
                                }

                                if (responses.size == 1) {
                                    val single = responses.first()
                                    liveLlmProvider.sendToolResponse(
                                        functionId = single.id,
                                        functionName = single.name,
                                        resultJson = rawJsonByCallId[single.id] ?: single.response.toString(),
                                    )
                                } else if (responses.size > 1) {
                                    liveLlmProvider.sendToolResponses(responses)
                                }
                                _sessionState.value = LiveSessionState.Listening
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val errMsg = e.message ?: "Unknown error (${e.javaClass.simpleName})"
                        Log.e(TAG, "Error in Live Voice session: $errMsg", e)
                        _sessionState.value = LiveSessionState.Error(errMsg)
                        _transcriptLog.value = _transcriptLog.value + ("System Error: $errMsg")
                    } finally {
                        captureJob?.cancel()
                        captureJob = null
                        audioInputCapturer.stopCapturing()
                    }
                }
        }

        private fun parseToolResultToJsonObject(resultJson: String): JsonObject {
            return try {
                when (val parsed = Json.parseToJsonElement(resultJson)) {
                    is JsonObject -> parsed
                    else ->
                        buildJsonObject {
                            put("result", parsed)
                        }
                }
            } catch (e: Exception) {
                buildJsonObject {
                    put("result", JsonPrimitive(resultJson))
                }
            }
        }

        private fun flushTurnBuffers(
            userTurnBuffer: StringBuilder,
            geminiTurnBuffer: StringBuilder,
            receivedAudioInTurn: Boolean,
        ) {
            val userText = userTurnBuffer.toString().trim()
            if (userText.isNotEmpty()) {
                _transcriptLog.value = _transcriptLog.value + ("You: $userText")
                userTurnBuffer.clear()
            }
            val geminiText = geminiTurnBuffer.toString().trim()
            if (geminiText.isNotEmpty()) {
                _transcriptLog.value = _transcriptLog.value + ("Gemini: $geminiText")
                if (System.currentTimeMillis() >= pinnedHudUntilMs) {
                    _hudCardState.value = "Gemini" to geminiText.take(90)
                }
                geminiTurnBuffer.clear()
            } else if (receivedAudioInTurn) {
                _transcriptLog.value = _transcriptLog.value + ("Gemini: [Voice response]")
            }
        }

        private suspend fun executeToolCall(
            metadata: AppFunctionMetadata,
            args: JsonObject?,
        ): String {
            return try {
                @Suppress("UNCHECKED_CAST")
                val paramsMap: Map<String, Any> =
                    if (args != null) {
                        args
                            .mapValues { (_, element) -> element.toPrimitive() }
                            .filterValues { it != null } as Map<String, Any>
                    } else {
                        emptyMap()
                    }

                val appFunctionDataResult =
                    convertInputToAppFunctionDataUseCase(
                        parameters = metadata.parameters,
                        components = metadata.components,
                        inputs = paramsMap,
                    )

                if (appFunctionDataResult.isFailure) {
                    return buildJsonObject {
                        put(
                            "error",
                            JsonPrimitive(
                                "Failed to convert arguments: ${appFunctionDataResult.exceptionOrNull()?.message}",
                            ),
                        )
                    }.toString()
                }

                when (
                    val result =
                        executeAppFunctionUseCase(
                            function = metadata,
                            parameters = appFunctionDataResult.getOrThrow(),
                            threadId = "live-session",
                        )
                ) {
                    is ExecuteAppFunctionResult.Data -> {
                        result.formattedJson
                    }
                    is ExecuteAppFunctionResult.PendingIntentAction -> {
                        try {
                            result.pendingIntent.send()
                        } catch (e: Exception) {
                            Log.w(TAG, "PendingIntent dispatch warning: ${e.message}")
                        }
                        buildJsonObject {
                            put("result", JsonPrimitive("Action dispatched successfully"))
                        }.toString()
                    }
                    is ExecuteAppFunctionResult.Error -> {
                        val exception = result.exception
                        if (exception is CancellationException) {
                            throw exception
                        }
                        val appFunctionException =
                            AppFunctionExceptionFormatter.getAppFunctionException(exception)
                        if (appFunctionException != null) {
                            val formattedError =
                                AppFunctionExceptionFormatter.format(
                                    appFunctionException,
                                    metadata.id,
                                )
                            buildJsonObject {
                                put("error", JsonPrimitive(formattedError))
                            }.toString()
                        } else {
                            buildJsonObject {
                                put("error", JsonPrimitive("Execution error: ${exception.message}"))
                            }.toString()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution failed for ${metadata.id}: ${e.message}", e)
                buildJsonObject {
                    put("error", JsonPrimitive("Failed to invoke function: ${e.message}"))
                }.toString()
            }
        }

        internal fun JsonElement.toPrimitive(): Any? {
            return when (this) {
                is JsonNull -> null
                is JsonPrimitive -> {
                    if (this.isString) {
                        this.content
                    } else {
                        this.content.toBooleanStrictOrNull()
                            ?: this.content.toLongOrNull()
                            ?: this.content.toDoubleOrNull()
                            ?: this.content
                    }
                }
                is JsonObject ->
                    this.mapValues { it.value.toPrimitive() }
                        .filterValues { it != null }
                is JsonArray -> this.mapNotNull { it.toPrimitive() }
            }
        }

        /**
         * Streams a single JPEG snapshot frame upstream to Gemini Live (used by `snap_glasses_frame`).
         */
        suspend fun sendVideoFrame(jpegBase64: String) {
            liveLlmProvider.sendVideoFrame(jpegBase64)
        }

        /**
         * Updates the active HUD card state (used by `update_hud_card` on XR Display Glasses).
         */
        fun updateHudCard(
            title: String,
            subtitle: String,
        ) {
            pinnedHudUntilMs = System.currentTimeMillis() + 6000L
            _hudCardState.value = title to subtitle
        }

        /**
         * Sends a text prompt turn directly over the live session and displays it in the transcript.
         */
        fun sendTextPrompt(prompt: String) {
            _transcriptLog.value = _transcriptLog.value + ("You: $prompt")
            orchestratorScope.launch {
                liveLlmProvider.sendTextTurn(prompt)
            }
        }

        /**
         * Closes current live session and stops all audio capturing and playback.
         */
        fun stopVoiceSession(playStopEarcon: Boolean = true) {
            captureJob?.cancel()
            captureJob = null
            sessionJob?.cancel()
            sessionJob = null
            audioInputCapturer.stopCapturing()
            audioOutputPlayer.stop()
            if (playStopEarcon) {
                orchestratorScope.launch {
                    audioOutputPlayer.playSessionStopEarcon()
                    liveLlmProvider.stopSession()
                }
            }
            _audioLevel.value = 0f
            _hudCardState.value = null
            pinnedHudUntilMs = 0L
            _sessionState.value = LiveSessionState.Idle
        }

        companion object {
            private const val TAG = "LiveVoiceOrchestrator"
            private const val DEFAULT_LIVE_SYSTEM_INSTRUCTION =
                "You are an on-device Android assistant running in Gemini Live mode. " +
                    "Speak concisely and naturally. When the user asks to interact with an app " +
                    "(like ChatApp to search contacts, send messages, or make a call), " +
                    "use your available AppFunction tools to fulfill their request immediately."
        }
    }
