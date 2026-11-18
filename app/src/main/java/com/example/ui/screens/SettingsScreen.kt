package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.AssistantLanguage
import com.example.device.PhoneAppBridge
import com.example.device.VoiceAssistantManager
import com.example.ui.theme.MyraCyanPrimary
import com.example.ui.theme.MyraVioletSecondary
import com.example.ui.viewmodel.JarvisViewModel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: JarvisViewModel,
    voiceManager: VoiceAssistantManager,
    phoneAppBridge: PhoneAppBridge,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val settings by viewModel.localSettings.collectAsStateWithLifecycle()
    val availableVoices by voiceManager.availableVoices.collectAsStateWithLifecycle()

    var installedApps by remember(phoneAppBridge) {
        mutableStateOf(phoneAppBridge.getAllInstalledApps())
    }
    var showAppsListPage by remember { mutableStateOf(false) }
    var appSearchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(installedApps, appSearchQuery) {
        val q = appSearchQuery.trim().lowercase()
        if (q.isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
        }
    }

    if (showAppsListPage) {
        BackHandler { showAppsListPage = false }
        AllInstalledAppsPage(
            installedApps = installedApps,
            filteredApps = filteredApps,
            searchQuery = appSearchQuery,
            onSearchQueryChange = { appSearchQuery = it },
            onRefreshApps = {
                installedApps = phoneAppBridge.refreshInstalledApps()
            },
            onOpenApp = { app ->
                val result = phoneAppBridge.openApp(app)
                scope.launch { snackbarHostState.showSnackbar(result.verificationMessage) }
            },
            onBack = { showAppsListPage = false },
            modifier = modifier
        )
        return
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_lazy_column"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text(
                text = "Jarvis Control Center",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "اردو زبان، آواز، تھیم اور فون ایپس (سب صرف فون میں محفوظ)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Appearance Card
        item {
            SettingsSectionCard(
                title = "ظاہری شکل (Appearance)",
                icon = Icons.Rounded.DarkMode
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Futuristic Dark Mode", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = if (settings.darkTheme) "گہری نیلی اور جامنی روشنی والا ڈارک موڈ" else "روشن لائٹ موڈ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.darkTheme,
                        onCheckedChange = { enabled ->
                            viewModel.updateSettings { it.copy(darkTheme = enabled) }
                        },
                        modifier = Modifier.testTag("dark_theme_switch")
                    )
                }
            }
        }

        // Language Selection
        item {
            SettingsSectionCard(
                title = "زبان (Language)",
                icon = Icons.Rounded.Language
            ) {
                Text(
                    text = "Jarvis کے جواب دینے اور بولنے کی زبان منتخب کریں (ڈیفالٹ: اردو)۔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistantLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = settings.preferredLanguage == lang.storageValue,
                            onClick = {
                                viewModel.updateSettings { it.copy(preferredLanguage = lang.storageValue) }
                            },
                            label = { Text(lang.displayLabel) }
                        )
                    }
                }
            }
        }

        // Voice Selection & Audio Controls
        item {
            SettingsSectionCard(
                title = "آواز اور اسپیچ (Voice & TTS)",
                icon = Icons.Rounded.RecordVoiceOver
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("آواز میوٹ کریں (Mute Voice)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "ہر جواب کے بعد Jarvis خودکار طریقے سے آواز میں سنائے گا (بند کرنے کے لیے آن کریں)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = settings.isMuted,
                        onCheckedChange = { muted ->
                            if (muted) voiceManager.stopSpeaking()
                            viewModel.updateSettings { it.copy(isMuted = muted) }
                        },
                        modifier = Modifier.testTag("settings_mute_switch")
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "بولنے کی رفتار: ${(settings.voiceSpeechRate * 10).roundToInt() / 10f}x",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = settings.voiceSpeechRate,
                    onValueChange = { rate ->
                        viewModel.updateSettings { it.copy(voiceSpeechRate = rate) }
                    },
                    valueRange = 0.6f..1.8f
                )

                Text(
                    text = "آواز کی پچ (Pitch): ${(settings.voicePitch * 10).roundToInt() / 10f}x",
                    style = MaterialTheme.typography.labelLarge
                )
                Slider(
                    value = settings.voicePitch,
                    onValueChange = { pitch ->
                        viewModel.updateSettings { it.copy(voicePitch = pitch) }
                    },
                    valueRange = 0.7f..1.5f
                )

                Button(
                    onClick = {
                        val lang = AssistantLanguage.fromStorageValue(settings.preferredLanguage)
                        voiceManager.speak(
                            rawText = "السلام علیکم! میں جاروس ہوں، آپ کا ذاتی مصنوعی ذہانت اسسٹنٹ۔ تمام نظام تیار ہیں۔",
                            language = lang,
                            speechRate = settings.voiceSpeechRate,
                            pitch = settings.voicePitch,
                            selectedVoiceName = settings.selectedVoiceName,
                            isMuted = false
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag("test_voice_button")
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Jarvis کی آواز ٹیسٹ کریں")
                }

                if (availableVoices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("دستیاب سسٹم آوازیں", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(6.dp))
                    availableVoices.take(8).forEach { voice ->
                        val selected = settings.selectedVoiceName == voice.name
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.updateSettings { it.copy(selectedVoiceName = voice.name) }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = {
                                    viewModel.updateSettings { it.copy(selectedVoiceName = voice.name) }
                                }
                            )
                            Column {
                                Text(voice.displayLabel, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = voice.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Installed Apps List Page Entry
        item {
            SettingsSectionCard(
                title = "ایپس کی فہرست / Installed Apps (${installedApps.size})",
                icon = Icons.Rounded.Apps
            ) {
                Text(
                    text = "فون میں موجود تمام ایپس کی مکمل فہرست (نام اور package name) دیکھیں۔",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = {
                        installedApps = phoneAppBridge.refreshInstalledApps()
                        showAppsListPage = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("open_apps_list_page_button")
                ) {
                    Icon(Icons.Rounded.Apps, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Show installed apps")
                }
            }
        }
    }
}

@Composable
private fun AllInstalledAppsPage(
    installedApps: List<com.example.device.InstalledLaunchableApp>,
    filteredApps: List<com.example.device.InstalledLaunchableApp>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onRefreshApps: () -> Unit,
    onOpenApp: (com.example.device.InstalledLaunchableApp) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("apps_list_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "واپس Settings"
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Column {
                    Text(
                        text = "ایپس کی فہرست",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "کل انسٹال شدہ ایپس: ${installedApps.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onRefreshApps,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("refresh_apps_list_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "فہرست تازہ کریں"
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("ایپ تلاش کریں...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("installed_apps_search")
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredApps.isEmpty()) {
            Text(
                text = "کوئی ایپ نہیں ملی۔",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("all_installed_apps_list"),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(filteredApps, key = { _, app -> app.packageName }) { idx, appInfo ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                text = appInfo.label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = appInfo.packageName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        OutlinedButton(
                            onClick = { onOpenApp(appInfo) },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("کھولیں")
                        }
                    }
                    if (idx < filteredApps.lastIndex) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.78f)),
        border = BorderStroke(1.dp, MyraVioletSecondary.copy(alpha = 0.28f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MyraCyanPrimary.copy(alpha = 0.16f)
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MyraCyanPrimary,
                        modifier = Modifier.padding(6.dp).size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
