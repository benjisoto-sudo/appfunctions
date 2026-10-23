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
package com.example.appfunctions.agent.data.live

import android.util.Log
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.example.appfunctions.agent.data.GeminiToolConverter
import com.example.appfunctions.agent.domain.live.LiveLlmProvider
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementation of [LiveLlmProvider] using Ktor WebSockets to communicate with
 * Google Gemini's Multimodal Live API (BidiGenerateContent).
 */
@Singleton
class GeminiLiveProviderImpl
    @Inject
    constructor(
        private val httpClient: HttpClient,
        private val toolConverter: GeminiToolConverter,
    ) : LiveLlmProvider {
        private var activeSession: WebSocketSession? = null

        @Volatile
        private var isSetupComplete: Boolean = false

        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        override suspend fun startSession(
            apiKey: String,
            modelName: String,
            tools: List<AppFunctionMetadata>,
            systemInstruction: String?,
            localToolDeclarations: List<JsonObject>,
        ): Flow<LiveServerMessage> =
            flow {
                stopSession()
                isSetupComplete = false

                val isBearerToken = apiKey.startsWith("AQ.") || apiKey.startsWith("ya29.")
                val endpointUrl =
                    if (isBearerToken) {
                        LIVE_API_ENDPOINT
                    } else {
                        "$LIVE_API_ENDPOINT?key=$apiKey"
                    }
                Log.d(TAG, "Connecting to Gemini Live API WebSocket...")

                val session =
                    httpClient.webSocketSession {
                        url(endpointUrl)
                        header("x-goog-api-client", "genai-android/live")
                        if (isBearerToken) {
                            header("Authorization", "Bearer $apiKey")
                        } else {
                            header("x-goog-api-key", apiKey)
                        }
                    }
                activeSession = session

                // Deduplicate and convert AppFunction tools to Gemini schemas (including zero-parameter tools)
                val convertedTools =
                    tools
                        .distinctBy { toolConverter.getToolName(it) }
                        .mapNotNull { tool ->
                            try {
                                val rawJson = toolConverter.convert(tool)
                                sanitizeForLiveWebSocket(rawJson)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to convert tool ${tool.id}: ${e.message}", e)
                                null
                            }
                        }

                val allDeclarations = convertedTools + localToolDeclarations
                val toolDeclarations =
                    if (allDeclarations.isNotEmpty()) {
                        listOf(LiveToolDeclaration(functionDeclarations = allDeclarations))
                    } else {
                        null
                    }

                val sysInstruction =
                    systemInstruction?.let {
                        LiveSystemInstruction(parts = listOf(LiveTextPart(it)))
                    }

                val normalizedModelName =
                    if (modelName.startsWith("models/")) {
                        modelName
                    } else {
                        "models/$modelName"
                    }

                val setupPayload =
                    LiveSetupPayload(
                        model = normalizedModelName,
                        generationConfig =
                            LiveGenerationConfig(
                                responseModalities = listOf("AUDIO"),
                                speechConfig =
                                    LiveSpeechConfig(
                                        voiceConfig =
                                            LiveVoiceConfig(
                                                prebuiltVoiceConfig = LivePrebuiltVoiceConfig("Aoede"),
                                            ),
                                    ),
                            ),
                        tools = toolDeclarations,
                        systemInstruction = sysInstruction,
                    )

                val setupMessage = LiveSetupClientMessage(setup = setupPayload)
                val setupJson = json.encodeToString(setupMessage)
                Log.d(TAG, "Sending Live setup frame (${allDeclarations.size} tools)")
                session.send(Frame.Text(setupJson))

                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            try {
                                val serverMessage = json.decodeFromString<LiveServerMessage>(text)
                                if (
                                    serverMessage.setupComplete != null ||
                                    serverMessage.serverContent != null ||
                                    serverMessage.toolCall != null
                                ) {
                                    isSetupComplete = true
                                }
                                emit(serverMessage)
                            } catch (e: Exception) {
                                Log.e(TAG, "Error decoding server frame: ${e.message}", e)
                            }
                        }
                        is Frame.Close -> {
                            Log.d(TAG, "Gemini Live WebSocket closed by server")
                            isSetupComplete = false
                            break
                        }
                        else -> Unit
                    }
                }
            }

        override suspend fun sendAudioChunk(pcmBase64: String) {
            if (!isSetupComplete) return
            val session = activeSession ?: return
            val chunkMessage =
                LiveRealtimeInputMessage(
                    realtimeInput =
                        LiveRealtimeInputPayload(
                            mediaChunks =
                                listOf(
                                    LiveMediaChunk(
                                        mimeType = "audio/pcm;rate=16000",
                                        data = pcmBase64,
                                    ),
                                ),
                        ),
                )
            val chunkJson = json.encodeToString(chunkMessage)
            session.send(Frame.Text(chunkJson))
        }

        override suspend fun sendVideoFrame(jpegBase64: String) {
            val session = activeSession ?: return
            val frameMessage =
                LiveRealtimeInputMessage(
                    realtimeInput =
                        LiveRealtimeInputPayload(
                            mediaChunks =
                                listOf(
                                    LiveMediaChunk(
                                        mimeType = "image/jpeg",
                                        data = jpegBase64,
                                    ),
                                ),
                        ),
                )
            val frameJson = json.encodeToString(frameMessage)
            session.send(Frame.Text(frameJson))
        }

        override suspend fun sendTextTurn(prompt: String) {
            val session = activeSession ?: return
            val textMessage =
                LiveClientContentMessage(
                    clientContent =
                        LiveClientContent(
                            turns =
                                listOf(
                                    LiveContentTurn(
                                        role = "user",
                                        parts = listOf(LiveContentPart(text = prompt)),
                                    ),
                                ),
                            turnComplete = true,
                        ),
                )
            val textJson = json.encodeToString(textMessage)
            Log.d(TAG, "Sending text prompt turn to Gemini Live: $prompt")
            session.send(Frame.Text(textJson))
        }

        override suspend fun sendToolResponse(
            functionId: String,
            functionName: String,
            resultJson: String,
        ) {
            sendToolResponses(
                listOf(
                    LiveFunctionResponse(
                        name = functionName,
                        id = functionId,
                        response = parseResultJsonToObject(resultJson),
                    ),
                ),
            )
        }

        override suspend fun sendToolResponses(responses: List<LiveFunctionResponse>) {
            if (responses.isEmpty()) return
            val session = activeSession ?: return
            val toolResponseMsg =
                LiveToolResponseMessage(
                    toolResponse =
                        LiveToolResponsePayload(
                            functionResponses = responses,
                        ),
                )
            val responseString = json.encodeToString(toolResponseMsg)
            Log.d(TAG, "Sending ToolResponse upstream (${responses.size} responses): $responseString")
            session.send(Frame.Text(responseString))
        }

        override suspend fun stopSession() {
            isSetupComplete = false
            try {
                activeSession?.close()
            } catch (e: Exception) {
                Log.w(TAG, "Error closing active Live session: ${e.message}")
            } finally {
                activeSession = null
            }
        }

        internal fun setActiveSessionForTesting(
            session: WebSocketSession?,
            setupComplete: Boolean,
        ) {
            activeSession = session
            isSetupComplete = setupComplete
        }

        internal fun parseResultJsonToObject(resultJson: String): JsonObject {
            return try {
                when (val parsed = json.parseToJsonElement(resultJson)) {
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

        internal fun sanitizeForLiveWebSocket(raw: JsonObject): JsonObject {
            val rawName = raw["name"]?.jsonPrimitive?.content ?: "Tool"
            val cleanName =
                rawName
                    .replace(Regex("[^a-zA-Z0-9_]"), "_")
                    .replace(Regex("_+"), "_")
                    .takeLast(MAX_TOOL_NAME_LENGTH)
                    .trim('_')
            val desc = raw["description"] ?: JsonPrimitive("")

            val paramsObj = raw["parameters"]?.jsonObject
            val sanitizedParams =
                paramsObj?.let { sanitizeSchemaNode(it, isRootParameters = true) }

            return buildJsonObject {
                put("name", JsonPrimitive(if (cleanName.isEmpty()) "tool_func" else cleanName))
                put("description", desc)
                if (sanitizedParams != null) {
                    put("parameters", sanitizedParams)
                }
            }
        }

        private fun sanitizeSchemaNode(
            node: JsonObject,
            isRootParameters: Boolean = false,
        ): JsonObject? {
            val rawType = node["type"]?.jsonPrimitive?.content?.lowercase() ?: "string"
            val description = node["description"]?.jsonPrimitive?.content

            return when (rawType) {
                "object" -> {
                    val props = node["properties"]?.jsonObject
                    val sanitizedProps =
                        if (!props.isNullOrEmpty()) {
                            buildJsonObject {
                                props.forEach { (propName, propVal) ->
                                    val propObj = propVal as? JsonObject
                                    if (propObj != null) {
                                        sanitizeSchemaNode(propObj, isRootParameters = false)?.let {
                                            put(propName, it)
                                        }
                                    }
                                }
                            }
                        } else {
                            null
                        }

                    if (sanitizedProps.isNullOrEmpty()) {
                        if (isRootParameters) {
                            null
                        } else {
                            buildJsonObject {
                                put("type", JsonPrimitive("string"))
                                if (!description.isNullOrBlank()) {
                                    put("description", JsonPrimitive(description))
                                }
                            }
                        }
                    } else {
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            if (!description.isNullOrBlank()) {
                                put("description", JsonPrimitive(description))
                            }
                            put("properties", sanitizedProps)
                            val reqArray = node["required"] as? JsonArray
                            if (reqArray != null) {
                                val validRequired =
                                    reqArray.filter { reqElem ->
                                        val reqName = (reqElem as? JsonPrimitive)?.content
                                        reqName != null && sanitizedProps.containsKey(reqName)
                                    }
                                if (validRequired.isNotEmpty()) {
                                    put("required", JsonArray(validRequired))
                                }
                            }
                        }
                    }
                }
                "array" -> {
                    val itemsObj = node["items"] as? JsonObject
                    val sanitizedItems =
                        itemsObj?.let { sanitizeSchemaNode(it, isRootParameters = false) }
                            ?: buildJsonObject { put("type", JsonPrimitive("string")) }
                    buildJsonObject {
                        put("type", JsonPrimitive("array"))
                        if (!description.isNullOrBlank()) {
                            put("description", JsonPrimitive(description))
                        }
                        put("items", sanitizedItems)
                    }
                }
                else -> {
                    buildJsonObject {
                        put("type", JsonPrimitive(rawType))
                        if (!description.isNullOrBlank()) {
                            put("description", JsonPrimitive(description))
                        }
                        val enumValues = (node["enum"] ?: node["enums"]) as? JsonArray
                        if (!enumValues.isNullOrEmpty() && rawType == "string") {
                            put("enum", enumValues)
                        }
                    }
                }
            }
        }

        companion object {
            private const val TAG = "GeminiLiveProviderImpl"
            private const val MAX_TOOL_NAME_LENGTH = 64
            const val DEFAULT_LIVE_MODEL = "models/gemini-3.1-flash-live-preview"
            private const val LIVE_API_ENDPOINT =
                "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        }
    }
