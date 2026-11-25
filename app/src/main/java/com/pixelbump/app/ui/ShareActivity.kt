package com.pixelbump.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pixelbump.app.R
import com.pixelbump.app.nfc.PixelHceService
import com.pixelbump.app.server.PixelWebServer
import java.io.InputStreamReader

enum class TransferDirection {
    SEND,
    RECEIVE
}

enum class QrMode {
    SAME_WIFI,
    HOTSPOT
}

val GoogleSansFontFamily = FontFamily(
    Font(R.font.google_sans_regular, FontWeight.Normal),
    Font(R.font.google_sans_medium, FontWeight.Medium),
    Font(R.font.google_sans_semibold, FontWeight.SemiBold),
    Font(R.font.google_sans_bold, FontWeight.Bold)
)

private val BaseTypography = Typography()
val PixelBumpTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontFamily = GoogleSansFontFamily),
    displayMedium = BaseTypography.displayMedium.copy(fontFamily = GoogleSansFontFamily),
    displaySmall = BaseTypography.displaySmall.copy(fontFamily = GoogleSansFontFamily),
    headlineLarge = BaseTypography.headlineLarge.copy(fontFamily = GoogleSansFontFamily),
    headlineMedium = BaseTypography.headlineMedium.copy(fontFamily = GoogleSansFontFamily),
    headlineSmall = BaseTypography.headlineSmall.copy(fontFamily = GoogleSansFontFamily),
    titleLarge = BaseTypography.titleLarge.copy(fontFamily = GoogleSansFontFamily),
    titleMedium = BaseTypography.titleMedium.copy(fontFamily = GoogleSansFontFamily),
    titleSmall = BaseTypography.titleSmall.copy(fontFamily = GoogleSansFontFamily),
    bodyLarge = BaseTypography.bodyLarge.copy(fontFamily = GoogleSansFontFamily),
    bodyMedium = BaseTypography.bodyMedium.copy(fontFamily = GoogleSansFontFamily),
    bodySmall = BaseTypography.bodySmall.copy(fontFamily = GoogleSansFontFamily),
    labelLarge = BaseTypography.labelLarge.copy(fontFamily = GoogleSansFontFamily),
    labelMedium = BaseTypography.labelMedium.copy(fontFamily = GoogleSansFontFamily),
    labelSmall = BaseTypography.labelSmall.copy(fontFamily = GoogleSansFontFamily)
)

// Android 17 Material 3 Expressive Tonal Palette (Solid, zero glassmorphism)
private object M3Colors {
    val Canvas = Color(0xFF14161D)
    val SurfaceLow = Color(0xFF1D202A)
    val SurfaceHigh = Color(0xFF252936)
    val SurfaceHigher = Color(0xFF2A2E3D)
    val PrimaryContainer = Color(0xFFA8C7FA)
    val OnPrimaryContainer = Color(0xFF041E49)
    val SecondaryContainer = Color(0xFFC4EED0)
    val OnSecondaryContainer = Color(0xFF072711)
    val PlumContainer = Color(0xFF2B253A)
    val LavenderContainer = Color(0xFFEADDFF)
    val OnLavenderContainer = Color(0xFF21005D)
    val LavenderButton = Color(0xFFD0BCFF)
    val OnLavenderButton = Color(0xFF381E72)
    val ForestContainer = Color(0xFF192D23)
    val ForestText = Color(0xFFA6D4B5)
    val AmberContainer = Color(0xFFFFDEA8)
    val OnAmberContainer = Color(0xFF271900)
    val CoralContainer = Color(0xFFFFDAD6)
    val OnCoralContainer = Color(0xFF410002)
    val NfcBanner = Color(0xFF212D42)
    val NfcSubtext = Color(0xFFB8CCF0)
    val SheetSurface = Color(0xFF1E212B)
    val TextSecondary = Color(0xFF9BA1B0)
    val MutedPill = Color(0xFFC4C9D6)
}

