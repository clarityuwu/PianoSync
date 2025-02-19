package io.pianosync.midi

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.pianosync.midi.data.manager.MidiConnectionManager
import io.pianosync.midi.data.model.MidiFile
import io.pianosync.midi.data.repository.MidiFileRepository
import io.pianosync.midi.ui.screens.home.HomeScreen
import io.pianosync.midi.ui.screens.player.MidiPlayerScreen
import io.pianosync.midi.ui.theme.PianoSyncTheme
import java.net.URLDecoder
import java.net.URLEncoder

class MainActivity : ComponentActivity() {
    private lateinit var midiManager: MidiConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize MIDI manager at activity level
        midiManager = MidiConnectionManager.getInstance(applicationContext)
        midiManager.initialize()

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        enableEdgeToEdge()

        setContent {
            PianoSyncTheme {
                val navController = rememberNavController()
                val context = LocalContext.current
                val repository = remember(context) { MidiFileRepository(context) }
                var loadedMidiFiles by remember { mutableStateOf<List<MidiFile>>(emptyList()) }

                // Share midiManager with composables
                CompositionLocalProvider(
                    LocalMidiManager provides midiManager
                ) {
                    LaunchedEffect(repository) {
                        repository.midiFiles.collect { files ->
                            loadedMidiFiles = files
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                onNavigateToPlayer = { midiFile ->
                                    val encodedPath = URLEncoder.encode(midiFile.path, "UTF-8")
                                    navController.navigate("player/$encodedPath")
                                }
                            )
                        }

                        composable(
                            route = "player/{midiFilePath}",
                            arguments = listOf(
                                navArgument("midiFilePath") {
                                    type = NavType.StringType
                                }
                            )
                        ) { backStackEntry ->
                            val encodedPath = backStackEntry.arguments?.getString("midiFilePath") ?: ""
                            val decodedPath = URLDecoder.decode(encodedPath, "UTF-8")

                            val midiFile = loadedMidiFiles.find { file ->
                                val normalizedStoredPath = Uri.decode(file.path)
                                val normalizedDecodedPath = Uri.decode(decodedPath)
                                normalizedStoredPath == normalizedDecodedPath
                            }

                            if (midiFile != null) {
                                MidiPlayerScreen(
                                    midiFile = midiFile,
                                    repository = repository,
                                    onBackPressed = {
                                        navController.popBackStack()
                                    }
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only cleanup MIDI when the activity is actually being destroyed
        if (isFinishing) {
            midiManager.cleanup()
        }
    }
}

// Create a CompositionLocal for MIDI manager
private val LocalMidiManager = compositionLocalOf<MidiConnectionManager> {
    error("No MidiConnectionManager provided")
}

// Extension function to easily access MidiManager in composables
@Composable
fun rememberMidiManager(): MidiConnectionManager = LocalMidiManager.current