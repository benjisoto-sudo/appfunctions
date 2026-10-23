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
package com.example.appfunctions.agent

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.appfunctions.agent.ui.screens.agentdemo.AgentDemoScreen
import com.example.appfunctions.agent.ui.screens.agentdemo.ConnectedAppsScreen
import com.example.appfunctions.agent.ui.screens.agentdemo.SettingsScreen
import com.example.appfunctions.agent.ui.screens.debugging.DebuggingScreen
import com.example.appfunctions.agent.ui.screens.main.AppStatus
import com.example.appfunctions.agent.ui.screens.main.MainViewModel
import com.example.appfunctions.agent.ui.screens.main.StatusWarningDialog
import com.example.appfunctions.agent.ui.theme.AppFunctionsAgentTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * The main activity of the application.
 *
 * This activity sets up the navigation host and bottom bar, and checks for required permissions on
 * startup.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private var autoStartLiveVoiceState by mutableStateOf(false)
    private var initialTargetPackageState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.IS_RETAIL) {
            // Only requesting location permission onStart when running for demo purpose
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        enableEdgeToEdge()
        handleLaunchIntent(intent)
        setContent {
            AppFunctionsAgentTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MainScreen(
                        onCloseApp = { finish() },
                        autoStartLiveVoice = autoStartLiveVoiceState,
                        initialTargetPackageName = initialTargetPackageState,
                        onAutoStartLiveVoiceConsumed = { autoStartLiveVoiceState = false },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_START_LIVE_VOICE, false)) {
            autoStartLiveVoiceState = true
        }
        val targetPkg = intent.getStringExtra(EXTRA_TARGET_PACKAGE_NAME)
        if (!targetPkg.isNullOrBlank()) {
            initialTargetPackageState = targetPkg
        }
    }

    companion object {
        const val ROUTE_AGENT_DEMO = "agent_demo?threadId={threadId}"
        const val ARG_THREAD_ID = "threadId"
        const val DEEPLINK_URI_PATTERN = "appfunctions-agent://chat?threadId={threadId}"
        const val EXTRA_INSTRUMENTATION_ERROR = "EXTRA_INSTRUMENTATION_ERROR"
        const val EXTRA_START_LIVE_VOICE = "EXTRA_START_LIVE_VOICE"
        const val EXTRA_TARGET_PACKAGE_NAME = "extra_target_package_name"
    }
}

/**
 * The main composable screen of the application, setting up the navigation host and bottom bar.
 *
 * @param viewModel The view model for this screen.
 */
@Composable
fun MainScreen(
    onCloseApp: () -> Unit,
    autoStartLiveVoice: Boolean = false,
    initialTargetPackageName: String? = null,
    onAutoStartLiveVoiceConsumed: () -> Unit = {},
    viewModel: MainViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(autoStartLiveVoice) {
        if (autoStartLiveVoice) {
            navController.navigate("agent_demo") {
                launchSingleTop = true
            }
        }
    }

    val items = listOf("debugging", "agent_demo", "settings")
    val icons = listOf(Icons.Default.Terminal, Icons.Default.PlayCircle, Icons.Default.Settings)
    val labels =
        listOf(
            stringResource(R.string.nav_debug),
            stringResource(R.string.nav_agent_demo),
            stringResource(R.string.nav_settings),
        )

    if (uiState.showDialog) {
        when (uiState.appStatus) {
            AppStatus.NotSupported -> {
                StatusWarningDialog(
                    title = stringResource(R.string.dialog_not_supported_title),
                    message = stringResource(R.string.dialog_not_supported_message),
                    isError = true,
                    onDismiss = onCloseApp,
                )
            }
            AppStatus.PermissionMissing -> {
                StatusWarningDialog(
                    title = stringResource(R.string.dialog_permission_missing_title),
                    message = stringResource(R.string.dialog_permission_missing_message),
                    isError = true,
                    onDismiss = onCloseApp,
                )
            }
            is AppStatus.InstrumentationFailed -> {
                StatusWarningDialog(
                    title = stringResource(R.string.dialog_instrumentation_failed_title),
                    message = stringResource(R.string.dialog_instrumentation_failed_message),
                    isError = true,
                    onDismiss = onCloseApp,
                )
            }
            else -> {}
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        bottomBar = {
            NavigationBar {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                items.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        icon = { Icon(icons[index], contentDescription = labels[index]) },
                        label = { Text(labels[index]) },
                        selected =
                            currentDestination?.hierarchy?.any {
                                it.route?.startsWith(screen) == true
                            } == true,
                        onClick = {
                            navController.navigate(screen) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination =
                if (autoStartLiveVoice) {
                    MainActivity.ROUTE_AGENT_DEMO
                } else {
                    "debugging"
                },
            modifier = Modifier.padding(innerPadding).consumeWindowInsets(innerPadding),
        ) {
            composable("debugging") { DebuggingScreen() }
            composable(
                route = MainActivity.ROUTE_AGENT_DEMO,
                arguments =
                    listOf(
                        navArgument(MainActivity.ARG_THREAD_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                deepLinks =
                    listOf(navDeepLink { uriPattern = MainActivity.DEEPLINK_URI_PATTERN }),
            ) {
                AgentDemoScreen(
                    autoStartLiveVoice = autoStartLiveVoice,
                    initialTargetPackageName = initialTargetPackageName,
                    onAutoStartLiveVoiceConsumed = onAutoStartLiveVoiceConsumed,
                )
            }
            composable("settings") {
                SettingsScreen(
                    onNavigateToConnectedApps = { navController.navigate("connected_apps") },
                )
            }
            composable("connected_apps") {
                ConnectedAppsScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
