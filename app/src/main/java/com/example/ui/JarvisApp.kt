package com.example.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Web
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Web
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.AssistantLanguage
import com.example.data.repository.LocalUiSettings
import com.example.data.repository.PreferencesDataStore
import com.example.device.PhoneAppBridge
import com.example.device.VoiceAssistantManager
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.DesignStudioScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.WebsiteCreatorScreen
import com.example.ui.theme.MyraTheme
import com.example.ui.viewmodel.DesignStudioViewModel
import com.example.ui.viewmodel.JarvisViewModel
import com.example.ui.viewmodel.WebsiteCreatorViewModel

private enum class JarvisDestination(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    CHAT("chat", "Jarvis", Icons.Rounded.AutoAwesome, Icons.Outlined.AutoAwesome),
    WEBSITE("website", "Web Studio", Icons.Rounded.Web, Icons.Outlined.Web),
    DESIGN("design", "Design", Icons.Rounded.Palette, Icons.Outlined.Palette),
    SETTINGS("settings", "Settings", Icons.Rounded.Settings, Icons.Outlined.Settings)
}

@Composable
fun JarvisApp() {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val dataStore = remember(appContext) { PreferencesDataStore(appContext) }
    val localSettings by dataStore.settingsFlow.collectAsStateWithLifecycle(
        initialValue = LocalUiSettings()
    )

    MyraTheme(darkTheme = localSettings.darkTheme) {
        JarvisWorkspace(dataStore = dataStore)
    }
}

@Composable
private fun JarvisWorkspace(
    dataStore: PreferencesDataStore
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }

    val jarvisViewModel: JarvisViewModel = viewModel(
        factory = JarvisViewModel.provideFactory(
            context = appContext,
            dataStore = dataStore
        )
    )
    val websiteViewModel: WebsiteCreatorViewModel = viewModel(
        factory = WebsiteCreatorViewModel.provideFactory(
            context = appContext,
            dataStore = dataStore
        )
    )
    val designViewModel: DesignStudioViewModel = viewModel(
        factory = DesignStudioViewModel.provideFactory(
            context = appContext,
            dataStore = dataStore
        )
    )

    val phoneAppBridge = remember(appContext) { PhoneAppBridge(appContext) }
    val settings by jarvisViewModel.localSettings.collectAsStateWithLifecycle()

    val voiceManager = remember(appContext) {
        VoiceAssistantManager(
            context = appContext,
            onFinalSpeechResult = { spokenText ->
                jarvisViewModel.stageVoiceInput(spokenText)
            }
        )
    }

    val ttsReady by voiceManager.ttsReady.collectAsStateWithLifecycle()

    LaunchedEffect(ttsReady) {
        if (ttsReady && jarvisViewModel.consumeStartupVoiceGreetingNeeded()) {
            val currentSettings = jarvisViewModel.localSettings.value
            if (!currentSettings.isMuted && !voiceManager.isListening.value) {
                val lang = AssistantLanguage.fromStorageValue(currentSettings.preferredLanguage)
                voiceManager.speak(
                    rawText = VoiceAssistantManager.STARTUP_GREETING,
                    language = lang,
                    speechRate = currentSettings.voiceSpeechRate,
                    pitch = currentSettings.voicePitch,
                    selectedVoiceName = currentSettings.selectedVoiceName,
                    isMuted = currentSettings.isMuted
                )
            }
        }
    }

    // Automatically speak every assistant reply aloud via TTS unless muted
    LaunchedEffect(jarvisViewModel, voiceManager) {
        jarvisViewModel.speakEvents.collect { replyText ->
            val currentSettings = jarvisViewModel.localSettings.value
            val lang = AssistantLanguage.fromStorageValue(currentSettings.preferredLanguage)
            voiceManager.speak(
                rawText = replyText,
                language = lang,
                speechRate = currentSettings.voiceSpeechRate,
                pitch = currentSettings.voicePitch,
                selectedVoiceName = currentSettings.selectedVoiceName,
                isMuted = currentSettings.isMuted
            )
        }
    }

    DisposableEffect(voiceManager) {
        onDispose { voiceManager.release() }
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: JarvisDestination.CHAT.route
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(currentRoute) {
        if (currentRoute != JarvisDestination.CHAT.route) {
            voiceManager.stopLiveConversation()
        }
    }

    LaunchedEffect(settings.isMuted) {
        if (settings.isMuted) {
            voiceManager.setLiveConversationActive(false)
            voiceManager.stopSpeaking()
        }
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(JarvisDestination.CHAT.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 700.dp

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        JarvisDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("nav_${dest.route}")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        JarvisDestination.entries.forEach { dest ->
                            val selected = currentRoute == dest.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateToTab(dest.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                                        contentDescription = dest.label
                                    )
                                },
                                label = { Text(dest.label) },
                                modifier = Modifier.testTag("rail_${dest.route}")
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = JarvisDestination.CHAT.route
                    ) {
                        composable(JarvisDestination.CHAT.route) {
                            ChatScreen(
                                viewModel = jarvisViewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = phoneAppBridge,
                                snackbarHostState = snackbarHostState,
                                onNavigateToSettings = { navigateToTab(JarvisDestination.SETTINGS.route) },
                                onNavigateToWebBuilder = { prompt ->
                                    websiteViewModel.prefillFromChatPrompt(prompt)
                                    navigateToTab(JarvisDestination.WEBSITE.route)
                                },
                                onNavigateToDesignStudio = { prompt ->
                                    designViewModel.prefillFromChatPrompt(prompt)
                                    navigateToTab(JarvisDestination.DESIGN.route)
                                }
                            )
                        }
                        composable(JarvisDestination.WEBSITE.route) {
                            WebsiteCreatorScreen(
                                viewModel = websiteViewModel,
                                snackbarHostState = snackbarHostState
                            )
                        }
                        composable(JarvisDestination.DESIGN.route) {
                            DesignStudioScreen(
                                viewModel = designViewModel,
                                snackbarHostState = snackbarHostState
                            )
                        }
                        composable(JarvisDestination.SETTINGS.route) {
                            SettingsScreen(
                                viewModel = jarvisViewModel,
                                voiceManager = voiceManager,
                                phoneAppBridge = phoneAppBridge,
                                snackbarHostState = snackbarHostState
                            )
                        }
                    }
                }
            }
        }
    }
}
