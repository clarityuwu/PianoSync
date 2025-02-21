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
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PianoOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.pianosync.midi.data.manager.MidiConnectionManager
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.parser.MidiParser
import io.pianosync.midi.data.repository.MidiFileRepository
import io.pianosync.midi.ui.screens.home.components.ImportCard
import io.pianosync.midi.ui.screens.home.components.MidiFileCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToPlayer: (MidiFile) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repository = remember(context) { MidiFileRepository(context) }
    val midiManager = remember { MidiConnectionManager.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var midiFiles by remember { mutableStateOf<List<MidiFile>>(emptyList()) }
    var showConnectionDialog by remember { mutableStateOf(false) }
    var selectedMidiFile by remember { mutableStateOf<MidiFile?>(null) }

    val isConnected by midiManager.isConnected.collectAsState()

    // Load saved MIDI files
    LaunchedEffect(repository) {
        repository.midiFiles.collect { files ->
            midiFiles = files
        }
    }

    LaunchedEffect(isConnected) {
        if (isConnected && selectedMidiFile != null) {
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
                            currentBpm = originalBpm // Initialize currentBpm to originalBpm
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
                title = { Text("PianoSync") },
                actions = {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Piano else Icons.Default.PianoOff,
                        contentDescription = if (isConnected) "Piano Connected" else "Piano Disconnected",
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 16.dp)
                    )
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
                MidiFileCard(
                    midiFile = midiFile,
                    onClick = {
                        if (isConnected) {
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

        // Connection dialog shown when needed
        if (showConnectionDialog && !isConnected) {
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
                            text = "Waiting for piano to connect...",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please connect your piano to play ${selectedMidiFile?.name}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = {
                            showConnectionDialog = false
                            selectedMidiFile = null
                        }) {
                            Text("Dismiss")
                        }
                    }
                }
            }
        }
    }
}