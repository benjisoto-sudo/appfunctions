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
package com.example.appfunctions.agent.ui.glasses

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.xr.glimmer.Button
import androidx.xr.glimmer.Card
import androidx.xr.glimmer.GlimmerTheme
import androidx.xr.glimmer.Text
import androidx.xr.glimmer.TitleChip
import androidx.xr.projected.ProjectedDeviceController
import androidx.xr.projected.ProjectedDeviceController.Capability
import androidx.xr.projected.ProjectedDisplayController
import androidx.xr.projected.ProjectedDisplayController.PresentationMode
import androidx.xr.projected.permissions.ProjectedPermissionsRequestParams
import androidx.xr.projected.permissions.ProjectedPermissionsResultContract
import com.example.appfunctions.agent.domain.device.AgentInteractionMode
import com.example.appfunctions.agent.domain.device.DeviceCapabilityRepository
import com.example.appfunctions.agent.domain.device.DeviceLocalToolRegistry
import com.example.appfunctions.agent.domain.device.FormFactor
import com.example.appfunctions.agent.domain.live.LiveSessionState
import com.example.appfunctions.agent.ui.screens.agentdemo.live.LiveVoiceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * Android XR Projected Activity (Entry Point A) for Intelligent Eyewear (Audio & Display Glasses).
 *
 * Follows the official one-activity doctrine (`ProjectedDeviceController` + `ProjectedDisplayController`):
 * - Branches at runtime on `CAPABILITY_VISUAL_UI` and `PresentationMode.VISUALS_ON` so display
 *   glasses with the display off or audio-only glasses seamlessly switch to the pure-black
 *   transparent audio experience.
 * - Uses Jetpack Glimmer (`GlimmerTheme`) with an additive waveguide-safe `#000000` background,
 *   routes audio & camera hardware via `ProjectedContext.createProjectedDeviceContext(this)`,
 *   and exposes all discovered Android `@AppFunction` tools alongside the 7 XR glasses hardware tools.
 */
@AndroidEntryPoint
class GlassesAgentActivity : ComponentActivity() {
    @Inject
    lateinit var deviceCapabilityRepository: DeviceCapabilityRepository

    private val liveVoiceViewModel: LiveVoiceViewModel by viewModels()

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var gestureDetector: GestureDetector

    @Volatile
    private var latestJpegFrameBase64: String? = null

    @Volatile
    private var isCameraStreaming: Boolean = false

    @Volatile
    private var lastFrameEncodedAtMs: Long = 0L

    private var isPausedState by mutableStateOf(false)

    // Default true so the emulator renders Glimmer UI even before a VirtualDevice is registered.
    private var isDisplayCapable by mutableStateOf(true)
    private var areVisualsOn by mutableStateOf(true)

    private val isAudioMode: Boolean
        get() =
            deviceCapabilityRepository.formFactorOverride.value == FormFactor.XR_GLASSES_AUDIO ||
                !(isDisplayCapable && areVisualsOn)

    private val projectedPermissionLauncher:
        ActivityResultLauncher<List<ProjectedPermissionsRequestParams>> =
        registerForActivityResult(ProjectedPermissionsResultContract()) { results ->
            val audioGranted = results[Manifest.permission.RECORD_AUDIO] == true
            if (audioGranted || hasRecordAudioPermission()) {
                initializeHardwareAndStartSession()
            } else {
                Log.w(
                    TAG,
                    "Projected RECORD_AUDIO permission not granted; starting session fallback.",
                )
                initializeHardwareAndStartSession()
            }
        }

