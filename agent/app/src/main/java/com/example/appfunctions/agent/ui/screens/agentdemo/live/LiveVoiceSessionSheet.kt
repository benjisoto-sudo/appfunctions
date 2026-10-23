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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.appfunctions.agent.domain.live.LiveSessionState

/**
 * Minimal Material 3 Gemini Live bottom sheet that complements the vanilla chat experience.
 *
 * Uses standard Material 3 surface and container tokens (`MaterialTheme.colorScheme`) to match
 * the existing `AgentDemoScreen` and `ChatApp` design language.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionName")
@Composable
fun LiveVoiceSessionSheet(
    sessionState: LiveSessionState,
    transcriptLog: List<String>,
    audioLevel: Float = 0f,
    onToggleMute: (Boolean) -> Unit = {},
    onSendTextPrompt: (String) -> Unit = {},
    onEndSession: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isMuted by remember { mutableStateOf(false) }
    var textPrompt by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(transcriptLog.size) {
        if (transcriptLog.isNotEmpty()) {
            listState.animateScrollToItem(transcriptLog.size - 1)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Gemini Live",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val statusLabel =
                when {
                    isMuted -> "Microphone muted"
                    sessionState is LiveSessionState.Connecting -> "Connecting..."
                    sessionState is LiveSessionState.Listening -> "Listening..."
                    sessionState is LiveSessionState.Speaking -> "Speaking..."
                    sessionState is LiveSessionState.ExecutingTool -> "Running AppFunction..."
                    sessionState is LiveSessionState.Error -> "Error: ${sessionState.message.take(40)}"
                    else -> "Ready"
                }

            val dotColor by
                animateColorAsState(
                    targetValue =
                        when {
                            isMuted || sessionState is LiveSessionState.Error ->
                                MaterialTheme.colorScheme.error
                            sessionState is LiveSessionState.Listening ->
                                MaterialTheme.colorScheme.primary
                            sessionState is LiveSessionState.Speaking ->
                                MaterialTheme.colorScheme.secondary
                            sessionState is LiveSessionState.ExecutingTool ->
                                MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    label = "dotColor",
                )

            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceBright,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .background(color = dotColor, shape = CircleShape),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AnimatedContent(
                        targetState = statusLabel,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "statusAnimation",
                    ) { animatedLabel ->
                        Text(
                            text = animatedLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            WaveformVisualizer(
                sessionState = if (isMuted) LiveSessionState.Idle else sessionState,
                audioLevel = if (isMuted) 0f else audioLevel,
                modifier = Modifier.width(220.dp).height(72.dp),
            )

            Spacer(modifier = Modifier.height(12.dp))

            val activeToolName = (sessionState as? LiveSessionState.ExecutingTool)?.toolName
            LiveToolExecutionCard(toolName = activeToolName)

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                if (transcriptLog.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text =
                                "Speak naturally to use AppFunctions " +
                                    "(e.g. \"Search my contacts for Alice\" or \"Send a message to Bob\").",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(transcriptLog) { transcript ->
                            val isUser =
                                transcript.startsWith("Voice:") || transcript.startsWith("You:")
                            val isTool =
                                transcript.startsWith("Executed ") ||
                                    transcript.startsWith("AppFunction:") ||
                                    transcript.startsWith("Tool:")
                            val isError = transcript.startsWith("System Error:")

                            val bubbleBg =
                                when {
                                    isError -> MaterialTheme.colorScheme.errorContainer
                                    isUser -> MaterialTheme.colorScheme.primaryContainer
                                    isTool -> MaterialTheme.colorScheme.secondaryContainer
                                    else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                }
                            val textColor =
                                when {
                                    isError -> MaterialTheme.colorScheme.onErrorContainer
                                    isUser -> MaterialTheme.colorScheme.onPrimaryContainer
                                    isTool -> MaterialTheme.colorScheme.onSecondaryContainer
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                            val alignment =
                                when {
                                    isUser -> Alignment.End
                                    isTool -> Alignment.CenterHorizontally
                                    else -> Alignment.Start
                                }

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = alignment,
                            ) {
                                AnimatedVisibility(
                                    visible = true,
                                    enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.medium,
                                        color = bubbleBg,
                                    ) {
                                        Text(
                                            text = transcript,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = textColor,
                                            maxLines = if (isTool) 4 else Int.MAX_VALUE,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier =
                                                Modifier.padding(
                                                    horizontal = 12.dp,
                                                    vertical = 8.dp,
                                                ),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = textPrompt,
                    onValueChange = { textPrompt = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = "Type a Live prompt (emulator fallback)...",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    singleLine = true,
                    shape = CircleShape,
                    colors =
                        OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                            unfocusedBorderColor = Color.Transparent,
                            focusedBorderColor = Color.Transparent,
                        ),
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (textPrompt.isNotBlank()) {
                                    onSendTextPrompt(textPrompt.trim())
                                    textPrompt = ""
                                }
                            },
                            enabled = textPrompt.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send Live Prompt",
                            )
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledIconButton(
                    onClick = {
                        isMuted = !isMuted
                        onToggleMute(isMuted)
                    },
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor =
                                if (isMuted) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.secondaryContainer
                                },
                            contentColor =
                                if (isMuted) {
                                    MaterialTheme.colorScheme.onErrorContainer
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                },
                        ),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = if (isMuted) "Unmute Microphone" else "Mute Microphone",
                    )
                }

                FilledIconButton(
                    onClick = onEndSession,
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Live Session",
                    )
                }
            }
        }
    }
}
