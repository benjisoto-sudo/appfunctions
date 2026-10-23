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

import androidx.appfunctions.metadata.AppFunctionMetadata
import com.example.appfunctions.agent.data.live.LiveFunctionResponse
import com.example.appfunctions.agent.data.live.LiveServerMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/**
 * Interface for establishing and communicating over a bidirectional live voice and multimodal streaming session.
 */
interface LiveLlmProvider {
    /**
     * Opens a live streaming session, dispatches initial setup with tool schemas,
     * and returns a flow of down-stream server messages.
     *
     * @param apiKey The Gemini API key.
     * @param modelName The model identifier (e.g., "models/gemini-3.1-flash-live-preview").
     * @param tools Discovered AppFunction metadata to expose as live voice tool capabilities.
     * @param systemInstruction Optional instructions for assistant voice persona and behavior.
     * @param localToolDeclarations Optional device-local tool declarations (e.g., XR glasses hardware tools).
     * @return A hot [Flow] emitting incoming [LiveServerMessage] frames (audio, transcripts, tool calls).
     */
    suspend fun startSession(
        apiKey: String,
        modelName: String,
        tools: List<AppFunctionMetadata>,
        systemInstruction: String? = null,
        localToolDeclarations: List<JsonObject> = emptyList(),
    ): Flow<LiveServerMessage>

    /**
     * Streams raw PCM16 microphone audio chunks (16 kHz mono Base64) upstream to Gemini.
     *
     * @param pcmBase64 Base64-encoded raw 16-bit PCM audio bytes.
     */
    suspend fun sendAudioChunk(pcmBase64: String)

    /**
     * Streams a single JPEG image frame (e.g., from XR glasses camera upon `snap_glasses_frame`) upstream to Gemini.
     *
     * @param jpegBase64 Base64-encoded JPEG image bytes.
     */
    suspend fun sendVideoFrame(jpegBase64: String) {}

    /**
     * Sends a direct user text turn over the bidirectional stream (essential for testing on emulators without mic input).
     *
     * @param prompt User prompt string to transmit as a live turn.
     */
    suspend fun sendTextTurn(prompt: String)

    /**
     * Sends back the JSON result of an executed AppFunction or device-local tool call.
     *
     * @param functionId The execution ID assigned by the server in the toolCall frame.
     * @param functionName The mapped tool name.
     * @param resultJson Raw JSON result string returned by tool execution.
     */
    suspend fun sendToolResponse(
        functionId: String,
        functionName: String,
        resultJson: String,
    )

    /**
     * Sends back a batch of executed AppFunction or device-local tool call responses in a single frame.
     *
     * @param responses List of [LiveFunctionResponse] items to send upstream.
     */
    suspend fun sendToolResponses(responses: List<LiveFunctionResponse>) {
        responses.forEach { response ->
            sendToolResponse(
                functionId = response.id,
                functionName = response.name,
                resultJson = response.response.toString(),
            )
        }
    }

    /**
     * Closes the live session WebSocket connection.
     */
    suspend fun stopSession()
}
