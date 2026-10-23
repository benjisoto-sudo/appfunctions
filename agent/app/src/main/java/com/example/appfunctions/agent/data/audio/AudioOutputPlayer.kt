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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plays 24 kHz mono PCM16 streaming audio received from Gemini Live API turns,
 * supporting instant interruption (barge-in flushing) when user speech overrides output.
 */
@Singleton
class AudioOutputPlayer
    @Inject
    constructor() {
        private var audioTrack: AudioTrack? = null
        private var cachedHardwareContext: Context? = null
        var onAudioLevel: (Float) -> Unit = {}

        @Volatile
        private var lastAudioWriteTimeMs: Long = 0L

        internal fun getCachedHardwareContextForTesting(): Context? = cachedHardwareContext

        /**
         * Returns true if audio chunks were written within the last 1200ms (accounting for hardware buffer tail).
         */
        fun isPlaying(): Boolean {
            if (lastAudioWriteTimeMs == 0L) return false
            return (SystemClock.elapsedRealtime() - lastAudioWriteTimeMs) < 1200L
        }

        /**
         * Initializes and starts the underlying [AudioTrack] if not already started.
         */
        @Synchronized
        fun start(hardwareContext: Context? = null) {
            if (hardwareContext != null) {
                cachedHardwareContext = hardwareContext
            }
            if (audioTrack != null && audioTrack?.state == AudioTrack.STATE_INITIALIZED) return
            audioTrack?.release()
            val minBufferSize =
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                )
            val builder =
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AUDIO_FORMAT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build(),
                    )
                    .setBufferSizeInBytes((minBufferSize * 4).coerceAtLeast(16384))
                    .setTransferMode(AudioTrack.MODE_STREAM)

            val targetContext = hardwareContext ?: cachedHardwareContext
            if (targetContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setContext(targetContext)
            }

            val track = builder.build()

            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                try {
                    Thread.sleep(15)
                } catch (e: Exception) {
                    // Ignore
                }
                audioTrack = track
            } else {
                Log.w(TAG, "AudioTrack failed to initialize on this emulator/device.")
                track.release()
                audioTrack = null
            }
        }

        /**
         * Decodes a Base64 PCM chunk and writes it to the audio playback buffer.
         *
         * @param pcmBase64 Base64-encoded PCM audio string received from Gemini modelTurn.
         */
        suspend fun playChunk(pcmBase64: String) {
            withContext(Dispatchers.IO) {
                try {
                    start(cachedHardwareContext)
                    val pcmBytes = Base64.decode(pcmBase64, Base64.DEFAULT)
                    val normalizedLevel = calculateNormalizedRms(pcmBytes)
                    onAudioLevel(normalizedLevel)

                    val track = audioTrack
                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        val written = track.write(pcmBytes, 0, pcmBytes.size)
                        if (written > 0) {
                            lastAudioWriteTimeMs = SystemClock.elapsedRealtime()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error playing PCM chunk: ${e.message}", e)
                }
            }
        }

        internal fun calculateNormalizedRms(pcmBytes: ByteArray): Float {
            if (pcmBytes.size < 2) return 0f
            var sumSquares = 0.0
            val numShorts = pcmBytes.size / 2
            for (i in 0 until numShorts) {
                val low = pcmBytes[i * 2].toInt() and 0xFF
                val high = pcmBytes[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort()
                sumSquares += (sample * sample).toDouble()
            }
            val rms = kotlin.math.sqrt(sumSquares / numShorts.coerceAtLeast(1)) / 32767.0
            return (rms * 3.2).coerceIn(0.0, 1.0).toFloat()
        }

        /**
         * Instantly interrupts and flushes queued audio buffers (e.g., during barge-in or tool execution).
         */
        @Synchronized
        fun flush() {
            lastAudioWriteTimeMs = 0L
            try {
                val track = audioTrack
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                    track.play()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error flushing AudioTrack: ${e.message}")
            }
        }

        /**
         * Stops audio playback and releases underlying hardware resources.
         */
        @Synchronized
        fun stop() {
            lastAudioWriteTimeMs = 0L
            try {
                val track = audioTrack
                audioTrack = null
                if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                    track.pause()
                    track.flush()
                    track.stop()
                    track.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
            }
        }

        /**
         * Synthesizes and plays an ascending/descending harmonic earcon chime with raised-cosine envelope.
         */
        suspend fun playEarcon(
            frequencies: List<Float>,
            toneDurationMs: Int = 110,
        ) {
            withContext(Dispatchers.IO) {
                try {
                    start(cachedHardwareContext)
                    val combinedBuffer = synthesizeEarconSamples(frequencies, toneDurationMs)
                    val track = audioTrack
                    if (track != null && track.state == AudioTrack.STATE_INITIALIZED) {
                        track.write(combinedBuffer, 0, combinedBuffer.size, AudioTrack.WRITE_BLOCKING)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error playing synthetic earcon: ${e.message}")
                }
            }
        }

        internal fun synthesizeEarconSamples(
            frequencies: List<Float>,
            toneDurationMs: Int,
        ): ShortArray {
            val numSamplesPerTone = (SAMPLE_RATE * toneDurationMs) / 1000
            val totalSamples = numSamplesPerTone * frequencies.size
            val combinedBuffer = ShortArray(totalSamples)

            for ((index, freq) in frequencies.withIndex()) {
                val offset = index * numSamplesPerTone
                for (i in 0 until numSamplesPerTone) {
                    val time = i.toDouble() / SAMPLE_RATE
                    val envelope =
                        kotlin.math.sin(kotlin.math.PI * i / numSamplesPerTone)
                    val sample =
                        (kotlin.math.sin(2.0 * kotlin.math.PI * freq * time) * 14000.0 * envelope)
                    combinedBuffer[offset + i] = sample.toInt().toShort()
                }
            }
            return combinedBuffer
        }

        /**
         * Plays a pleasant ascending 3-tone session start chime (C5 -> E5 -> A5).
         */
        suspend fun playSessionStartEarcon() {
            playEarcon(listOf(523.25f, 659.25f, 880.0f), toneDurationMs = 90)
        }

        /**
         * Plays an alert 2-tone action chime (G5 -> C6) when an AppFunction tool is triggered.
         */
        suspend fun playToolExecutionEarcon() {
            playEarcon(listOf(783.99f, 1046.50f), toneDurationMs = 75)
        }

        /**
         * Plays a soft descending 3-tone hangup chime (E5 -> C5 -> G4) upon closing session.
         */
        suspend fun playSessionStopEarcon() {
            playEarcon(listOf(659.25f, 523.25f, 392.00f), toneDurationMs = 90)
        }

        companion object {
            private const val TAG = "AudioOutputPlayer"
            private const val SAMPLE_RATE = 24000
            private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
            private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        }
    }
