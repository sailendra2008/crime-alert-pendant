package com.avinash.crimealertpendant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.avinash.crimealertpendant.ble.BleConnectionState
import com.avinash.crimealertpendant.ble.PendantBleManager
import com.avinash.crimealertpendant.data.EmergencyContact
import com.avinash.crimealertpendant.data.SafetyPreferences
import com.avinash.crimealertpendant.ui.ContactsScreen
import com.avinash.crimealertpendant.ui.HomeScreen
import com.avinash.crimealertpendant.ui.SettingsScreen
import com.avinash.crimealertpendant.util.EmergencyDispatcher
import kotlinx.coroutines.delay

private val Background = Color(0xFFF4F5F7)
private val Ink = Color(0xFF17243A)
private val Muted = Color(0xFF667085)
private val BorderColor = Color(0xFFE2E6EB)

class MainActivity : ComponentActivity() {

    private lateinit var bleManager: PendantBleManager
    private lateinit var preferences: SafetyPreferences
    private lateinit var dispatcher: EmergencyDispatcher

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        preferences = SafetyPreferences(this)
        dispatcher = EmergencyDispatcher(this)
        bleManager = PendantBleManager(this).apply {
            targetDeviceName = preferences.targetDeviceName
        }

        setContent {
            MaterialTheme {
                CrimeAlertApp(
                    bleManager = bleManager,
                    preferences = preferences,
                    dispatcher = dispatcher
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        dispatcher.stopSiren()
        bleManager.disconnect()
    }
}

@Composable
fun CrimeAlertApp(
    bleManager: PendantBleManager,
    preferences: SafetyPreferences,
    dispatcher: EmergencyDispatcher
) {
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    var seconds by remember { mutableIntStateOf(preferences.countdownDuration) }
    var alertActive by remember { mutableStateOf(false) }
    var alertSource by remember { mutableStateOf("Manual Button") }
    var showLocationDialog by remember { mutableStateOf(false) }

    // Emergency Contacts State
    var contacts by remember { mutableStateOf(preferences.getContacts()) }

    // BLE States
    val bleState by bleManager.connectionState.collectAsState()
    val discoveredDevices by bleManager.discoveredDevices.collectAsState()
    val logMessages by bleManager.logMessages.collectAsState()

    // Android Permissions Launcher
    val requiredPermissions = remember {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            bleManager.log("Bluetooth and location permissions granted.")
            bleManager.startScan()
        } else {
            bleManager.log("Some permissions denied. Bluetooth scanning may be restricted.")
            Toast.makeText(context, "Permissions required to connect ESP32-C3 Pendant.", Toast.LENGTH_LONG).show()
        }
    }

    // Auto-prompt permissions on launch if not granted
    LaunchedEffect(Unit) {
        val missing = requiredPermissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(requiredPermissions)
        } else {
            // Auto start scan for ESP32 pendant
            bleManager.startScan()
        }
    }

    // Listen to hardware SOS trigger events coming from ESP32-C3 GPIO4!
    LaunchedEffect(Unit) {
        bleManager.sosTriggerFlow.collect { event ->
            // Triggered from GPIO4 Push Button on ESP32-C3!
            alertSource = event.source
            seconds = preferences.countdownDuration
            alertActive = true

            if (preferences.isVibrationEnabled) {
                dispatcher.triggerVibration()
            }
            if (preferences.isSirenEnabled) {
                dispatcher.startSiren()
            }
        }
    }

