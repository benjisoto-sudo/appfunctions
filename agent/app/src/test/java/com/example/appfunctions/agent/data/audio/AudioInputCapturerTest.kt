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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Unit tests for [AudioInputCapturer]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioInputCapturerTest {
    private val capturer = AudioInputCapturer()

    @Test
    fun `calculateNormalizedRms returns zero for silence and scales non-zero PCM16 samples`() {
        val silence = ByteArray(64)
        assertEquals(0f, capturer.calculateNormalizedRms(silence, silence.size), 0.001f)
        assertEquals(0f, capturer.calculateNormalizedRms(silence, 1), 0.001f)

        // Create a PCM16 little-endian buffer with peak amplitude (16384 = 0x4000)
        val loudBuffer = ByteArray(4)
        loudBuffer[0] = 0x00
        loudBuffer[1] = 0x40
        loudBuffer[2] = 0x00
        loudBuffer[3] = 0x40
        val rmsLevel = capturer.calculateNormalizedRms(loudBuffer, loudBuffer.size)
        assertTrue(rmsLevel > 0.5f)
        assertTrue(rmsLevel <= 1.0f)
    }

    @Test
    fun `isMuted state toggles and stopCapturing is safe when idle`() {
        assertFalse(capturer.isMuted)
        capturer.isMuted = true
        assertTrue(capturer.isMuted)
        capturer.stopCapturing()
        capturer.isMuted = false
        assertFalse(capturer.isMuted)
    }
}
