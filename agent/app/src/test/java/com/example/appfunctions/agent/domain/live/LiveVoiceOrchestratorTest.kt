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

import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.example.appfunctions.agent.data.GeminiToolConverter
import com.example.appfunctions.agent.data.audio.AudioInputCapturer
import com.example.appfunctions.agent.data.audio.AudioOutputPlayer
import com.example.appfunctions.agent.data.live.LiveFunctionCall
import com.example.appfunctions.agent.data.live.LiveServerContent
import com.example.appfunctions.agent.data.live.LiveServerMessage
import com.example.appfunctions.agent.data.live.LiveServerToolCall
import com.example.appfunctions.agent.data.live.LiveTranscription
import com.example.appfunctions.agent.domain.appfunction.ConvertAppFunctionDataToJsonUseCase
import com.example.appfunctions.agent.domain.appfunction.ConvertInputToAppFunctionDataUseCase
import com.example.appfunctions.agent.domain.appfunction.ExecuteAppFunctionResult
import com.example.appfunctions.agent.domain.appfunction.ExecuteAppFunctionUseCase
import com.example.appfunctions.agent.domain.device.DeviceLocalToolRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Unit tests for [LiveVoiceOrchestrator]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveVoiceOrchestratorTest {
    private val liveLlmProvider = mockk<LiveLlmProvider>(relaxed = true)
    private val audioInputCapturer = mockk<AudioInputCapturer>(relaxed = true)
    private val audioOutputPlayer = mockk<AudioOutputPlayer>(relaxed = true)
    private val executeAppFunctionUseCase = mockk<ExecuteAppFunctionUseCase>(relaxed = true)
    private val convertInputToAppFunctionDataUseCase =
        mockk<ConvertInputToAppFunctionDataUseCase>(relaxed = true)
    private val convertAppFunctionDataToJsonUseCase =
        mockk<ConvertAppFunctionDataToJsonUseCase>(relaxed = true)
    private val toolConverter = mockk<GeminiToolConverter>(relaxed = true)

    private val orchestrator =
        LiveVoiceOrchestrator(
            liveLlmProvider = liveLlmProvider,
            audioInputCapturer = audioInputCapturer,
            audioOutputPlayer = audioOutputPlayer,
            executeAppFunctionUseCase = executeAppFunctionUseCase,
            convertInputToAppFunctionDataUseCase = convertInputToAppFunctionDataUseCase,
            convertAppFunctionDataToJsonUseCase = convertAppFunctionDataToJsonUseCase,
            toolConverter = toolConverter,
        )

    @Test
    fun `toolCall frame executes AppFunction and sends toolResponse upstream`() =
        runTest {
            val metadata = mockk<AppFunctionMetadata>(relaxed = true)
            every { metadata.id } returns "com.example.chatapp.functions.ChatFunctions#send"
            every { toolConverter.getToolName(metadata) } returns
                "com_example_chatapp_functions_ChatFunctions_send"

            val toolCallMsg =
                LiveServerMessage(
                    toolCall =
                        LiveServerToolCall(
                            functionCalls =
                                listOf(
                                    LiveFunctionCall(
                                        id = "call-1",
                                        name = "com_example_chatapp_functions_ChatFunctions_send",
                                    ),
                                ),
                        ),
                )

            coEvery { liveLlmProvider.startSession(any(), any(), any(), any(), any()) } returns
                flowOf(toolCallMsg)
            every { convertInputToAppFunctionDataUseCase(any(), any(), any()) } returns
                Result.success(AppFunctionData.EMPTY)
            coEvery { executeAppFunctionUseCase(any(), any(), any()) } returns
                ExecuteAppFunctionResult.Data(
                    formattedJson = "{\"status\": \"success\"}",
                    data = AppFunctionData.EMPTY,
                )

            orchestrator.startVoiceSession(
                apiKey = "test-key",
                modelName = "models/gemini-3.1-flash-live-preview",
                tools = listOf(metadata),
            )

            coVerify(timeout = 1000) {
                liveLlmProvider.sendToolResponse(
                    "call-1",
                    "com_example_chatapp_functions_ChatFunctions_send",
                    "{\"status\": \"success\"}",
                )
            }
            assertEquals(LiveSessionState.Listening, orchestrator.sessionState.value)
        }

    @Test
    fun `multiple toolCalls in single frame sends batched sendToolResponses upstream`() =
        runTest {
            val metadata = mockk<AppFunctionMetadata>(relaxed = true)
            every { metadata.id } returns "com.example.chatapp.functions.ChatFunctions#searchContacts"
            every { toolConverter.getToolName(metadata) } returns
                "com_example_chatapp_functions_ChatFunctions_searchContacts"

            val toolCallMsg =
                LiveServerMessage(
                    toolCall =
                        LiveServerToolCall(
                            functionCalls =
                                listOf(
                                    LiveFunctionCall(
                                        id = "call-1",
                                        name = "com_example_chatapp_functions_ChatFunctions_searchContacts",
                                    ),
                                    LiveFunctionCall(
                                        id = "call-2",
                                        name = "com_example_chatapp_functions_ChatFunctions_searchContacts",
                                    ),
                                ),
                        ),
                )

            coEvery { liveLlmProvider.startSession(any(), any(), any(), any(), any()) } returns
                flowOf(toolCallMsg)
            every { convertInputToAppFunctionDataUseCase(any(), any(), any()) } returns
                Result.success(AppFunctionData.EMPTY)
            coEvery { executeAppFunctionUseCase(any(), any(), any()) } returns
                ExecuteAppFunctionResult.Data(
                    formattedJson = "[{\"name\": \"Alice\"}]",
                    data = AppFunctionData.EMPTY,
                )

            orchestrator.startVoiceSession(
                apiKey = "test-key",
                modelName = "models/gemini-3.1-flash-live-preview",
                tools = listOf(metadata),
            )

            coVerify(timeout = 1000) {
                liveLlmProvider.sendToolResponses(
                    match { it.size == 2 && it[0].id == "call-1" && it[1].id == "call-2" },
                )
            }
        }

    @Test
    fun `interrupted serverContent immediately flushes audioOutputPlayer`() =
        runTest {
            val interruptedMsg =
                LiveServerMessage(
                    serverContent = LiveServerContent(interrupted = true),
                )
            coEvery { liveLlmProvider.startSession(any(), any(), any(), any(), any()) } returns
                flowOf(interruptedMsg)

            orchestrator.startVoiceSession(
                apiKey = "test-key",
                modelName = "models/gemini-3.1-flash-live-preview",
                tools = emptyList(),
            )

            verify(timeout = 1000) { audioOutputPlayer.flush() }
            assertEquals(LiveSessionState.Listening, orchestrator.sessionState.value)
        }

    @Test
    fun `local XR glasses tool update_hud_card updates HUD and responds upstream`() =
        runTest {
            val capturedTitle = AtomicReference<String>()
            val capturedPrimary = AtomicReference<String>()
            val registry =
                DeviceLocalToolRegistry().apply {
                    registerStandardGlassesTools(
                        onSnapFrame = { true },
                        onUpdateHudCard = { title, primaryText, _ ->
                            capturedTitle.set(title)
                            capturedPrimary.set(primaryText)
                            orchestrator.updateHudCard(title, primaryText)
                        },
                        onAdjustVolume = { _, _ -> "ok" },
                        onPauseSession = {},
                        onEndSession = {},
                    )
                }

            val toolCallMsg =
                LiveServerMessage(
                    toolCall =
                        LiveServerToolCall(
                            functionCalls =
                                listOf(
                                    LiveFunctionCall(
                                        id = "hud-1",
                                        name = "update_hud_card",
                                        args =
                                            buildJsonObject {
                                                put("title", JsonPrimitive("Sent to Alice"))
                                                put("primaryText", JsonPrimitive("Hello from XR"))
                                                put("subtitleHint", JsonPrimitive("ChatApp"))
                                            },
                                    ),
                                ),
                        ),
                )

            coEvery { liveLlmProvider.startSession(any(), any(), any(), any(), any()) } returns
                flowOf(toolCallMsg)

            orchestrator.startVoiceSession(
                apiKey = "test-key",
                modelName = "models/gemini-3.1-flash-live-preview",
                tools = emptyList(),
                localToolRegistry = registry,
            )

            coVerify(timeout = 1000) {
                liveLlmProvider.sendToolResponse(
                    "hud-1",
                    "update_hud_card",
                    any(),
                )
            }
            assertEquals("Sent to Alice", capturedTitle.get())
            assertEquals("Hello from XR", capturedPrimary.get())
            assertEquals("Sent to Alice" to "Hello from XR", orchestrator.hudCardState.value)
        }

    @Test
    fun `onSetupComplete is invoked and pinned HUD card resists outputTranscription overwrite`() =
        runTest {
            val setupLatch = CountDownLatch(1)
            val metadata = mockk<AppFunctionMetadata>(relaxed = true)
            every { metadata.id } returns "com.example.chatapp.functions.ChatFunctions#send"
            every { toolConverter.getToolName(metadata) } returns
                "com_example_chatapp_functions_ChatFunctions_send"

            val setupMsg = LiveServerMessage(setupComplete = buildJsonObject {})
            val toolCallMsg =
                LiveServerMessage(
                    toolCall =
                        LiveServerToolCall(
                            functionCalls =
                                listOf(
                                    LiveFunctionCall(
                                        id = "call-pin",
                                        name = "com_example_chatapp_functions_ChatFunctions_send",
                                    ),
                                ),
                        ),
                )
            val transcriptMsg =
                LiveServerMessage(
                    serverContent =
                        LiveServerContent(
                            outputTranscription = LiveTranscription(text = "I have sent your message."),
                            turnComplete = true,
                        ),
                )

            coEvery { liveLlmProvider.startSession(any(), any(), any(), any(), any()) } returns
                flowOf(setupMsg, toolCallMsg, transcriptMsg)
            every { convertInputToAppFunctionDataUseCase(any(), any(), any()) } returns
                Result.success(AppFunctionData.EMPTY)
            coEvery { executeAppFunctionUseCase(any(), any(), any()) } returns
                ExecuteAppFunctionResult.Data(
                    formattedJson = "{\"delivered\":true}",
                    data = AppFunctionData.EMPTY,
                )

            orchestrator.startVoiceSession(
                apiKey = "test-key",
                modelName = "models/gemini-3.1-flash-live-preview",
                tools = listOf(metadata),
                onSetupComplete = { setupLatch.countDown() },
            )

            assertTrue(setupLatch.await(1, TimeUnit.SECONDS))
            coVerify(timeout = 1000) {
                liveLlmProvider.sendToolResponse("call-pin", any(), any())
            }
            // Pinned tool HUD card should still be active (not overwritten by "Gemini")
            assertEquals("Tool: send", orchestrator.hudCardState.value?.first)
        }

    @Test
    fun `setMuted true flushes audioOutputPlayer and JsonNull toPrimitive returns null`() {
        orchestrator.setMuted(true)
        verify { audioInputCapturer.isMuted = true }
        verify { audioOutputPlayer.flush() }
        assertEquals(0f, orchestrator.audioLevel.value)

        with(orchestrator) {
            assertNull(JsonNull.toPrimitive())
            assertEquals(42L, JsonPrimitive(42).toPrimitive())
            assertEquals(true, JsonPrimitive(true).toPrimitive())
        }
    }
}
