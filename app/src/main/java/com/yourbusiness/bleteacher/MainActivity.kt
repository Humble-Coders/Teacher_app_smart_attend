package com.yourbusiness.bleteacher

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.yourbusiness.bleteacher.ui.theme.BLETeacherTheme
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.*
import kotlin.experimental.and

class MainActivity : ComponentActivity() {


    // Firestore variables
    private val firestore = FirebaseFirestore.getInstance()
    private var headcountListener: ListenerRegistration? = null
    private var currentHeadcount by mutableStateOf(0)
    private var showStudentList by mutableStateOf(false)
    private var studentList by mutableStateOf<List<Pair<String, String>>>(emptyList()) // Pair<rollNo, name>

    // Add these with your existing state variables
    private var defaulterCount by mutableStateOf(0)
    private var showDefaulterList by mutableStateOf(false)
    private var defaulterList by mutableStateOf<List<Pair<String, String>>>(emptyList()) // Pair<rollNo, name>
    // Permission handling
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>
    private var isRequestingPermissions = false
    private val BLUETOOTH_ADVERTISE_PERMISSION_REQUEST_CODE = 100

    // Teacher information
    private val teacherName = "Dr. Ishansh"
    private var teacherId = "T123"

    // Subject and room information
    private var roomId = "R204"
    private var subjectCode = "CS101"

    // Demo subject options
    private val subjectOptions = listOf(
        "CS101 - Introduction to Computer Science",
        "CS202 - Data Structures",
        "CS303 - Database Systems",
        "CS404 - Computer Networks",
        "CS505 - Artificial Intelligence"
    )

    // NFC related properties
    private var nfcAdapter: NfcAdapter? = null
    private val TAG = "NFCTeacherApp"
    private var isInWriteMode = false
    private var messageToWrite = ""
    private val NFC_TRIGGER_MESSAGE = "activate this bitch"

    // BLE related properties
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
    private var isBroadcasting = false
    private var currentBroadcastMessage = ""
    private var currentBroadcastPower = "High"

