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
package com.example.appfunctions.agent.data.audio

import android.content.Context
import android.util.Base64
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests for [AudioOutputPlayer]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioOutputPlayerTest {
    private val player = AudioOutputPlayer()

    @Test
    fun `start caches hardwareContext across stop and internal restart`() {
        val mockGlassesContext = mockk<Context>(relaxed = true)
        player.start(mockGlassesContext)
        assertSame(mockGlassesContext, player.getCachedHardwareContextForTesting())

        player.stop()
        // Internal start() with null argument preserves cached XR ProjectedContext
        player.start()
        assertSame(mockGlassesContext, player.getCachedHardwareContextForTesting())
    }

    @Test
    fun `calculateNormalizedRms and synthesizeEarconSamples produce expected waveforms`() =
        runTest {
            val silence = ByteArray(32)
            assertEquals(0f, player.calculateNormalizedRms(silence), 0.001f)

            val pcm = ByteArray(4)
            pcm[0] = 0x00
            pcm[1] = 0x40
            pcm[2] = 0x00
            pcm[3] = 0x40
            val level = player.calculateNormalizedRms(pcm)
            assertTrue(level > 0.4f)

            val earconSamples = player.synthesizeEarconSamples(listOf(523.25f, 659.25f), 90)
            // 24000 Hz * 0.090s * 2 tones = 4320 samples
            assertEquals(4320, earconSamples.size)
            assertTrue(earconSamples.any { it.toInt() != 0 })

            var reportedLevel = -1f
            player.onAudioLevel = { reportedLevel = it }
            val base64Chunk = Base64.encodeToString(pcm, Base64.NO_WRAP)
            player.playChunk(base64Chunk)
            assertTrue(reportedLevel > 0.4f)

            player.flush()
            assertFalse(player.isPlaying())
            player.stop()
        }
}
