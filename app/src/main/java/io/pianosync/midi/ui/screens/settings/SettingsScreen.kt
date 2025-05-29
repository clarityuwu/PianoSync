package io.pianosync.midi.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.pianosync.midi.R
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
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
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
            SettingsSection(title = stringResource(R.string.gameplay)) {
                // Difficulty Level
                SettingsItem(
                    icon = Icons.Default.Speed,
                    title = stringResource(R.string.difficulty_level),
                    subtitle = "${getDifficultyDisplayName(settings.difficultyLevel)} - ${getDifficultyDescription(settings.difficultyLevel)}",
                    onClick = { showDifficultyDialog = true }
                )

                // Key Names Toggle
                SettingsItem(
                    icon = Icons.Default.Label,
                    title = stringResource(R.string.show_key_names),
                    subtitle = if (settings.showKeyNames)
                        stringResource(R.string.key_names_shown)
                    else
                        stringResource(R.string.key_names_hidden),
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
            SettingsSection(title = stringResource(R.string.audio)) {
                // Playback Offset
                SettingsItem(
                    icon = Icons.Default.Sync,
                    title = stringResource(R.string.playback_sync_offset),
                    subtitle = stringResource(R.string.sync_offset_desc, settings.playbackOffsetMs),
                    onClick = {
                        tempOffsetValue = settings.playbackOffsetMs.toString()
                        showOffsetDialog = true
                    }
                )

                // Metronome Volume
                SettingsItem(
                    icon = Icons.Default.VolumeUp,
                    title = stringResource(R.string.metronome_volume),
                    subtitle = stringResource(R.string.volume_percentage, (settings.metronomeVolume * 100).toInt())
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
            SettingsSection(title = stringResource(R.string.about)) {
                // GitHub Link
                SettingsItem(
                    icon = Icons.Default.Code,
                    title = stringResource(R.string.view_on_github),
                    subtitle = stringResource(R.string.github_desc),
                    onClick = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/clarityuwu/PianoSync/tree/dev-android"))
                        context.startActivity(intent)
                    }
                )

                // App Info
                SettingsItem(
                    icon = Icons.Default.Info,
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.app_version)
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
private fun getDifficultyDisplayName(level: DifficultyLevel): String {
    return when (level) {
        DifficultyLevel.EASY -> stringResource(R.string.difficulty_easy)
        DifficultyLevel.MEDIUM -> stringResource(R.string.difficulty_medium)
        DifficultyLevel.HARD -> stringResource(R.string.difficulty_hard)
        DifficultyLevel.EXPERT -> stringResource(R.string.difficulty_expert)
    }
}

@Composable
private fun getDifficultyDescription(level: DifficultyLevel): String {
    return when (level) {
        DifficultyLevel.EASY -> stringResource(R.string.difficulty_easy_desc)
        DifficultyLevel.MEDIUM -> stringResource(R.string.difficulty_medium_desc)
        DifficultyLevel.HARD -> stringResource(R.string.difficulty_hard_desc)
        DifficultyLevel.EXPERT -> stringResource(R.string.difficulty_expert_desc)
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
        title = { Text(stringResource(R.string.select_difficulty_level)) },
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
                                text = getDifficultyDisplayName(level),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )

                            Text(
                                text = stringResource(
                                    R.string.tolerance_format,
                                    getDifficultyDescription(level),
                                    level.correctNoteWindowMs.toInt()
                                ),
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
                Text(stringResource(R.string.close))
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
        title = { Text(stringResource(R.string.adjust_playback_sync)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.sync_adjustment_desc),
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
                    label = { Text(stringResource(R.string.offset_milliseconds)) },
                    placeholder = { Text(stringResource(R.string.offset_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.offset_recommendation),
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
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}