    // Countdown Timer logic
    LaunchedEffect(alertActive) {
        if (alertActive) {
            while (alertActive && seconds > 0) {
                delay(1000)
                seconds--
                if (preferences.isVibrationEnabled && seconds > 0) {
                    dispatcher.triggerVibration()
                }
            }

            // Countdown reached 0: Emergency Trigger!
            if (seconds == 0 && alertActive) {
                bleManager.log("🚨 SOS Countdown Expired! Dispatching emergency notifications...")
                if (preferences.isSirenEnabled) {
                    dispatcher.startSiren()
                }
                if (preferences.isAutoSmsEnabled && contacts.isNotEmpty()) {
                    val fullMessage = dispatcher.formatEmergencyMessage(preferences.emergencyMessage)
                    dispatcher.sendEmergencySms(contacts, fullMessage)
                    Toast.makeText(context, "Emergency SMS dispatched to ${contacts.size} contacts!", Toast.LENGTH_LONG).show()
                }
            }
        } else {
            dispatcher.stopSiren()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            // Main Content Area based on Selected Tab
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when (selectedTab) {
                    0 -> HomeScreen(
                        alertActive = alertActive,
                        alertSource = alertSource,
                        seconds = seconds,
                        bleState = bleState,
                        contactsCount = contacts.size,
                        locationString = dispatcher.getLastKnownLocation()?.let {
                            "Lat: ${String.format("%.4f", it.latitude)}, Lon: ${String.format("%.4f", it.longitude)}"
                        } ?: "GPS Location Active",
                        onSosClick = {
                            if (!alertActive) {
                                alertSource = "App Screen Button"
                                seconds = preferences.countdownDuration
                                alertActive = true
                                if (preferences.isVibrationEnabled) dispatcher.triggerVibration()
                                if (preferences.isSirenEnabled) dispatcher.startSiren()
                            }
                        },
                        onCancelAlert = {
                            alertActive = false
                            seconds = preferences.countdownDuration
                            dispatcher.stopSiren()
                            bleManager.log("SOS Alert cancelled by user.")
                        },
                        onNavigateToContacts = { selectedTab = 1 },
                        onNavigateToSettings = { selectedTab = 2 },
                        onConnectPendant = {
                            val missing = requiredPermissions.any {
                                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing) {
                                permissionLauncher.launch(requiredPermissions)
                            } else {
                                bleManager.startScan()
                            }
                        },
                        onShowLocationModal = { showLocationDialog = true }
                    )
                    1 -> ContactsScreen(
                        contacts = contacts,
                        onAddContact = { newContact ->
                            val updated = contacts + newContact
                            contacts = updated
                            preferences.saveContacts(updated)
                        },
                        onDeleteContact = { toDelete ->
                            val updated = contacts.filterNot { it.id == toDelete.id }
                            contacts = updated
                            preferences.saveContacts(updated)
                        },
                        onCallContact = { phone ->
                            dispatcher.initiateEmergencyCall(phone)
                        }
                    )
                    2 -> SettingsScreen(
                        prefs = preferences,
                        bleState = bleState,
                        discoveredDevices = discoveredDevices,
                        logMessages = logMessages,
                        onStartScan = {
                            val missing = requiredPermissions.any {
                                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                            }
                            if (missing) {
                                permissionLauncher.launch(requiredPermissions)
                            } else {
                                bleManager.startScan()
                            }
                        },
                        onStopScan = { bleManager.stopScan() },
                        onConnectDevice = { device -> bleManager.connectToDevice(device) },
                        onDisconnect = { bleManager.disconnect() },
                        onTestGpio4Click = { bleManager.simulateGpio4SosClick() }
                    )
                }
            }

            HorizontalDivider(color = BorderColor)

            // Bottom Navigation Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .background(Color.White),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    Pair("Home", "🏠"),
                    Pair("Contacts", "👥"),
                    Pair("Settings", "⚙️")
                ).forEachIndexed { index, (label, icon) ->
                    val isSelected = selectedTab == index
                    TextButton(
                        onClick = { selectedTab = index }
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(icon, fontSize = 16.sp)
                            Text(
                                label,
                                color = if (isSelected) Ink else Muted,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }

    // Location Detail Modal
    if (showLocationDialog) {
        val loc = dispatcher.getLastKnownLocation()
        AlertDialog(
            onDismissRequest = { showLocationDialog = false },
            title = { Text("Real-Time Device Location", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    if (loc != null) {
                        Text("Latitude: ${loc.latitude}", fontWeight = FontWeight.SemiBold)
                        Text("Longitude: ${loc.longitude}", fontWeight = FontWeight.SemiBold)
                        Text("Accuracy: ±${loc.accuracy} meters", fontSize = 12.sp, color = Muted)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "This coordinate link will be dispatched to your emergency contacts when the ESP32-C3 pendant SOS button is triggered.",
                            fontSize = 12.sp,
                            color = Muted
                        )
                    } else {
                        Text(
                            "Location services or GPS permissions are currently settling. Ensure Location is enabled in phone settings.",
                            fontSize = 13.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { showLocationDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Ink)
                ) {
                    Text("Close")
                }
            }
        )
    }
}