    private val fallbackPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            initializeHardwareAndStartSession()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        @OptIn(ExperimentalComposeUiApi::class)
        ComposeUiFlags.isInitialFocusOnFocusableAvailable = true

        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            try {
                val deviceController = ProjectedDeviceController.create(this@GlassesAgentActivity)
                isDisplayCapable =
                    deviceController.capabilities.contains(Capability.CAPABILITY_VISUAL_UI)
                Log.i(TAG, "Glasses display capable (CAPABILITY_VISUAL_UI): $isDisplayCapable")

                val displayController = ProjectedDisplayController.create(this@GlassesAgentActivity)
                displayController.addPresentationModeChangedListener { flags ->
                    areVisualsOn = flags.hasPresentationMode(PresentationMode.VISUALS_ON)
                    Log.i(TAG, "Glasses visuals on (VISUALS_ON): $areVisualsOn")
                }
            } catch (e: Exception) {
                // Expected under glasses-direct preview or when GlassesCore pairing is absent.
                Log.d(TAG, "ProjectedDeviceController/ProjectedDisplayController fallback: ${e.message}")
            }
        }

        gestureDetector =
            GestureDetector(
                this,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                        togglePause()
                        return true
                    }
                },
            )

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!isPausedState) {
                        pauseSession("temple_swipe_down")
                    } else {
                        liveVoiceViewModel.stopSession()
                        finish()
                    }
                }
            },
        )

        setContent {
            val formFactorOverride by
                deviceCapabilityRepository.formFactorOverride.collectAsStateWithLifecycle()
            val audioOnlyMode =
                formFactorOverride == FormFactor.XR_GLASSES_AUDIO ||
                    !(isDisplayCapable && areVisualsOn)

            GlimmerTheme {
                if (!audioOnlyMode) {
                    GlassesHudScreen(
                        viewModel = liveVoiceViewModel,
                        isPaused = isPausedState,
                        onTogglePause = { togglePause() },
                        onExit = {
                            liveVoiceViewModel.stopSession()
                            finish()
                        },
                    )
                } else {
                    // Audio glasses or display sleeping: keep lens 100% transparent (#000000)
                    // while temple touchpad tap toggles mute/resume.
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .clickable { togglePause() },
                    )
                }
            }
        }

        requestGlassesPermissionsIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        initializeHardwareAndStartSession()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (::gestureDetector.isInitialized && gestureDetector.onTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestGlassesPermissionsIfNeeded() {
        if (hasRecordAudioPermission() && hasCameraPermission()) {
            initializeHardwareAndStartSession()
            return
        }
        try {
            val params =
                ProjectedPermissionsRequestParams(
                    permissions =
                        listOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.CAMERA,
                        ),
                    rationale =
                        "Microphone and camera access allow Gemini Live on your glasses to " +
                            "hear your voice commands, see what you're looking at, and run " +
                            "AppFunctions.",
                )
            projectedPermissionLauncher.launch(listOf(params))
        } catch (e: Exception) {
            Log.w(TAG, "Falling back to standard permission request: ${e.message}")
            fallbackPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.CAMERA,
                ),
            )
        }
    }

    private fun initializeHardwareAndStartSession() {
        isPausedState = false
        val glassesContext =
            deviceCapabilityRepository.resolveHardwareContext(
                activityContext = this,
                isProjectedSurface = true,
            )
        val hostContext = deviceCapabilityRepository.resolveHostContext(this)
        if (hasCameraPermission()) {
            startCameraSnapshotAnalyzer(glassesContext)
        }

        val targetPackageName = intent?.getStringExtra(EXTRA_TARGET_PACKAGE_NAME)
        val localToolRegistry = buildGlassesToolRegistry(glassesContext)
        val resolvedFormFactor =
            if (isAudioMode) FormFactor.XR_GLASSES_AUDIO else FormFactor.XR_GLASSES_DISPLAY
        val resolvedMode =
            if (isAudioMode) AgentInteractionMode.LIVE_VOICE else AgentInteractionMode.LIVE_MULTIMODAL
        val systemInstruction =
            deviceCapabilityRepository.buildSystemInstruction(
                formFactor = resolvedFormFactor,
                mode = resolvedMode,
            )

        liveVoiceViewModel.startSession(
            targetPackageName = targetPackageName,
            systemInstruction = systemInstruction,
            hardwareContext = glassesContext,
            hostContext = hostContext,
            localToolRegistry = localToolRegistry,
            onSetupComplete = {
                delay(400L)
                latestJpegFrameBase64?.let { liveVoiceViewModel.sendVideoFrame(it) }
            },
        )
    }

    private fun buildGlassesToolRegistry(glassesContext: Context): DeviceLocalToolRegistry {
        return DeviceLocalToolRegistry().apply {
            registerStandardGlassesTools(
                onSnapFrame = { _ ->
                    val frame = latestJpegFrameBase64
                    if (frame != null) {
                        liveVoiceViewModel.sendVideoFrame(frame)
                        true
                    } else {
                        false
                    }
                },
                onUpdateHudCard = { title, primaryText, subtitleHint ->
                    val combinedSubtitle =
                        if (subtitleHint.isNotBlank()) {
                            "$primaryText\n$subtitleHint"
                        } else {
                            primaryText
                        }
                    mainHandler.postDelayed(
                        {
                            liveVoiceViewModel.updateHudCard(title, combinedSubtitle)
                        },
                        HUD_CARD_DELAY_MS,
                    )
                },
                onAdjustVolume = { direction, steps ->
                    adjustHardwareVolume(glassesContext, direction, steps)
                },
                onPauseSession = { reason ->
                    pauseSession(reason)
                },
                onEndSession = { reason ->
                    mainHandler.post {
                        liveVoiceViewModel.updateHudCard(
                            "Session Complete",
                            "Closing glasses session ($reason)...",
                        )
                    }
                    mainHandler.postDelayed(
                        {
                            liveVoiceViewModel.stopSession()
                            finish()
                        },
                        END_SESSION_DELAY_MS,
                    )
                },
                onSetCameraStreaming = { enabled ->
                    isCameraStreaming = enabled
                    if (enabled) {
                        "Continuous 1 FPS camera streaming enabled on glasses."
                    } else {
                        "Continuous camera streaming disabled; switched to on-demand snapshots."
                    }
                },
                onGetHardwareStatus = {
                    val activeFormFactor =
                        if (isAudioMode) {
                            FormFactor.XR_GLASSES_AUDIO.name
                        } else {
                            FormFactor.XR_GLASSES_DISPLAY.name
                        }
                    mapOf(
                        "formFactor" to activeFormFactor,
                        "displayCapable" to isDisplayCapable.toString(),
                        "visualsOn" to areVisualsOn.toString(),
                        "audioOnlyMode" to isAudioMode.toString(),
                        "cameraStreaming" to isCameraStreaming.toString(),
                        "isPaused" to isPausedState.toString(),
                        "hasLatestFrame" to (latestJpegFrameBase64 != null).toString(),
                    )
                },
                onTogglePauseState = { paused, reason ->
                    if (paused) {
                        pauseSession(reason)
                    } else {
                        resumeSession()
                    }
                },
            )
        }
    }

    private fun adjustHardwareVolume(
        glassesContext: Context,
        direction: String,
        steps: Int,
    ): String {
        return try {
            val audioManager =
                glassesContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val normalized = direction.uppercase()
            if (normalized == "MAX") {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxVol, 0)
            } else {
                val flag =
                    when (normalized) {
                        "UP" -> AudioManager.ADJUST_RAISE
                        "DOWN" -> AudioManager.ADJUST_LOWER
                        "MUTE" -> AudioManager.ADJUST_MUTE
                        "UNMUTE" -> AudioManager.ADJUST_UNMUTE
                        else -> AudioManager.ADJUST_SAME
                    }
                repeat(steps.coerceIn(1, 5)) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, flag, 0)
                }
            }
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            "Adjusted glasses speaker volume ($normalized) to $currentVol/$maxVol."
        } catch (e: Exception) {
            "Volume adjustment error: ${e.message}"
        }
    }

    private fun startCameraSnapshotAnalyzer(glassesContext: Context) {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(glassesContext)
            cameraProviderFuture.addListener(
                {
                    try {
                        val cameraProvider = cameraProviderFuture.get()
                        val resolutionSelector =
                            ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(640, 480),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER,
                                    ),
                                )
                                .build()

                        val imageAnalysis =
                            ImageAnalysis.Builder()
                                .setResolutionSelector(resolutionSelector)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                .build()

                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            encodeFrameToJpegBase64(imageProxy)
                        }

                        val selector =
                            when {
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                                    CameraSelector.DEFAULT_BACK_CAMERA
                                cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                                    CameraSelector.DEFAULT_FRONT_CAMERA
                                else -> null
                            }

                        if (selector != null) {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(this, selector, imageAnalysis)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "CameraX bind skipped on glasses context: ${e.message}")
                    }
                },
                ContextCompat.getMainExecutor(this),
            )
        } catch (e: Exception) {
            Log.w(TAG, "CameraX initialization skipped: ${e.message}")
        }
    }

    private fun encodeFrameToJpegBase64(imageProxy: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameEncodedAtMs < FRAME_THROTTLE_MS) {
                return
            }
            lastFrameEncodedAtMs = now

            val rawBitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val finalBitmap =
                if (rotationDegrees != 0) {
                    val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                    Bitmap.createBitmap(
                        rawBitmap,
                        0,
                        0,
                        rawBitmap.width,
                        rawBitmap.height,
                        matrix,
                        true,
                    )
                } else {
                    rawBitmap
                }
            val outputStream = ByteArrayOutputStream()
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val frame = Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
            latestJpegFrameBase64 = frame

            if (isCameraStreaming && !isPausedState) {
                lifecycleScope.launch {
                    liveVoiceViewModel.sendVideoFrame(frame)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error encoding camera frame: ${e.message}")
        } finally {
            imageProxy.close()
        }
    }

    private fun pauseSession(reason: String = "user_requested_pause") {
        isPausedState = true
        liveVoiceViewModel.setMuted(true)
        liveVoiceViewModel.updateHudCard(
            "Session Paused",
            "Tap temple touchpad to resume ($reason)",
        )
    }

    private fun resumeSession() {
        isPausedState = false
        liveVoiceViewModel.setMuted(false)
        liveVoiceViewModel.updateHudCard(
            "Session Resumed",
            "Listening...",
        )
    }

    private fun togglePause() {
        if (isPausedState) {
            resumeSession()
        } else {
            pauseSession("temple_tap")
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        cameraExecutor.shutdown()
        liveVoiceViewModel.stopSession()
    }

    companion object {
        private const val TAG = "GlassesAgentActivity"
        private const val FRAME_THROTTLE_MS = 1000L
        private const val HUD_CARD_DELAY_MS = 1200L
        private const val END_SESSION_DELAY_MS = 2000L
        const val EXTRA_THREAD_ID = "extra_thread_id"
        const val EXTRA_TARGET_PACKAGE_NAME = "extra_target_package_name"
    }
}

