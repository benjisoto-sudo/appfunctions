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

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Unit tests for [LiveProtocolMessages] serialization and deserialization. */
class LiveProtocolMessagesTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Test
    fun `serialize setup client message generates expected JSON structure`() {
        val setupMsg =
            LiveSetupClientMessage(
                setup =
                    LiveSetupPayload(
                        model = "models/gemini-2.0-flash-exp",
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
                    ),
            )

        val serialized = json.encodeToString(setupMsg)
        assertNotNull(serialized)
        assert(serialized.contains("models/gemini-2.0-flash-exp"))
        assert(serialized.contains("Aoede"))
        assert(serialized.contains("AUDIO"))
    }

    @Test
    fun `serialize realtime input message encodes media chunk correctly`() {
        val inputMsg =
            LiveRealtimeInputMessage(
                realtimeInput =
                    LiveRealtimeInputPayload(
                        mediaChunks =
                            listOf(
                                LiveMediaChunk(
                                    mimeType = "audio/pcm;rate=16000",
                                    data = "AQIDBA==",
                                ),
                            ),
                    ),
            )

        val serialized = json.encodeToString(inputMsg)
        assertNotNull(serialized)
        assert(serialized.contains("audio/pcm;rate=16000"))
        assert(serialized.contains("AQIDBA=="))
    }

    @Test
    fun `deserialize server tool call frame parses function call`() {
        val jsonString =
            """
            {
              "toolCall": {
                "functionCalls": [
                  {
                    "id": "call-123",
                    "name": "example_chatapp_send",
                    "args": {
                      "messageBody": "Hello"
                    }
                  }
                ]
              }
            }
            """.trimIndent()

        val serverMessage = json.decodeFromString<LiveServerMessage>(jsonString)
        assertNotNull(serverMessage.toolCall)
        assertEquals(1, serverMessage.toolCall?.functionCalls?.size)
        val functionCall = serverMessage.toolCall?.functionCalls?.first()
        assertEquals("call-123", functionCall?.id)
        assertEquals("example_chatapp_send", functionCall?.name)
    }

    @Test
    fun `serialize tool response frame wraps JSON result`() {
        val responsePayload =
            buildJsonObject {
                put("result", "Message sent to Alice")
            }
        val responseMsg =
            LiveToolResponseMessage(
                toolResponse =
                    LiveToolResponsePayload(
                        functionResponses =
                            listOf(
                                LiveFunctionResponse(
                                    name = "example_chatapp_send",
                                    id = "call-123",
                                    response = responsePayload,
                                ),
                            ),
                    ),
            )

        val serialized = json.encodeToString(responseMsg)
        assert(serialized.contains("Message sent to Alice"))
        assert(serialized.contains("call-123"))
    }
}
