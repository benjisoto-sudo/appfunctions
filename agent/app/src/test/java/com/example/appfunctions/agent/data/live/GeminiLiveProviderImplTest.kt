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
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Unit tests for [GeminiLiveProviderImpl]. */
class GeminiLiveProviderImplTest {
    private val mockEngine = MockEngine { _ -> respondOk() }
    private val httpClient = HttpClient(mockEngine)
    private val toolConverter = mockk<GeminiToolConverter>()
    private val provider = GeminiLiveProviderImpl(httpClient, toolConverter)

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
    }

    @Test
    fun `tool deduplication ensures distinct Gemini function names`() {
        val tool1 = mockk<AppFunctionMetadata>(relaxed = true)
        val tool2 = mockk<AppFunctionMetadata>(relaxed = true)

        every { toolConverter.getToolName(tool1) } returns "example_chatapp_send"
        every { toolConverter.getToolName(tool2) } returns "example_chatapp_send"
        every { toolConverter.convert(any()) } returns JsonObject(emptyMap())

        val list = listOf(tool1, tool2)
        val deduplicated = list.distinctBy { toolConverter.getToolName(it) }

        assertEquals(1, deduplicated.size)
    }

    @Test
    fun `sanitizeForLiveWebSocket preserves 64 char ChatApp tool names`() {
        val chatAppToolName = "com_example_chatapp_functions_ChatFunctions_searchContacts"
        val declaration =
            buildJsonObject {
                put("name", JsonPrimitive(chatAppToolName))
                put("description", JsonPrimitive("Searches contacts in ChatApp"))
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", JsonPrimitive("OBJECT"))
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "name",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("STRING"))
                                    },
                                )
                            },
                        )
                    },
                )
            }

        val sanitized = provider.sanitizeForLiveWebSocket(declaration)
        assertEquals(chatAppToolName, sanitized["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sanitizeForLiveWebSocket omits empty parameters object for zero-parameter tools`() {
        val declaration =
            buildJsonObject {
                put("name", JsonPrimitive("zero_param_tool"))
                put("description", JsonPrimitive("Tool with no parameters"))
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", JsonPrimitive("OBJECT"))
                        put("properties", buildJsonObject {})
                    },
                )
            }

        val sanitized = provider.sanitizeForLiveWebSocket(declaration)
        assertFalse(sanitized.containsKey("parameters"))
    }

    @Test
    fun `sanitizeForLiveWebSocket preserves nested object and array schemas recursively`() {
        val declaration =
            buildJsonObject {
                put("name", JsonPrimitive("send_complex_message"))
                put("description", JsonPrimitive("Sends message with recipient object and attachments"))
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "recipient",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("object"))
                                        put("description", JsonPrimitive("Recipient info"))
                                        put(
                                            "properties",
                                            buildJsonObject {
                                                put(
                                                    "id",
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("integer"))
                                                    },
                                                )
                                                put(
                                                    "handle",
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("string"))
                                                    },
                                                )
                                            },
                                        )
                                        put(
                                            "required",
                                            buildJsonArray { add(JsonPrimitive("id")) },
                                        )
                                    },
                                )
                                put(
                                    "tags",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("array"))
                                        put(
                                            "items",
                                            buildJsonObject {
                                                put("type", JsonPrimitive("string"))
                                            },
                                        )
                                    },
                                )
                                put(
                                    "degenerateObj",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("object"))
                                        put("description", JsonPrimitive("Empty nested object"))
                                    },
                                )
                            },
                        )
                        put(
                            "required",
                            buildJsonArray { add(JsonPrimitive("recipient")) },
                        )
                    },
                )
            }

        val sanitized = provider.sanitizeForLiveWebSocket(declaration)
        val params = sanitized["parameters"]?.jsonObject
        assertNotNull(params)
        val props = params!!["properties"]?.jsonObject!!

        val recipientObj = props["recipient"]?.jsonObject!!
        assertEquals("object", recipientObj["type"]?.jsonPrimitive?.content)
        assertEquals(
            "integer",
            recipientObj["properties"]
                ?.jsonObject
                ?.get("id")
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals(
            "id",
            recipientObj["required"]
                ?.jsonArray
                ?.first()
                ?.jsonPrimitive
                ?.content,
        )

        val tagsObj = props["tags"]?.jsonObject!!
        assertEquals("array", tagsObj["type"]?.jsonPrimitive?.content)
        assertEquals(
            "string",
            tagsObj["items"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.content,
        )

        // Degenerate nested object with empty properties falls back to string
        val degenerateObj = props["degenerateObj"]?.jsonObject!!
        assertEquals("string", degenerateObj["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sanitizeForLiveWebSocket normalizes enums to enum on string properties`() {
        val declaration =
            buildJsonObject {
                put("name", JsonPrimitive("adjust_glasses_volume"))
                put("description", JsonPrimitive("Adjusts volume"))
                put(
                    "parameters",
                    buildJsonObject {
                        put("type", JsonPrimitive("object"))
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "direction",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("string"))
                                        put(
                                            "enums",
                                            buildJsonArray {
                                                add(JsonPrimitive("UP"))
                                                add(JsonPrimitive("DOWN"))
                                            },
                                        )
                                    },
                                )
                                put(
                                    "priority",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("integer"))
                                        put(
                                            "enums",
                                            buildJsonArray {
                                                add(JsonPrimitive(1))
                                                add(JsonPrimitive(2))
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            }

        val sanitized = provider.sanitizeForLiveWebSocket(declaration)
        val props =
            sanitized["parameters"]
                ?.jsonObject
                ?.get("properties")
                ?.jsonObject!!
        val directionProp = props["direction"]?.jsonObject!!
        assertFalse(directionProp.containsKey("enums"))
        assertTrue(directionProp.containsKey("enum"))
        assertEquals(2, (directionProp["enum"] as JsonArray).size)

        val priorityProp = props["priority"]?.jsonObject!!
        assertFalse(priorityProp.containsKey("enums"))
        assertFalse(priorityProp.containsKey("enum"))
    }

    @Test
    fun `parseResultJsonToObject wraps JsonArray in result field and preserves JsonObject`() {
        val arrayResult = "[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}]"
        val parsedArrayObj = provider.parseResultJsonToObject(arrayResult)
        assertTrue(parsedArrayObj["result"] is JsonArray)
        assertEquals(2, parsedArrayObj["result"]?.jsonArray?.size)

        val objectResult = "{\"status\":\"sent\",\"messageId\":42}"
        val parsedObj = provider.parseResultJsonToObject(objectResult)
        assertEquals("sent", parsedObj["status"]?.jsonPrimitive?.content)
        assertEquals(42, parsedObj["messageId"]?.jsonPrimitive?.content?.toInt())

        val plainString = " raw fallback output "
        val parsedFallback = provider.parseResultJsonToObject(plainString)
        assertEquals(plainString, parsedFallback["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `sendAudioChunk is gated on isSetupComplete and sendToolResponses batches responses`() =
        runTest {
            val wsSession = mockk<WebSocketSession>(relaxed = true)

            // When setupComplete is false, sendAudioChunk should be ignored
            provider.setActiveSessionForTesting(wsSession, setupComplete = false)
            provider.sendAudioChunk("AAAA")
            coVerify(exactly = 0) { wsSession.send(any()) }

            // Once setupComplete is true, sendAudioChunk transmits the frame
            provider.setActiveSessionForTesting(wsSession, setupComplete = true)
            provider.sendAudioChunk("BBBB")
            coVerify(exactly = 1) { wsSession.send(any()) }

            // sendToolResponses batches multiple functionResponses into a single frame
            val capturedFrames = mutableListOf<Frame.Text>()
            val batch =
                listOf(
                    LiveFunctionResponse(
                        name = "searchContacts",
                        id = "c1",
                        response = provider.parseResultJsonToObject("[{\"name\":\"Alice\"}]"),
                    ),
                    LiveFunctionResponse(
                        name = "sendMessage",
                        id = "c2",
                        response = provider.parseResultJsonToObject("{\"ok\":true}"),
                    ),
                )
            provider.sendToolResponses(batch)
            coVerify(exactly = 2) { wsSession.send(capture(capturedFrames)) }
            val payloadText = capturedFrames.last().readText()
            assertTrue(payloadText.contains("\"searchContacts\""))
            assertTrue(payloadText.contains("\"sendMessage\""))
        }
}
