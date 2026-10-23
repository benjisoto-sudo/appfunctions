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

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Captures real-time PCM16 mono microphone audio at 16,000 Hz, applying hardware acoustic echo
 * cancellation and noise suppression, and emits Base64 encoded chunks suitable for Gemini Live.
 *
 * Supports an optional [hardwareContext] (such as `ProjectedContext.createProjectedDeviceContext(activity)`)
 * to route audio capture through XR glasses temple microphones when active.
 */
@Singleton
class AudioInputCapturer
    @Inject
    constructor() {
        private var audioRecord: AudioRecord? = null
        private var echoCanceler: AcousticEchoCanceler? = null
        private var noiseSuppressor: NoiseSuppressor? = null

        @Volatile
        var isMuted: Boolean = false

        /**
         * Starts microphone recording and emits a flow of Base64 encoded PCM audio chunks.
         *
         * @param hardwareContext Optional hardware [Context] for XR projected device microphone routing.
         * @param onAudioLevel Callback invoked with normalized RMS audio level in [0.0, 1.0].
         */
        @SuppressLint("MissingPermission")
        fun startCapturing(
            hardwareContext: Context? = null,
            onAudioLevel: (Float) -> Unit = {},
        ): Flow<String> =
            flow {
                val bufferSize =
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                    ).coerceAtLeast(CHUNK_SIZE_BYTES)

                val sourcesToTry =
                    listOf(
                        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        MediaRecorder.AudioSource.MIC,
                        MediaRecorder.AudioSource.DEFAULT,
                        MediaRecorder.AudioSource.UNPROCESSED,
                    )
                var activeRecorder: AudioRecord? = null
                for (source in sourcesToTry) {
                    try {
                        val candidate =
                            if (hardwareContext != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                AudioRecord.Builder()
                                    .setContext(hardwareContext)
                                    .setAudioSource(source)
                                    .setAudioFormat(
                                        AudioFormat.Builder()
                                            .setEncoding(AUDIO_FORMAT)
                                            .setSampleRate(SAMPLE_RATE)
                                            .setChannelMask(CHANNEL_CONFIG)
                                            .build(),
                                    )
                                    .setBufferSizeInBytes(bufferSize * 2)
                                    .build()
                            } else {
                                AudioRecord(
                                    source,
                                    SAMPLE_RATE,
                                    CHANNEL_CONFIG,
                                    AUDIO_FORMAT,
                                    bufferSize * 2,
                                )
                            }
                        if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                            activeRecorder = candidate
                            Log.d(TAG, "AudioRecord initialized successfully with AudioSource: $source")
                            break
                        } else {
                            Log.w(TAG, "AudioRecord(source=$source) returned state=${candidate.state}")
                            candidate.release()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Exception initializing AudioRecord(source=$source): ${e.message}")
                    }
                }

                if (activeRecorder == null || activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "AudioRecord could not be initialized across all audio sources on this emulator/device.")
                    activeRecorder?.release()
                    return@flow
                }

                audioRecord = activeRecorder

                val sessionId = activeRecorder.audioSessionId
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable AcousticEchoCanceler: ${e.message}")
                    }
                }
                if (NoiseSuppressor.isAvailable()) {
                    try {
                        noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to enable NoiseSuppressor: ${e.message}")
                    }
                }

                try {
                    if (activeRecorder.state == AudioRecord.STATE_INITIALIZED) {
                        activeRecorder.startRecording()
                    } else {
                        return@flow
                    }
                    val buffer = ByteArray(CHUNK_SIZE_BYTES)
                    while (coroutineContext.isActive) {
                        val readBytes = activeRecorder.read(buffer, 0, buffer.size)
                        if (readBytes > 0) {
                            if (isMuted) {
                                onAudioLevel(0f)
                                continue
                            }
                            val normalizedLevel = calculateNormalizedRms(buffer, readBytes)
                            onAudioLevel(normalizedLevel)

                            val base64Chunk =
                                Base64.encodeToString(buffer, 0, readBytes, Base64.NO_WRAP)
                            emit(base64Chunk)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during audio capture: ${e.message}", e)
                } finally {
                    stopCapturing()
                }
            }.flowOn(Dispatchers.IO)

        internal fun calculateNormalizedRms(
            buffer: ByteArray,
            readBytes: Int,
        ): Float {
            if (readBytes < 2) return 0f
            var sumSquares = 0.0
            val numShorts = readBytes / 2
            for (i in 0 until numShorts) {
                val low = buffer[i * 2].toInt() and 0xFF
                val high = buffer[i * 2 + 1].toInt()
                val sample = ((high shl 8) or low).toShort()
                sumSquares += (sample * sample).toDouble()
            }
            val rms = kotlin.math.sqrt(sumSquares / numShorts.coerceAtLeast(1)) / 32767.0
            return (rms * 3.8).coerceIn(0.0, 1.0).toFloat()
        }

        /**
         * Stops microphone recording and releases hardware audio resources.
         */
        fun stopCapturing() {
            try {
                echoCanceler?.release()
                noiseSuppressor?.release()
                audioRecord?.run {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        stop()
                    }
                    release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AudioRecord: ${e.message}")
            } finally {
                echoCanceler = null
                noiseSuppressor = null
                audioRecord = null
            }
        }

        companion object {
            private const val TAG = "AudioInputCapturer"
            private const val SAMPLE_RATE = 16000
            private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
            private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
            private const val CHUNK_SIZE_BYTES = 2048
        }
    }