/**
 * Jetpack Glimmer HUD screen for Android XR Display & Audio Glasses.
 *
 * Designed specifically for additive optical waveguides:
 * - `#000000` pure black background renders as transparent on the lens.
 * - `Column` containing `TitleChip` and `Card` anchored to `Alignment.BottomCenter` so the
 *   wearer's central field of view remains unobstructed (Rule 5.3 of `DEVELOPER_GUIDE.md`).
 * - Audio-synchronized `Card` HUD that appears while Gemini speaks or executes an `@AppFunction`,
 *   with a `1200ms` hardware audio decay tail so the card never vanishes mid-sentence.
 */
@Suppress("FunctionName")
@Composable
fun GlassesHudScreen(
    viewModel: LiveVoiceViewModel,
    isPaused: Boolean,
    onTogglePause: () -> Unit,
    onExit: () -> Unit,
) {
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val hudCardState by viewModel.hudCardState.collectAsStateWithLifecycle()
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }

    // Poll hardware audio playback tail (1200ms) so the HUD card stays visible until the last word is spoken
    var isAudioTailActive by remember { mutableStateOf(false) }
    LaunchedEffect(sessionState, hudCardState) {
        while (true) {
            val playing = viewModel.isAudioPlaying()
            val activeState =
                sessionState is LiveSessionState.Speaking ||
                    sessionState is LiveSessionState.ExecutingTool
            isAudioTailActive = playing || activeState
            delay(150L)
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    // TitleChip guideline: <= 3 words, single line
    val statusText =
        when {
            isPaused -> "Session Paused"
            sessionState is LiveSessionState.Connecting -> "Connecting Live..."
            sessionState is LiveSessionState.ExecutingTool -> {
                val tool =
                    (sessionState as LiveSessionState.ExecutingTool)
                        .toolName
                        .substringAfterLast('_')
                "Running $tool"
            }
            sessionState is LiveSessionState.Speaking || isAudioTailActive -> "Gemini Speaking"
            sessionState is LiveSessionState.Error -> "Connection Error"
            else -> "Listening..."
        }

    val showHudCard =
        isPaused ||
            (
                hudCardState != null &&
                    (isAudioTailActive || sessionState is LiveSessionState.ExecutingTool)
            )

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                // Pure #000000 black renders as transparent on optical waveguide lenses
                .background(Color.Black)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        when (event.key) {
                            Key.Back, Key.Escape -> {
                                if (!isPaused) {
                                    onTogglePause()
                                } else {
                                    onExit()
                                }
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                                onTogglePause()
                                true
                            }
                            else -> false
                        }
                    } else {
                        false
                    }
                }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                ) {
                    onTogglePause()
                }
                .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        // Bottom-Centered HUD Column (TitleChip + Card + Temple Hint) keeping central FOV clear
        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TitleChip {
                Text(
                    text = statusText,
                    style = GlimmerTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            AnimatedVisibility(
                visible = showHudCard,
                enter = fadeIn(tween(220)),
                exit = fadeOut(tween(450)),
            ) {
                val (cardTitle, cardSubtitle) =
                    hudCardState ?: ("Session Paused" to "Tap temple touchpad to resume")
                if (isPaused) {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.88f),
                        title = {
                            Text(
                                text = cardTitle,
                                style = GlimmerTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        action = {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onTogglePause) {
                                    Text("Resume")
                                }
                                Button(onClick = onExit) {
                                    Text("Exit")
                                }
                            }
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = cardSubtitle,
                                style = GlimmerTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(0.88f),
                        title = {
                            Text(
                                text = cardTitle,
                                style = GlimmerTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = cardSubtitle,
                                style = GlimmerTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tap temple: Mute/Resume | Swipe down: Pause/Exit",
                style = GlimmerTheme.typography.caption,
                color = Color.White.copy(alpha = 0.65f),
            )
        }
    }
}