    // State for Bluetooth permissions
    private var hasBluetoothPermissions = false

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onCreate(savedInstanceState: Bundle?) {
        FirebaseApp.initializeApp(this)
        super.onCreate(savedInstanceState)

        // Initialize NFC adapter
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Set up permission launcher
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                Log.d(TAG, "All permissions granted")
                // Update the state if we're in a composable context
                runOnUiThread {
                    hasBluetoothPermissions = true
                }
                // Re-try the operation that needed permissions
                if (!isBroadcasting && currentBroadcastMessage.isNotEmpty()) {
                    startBroadcasting(currentBroadcastPower)
                    isBroadcasting = true
                }
            } else {
                // Some permissions were denied
                val deniedPermissions = permissions.filter { !it.value }.keys.joinToString(", ")
                Log.d(TAG, "Some permissions denied: $deniedPermissions")
                Toast.makeText(
                    this,
                    "Some permissions were denied. The app may not function correctly.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        setContent {
            BLETeacherTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ModernAttendanceUI()
                }
            }
        }
    }

    private fun setupFirestoreListener(subjectCode: String) {
        val dateFormat = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
        val todayDate = dateFormat.format(Date())

        // Clear previous listeners
        headcountListener?.remove()

        // Listen for regular attendance
        val attendanceRef = firestore
            .collection("Subjects")
            .document(subjectCode)
            .collection(todayDate)

        headcountListener = attendanceRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Attendance listen failed.", error)
                return@addSnapshotListener
            }

            // Filter out the Defaulters document
            val validStudents = snapshot?.documents?.filter { it.id != "Defaulters" }

            currentHeadcount = validStudents?.size ?: 0
            val students = mutableListOf<Pair<String, String>>()
            validStudents?.forEach { doc ->
                val name = doc.getString("name") ?: "Unknown"
                students.add(doc.id to name)
            }
            studentList = students
        }

        // Listener for defaulters remains the same
        val defaultersRef = firestore
            .collection("Subjects")
            .document(subjectCode)
            .collection(todayDate)
            .document("Defaulters")

        defaultersRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Defaulters listen failed.", error)
                return@addSnapshotListener
            }

            val defaulters = mutableListOf<Pair<String, String>>()
            snapshot?.data?.forEach { (rollNo, name) ->
                if (rollNo != "name") {  // Skip the 'name' field if it exists
                    defaulters.add(rollNo to (name as? String ?: "Unknown"))
                }
            }

            defaulterCount = defaulters.size
            defaulterList = defaulters
        }
    }

    @Composable
    private fun DefaulterDisplay() {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Defaulter count display
            Text(
                text = "Potential Defaulters: $defaulterCount",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable { showDefaulterList = !showDefaulterList }
            )

            // Defaulter list when expanded
            if (showDefaulterList && defaulterList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "Potential Defaulters:",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 4.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )

                        defaulterList.forEach { (rollNo, name) ->
                            Text(
                                text = "$rollNo - $name",
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun HeadcountDisplay() {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Headcount display
            Text(
                text = "Current Headcount: $currentHeadcount",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable { showStudentList = !showStudentList }
            )

            // Student list when expanded
            if (showStudentList && studentList.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Text(
                            text = "Present Students:",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 4.dp))



                        studentList.forEach { (rollNo, name) ->
                            Text(
                                text = "$rollNo - $name",
                                modifier = Modifier.padding(vertical = 2.dp))

                        }
                    }
                }
            }
        }
    }

    @Composable
    fun ModernAttendanceUI() {
        val context = LocalContext.current

        // State variables
        var selectedSubject by remember { mutableStateOf(subjectOptions[0]) }
        var isSubjectDropdownExpanded by remember { mutableStateOf(false) }
        var showDetailsDialog by remember { mutableStateOf(false) }

        // Get current date and time
        val currentDateTime = remember {
            val sdf = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
            sdf.format(Date())
        }

        // Check permissions when component loads
        LaunchedEffect(Unit) {
            val hasPermissions = checkAndRequestPermissions()
            if (hasPermissions) {
                hasBluetoothPermissions = true
            } else {
                Toast.makeText(
                    context,
                    "Required permissions were not granted. Some features may not work.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        if (nfcAdapter == null) {
            // Show a message that NFC is not available
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Nfc,
                    contentDescription = "NFC not available",
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "NFC is not available on this device",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
            return
        }

        // Main UI Content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header Section
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Welcome, $teacherName",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = currentDateTime,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Subject Selector
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = 1.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isSubjectDropdownExpanded = true }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.School,
                                contentDescription = "Subject",
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = selectedSubject,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "Expand"
                        )
                    }

                    DropdownMenu(
                        expanded = isSubjectDropdownExpanded,
                        onDismissRequest = { isSubjectDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        subjectOptions.forEach { subject ->
                            DropdownMenuItem(
                                text = { Text(text = subject) },
                                onClick = {
                                    selectedSubject = subject
                                    // Extract the subject code (e.g., CS101)
                                    subjectCode = subject.split(" - ")[0].trim()
                                    isSubjectDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Main Content - Status and NFC Animation
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Status text
                    Text(
                        text = if (isBroadcasting)
                            "Attendance Device Authenticated"
                        else
                            "Ready for Authentication",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        color = if (isBroadcasting)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Current broadcasting info
                    if (isBroadcasting) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Broadcasting: HC-$subjectCode",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = "Power Level: $currentBroadcastPower",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    } else {
                        Text(
                            text = "Tap on the Attendance Device to authenticate",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    // NFC Animation
                    val infiniteTransition = rememberInfiniteTransition(label = "nfc_animation")
                    val size by infiniteTransition.animateFloat(
                        initialValue = 150f,
                        targetValue = 200f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "size_animation"
                    )

                    Box(
                        modifier = Modifier
                            .size(size.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = CircleShape,
                                spotColor = if (isBroadcasting)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.secondary
                            )
                            .background(
                                brush = Brush.radialGradient(
                                    colors = if (isBroadcasting) {
                                        listOf(
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                                        )
                                    } else {
                                        listOf(
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.7f),
                                            MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                                        )
                                    },
                                ),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Nfc,
                            contentDescription = "NFC Icon",
                            modifier = Modifier.size(80.dp),
                            tint = if (isBroadcasting)
                                MaterialTheme.colorScheme.onPrimary
                            else
                                MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isBroadcasting) {
                HeadcountDisplay()
                Spacer(modifier = Modifier.height(8.dp))
                DefaulterDisplay()
            }

            // Action Buttons
            if (isBroadcasting) {
                Button(
                    onClick = {
                        stopBroadcasting()
                        isBroadcasting = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Stop Authentication",
                        fontSize = 16.sp
                    )
                }
            } else {
                Button(
                    onClick = { showDetailsDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = "Configure Details",
                        fontSize = 16.sp
                    )
                }
            }
        }


        // Details Dialog
        if (showDetailsDialog) {
            AlertDialog(
                onDismissRequest = { showDetailsDialog = false },
                title = { Text("Attendance Details") },
                text = {
                    Column {
                        Text("Enter Teacher ID:")
                        OutlinedTextField(
                            value = teacherId,
                            onValueChange = { teacherId = it },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Enter Room ID:")
                        OutlinedTextField(
                            value = roomId,
                            onValueChange = { roomId = it },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDetailsDialog = false
                            Toast.makeText(
                                this@MainActivity,
                                "Details updated",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    ) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDetailsDialog = false }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.let {
            enableNfcForegroundDispatch()
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onPause() {
        super.onPause()
        nfcAdapter?.let {
            disableNfcForegroundDispatch()
        }

        // Also stop broadcasting if app goes to background
        if (isBroadcasting) {
            stopBroadcasting()
            isBroadcasting = false
        }
    }

    private fun enableNfcForegroundDispatch() {
        try {
            val intent = Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val filters = arrayOf(
                IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
            )

            val techLists = arrayOf(
                arrayOf(Ndef::class.java.name),
                arrayOf(NdefFormatable::class.java.name)
            )

            nfcAdapter?.enableForegroundDispatch(this, pendingIntent, filters, techLists)
        } catch (e: Exception) {
            Log.e(TAG, "Error enabling NFC foreground dispatch", e)
        }
    }

    private fun disableNfcForegroundDispatch() {
        try {
            nfcAdapter?.disableForegroundDispatch(this)
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling NFC foreground dispatch", e)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleNfcIntent(intent)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun handleNfcIntent(intent: Intent) {
        if (NfcAdapter.ACTION_TAG_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_TECH_DISCOVERED == intent.action ||
            NfcAdapter.ACTION_NDEF_DISCOVERED == intent.action) {

            val tag = intent.getParcelableExtra<Tag>(NfcAdapter.EXTRA_TAG)
            tag?.let {
                if (isInWriteMode) {
                    // Write mode
                    writeToNfcTag(tag, messageToWrite)
                    isInWriteMode = false
                    return
                }

                // Read mode
                val tagId = bytesToHexString(it.id)
                Log.d(TAG, "NFC Tag Detected! ID: $tagId")

                // Read any existing NDEF messages
                val tagContent = readNdefMessages(tag)

                // If the specific trigger message is detected, toggle broadcasting
                if (tagContent.contains(NFC_TRIGGER_MESSAGE)) {
                    toggleBroadcasting()
                } else {
                    // Just show the content of the tag
                    Toast.makeText(
                        this,
                        "Tag ID: $tagId\nContent: $tagContent",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleBroadcasting() {
        if (!isBroadcasting) {
            // Start broadcasting using the selected subject code
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                if (!isRequestingPermissions) {
                    isRequestingPermissions = true
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE),
                        BLUETOOTH_ADVERTISE_PERMISSION_REQUEST_CODE
                    )
                }
                return
            }

            startBroadcasting(currentBroadcastPower)
            isBroadcasting = true

            Toast.makeText(
                this,
                "Attendance Device Authenticated",
                Toast.LENGTH_LONG
            ).show()
        } else {
            // Stop broadcasting
            stopBroadcasting()
            isBroadcasting = false

            Toast.makeText(
                this,
                "Authentication stopped",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun writeToNfcTag(tag: Tag, message: String) {
        try {
            Log.d(TAG, "Attempting to write to NFC tag: $message")
            val ndef = Ndef.get(tag)

            if (ndef != null) {
                // Tag is NDEF formatted
                Log.d(TAG, "Tag is NDEF formatted")

                ndef.connect()

                if (!ndef.isWritable) {
                    Log.e(TAG, "Tag is read-only")
                    Toast.makeText(this, "Error: NFC tag is read-only", Toast.LENGTH_SHORT).show()
                    return
                }

                val maxSize = ndef.maxSize
                if (message.toByteArray().size > maxSize) {
                    Log.e(TAG, "Message too large (${message.toByteArray().size} bytes, max $maxSize bytes)")
                    Toast.makeText(this, "Error: Message too large for this tag", Toast.LENGTH_SHORT).show()
                    return
                }

                // Create NDEF message
                val record = NdefRecord.createTextRecord("en", message)
                val ndefMessage = NdefMessage(arrayOf(record))

                // Write to tag
                ndef.writeNdefMessage(ndefMessage)

                // Close connection
                ndef.close()

                Log.d(TAG, "Successfully wrote NDEF message to tag")
                Toast.makeText(this, "Successfully wrote to NFC tag", Toast.LENGTH_SHORT).show()
            } else {
                // Tag is not NDEF formatted
                Log.d(TAG, "Tag is not NDEF formatted, attempting to format")

                val ndefFormatable = NdefFormatable.get(tag)
                if (ndefFormatable != null) {
                    try {
                        ndefFormatable.connect()

                        // Create NDEF message
                        val record = NdefRecord.createTextRecord("en", message)
                        val ndefMessage = NdefMessage(arrayOf(record))

                        // Format and write to tag
                        ndefFormatable.format(ndefMessage)

                        // Close connection
                        ndefFormatable.close()

                        Log.d(TAG, "Successfully formatted and wrote to NFC tag")
                        Toast.makeText(this, "Successfully formatted and wrote to NFC tag", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error formatting NFC tag", e)
                        Toast.makeText(this, "Error formatting NFC tag: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Log.e(TAG, "Tag doesn't support NDEF format")
                    Toast.makeText(this, "Error: Tag doesn't support NDEF format", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to NFC tag", e)
            Toast.makeText(this, "Error writing to NFC tag: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun readNdefMessages(tag: Tag): String {
        val ndef = Ndef.get(tag)
        var content = ""

        if (ndef != null) {
            try {
                ndef.connect()
                val ndefMessage = ndef.cachedNdefMessage
                ndef.close()

                if (ndefMessage != null) {
                    val records = ndefMessage.records
                    for (record in records) {
                        if (record.tnf == NdefRecord.TNF_WELL_KNOWN &&
                            Arrays.equals(record.type, NdefRecord.RTD_TEXT)) {

                            val payload = record.payload
                            val textEncoding = if ((payload[0] and 0x80.toByte()) == 0.toByte()) "UTF-8" else "UTF-16"
                            val languageCodeLength = payload[0] and 0x3F

                            val text = String(
                                payload,
                                languageCodeLength + 1,
                                payload.size - languageCodeLength - 1,
                                Charset.forName(textEncoding)
                            )

                            content = text
                            Log.d(TAG, "NFC tag contains text: $text")
                        }
                    }
                } else {
                    Log.d(TAG, "No NDEF messages found on tag")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading NDEF message", e)
            }
        }

        return content
    }

    private fun bytesToHexString(bytes: ByteArray): String {
        val hexArray = "0123456789ABCDEF".toCharArray()
        val hexChars = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hexChars[i * 2] = hexArray[v ushr 4]
            hexChars[i * 2 + 1] = hexArray[v and 0x0F]
        }
        return String(hexChars)
    }

    // Function to start BLE broadcasting with subject code
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    private fun startBroadcasting(range: String) {

        setupFirestoreListener(subjectCode)
        // Check permissions
        if (!checkAndRequestPermissions()) {
            currentBroadcastPower = range
            return
        }

        // Configure AdvertiseSettings based on selected range
        val advertiseSettings = when (range) {
            "Very High" -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            "High" -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            "Medium" -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            "Low" -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_ULTRA_LOW)
                .setConnectable(false)
                .setTimeout(0)
                .build()

            else -> AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build()
        }

        // Create the HC-SUBJECTCODE broadcast message
        val broadcastMessage = "HC-$subjectCode"
        currentBroadcastMessage = broadcastMessage

        val advertiseData = AdvertiseData.Builder()
            // Include the device name
            .setIncludeDeviceName(true)
            // Add your message as manufacturer-specific data
            // Using Google's manufacturer ID (0x00E0) for testing
            .addManufacturerData(0x00E0, broadcastMessage.toByteArray())
            .build()

        advertiser?.startAdvertising(advertiseSettings, advertiseData, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun stopBroadcasting() {

        headcountListener?.remove()
        headcountListener = null
        currentHeadcount = 0
        studentList = emptyList()

        defaulterCount = 0
        defaulterList = emptyList()
        showDefaulterList = false

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_ADVERTISE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        advertiser?.stopAdvertising(advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            super.onStartSuccess(settingsInEffect)
            Log.d(TAG, "Broadcast started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            super.onStartFailure(errorCode)
            Log.e(TAG, "Broadcast failed with error code: $errorCode")

            runOnUiThread {
                isBroadcasting = false

                val errorMessage = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "Advertising data too large"
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too many advertisers"
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "Advertising already started"
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal error"
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "Feature unsupported"
                    else -> "Unknown error"
                }

                Toast.makeText(
                    this@MainActivity,
                    "Failed to start broadcasting: $errorMessage",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Add this method to your MainActivity class
    private fun checkAndRequestPermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
            false
        } else {
            true
        }
    }

    // Handle permissions result
    @RequiresPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        isRequestingPermissions = false

        if (requestCode == BLUETOOTH_ADVERTISE_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Bluetooth advertising permission granted", Toast.LENGTH_SHORT).show()
                // Try broadcasting again if we were trying to broadcast
                if (!isBroadcasting && currentBroadcastMessage.isNotEmpty()) {
                    startBroadcasting(currentBroadcastPower)
                    isBroadcasting = true
                }
            } else {
                Toast.makeText(this, "Bluetooth advertising permission denied - cannot broadcast", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Utility function to check if Bluetooth is enabled
    private fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    // Utility function to check if both NFC and Bluetooth are available and enabled
    private fun areRequiredFeaturesAvailable(): Boolean {
        val nfcAvailable = nfcAdapter != null
        val bluetoothAvailable = bluetoothAdapter != null
        val bluetoothEnabled = bluetoothAdapter?.isEnabled == true
        val bleSupported = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

        val missingFeatures = mutableListOf<String>()

        if (!nfcAvailable) missingFeatures.add("NFC")
        if (!bluetoothAvailable) missingFeatures.add("Bluetooth")
        if (bluetoothAvailable && !bluetoothEnabled) missingFeatures.add("Bluetooth is disabled")
        if (!bleSupported) missingFeatures.add("Bluetooth LE")

        if (missingFeatures.isNotEmpty()) {
            val message = "Missing features: ${missingFeatures.joinToString(", ")}"
            Log.e(TAG, message)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            return false
        }

        return true
    }
}