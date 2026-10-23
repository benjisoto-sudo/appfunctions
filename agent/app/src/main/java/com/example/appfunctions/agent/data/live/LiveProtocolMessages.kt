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

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Client setup frame dispatched on initial WebSocket connection to configure model, tools, and voice.
 */
@Serializable
data class LiveSetupClientMessage(
    @SerialName("setup")
    val setup: LiveSetupPayload,
)

/**
 * Payload specifying the model name, voice configuration, and available AppFunction tool declarations.
 */
@Serializable
data class LiveSetupPayload(
    @SerialName("model")
    val model: String = "models/gemini-3.1-flash-live-preview",
    @SerialName("generationConfig")
    val generationConfig: LiveGenerationConfig? = null,
    @SerialName("tools")
    val tools: List<LiveToolDeclaration>? = null,
    @SerialName("systemInstruction")
    val systemInstruction: LiveSystemInstruction? = null,
    @SerialName("inputAudioTranscription")
    val inputAudioTranscription: LiveAudioTranscriptionConfig? = LiveAudioTranscriptionConfig(),
    @SerialName("outputAudioTranscription")
    val outputAudioTranscription: LiveAudioTranscriptionConfig? = LiveAudioTranscriptionConfig(),
)

/**
 * Configuration enabling server-side real-time transcription of audio streams.
 */
@Serializable
class LiveAudioTranscriptionConfig

/**
 * System instructions passed to the Live session.
 */
@Serializable
data class LiveSystemInstruction(
    @SerialName("parts")
    val parts: List<LiveTextPart>,
)

/**
 * Represents text parts in prompt or model turns.
 */
@Serializable
data class LiveTextPart(
    @SerialName("text")
    val text: String,
)

/**
 * Configuration for voice output parameters during live sessions.
 */
@Serializable
data class LiveGenerationConfig(
    @SerialName("responseModalities")
    val responseModalities: List<String> = listOf("AUDIO"),
    @SerialName("speechConfig")
    val speechConfig: LiveSpeechConfig? = null,
)

/**
 * Speech configuration containing voice selection.
 */
@Serializable
data class LiveSpeechConfig(
    @SerialName("voiceConfig")
    val voiceConfig: LiveVoiceConfig? = null,
)

/**
 * Prebuilt voice configuration selecting the Gemini voice profile.
 */
@Serializable
data class LiveVoiceConfig(
    @SerialName("prebuiltVoiceConfig")
    val prebuiltVoiceConfig: LivePrebuiltVoiceConfig? = null,
)

/**
 * Specific prebuilt voice name (e.g., "Aoede", "Puck", "Charon", "Kore", "Fenrir").
 */
@Serializable
data class LivePrebuiltVoiceConfig(
    @SerialName("voiceName")
    val voiceName: String = "Aoede",
)

/**
 * Tool declaration wrapping a list of converted AppFunction and local device tool schemas.
 */
@Serializable
data class LiveToolDeclaration(
    @SerialName("functionDeclarations")
    val functionDeclarations: List<JsonObject>,
)

/**
 * Client frame streaming real-time PCM audio or JPEG video chunks.
 */
@Serializable
data class LiveRealtimeInputMessage(
    @SerialName("realtimeInput")
    val realtimeInput: LiveRealtimeInputPayload,
)

/**
 * Payload containing media chunks sent from client to server.
 */
@Serializable
data class LiveRealtimeInputPayload(
    @SerialName("mediaChunks")
    val mediaChunks: List<LiveMediaChunk>,
)

/**
 * Base64-encoded media chunk (e.g., 16kHz PCM audio or JPEG snapshot frame).
 */
@Serializable
data class LiveMediaChunk(
    @SerialName("mimeType")
    val mimeType: String = "audio/pcm;rate=16000",
    @SerialName("data")
    val data: String,
)

/**
 * Client frame sending explicit text turns over bidirectional connection.
 */
@Serializable
data class LiveClientContentMessage(
    @SerialName("clientContent")
    val clientContent: LiveClientContent,
)

@Serializable
data class LiveClientContent(
    @SerialName("turns")
    val turns: List<LiveContentTurn>,
    @SerialName("turnComplete")
    val turnComplete: Boolean = true,
)

@Serializable
data class LiveContentTurn(
    @SerialName("role")
    val role: String = "user",
    @SerialName("parts")
    val parts: List<LiveContentPart>,
)

@Serializable
data class LiveContentPart(
    @SerialName("text")
    val text: String,
)

/**
 * Client frame sending function execution outputs back to Gemini Live.
 */
@Serializable
data class LiveToolResponseMessage(
    @SerialName("toolResponse")
    val toolResponse: LiveToolResponsePayload,
)

/**
 * Payload containing the list of function execution outputs.
 */
@Serializable
data class LiveToolResponsePayload(
    @SerialName("functionResponses")
    val functionResponses: List<LiveFunctionResponse>,
)

/**
 * Individual function execution result matched by execution call id.
 */
@Serializable
data class LiveFunctionResponse(
    @SerialName("name")
    val name: String,
    @SerialName("id")
    val id: String,
    @SerialName("response")
    val response: JsonObject,
)

/**
 * Top-level response container received from Gemini BidiGenerateContent WebSocket.
 */
@Serializable
data class LiveServerMessage(
    @SerialName("setupComplete")
    val setupComplete: JsonObject? = null,
    @SerialName("serverContent")
    val serverContent: LiveServerContent? = null,
    @SerialName("toolCall")
    val toolCall: LiveServerToolCall? = null,
)

/**
 * Server content turn containing spoken audio output or text transcripts.
 */
@Serializable
data class LiveServerContent(
    @SerialName("modelTurn")
    val modelTurn: LiveServerModelTurn? = null,
    @SerialName("turnComplete")
    val turnComplete: Boolean? = null,
    @SerialName("interrupted")
    val interrupted: Boolean? = null,
    @SerialName("inputTranscription")
    val inputTranscription: LiveTranscription? = null,
    @SerialName("outputTranscription")
    val outputTranscription: LiveTranscription? = null,
)

/**
 * Real-time transcription payload for input or output audio streams.
 */
@Serializable
data class LiveTranscription(
    @SerialName("text")
    val text: String? = null,
)

/**
 * Model turn containing parts emitted by Gemini.
 */
@Serializable
data class LiveServerModelTurn(
    @SerialName("parts")
    val parts: List<LiveServerPart> = emptyList(),
)

/**
 * Part containing either inline Base64 audio, text, or a function call.
 */
@Serializable
data class LiveServerPart(
    @SerialName("inlineData")
    val inlineData: LiveMediaChunk? = null,
    @SerialName("text")
    val text: String? = null,
    @SerialName("functionCall")
    val functionCall: LiveFunctionCall? = null,
)

/**
 * Top-level tool call frame sent by server requesting AppFunction execution.
 */
@Serializable
data class LiveServerToolCall(
    @SerialName("functionCalls")
    val functionCalls: List<LiveFunctionCall> = emptyList(),
)

/**
 * Function call request emitted by Gemini during live speech.
 */
@Serializable
data class LiveFunctionCall(
    @SerialName("id")
    val id: String,
    @SerialName("name")
    val name: String,
    @SerialName("args")
    val args: JsonObject? = null,
)
