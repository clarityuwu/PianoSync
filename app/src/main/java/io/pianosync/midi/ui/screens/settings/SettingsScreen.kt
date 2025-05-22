package io.pianosync.midi.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.pianosync.midi.data.model.AppSettings
import io.pianosync.midi.data.model.DifficultyLevel
import io.pianosync.midi.data.repository.SettingsRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackPressed: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    val scope = rememberCoroutineScope()

    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())

    var showDifficultyDialog by remember { mutableStateOf(false) }
    var showOffsetDialog by remember { mutableStateOf(false) }
    var tempOffsetValue by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Gameplay Settings Section
            SettingsSection(title = "Gameplay") {
                // Difficulty Level
                SettingsItem(
                    icon = Icons.Default.Speed,
                    title = "Difficulty Level",
                    subtitle = "${settings.difficultyLevel.displayName} - ${settings.difficultyLevel.description}",
                    onClick = { showDifficultyDialog = true }
                )

                // Key Names Toggle
                SettingsItem(
                    icon = Icons.Default.Label,
                    title = "Show Key Names",
                    subtitle = if (settings.showKeyNames) "Key names shown on easy mode" else "Key names hidden",
                    trailing = {
                        Switch(
                            checked = settings.showKeyNames,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    settingsRepository.updateShowKeyNames(enabled)
                                }
                            }
                        )
                    }
                )
            }

            // Audio Settings Section
            SettingsSection(title = "Audio") {
                // Playback Offset
                SettingsItem(
                    icon = Icons.Default.Sync,
                    title = "Playback Sync Offset",
                    subtitle = "${settings.playbackOffsetMs}ms - Adjust if notes aren't synchronized",
                    onClick = {
                        tempOffsetValue = settings.playbackOffsetMs.toString()
                        showOffsetDialog = true
                    }
                )

                // Metronome Volume
                SettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = "Metronome Volume",
                    subtitle = "${(settings.metronomeVolume * 100).toInt()}%"
                ) {
                    Slider(
                        value = settings.metronomeVolume,
                        onValueChange = { volume ->
                            scope.launch {
                                settingsRepository.updateMetronomeVolume(volume)
                            }
                        },
                        modifier = Modifier.width(120.dp)
                    )
                }
            }

            // About Section
            SettingsSection(title = "About") {
                // GitHub Link
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = "View on GitHub",
                    subtitle = "Source code, issues, and contributions",
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/clarityuwu/PianoSync/tree/dev-android"))
                        context.startActivity(intent)
                    }
                )

                // App Info
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = "PianoSync",
                    subtitle = "Version 0.5.0 - Piano learning companion app"
                )
            }
        }
    }

    // Difficulty Selection Dialog
    if (showDifficultyDialog) {
        DifficultySelectionDialog(
            currentLevel = settings.difficultyLevel,
            onLevelSelected = { level ->
                scope.launch {
                    settingsRepository.updateDifficultyLevel(level)
                }
                showDifficultyDialog = false
            },
            onDismiss = { showDifficultyDialog = false }
        )
    }

    // Offset Adjustment Dialog
    if (showOffsetDialog) {
        OffsetAdjustmentDialog(
            currentOffset = tempOffsetValue,
            onOffsetChanged = { tempOffsetValue = it },
            onSave = {
                tempOffsetValue.toLongOrNull()?.let { offset ->
                    scope.launch {
                        settingsRepository.updatePlaybackOffset(offset.coerceIn(0L, 10000L))
                    }
                }
                showOffsetDialog = false
            },
            onDismiss = { showOffsetDialog = false }
        )
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content
            )
        }
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.selectable(
                        selected = false,
                        onClick = onClick
                    )
                } else Modifier
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        trailing?.invoke()
    }
}

@Composable
fun DifficultySelectionDialog(
    currentLevel: DifficultyLevel,
    onLevelSelected: (DifficultyLevel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Difficulty Level") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp) // Limit height and make scrollable
                    .verticalScroll(rememberScrollState())
            ) {
                DifficultyLevel.values().forEach { level ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = level == currentLevel,
                                onClick = { onLevelSelected(level) }
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = level == currentLevel,
                            onClick = { onLevelSelected(level) }
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Column {
                            Text(
                                text = level.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )

                            Text(
                                text = "${level.description} (${level.correctNoteWindowMs}ms tolerance)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun OffsetAdjustmentDialog(
    currentOffset: String,
    onOffsetChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adjust Playback Sync") },
        text = {
            Column {
                Text(
                    text = "If the falling notes don't align with the audio, adjust this offset. Higher values make notes appear earlier.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                OutlinedTextField(
                    value = currentOffset,
                    onValueChange = { value ->
                        if (value.isEmpty() || value.toLongOrNull() != null) {
                            onOffsetChanged(value)
                        }
                    },
                    label = { Text("Offset (milliseconds)") },
                    placeholder = { Text("2000") },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Recommended: 2000ms for tablets, 4500ms for phones",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = currentOffset.isNotEmpty() && currentOffset.toLongOrNull() != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}