class ShareActivity : ComponentActivity() {

    companion object {
        private const val TAG = "PixelBumpActivity"
        private const val PREFS_NAME = "pixelbump_prefs"
        private const val KEY_SSID = "hotspot_ssid"
        private const val KEY_PASS = "hotspot_password"
    }

    private lateinit var webServer: PixelWebServer
    private var activePort by mutableIntStateOf(8765)
    private var selectedDirection by mutableStateOf(TransferDirection.SEND)
    private var selectedQrMode by mutableStateOf(QrMode.SAME_WIFI)

    // Outbound state (Pixel -> Apple)
    private var stagedFile by mutableStateOf<PixelWebServer.StagedFile?>(null)
    private var transferUrl by mutableStateOf("")
    private var qrBitmap by mutableStateOf<Bitmap?>(null)
    private var transferProgress by mutableFloatStateOf(0f)
    private var transferSpeed by mutableStateOf("")

    // Hotspot QR state
    private var wifiQrBitmap by mutableStateOf<Bitmap?>(null)
    private var hotspotSsid by mutableStateOf("Pixel 6a Hotspot")
    private var hotspotPassword by mutableStateOf("12345678")
    private var isNfcEnabled by mutableStateOf(true)

    // Inbound state (Apple -> Pixel)
    private var incomingRequest by mutableStateOf<PixelWebServer.IncomingTransferRequest?>(null)
    private var rxIsReceiving by mutableStateOf(false)
    private var rxComplete by mutableStateOf(false)
    private var rxProgress by mutableFloatStateOf(0f)
    private var rxSpeed by mutableStateOf("")
    private var rxCompletedBytes = 0L
    private var rxCompletedCount = 0
    private var rxLastSpeedTime = 0L
    private var rxLastSpeedBytes = 0L

