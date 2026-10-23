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
package com.example.appfunctions.agent.ui.screens.agentdemo.live

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import com.example.appfunctions.agent.domain.live.LiveSessionState
import kotlin.math.sin

/**
 * Minimal Material 3 audio waveform visualizer for Gemini Live voice sessions.
 */
@Suppress("FunctionName")
@Composable
fun WaveformVisualizer(
    sessionState: LiveSessionState,
    audioLevel: Float = 0f,
    modifier: Modifier = Modifier,
) {
    val animatedAudioLevel by
        animateFloatAsState(
            targetValue = audioLevel,
            animationSpec = tween(durationMillis = 70, easing = LinearOutSlowInEasing),
            label = "audioLevelSmooth",
        )
    val infiniteTransition = rememberInfiniteTransition(label = "gemini_live_waveform")

    val phase by
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = (2 * Math.PI).toFloat(),
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 2200, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "phase",
        )

    val breathingPulse by
        infiniteTransition.animateFloat(
            initialValue = 0.7f,
            targetValue = 1.0f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
            label = "breathing",
        )

    val barColor =
        when (sessionState) {
            is LiveSessionState.Listening -> MaterialTheme.colorScheme.primary
            is LiveSessionState.Speaking -> MaterialTheme.colorScheme.secondary
            is LiveSessionState.ExecutingTool -> MaterialTheme.colorScheme.tertiary
            is LiveSessionState.Error -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.outline
        }

    Box(
        modifier = modifier.width(220.dp).height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            val numBars = 9
            val barWidth = 6.dp.toPx()
            val totalBarSpacing = canvasWidth * 0.75f
            val spacing = totalBarSpacing / (numBars - 1)
            val startX = (canvasWidth - totalBarSpacing) / 2f

            val baseAmplitudes =
                listOf(0.25f, 0.45f, 0.70f, 0.90f, 1.0f, 0.90f, 0.70f, 0.45f, 0.25f)

            val activityFactor =
                when (sessionState) {
                    is LiveSessionState.Listening -> 0.85f
                    is LiveSessionState.Speaking -> 1.15f
                    is LiveSessionState.ExecutingTool -> 1.0f
                    else -> 0.45f
                }

            val isStatic =
                sessionState is LiveSessionState.Idle || sessionState is LiveSessionState.Error

            for (i in 0 until numBars) {
                val x = startX + i * spacing
                val barHeight =
                    if (isStatic) {
                        (baseAmplitudes[i] * canvasHeight * 0.22f).coerceAtLeast(8.dp.toPx())
                    } else {
                        val barPhaseOffset = i * 0.55f
                        val dynamicWave =
                            sin((phase * 2.2f + barPhaseOffset).toDouble()).toFloat() * 0.2f

                        val volumeBoost = 1.0f + (animatedAudioLevel * 2.8f)
                        val rawHeight =
                            (baseAmplitudes[i] + dynamicWave) *
                                (canvasHeight * 0.75f) *
                                activityFactor *
                                breathingPulse *
                                volumeBoost
                        rawHeight.coerceIn(10.dp.toPx(), canvasHeight)
                    }
                val y = (canvasHeight - barHeight) / 2f

                drawRoundRect(
                    color = barColor,
                    topLeft = Offset(x - barWidth / 2f, y),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                )
            }
        }
    }
}
