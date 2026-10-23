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

/**
 * Represents the reactive state of an active Gemini Live voice session.
 */
sealed interface LiveSessionState {
    /** Session is not currently active. */
    data object Idle : LiveSessionState

    /** Establishing WebSocket handshake with Gemini Live API. */
    data object Connecting : LiveSessionState

    /** Active full-duplex session listening to user voice input. */
    data object Listening : LiveSessionState

    /** Gemini model is currently speaking an audio response. */
    data object Speaking : LiveSessionState

    /** Intercepted a voice command and currently executing target AppFunction IPC. */
    data class ExecutingTool(val toolName: String) : LiveSessionState

    /** Encountered a session or network error. */
    data class Error(val message: String) : LiveSessionState
}
