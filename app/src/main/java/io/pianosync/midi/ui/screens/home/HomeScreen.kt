package io.pianosync.midi.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PianoOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.pianosync.midi.R
import io.pianosync.midi.data.manager.MidiConnectionManager
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.model.PerformanceRecord
import io.pianosync.midi.data.parser.MidiParser
import io.pianosync.midi.data.repository.MidiFileRepository
import io.pianosync.midi.data.repository.PerformanceRepository
import io.pianosync.midi.ui.screens.home.components.ImportCard
import io.pianosync.midi.ui.screens.home.components.MidiFileCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPlayer: (MidiFile) -> Unit,
    onNavigateToProgress: () -> Unit,
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember(context) { MidiFileRepository(context) }
    val performanceRepository = remember(context) { PerformanceRepository(context) }
    val midiManager = remember { MidiConnectionManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var midiFiles by remember { mutableStateOf<List<MidiFile>>(emptyList()) }
    var performanceData by remember { mutableStateOf<Map<String, List<PerformanceRecord>>>(emptyMap()) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var selectedMidiFile by remember { mutableStateOf<MidiFile?>(null) }

    // Debug mode - toggle this for testing without piano
    var debugMode by remember { mutableStateOf(true) } // Set to true for testing

    val isConnected by midiManager.isConnected.collectAsState()

    // For testing: consider connected if debug mode is on OR actually connected
    val effectivelyConnected = debugMode || isConnected

    // Load saved MIDI files
    LaunchedEffect(repository) {
        repository.midiFiles.collect { files ->
            midiFiles = files
        }
    }

    // Load performance data for all files
    LaunchedEffect(performanceRepository) {
        performanceRepository.performanceHistory.collect { allPerformances ->
            // Group performances by MIDI file path and take recent ones
            performanceData = allPerformances
                .groupBy { it.midiFilePath }
                .mapValues { (_, performances) ->
                    // Sort by timestamp (newest first) and take last 5
                    performances.sortedByDescending { it.timestamp }.take(5)
                }
        }
    }

    LaunchedEffect(effectivelyConnected) {
        if (effectivelyConnected && selectedMidiFile != null) {
            onNavigateToPlayer(selectedMidiFile!!)
            selectedMidiFile = null
            showConnectionDialog = false
        }
    }

    // File picker launcher with permission handling
    val midiFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            try {
                // Take persistent URI permission
                val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(selectedUri, takeFlags)

                context.contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst()) {
                        val fileName = cursor.getString(nameIndex)
                        val originalBpm = MidiParser.extractBPM(context, selectedUri)

                        val newMidiFile = MidiFile(
                            name = fileName,
                            path = selectedUri.toString(),
                            dateImported = System.currentTimeMillis(),
                            originalBpm = originalBpm,
                            currentBpm = originalBpm
                        )

                        midiFiles = (listOf(newMidiFile) + midiFiles).take(10)
                        coroutineScope.launch {
                            repository.saveMidiFiles(midiFiles)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeScreen", "Error importing MIDI file", e)
                e.printStackTrace()
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    // Show debug indicator and connection status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        if (debugMode) {
                            AssistChip(
                                onClick = { debugMode = !debugMode },
                                label = { Text(stringResource(R.string.debug)) },
                                modifier = Modifier.padding(end = 8.dp)
                            )
                        }

                        // Add Progress button
                        IconButton(
                            onClick = onNavigateToProgress
                        ) {
                            Icon(
                                imageVector = Icons.Default.BarChart,
                                contentDescription = stringResource(R.string.progress_tracking),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Add Settings button
                        IconButton(
                            onClick = onNavigateToSettings
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = stringResource(R.string.settings),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Icon(
                            imageVector = if (effectivelyConnected) Icons.Default.Piano else Icons.Default.PianoOff,
                            contentDescription = if (effectivelyConnected) stringResource(R.string.piano_connected) else stringResource(R.string.piano_disconnected),
                            tint = if (effectivelyConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyRow(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            item {
                ImportCard(
                    onImportClick = {
                        midiFileLauncher.launch(arrayOf(
                            "audio/midi",
                            "audio/x-midi",
                            "application/x-midi",
                            "*/*"
                        ))
                    }
                )
            }

            items(midiFiles) { midiFile ->
                // Get performance data for this specific file
                val filePerformances = performanceData[midiFile.path] ?: emptyList()

                MidiFileCard(
                    midiFile = midiFile,
                    recentPerformances = filePerformances, // Pass performance data
                    onClick = {
                        if (effectivelyConnected) {
                            onNavigateToPlayer(midiFile)
                        } else {
                            selectedMidiFile = midiFile
                            showConnectionDialog = true
                        }
                    },
                    onDelete = {
                        midiFiles = midiFiles.filter { it != midiFile }
                        coroutineScope.launch {
                            repository.saveMidiFiles(midiFiles)
                        }
                    }
                )
            }
        }

        // Connection dialog - won't show in debug mode
        if (showConnectionDialog && !effectivelyConnected) {
            Dialog(onDismissRequest = {
                showConnectionDialog = false
                selectedMidiFile = null
            }) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.waiting_for_piano),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.connect_piano_message, selectedMidiFile?.name ?: ""),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            OutlinedButton(
                                onClick = { debugMode = true },
                                modifier = Modifier.padding(end = 8.dp)
                            ) {
                                Text(stringResource(R.string.test_mode))
                            }
                            Button(onClick = {
                                showConnectionDialog = false
                                selectedMidiFile = null
                            }) {
                                Text(stringResource(R.string.dismiss))
                            }
                        }
                    }
                }
            }
        }
    }
}