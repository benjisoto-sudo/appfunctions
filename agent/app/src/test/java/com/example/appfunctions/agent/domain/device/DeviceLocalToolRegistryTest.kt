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

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DeviceLocalToolRegistry] and the 7 Android XR Glasses hardware & HUD tools.
 */
class DeviceLocalToolRegistryTest {
    @Test
    fun `registerStandardGlassesTools registers all 7 XR glasses tools and schemas`() {
        val registry = DeviceLocalToolRegistry()
        registry.registerStandardGlassesTools(
            onSnapFrame = { true },
            onUpdateHudCard = { _, _, _ -> },
            onAdjustVolume = { dir, steps -> "$dir:$steps" },
            onPauseSession = {},
            onEndSession = {},
        )

        val expectedTools =
            listOf(
                "adjust_glasses_volume",
                "end_session",
                "get_glasses_hardware_status",
                "pause_session",
                "set_camera_streaming",
                "snap_glasses_frame",
                "update_hud_card",
            )
        expectedTools.forEach { toolName ->
            assertTrue("Expected tool $toolName to be registered", registry.hasTool(toolName))
        }

        val declarations = registry.toGeminiFunctionDeclarations()
        assertEquals(7, declarations.size)
        assertEquals(
            expectedTools,
            declarations.map { it["name"]?.jsonPrimitive?.content },
        )

        val volumeDecl = declarations.first { it["name"]?.jsonPrimitive?.content == "adjust_glasses_volume" }
        val directionEnum =
            volumeDecl["parameters"]
                ?.jsonObject
                ?.get("properties")
                ?.jsonObject
                ?.get("direction")
                ?.jsonObject
                ?.get("enum")
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
        assertEquals(listOf("UP", "DOWN", "MUTE", "UNMUTE", "MAX"), directionEnum)
    }

    @Test
    fun `execute invokes all 7 XR glasses tool handlers and supports level alias and pause toggle`() =
        runTest {
            var snappedReason: String? = null
            var hudTriple: Triple<String, String, String>? = null
            var volumeCall: Pair<String, Int>? = null
            var pausedPair: Pair<Boolean, String>? = null
            var endedReason: String? = null
            var streamingEnabled: Boolean? = null

            val registry = DeviceLocalToolRegistry()
            registry.registerStandardGlassesTools(
                onSnapFrame = { reason ->
                    snappedReason = reason
                    true
                },
                onUpdateHudCard = { title, primary, subtitle ->
                    hudTriple = Triple(title, primary, subtitle)
                },
                onAdjustVolume = { direction, steps ->
                    volumeCall = direction to steps
                    "Vol $direction ($steps)"
                },
                onPauseSession = { reason ->
                    pausedPair = true to reason
                },
                onEndSession = { reason ->
                    endedReason = reason
                },
                onSetCameraStreaming = { enabled ->
                    streamingEnabled = enabled
                    "Streaming=$enabled"
                },
                onGetHardwareStatus = {
                    mapOf("battery" to "88%", "connected" to "true")
                },
                onTogglePauseState = { paused, reason ->
                    pausedPair = paused to reason
                },
            )

            // 1. snap_glasses_frame
            val snapRes =
                registry.execute(
                    "snap_glasses_frame",
                    buildJsonObject { put("reason", JsonPrimitive("check_object")) },
                )
            assertTrue(snapRes.ok)
            assertEquals("check_object", snappedReason)

            // 2. set_camera_streaming
            val streamRes =
                registry.execute(
                    "set_camera_streaming",
                    buildJsonObject { put("enabled", JsonPrimitive(true)) },
                )
            assertTrue(streamRes.ok)
            assertEquals(true, streamingEnabled)
            assertEquals("true", streamRes.payload["cameraStreaming"])

            // 3. get_glasses_hardware_status
            val statusRes = registry.execute("get_glasses_hardware_status", null)
            assertTrue(statusRes.ok)
            assertEquals("88%", statusRes.payload["battery"])
            assertTrue(statusRes.message.contains("battery=88%"))

            // 4. update_hud_card
            val hudRes =
                registry.execute(
                    "update_hud_card",
                    buildJsonObject {
                        put("title", JsonPrimitive("ChatApp"))
                        put("primaryText", JsonPrimitive("Sent message to Alice"))
                        put("subtitleHint", JsonPrimitive("Say 'Call Alice' to follow up"))
                    },
                )
            assertTrue(hudRes.ok)
            assertEquals(
                Triple("ChatApp", "Sent message to Alice", "Say 'Call Alice' to follow up"),
                hudTriple,
            )

            // 5. adjust_glasses_volume (using 'level' fallback and step clamping)
            val volRes =
                registry.execute(
                    "adjust_glasses_volume",
                    buildJsonObject {
                        put("level", JsonPrimitive("max"))
                        put("steps", JsonPrimitive(99))
                    },
                )
            assertTrue(volRes.ok)
            assertEquals("MAX" to 5, volumeCall)

            // 6. pause_session (with paused = false and reason)
            val pauseRes =
                registry.execute(
                    "pause_session",
                    buildJsonObject {
                        put("paused", JsonPrimitive(false))
                        put("reason", JsonPrimitive("user_said_resume"))
                    },
                )
            assertTrue(pauseRes.ok)
            assertEquals(false to "user_said_resume", pausedPair)
            assertEquals("false", pauseRes.payload["paused"])

            // 7. end_session
            val endRes =
                registry.execute(
                    "end_session",
                    buildJsonObject { put("reason", JsonPrimitive("done_testing")) },
                )
            assertTrue(endRes.ok)
            assertEquals("done_testing", endedReason)
        }

    @Test
    fun `deduplicatePerTurn prevents duplicate execution within same turn and resets onTurnBoundary`() =
        runTest {
            var invocationCount = 0
            val registry = DeviceLocalToolRegistry()
            registry.register(
                LocalDeviceToolSpec(
                    name = "test_tool",
                    description = "Test tool",
                    deduplicatePerTurn = true,
                ),
            ) {
                invocationCount++
                LocalToolResult(ok = true, message = "Executed #$invocationCount")
            }

            val args = buildJsonObject { put("key", JsonPrimitive("val")) }
            val first = registry.execute("test_tool", args)
            val duplicate = registry.execute("test_tool", args)

            assertTrue(first.ok)
            assertTrue(duplicate.ok)
            assertEquals(1, invocationCount)
            assertTrue(duplicate.message.contains("Already executed"))

            registry.onTurnBoundary()
            val afterBoundary = registry.execute("test_tool", args)
            assertTrue(afterBoundary.ok)
            assertEquals(2, invocationCount)
        }

    @Test
    fun `execute handles unknown tool and handler exceptions gracefully`() =
        runTest {
            val registry = DeviceLocalToolRegistry()
            registry.register(
                LocalDeviceToolSpec(
                    name = "failing_tool",
                    description = "Throws error",
                ),
            ) {
                throw IllegalStateException("Hardware sensor offline")
            }

            val unknownResult = registry.execute("non_existent_tool", null)
            assertFalse(unknownResult.ok)
            assertTrue(unknownResult.toJsonString().contains("\"status\":\"error\""))

            val exceptionResult = registry.execute("failing_tool", null)
            assertFalse(exceptionResult.ok)
            assertTrue(exceptionResult.message.contains("Hardware sensor offline"))
        }
}
