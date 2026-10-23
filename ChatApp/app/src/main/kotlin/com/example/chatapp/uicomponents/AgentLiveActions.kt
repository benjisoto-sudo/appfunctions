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
package com.example.chatapp.uicomponents

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.xr.projected.ProjectedContext
import androidx.xr.projected.experimental.ExperimentalProjectedApi

private const val AGENT_PACKAGE = "com.example.appfunctions.agent"
private const val AGENT_MAIN_ACTIVITY = "com.example.appfunctions.agent.MainActivity"
private const val AGENT_GLASSES_ACTIVITY =
    "com.example.appfunctions.agent.ui.glasses.GlassesAgentActivity"
private const val EXTRA_START_LIVE_VOICE = "EXTRA_START_LIVE_VOICE"
private const val EXTRA_TARGET_PACKAGE_NAME = "extra_target_package_name"

/**
 * Minimal TopAppBar action buttons for launching the AppFunctions Gemini Live agent on the
 * mobile handset or on connected Android XR Smart Glasses.
 *
 * - **Glasses Icon**: Enabled when Android XR glasses are connected (`isGlassesConnected == true`)
 *   and launches the projected `GlassesAgentActivity` on the glasses display. Disabled when
 *   glasses are disconnected.
 * - **Live Icon**: Launches the mobile Gemini Live voice session so the user can invoke ChatApp's
 *   `@AppFunction`s (`searchContacts`, `send`, `makeCall`) via real-time voice on the phone.
 */
@OptIn(ExperimentalProjectedApi::class)
@Suppress("FunctionName")
@Composable
fun AgentLiveActions(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isGlassesConnected by produceState(initialValue = false, context) {
        runCatching {
            ProjectedContext.isProjectedDeviceConnected(context, coroutineContext).collect {
                value = it
            }
        }.onFailure {
            value = false
        }
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { launchAgentOnGlasses(context) },
            enabled = isGlassesConnected,
        ) {
            SmartGlassesVectorIcon(
                tint = LocalContentColor.current,
                contentDescription =
                    if (isGlassesConnected) {
                        "Launch Agent on XR Glasses"
                    } else {
                        "XR Glasses Disconnected"
                    },
            )
        }

        IconButton(
            onClick = { launchMobileLiveAgent(context) },
        ) {
            GeminiLiveWaveformIcon(
                tint = LocalContentColor.current,
                contentDescription = "Start Gemini Live",
            )
        }
    }
}

@OptIn(ExperimentalProjectedApi::class)
private fun launchAgentOnGlasses(context: Context) {
    runCatching {
        val options = ProjectedContext.createProjectedActivityOptions(context).toBundle()
        val intent =
            Intent().apply {
                component = ComponentName(AGENT_PACKAGE, AGENT_GLASSES_ACTIVITY)
                putExtra(EXTRA_TARGET_PACKAGE_NAME, context.packageName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        context.startActivity(intent, options)
    }.onFailure {
        Toast.makeText(
            context,
            "Unable to launch AppFunctions Agent on XR Glasses",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private fun launchMobileLiveAgent(context: Context) {
    runCatching {
        val intent =
            Intent().apply {
                component = ComponentName(AGENT_PACKAGE, AGENT_MAIN_ACTIVITY)
                putExtra(EXTRA_START_LIVE_VOICE, true)
                putExtra(EXTRA_TARGET_PACKAGE_NAME, context.packageName)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            }
        context.startActivity(intent)
    }.onFailure {
        Toast.makeText(
            context,
            "Install the AppFunctions Testing Agent to use Gemini Live",
            Toast.LENGTH_SHORT,
        ).show()
    }
}

@Suppress("FunctionName")
@Composable
private fun SmartGlassesVectorIcon(
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .size(24.dp)
                .semantics { this.contentDescription = contentDescription },
    ) {
        val strokeWidth = 2.dp.toPx()
        val w = size.width
        val h = size.height
        val lensWidth = w * 0.36f
        val lensHeight = h * 0.28f
        val lensTop = h * 0.38f
        val leftLensX = w * 0.10f
        val rightLensX = w * 0.54f

        drawRoundRect(
            color = tint,
            topLeft = Offset(leftLensX, lensTop),
            size = Size(lensWidth, lensHeight),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(rightLensX, lensTop),
            size = Size(lensWidth, lensHeight),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            style = Stroke(width = strokeWidth),
        )
        val bridgePath =
            Path().apply {
                moveTo(leftLensX + lensWidth, lensTop + lensHeight * 0.35f)
                lineTo(rightLensX, lensTop + lensHeight * 0.35f)
                moveTo(leftLensX, lensTop + lensHeight * 0.25f)
                lineTo(w * 0.04f, lensTop - h * 0.06f)
                moveTo(rightLensX + lensWidth, lensTop + lensHeight * 0.25f)
                lineTo(w * 0.96f, lensTop - h * 0.06f)
            }
        drawPath(
            path = bridgePath,
            color = tint,
            style =
                Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
        )
    }
}

@Suppress("FunctionName")
@Composable
private fun GeminiLiveWaveformIcon(
    tint: Color,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier =
            modifier
                .size(24.dp)
                .semantics { this.contentDescription = contentDescription },
    ) {
        val strokeWidth = 2.2.dp.toPx()
        val w = size.width
        val h = size.height
        val centerY = h / 2f
        val bars = listOf(0.25f, 0.55f, 0.85f, 0.55f, 0.25f)
        val spacing = w / (bars.size + 1)

        bars.forEachIndexed { index, heightFraction ->
            val x = spacing * (index + 1)
            val barHalfHeight = (h * heightFraction) / 2f
            drawLine(
                color = tint,
                start = Offset(x, centerY - barHalfHeight),
                end = Offset(x, centerY + barHalfHeight),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}