    private val selectFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { handleFileUri(it) }
    }

    override fun onResume() {
        super.onResume()
        val adapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
        isNfcEnabled = adapter?.isEnabled == true
        if (::webServer.isInitialized) {
            refreshActiveEndpoint()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            hotspotSsid = prefs.getString(KEY_SSID, "Pixel 6a Hotspot") ?: "Pixel 6a Hotspot"
            hotspotPassword = prefs.getString(KEY_PASS, "12345678") ?: "12345678"
            updateWifiQr()

            val htmlAsset = readWebReceiverHtml()
            webServer = PixelWebServer(this, htmlAsset)

            webServer.onTransferProgress = { transferred, total ->
                runOnUiThread {
                    if (total > 0) {
                        transferProgress = (transferred.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    }
                }
            }

            webServer.onTransferComplete = {
                runOnUiThread {
                    transferProgress = 1f
                    transferSpeed = "Transfer Complete"
                    Toast.makeText(this, "File successfully sent to Apple device!", Toast.LENGTH_SHORT).show()
                }
            }

            webServer.onIncomingRequest = { req ->
                runOnUiThread {
                    rxCompletedBytes = 0L
                    rxCompletedCount = 0
                    rxLastSpeedTime = System.currentTimeMillis()
                    rxLastSpeedBytes = 0L
                    rxProgress = 0f
                    rxSpeed = "Receiving..."
                    rxIsReceiving = false
                    rxComplete = false
                    incomingRequest = req
                }
            }

            webServer.onReceiveProgress = { _, bytesReceived, _ ->
                runOnUiThread {
                    val req = incomingRequest ?: return@runOnUiThread
                    val totalAggregated = rxCompletedBytes + bytesReceived
                    val grandTotal = req.totalSize.coerceAtLeast(1L)
                    rxProgress = (totalAggregated.toFloat() / grandTotal.toFloat()).coerceIn(0f, 1f)

                    val now = System.currentTimeMillis()
                    val dt = now - rxLastSpeedTime
                    if (dt >= 250) {
                        val deltaBytes = totalAggregated - rxLastSpeedBytes
                        val mbps = (deltaBytes.toFloat() / (1024f * 1024f)) / (dt.toFloat() / 1000f)
                        rxSpeed = "${mbps.coerceAtLeast(0f).format(1)} MB/s • Saving"
                        rxLastSpeedTime = now
                        rxLastSpeedBytes = totalAggregated
                    }
                }
            }

            webServer.onReceiveFileComplete = { _, bytesSaved ->
                runOnUiThread {
                    val req = incomingRequest ?: return@runOnUiThread
                    rxCompletedBytes += bytesSaved
                    rxCompletedCount += 1
                    if (rxCompletedCount >= req.files.size) {
                        rxProgress = 1f
                        rxIsReceiving = false
                        rxComplete = true
                        rxSpeed = "${formatBytes(rxCompletedBytes)} received"
                        Toast.makeText(this, "Saved ${req.files.size} file(s) to Photos & Downloads!", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            activePort = webServer.ensureStarted(8765)
            handleIntent(intent)
            refreshActiveEndpoint()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during onCreate setup", e)
        }

        setContent {
            PixelBumpTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = M3Colors.Canvas
                ) {
                    PixelBumpScreen(
                        selectedDirection = selectedDirection,
                        onDirectionSelected = { dir ->
                            selectedDirection = dir
                            refreshActiveEndpoint()
                        },
                        stagedFile = stagedFile,
                        transferUrl = transferUrl,
                        qrBitmap = qrBitmap,
                        wifiQrBitmap = wifiQrBitmap,
                        selectedQrMode = selectedQrMode,
                        onQrModeSelected = { mode ->
                            selectedQrMode = mode
                            refreshActiveEndpoint()
                        },
                        hotspotSsid = hotspotSsid,
                        hotspotPassword = hotspotPassword,
                        onSaveHotspotConfig = { newSsid, newPass ->
                            saveHotspotConfig(newSsid, newPass)
                        },
                        progress = transferProgress,
                        speed = transferSpeed,
                        isNfcEnabled = isNfcEnabled,
                        onSelectFileClick = { selectFileLauncher.launch("*/*") },
                        onRefreshEndpoint = { refreshActiveEndpoint() },
                        incomingRequest = incomingRequest,
                        rxIsReceiving = rxIsReceiving,
                        rxComplete = rxComplete,
                        rxProgress = rxProgress,
                        rxSpeed = rxSpeed,
                        onAcceptIncoming = {
                            val req = incomingRequest
                            if (req != null) {
                                rxIsReceiving = true
                                rxLastSpeedTime = System.currentTimeMillis()
                                req.decisionFuture.complete(true)
                            }
                        },
                        onDeclineIncoming = {
                            val req = incomingRequest
                            req?.decisionFuture?.complete(false)
                            incomingRequest = null
                            rxIsReceiving = false
                            rxComplete = false
                        },
                        onDismissCompletedRx = {
                            incomingRequest = null
                            rxIsReceiving = false
                            rxComplete = false
                        }
                    )
                }
            }
        }
    }

    private fun refreshActiveEndpoint() {
        if (!::webServer.isInitialized) return
        activePort = webServer.ensureStarted(8765)
        webServer.isReceiveMode = (selectedDirection == TransferDirection.RECEIVE)
        val preferHotspot = (selectedQrMode == QrMode.HOTSPOT)

        val url = if (selectedDirection == TransferDirection.RECEIVE) {
            webServer.buildReceiveUrl(activePort, preferHotspot)
        } else {
            webServer.buildTransferUrl(activePort, preferHotspot)
        }

        transferUrl = url
        qrBitmap = QrCodeGenerator.generateQrBitmap(url, 400)
        PixelHceService.currentTransferUri = url
    }

    private fun updateWifiQr() {
        val payload = "WIFI:S:$hotspotSsid;T:WPA;P:$hotspotPassword;;"
        wifiQrBitmap = QrCodeGenerator.generateQrBitmap(payload, 400)
    }

    private fun saveHotspotConfig(ssid: String, pass: String) {
        hotspotSsid = ssid
        hotspotPassword = pass
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SSID, ssid)
            .putString(KEY_PASS, pass)
            .apply()
        updateWifiQr()
        Toast.makeText(this, "Hotspot credentials saved", Toast.LENGTH_SHORT).show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        incomingRequest?.decisionFuture?.complete(false)
        if (::webServer.isInitialized) {
            webServer.stop()
        }
    }

    private fun handleIntent(intent: Intent?) {
        try {
            when (intent?.action) {
                Intent.ACTION_SEND -> {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    if (uri != null) {
                        handleFileUri(uri)
                    }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                    if (!uris.isNullOrEmpty()) {
                        handleFileUri(uris[0])
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming share intent", e)
        }
    }

    private fun handleFileUri(uri: Uri) {
        try {
            val file = webServer.stageFile(uri)
            stagedFile = file
            selectedDirection = TransferDirection.SEND
            transferProgress = 0f
            transferSpeed = ""
            refreshActiveEndpoint()
            Log.i(TAG, "File staged successfully: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Error staging file URI", e)
            Toast.makeText(this, "Failed to load file: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readWebReceiverHtml(): String {
        return try {
            assets.open("web_receiver.html").use { stream ->
                InputStreamReader(stream, Charsets.UTF_8).readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not load web_receiver.html from assets, using fallback", e)
            "<!DOCTYPE html><html><body><h1>PixelBump Receiver Ready</h1></body></html>"
        }
    }
}

@Composable
fun PixelBumpTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = M3Colors.PrimaryContainer,
            onPrimary = M3Colors.OnPrimaryContainer,
            secondary = M3Colors.SecondaryContainer,
            onSecondary = M3Colors.OnSecondaryContainer,
            background = M3Colors.Canvas,
            surface = M3Colors.SurfaceLow
        ),
        typography = PixelBumpTypography,
        content = content
    )
}

@Composable
fun PixelBumpScreen(
    selectedDirection: TransferDirection,
    onDirectionSelected: (TransferDirection) -> Unit,
    stagedFile: PixelWebServer.StagedFile?,
    transferUrl: String,
    qrBitmap: Bitmap?,
    wifiQrBitmap: Bitmap?,
    selectedQrMode: QrMode,
    onQrModeSelected: (QrMode) -> Unit,
    hotspotSsid: String,
    hotspotPassword: String,
    onSaveHotspotConfig: (String, String) -> Unit,
    progress: Float,
    speed: String,
    isNfcEnabled: Boolean,
    onSelectFileClick: () -> Unit,
    onRefreshEndpoint: () -> Unit,
    incomingRequest: PixelWebServer.IncomingTransferRequest?,
    rxIsReceiving: Boolean,
    rxComplete: Boolean,
    rxProgress: Float,
    rxSpeed: String,
    onAcceptIncoming: () -> Unit,
    onDeclineIncoming: () -> Unit,
    onDismissCompletedRx: () -> Unit
) {
    var showConfigDialog by remember { mutableStateOf(false) }
    var tempSsid by remember(hotspotSsid) { mutableStateOf(hotspotSsid) }
    var tempPassword by remember(hotspotPassword) { mutableStateOf(hotspotPassword) }
    var hotspotSubStep by remember { mutableIntStateOf(1) }

    if (showConfigDialog) {
        AlertDialog(
            onDismissRequest = { showConfigDialog = false },
            title = { Text("Pixel Hotspot Settings", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        "Enter your Pixel's hotspot credentials so Apple devices can connect via QR:",
                        color = M3Colors.TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = tempSsid,
                        onValueChange = { tempSsid = it },
                        label = { Text("Hotspot Name (SSID)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = tempPassword,
                        onValueChange = { tempPassword = it },
                        label = { Text("Hotspot Password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSaveHotspotConfig(tempSsid, tempPassword)
                        showConfigDialog = false
                    },
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = M3Colors.PrimaryContainer,
                        contentColor = M3Colors.OnPrimaryContainer
                    )
                ) {
                    Text("Save", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfigDialog = false }) {
                    Text("Cancel", color = M3Colors.MutedPill)
                }
            },
            containerColor = M3Colors.SheetSurface,
            shape = RoundedCornerShape(28.dp)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3Colors.Canvas)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Bold Material 3 Expressive Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(M3Colors.PrimaryContainer, RoundedCornerShape(16.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "PB",
                            color = M3Colors.OnPrimaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            "PixelBump",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 19.sp
                        )
                        Text(
                            if (selectedDirection == TransferDirection.RECEIVE) "Receive • Apple → Pixel"
                            else "Send • Pixel → Apple",
                            color = M3Colors.TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                val chipBg = when {
                    selectedDirection == TransferDirection.RECEIVE -> M3Colors.PrimaryContainer
                    isNfcEnabled -> M3Colors.SecondaryContainer
                    else -> M3Colors.AmberContainer
                }
                val chipTextColor = when {
                    selectedDirection == TransferDirection.RECEIVE -> M3Colors.OnPrimaryContainer
                    isNfcEnabled -> M3Colors.OnSecondaryContainer
                    else -> M3Colors.OnAmberContainer
                }
                val dotColor = when {
                    selectedDirection == TransferDirection.RECEIVE -> Color(0xFF041E49)
                    isNfcEnabled -> Color(0xFF146C2E)
                    else -> Color(0xFF7A4E00)
                }
                val chipLabel = when {
                    selectedDirection == TransferDirection.RECEIVE -> "Listening"
                    isNfcEnabled -> "NFC On"
                    else -> "NFC Off"
                }

                Row(
                    modifier = Modifier
                        .background(chipBg, CircleShape)
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(dotColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        chipLabel,
                        color = chipTextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 2. Big Colorful Direction Switcher Buttons (Send vs Receive)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val isSend = selectedDirection == TransferDirection.SEND
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isSend) M3Colors.PrimaryContainer else M3Colors.SurfaceHigh)
                        .clickable { onDirectionSelected(TransferDirection.SEND) }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "📤  Send",
                        color = if (isSend) M3Colors.OnPrimaryContainer else M3Colors.MutedPill,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                val isRecv = selectedDirection == TransferDirection.RECEIVE
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (isRecv) M3Colors.SecondaryContainer else M3Colors.SurfaceHigh)
                        .clickable { onDirectionSelected(TransferDirection.RECEIVE) }
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "📥  Receive",
                        color = if (isRecv) M3Colors.OnSecondaryContainer else M3Colors.MutedPill,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            val showBridgeCenter = (selectedDirection == TransferDirection.RECEIVE) || (stagedFile != null)

            if (showBridgeCenter) {
                // 3. Colorful File / Drop Box Card
                if (selectedDirection == TransferDirection.SEND && stagedFile != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3Colors.PlumContainer, RoundedCornerShape(26.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(M3Colors.LavenderContainer, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🏔️", fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stagedFile.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val mbSize = (stagedFile.size.toFloat() / (1024f * 1024f)).format(1)
                            Text(
                                text = "$mbSize MB • ${stagedFile.mimeType}",
                                color = Color(0xFFCBC4D8),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(M3Colors.LavenderButton)
                                .clickable { onSelectFileClick() }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) {
                            Text(
                                "Change",
                                color = M3Colors.OnLavenderButton,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                } else if (selectedDirection == TransferDirection.RECEIVE) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3Colors.ForestContainer, RoundedCornerShape(26.dp))
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .background(M3Colors.SecondaryContainer, RoundedCornerShape(16.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📥", fontSize = 22.sp)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Drop Box Listening",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = "Saves to Photos & Downloads",
                                color = M3Colors.ForestText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(M3Colors.SecondaryContainer, CircleShape)
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                "Ready",
                                color = M3Colors.OnSecondaryContainer,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 4. Big Network Mode Selector Pills
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3Colors.SurfaceLow, RoundedCornerShape(22.dp))
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isSameWifi = selectedQrMode == QrMode.SAME_WIFI
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isSameWifi) M3Colors.AmberContainer else Color.Transparent)
                            .clickable { onQrModeSelected(QrMode.SAME_WIFI) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "🏠  Same Wi-Fi",
                            color = if (isSameWifi) M3Colors.OnAmberContainer else M3Colors.MutedPill,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val isHotspot = selectedQrMode == QrMode.HOTSPOT
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (isHotspot) M3Colors.CoralContainer else Color.Transparent)
                            .clickable { onQrModeSelected(QrMode.HOTSPOT) }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "📡  Hotspot",
                            color = if (isHotspot) M3Colors.OnCoralContainer else M3Colors.MutedPill,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // 5. Solid Elevated QR Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3Colors.SurfaceLow, RoundedCornerShape(30.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (selectedQrMode == QrMode.HOTSPOT) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (hotspotSubStep == 1) M3Colors.PrimaryContainer else M3Colors.SurfaceHigher)
                                    .clickable { hotspotSubStep = 1 }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "1. Join Wi-Fi",
                                    color = if (hotspotSubStep == 1) M3Colors.OnPrimaryContainer else M3Colors.MutedPill,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(CircleShape)
                                    .background(if (hotspotSubStep == 2) M3Colors.PrimaryContainer else M3Colors.SurfaceHigher)
                                    .clickable {
                                        hotspotSubStep = 2
                                        onRefreshEndpoint()
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "2. Safari Link",
                                    color = if (hotspotSubStep == 2) M3Colors.OnPrimaryContainer else M3Colors.MutedPill,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(24.dp))
                            .padding(14.dp)
                    ) {
                        val activeBmp = if (selectedQrMode == QrMode.SAME_WIFI || hotspotSubStep == 2) qrBitmap else wifiQrBitmap
                        activeBmp?.let { bmp ->
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "QR Code",
                                modifier = Modifier.size(148.dp)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (selectedQrMode == QrMode.HOTSPOT && hotspotSubStep == 1) {
                            Text(
                                "Point iPad camera → Tap [Join]",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "SSID: $hotspotSsid",
                                    color = M3Colors.TextSecondary,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "Edit",
                                    color = M3Colors.PrimaryContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { showConfigDialog = true }
                                )
                            }
                        } else {
                            Text(
                                "Scan with iPad → Tap [Open in Safari]",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = transferUrl,
                                color = M3Colors.TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.clickable { onRefreshEndpoint() }
                            )
                        }
                    }
                }

                // Outbound Transfer Progress (if active)
                if (selectedDirection == TransferDirection.SEND && progress > 0f) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3Colors.SurfaceLow, RoundedCornerShape(22.dp))
                            .padding(16.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(CircleShape),
                            color = M3Colors.SecondaryContainer,
                            trackColor = M3Colors.SurfaceHigher
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(progress * 100).toInt()}% • $speed",
                            color = M3Colors.SecondaryContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // 6. Big Expressive NFC Bump Card
                ExpressiveNfcBanner(
                    title = if (selectedDirection == TransferDirection.RECEIVE) "Bump iPhone to Send Back" else "Touch Top of iPhone",
                    subtitle = if (selectedDirection == TransferDirection.RECEIVE) "Opens Pixel Drop Box in Safari" else "Triggers Safari banner via NFC"
                )
            } else {
                // Empty State in Send Mode
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3Colors.SurfaceLow, RoundedCornerShape(30.dp))
                        .padding(32.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .background(M3Colors.LavenderContainer, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📲", fontSize = 34.sp)
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(
                        "No File Selected",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Pick a photo or file to send, or tap Receive above to accept from iPhone / iPad",
                        color = M3Colors.TextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onSelectFileClick,
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = M3Colors.PrimaryContainer,
                            contentColor = M3Colors.OnPrimaryContainer
                        )
                    ) {
                        Text("Choose File to Send", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Bottom IP Refresh Footer
            Text(
                text = if (transferUrl.isNotEmpty()) "$transferUrl (Tap to refresh IP)" else "PixelBump Engine Ready",
                color = M3Colors.TextSecondary.copy(alpha = 0.65f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clickable { onRefreshEndpoint() }
            )
        }

        // =========================================================================
        // SOLID MATERIAL 3 EXPRESSIVE BOTTOM SHEET (Incoming Apple -> Pixel)
        // =========================================================================
        if (incomingRequest != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .navigationBarsPadding(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            M3Colors.SheetSurface,
                            RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                        )
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Drag Handle
                    Box(
                        modifier = Modifier
                            .width(42.dp)
                            .height(5.dp)
                            .background(Color(0xFF4B5164), CircleShape)
                            .align(Alignment.CenterHorizontally)
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .background(M3Colors.CoralContainer, RoundedCornerShape(18.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🍎", fontSize = 24.sp)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column {
                            Text(
                                text = "\"${incomingRequest.senderName}\" wants to send",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "${incomingRequest.files.size} file(s) • ${formatBytes(incomingRequest.totalSize)}",
                                color = M3Colors.TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Solid File List Card
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3Colors.Canvas, RoundedCornerShape(22.dp))
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        incomingRequest.files.take(4).forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📄 ${item.name}",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = formatBytes(item.size),
                                    color = M3Colors.TextSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        if (incomingRequest.files.size > 4) {
                            Text(
                                text = "+ ${incomingRequest.files.size - 4} more files",
                                color = M3Colors.PrimaryContainer,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (rxIsReceiving || rxComplete) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(
                                progress = { rxProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(10.dp)
                                    .clip(CircleShape),
                                color = Color(0xFF6DD58C),
                                trackColor = Color(0xFF2C303D)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = rxSpeed,
                                    color = M3Colors.SecondaryContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "${(rxProgress * 100).toInt()}%",
                                    color = M3Colors.SecondaryContainer,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    if (!rxIsReceiving && !rxComplete) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onDeclineIncoming,
                                modifier = Modifier.weight(1f),
                                shape = CircleShape,
                                contentPadding = PaddingValues(vertical = 15.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF303443),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Decline", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onAcceptIncoming,
                                modifier = Modifier.weight(1f),
                                shape = CircleShape,
                                contentPadding = PaddingValues(vertical = 15.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = M3Colors.PrimaryContainer,
                                    contentColor = M3Colors.OnPrimaryContainer
                                )
                            ) {
                                Text("Accept & Save", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    } else if (rxComplete) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3Colors.ForestContainer, RoundedCornerShape(18.dp))
                                .padding(14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "✓ Saved to Photos & Downloads!",
                                color = M3Colors.SecondaryContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Button(
                            onClick = onDismissCompletedRx,
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                            contentPadding = PaddingValues(vertical = 15.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = M3Colors.SecondaryContainer,
                                contentColor = M3Colors.OnSecondaryContainer
                            )
                        ) {
                            Text("Done", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ExpressiveNfcBanner(title: String, subtitle: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "m3_nfc_bounce")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(M3Colors.NfcBanner, RoundedCornerShape(24.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .scale(scale)
                .background(M3Colors.PrimaryContainer, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("📶", fontSize = 20.sp)
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Text(
                text = subtitle,
                color = M3Colors.NfcSubtext,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

fun Float.format(digits: Int) = "%.${digits}f".format(this)

fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.size - 1) {
        value /= 1024.0
        idx++
    }
    return "%.1f %s".format(value, units[idx])
}
