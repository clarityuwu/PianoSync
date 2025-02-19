package io.pianosync.midi.data.manager

import android.content.Context
import android.media.midi.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MidiConnectionManager private constructor(private val context: Context) {
    private val midiManager: MidiManager = context.getSystemService(Context.MIDI_SERVICE) as MidiManager
    private var currentDevice: MidiDevice? = null
    private var midiReceiver: MidiReceiver? = null
    private var currentDeviceInfo: MidiDeviceInfo? = null

    @Volatile
    private var midiInputPort: MidiInputPort? = null
    private var midiOutputPort: MidiOutputPort? = null

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _pressedKeys = MutableStateFlow<Set<Int>>(emptySet())
    val pressedKeys: StateFlow<Set<Int>> = _pressedKeys.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            Log.d("MidiConnection", "Device added: ${device.properties}")
            handleDeviceConnection(device)
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (currentDeviceInfo == device) {
                closeCurrentDevice()
            }
        }
    }

    fun initialize() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            midiManager.registerDeviceCallback(
                deviceCallback,
                Handler(Looper.getMainLooper())
            )
        }
        checkExistingDevices()
    }

    private fun handleDeviceConnection(deviceInfo: MidiDeviceInfo) {
        Log.d("MidiConnection", "Handling device connection")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && context is ComponentActivity) {
            try {
                openDevice(deviceInfo)
            } catch (e: SecurityException) {
                _errorMessage.value = "Permission denied: ${e.message}"
                _isConnected.value = false
            }
        } else {
            openDevice(deviceInfo)
        }
    }

    private fun openDevice(deviceInfo: MidiDeviceInfo) {
        midiManager.openDevice(
            deviceInfo,
            { device ->
                if (device == null) {
                    _errorMessage.value = "Failed to open device"
                    return@openDevice
                }
                currentDevice = device
                currentDeviceInfo = deviceInfo
                setupMidiInput(device)
            },
            Handler(Looper.getMainLooper())
        )
    }

    @Synchronized
    fun getMidiInputPort(): MidiInputPort? {
        if (midiInputPort == null) {
            Log.w("MidiConnection", "Input port is null, attempting to reopen")
            currentDevice?.let { device ->
                try {
                    midiInputPort = device.openInputPort(0)
                    Log.d("MidiConnection", "Reopened input port: ${midiInputPort != null}")
                } catch (e: Exception) {
                    Log.e("MidiConnection", "Failed to reopen input port", e)
                }
            }
        }
        return midiInputPort
    }

    private fun setupMidiInput(device: MidiDevice) {
        try {
            Log.d("MidiConnection", "Setting up MIDI input for device: ${device.info.properties}")

            // Close existing ports if any
            midiOutputPort?.close()
            midiInputPort?.close()

            // Open new ports
            midiOutputPort = device.openOutputPort(0)
            midiInputPort = device.openInputPort(0)

            if (midiOutputPort == null) {
                _errorMessage.value = "Could not open MIDI output port"
                Log.e("MidiConnection", "No output port available")
                return
            }

            if (midiInputPort == null) {
                _errorMessage.value = "Could not open MIDI input port"
                Log.e("MidiConnection", "No input port available")
                return
            }

            midiReceiver = object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    if (count > 2 && msg[offset].toInt() and 0xF0 == 0x90) { // Note On
                        val note = msg[offset + 1].toInt()
                        val velocity = msg[offset + 2].toInt()

                        if (velocity > 0) {
                            _pressedKeys.value = _pressedKeys.value + note
                        } else {
                            _pressedKeys.value = _pressedKeys.value - note
                        }
                    }
                }
            }

            midiOutputPort?.connect(midiReceiver)
            _isConnected.value = true
            _errorMessage.value = null
            Log.d("MidiConnection", "MIDI setup completed successfully with input port: ${midiInputPort != null}")

        } catch (e: Exception) {
            _errorMessage.value = "Error setting up MIDI: ${e.message}"
            Log.e("MidiConnection", "Error in setupMidiInput", e)
            e.printStackTrace()
        }
    }

    private fun checkExistingDevices() {
        val devices = midiManager.devices
        Log.d("MidiConnection", "Found ${devices.size} MIDI devices")

        devices.forEach { device ->
            Log.d("MidiConnection", """
            Device Info:
            - Manufacturer: ${device.properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER)}
            - Product: ${device.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)}
            - Input Ports: ${device.inputPortCount}
            - Output Ports: ${device.outputPortCount}
        """.trimIndent())
        }

        devices.firstOrNull { device ->
            device.outputPortCount > 0 && device.inputPortCount > 0
        }?.let { device ->
            Log.d("MidiConnection", "Selected device with ${device.outputPortCount} output ports and ${device.inputPortCount} input ports")
            handleDeviceConnection(device)
        }
    }

    private fun closeCurrentDevice() {
        try {
            midiReceiver?.let { receiver ->
                midiOutputPort?.disconnect(receiver)
            }
            midiOutputPort?.close()
            midiInputPort?.close()
            currentDevice?.close()
        } catch (e: Exception) {
            _errorMessage.value = "Error closing device: ${e.message}"
            e.printStackTrace()
        } finally {
            midiInputPort = null
            midiOutputPort = null
            midiReceiver = null
            currentDevice = null
            currentDeviceInfo = null
            _isConnected.value = false
            _pressedKeys.value = emptySet()
        }
    }

    fun cleanup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            midiManager.unregisterDeviceCallback(deviceCallback)
        }
        closeCurrentDevice()
    }

    companion object {
        @Volatile
        private var INSTANCE: MidiConnectionManager? = null

        fun getInstance(context: Context): MidiConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: MidiConnectionManager(context).also { INSTANCE = it }
            }
        }
    }
}