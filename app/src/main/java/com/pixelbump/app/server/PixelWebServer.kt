package com.pixelbump.app.server

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pure Android-native bidirectional HTTP streaming server using standard ServerSocket.
 * Supports:
 *  - GET /stream (Pixel -> Apple AirDrop-style chunked download)
 *  - POST /ask & POST /upload (Apple -> Pixel zero-app Safari upload directly to MediaStore)
 *  - Apple Captive Portal probe routes
 */
class PixelWebServer(
    private val context: Context,
    private val webAssetHtml: String
) {
    companion object {
        private const val TAG = "PixelWebServer"
        const val DEFAULT_PORT = 8765
    }

    data class StagedFile(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mimeType: String,
        val sessionToken: String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)
    )

    data class IncomingFileInfo(
        val name: String,
        val size: Long,
        val type: String
    )

    data class IncomingTransferRequest(
        val id: String,
        val senderName: String,
        val files: List<IncomingFileInfo>,
        val totalSize: Long,
        val decisionFuture: CompletableFuture<Boolean>
    )

    private var serverSocket: ServerSocket? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var isRunning = false
    private val executor = Executors.newCachedThreadPool()

    var activePort: Int = DEFAULT_PORT
        private set

    var activeStagedFile: StagedFile? = null
        private set

    @Volatile
    var isReceiveMode: Boolean = false

    // Outbound (Pixel -> Apple) callbacks & live progress state for /progress polling
    var onTransferProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null
    var onTransferComplete: (() -> Unit)? = null
    @Volatile private var activeTransferBytesSent: Long = 0L
    @Volatile private var activeTransferTotalBytes: Long = 0L
    @Volatile private var activeTransferSpeedMbps: Double = 0.0
    @Volatile private var activeTransferDone: Boolean = false

    // Inbound (Apple -> Pixel) callbacks
    var onIncomingRequest: ((IncomingTransferRequest) -> Unit)? = null
    var onReceiveProgress: ((fileName: String, bytesReceived: Long, totalBytes: Long) -> Unit)? = null
    var onReceiveFileComplete: ((fileName: String, bytesSaved: Long) -> Unit)? = null

    fun ensureStarted(preferredPort: Int = DEFAULT_PORT): Int {
        if (isRunning && serverSocket != null && serverSocket?.isClosed == false) {
            return activePort
        }
        return start(preferredPort)
    }

    fun start(preferredPort: Int = DEFAULT_PORT): Int {
        stop()
        val portsToTry = linkedSetOf(80, preferredPort, DEFAULT_PORT, 0)

        for (port in portsToTry) {
            try {
                serverSocket = ServerSocket(port, 50, InetAddress.getByName("0.0.0.0")).apply {
                    reuseAddress = true
                    receiveBufferSize = 256 * 1024
                }
                isRunning = true
                acquireHighPerfLocks()

                executor.execute {
                    acceptConnections()
                }

                activePort = serverSocket?.localPort ?: port
                Log.i(TAG, "PixelWebServer successfully started on port $activePort")
                return activePort
            } catch (e: Exception) {
                Log.d(TAG, "Port $port unavailable (${e.message}), attempting fallback...")
            }
        }
        return DEFAULT_PORT
    }

    fun stop() {
        isRunning = false
        releaseHighPerfLocks()
        try {
            serverSocket?.close()
            serverSocket = null
            Log.i(TAG, "PixelWebServer stopped")
        } catch (e: Exception) {
            Log.w(TAG, "Error closing serverSocket", e)
        }
    }

    private fun acquireHighPerfLocks() {
        try {
            if (wifiLock == null) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                if (wm != null) {
                    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wm.createWifiLock(mode, "PixelBump::HighPerfWifiLock").apply {
                        setReferenceCounted(false)
                    }
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }

            if (wakeLock == null) {
                val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PixelBump::TransferWakeLock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(15 * 60 * 1000L) // Safety 15-minute ceiling
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to acquire high-perf Wi-Fi/Wake locks", e)
        }
    }

    private fun releaseHighPerfLocks() {
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {}
    }

    private fun acceptConnections() {
        while (isRunning) {
            try {
                val client = serverSocket?.accept() ?: break
                executor.execute {
                    handleClient(client)
                }
            } catch (e: Exception) {
                if (!isRunning) break
                Log.w(TAG, "Exception accepting socket", e)
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 120_000 // 120s timeout for large streams and approval prompts
            client.tcpNoDelay = true
            client.sendBufferSize = 256 * 1024
            client.receiveBufferSize = 256 * 1024
            val inputStream = BufferedInputStream(client.getInputStream(), 256 * 1024)
            val outputStream = BufferedOutputStream(client.getOutputStream(), 256 * 1024)

            val requestLine = readHttpLine(inputStream) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val fullPath = parts[1]
            val path = if (fullPath.contains("?")) fullPath.substringBefore("?") else fullPath
            val queryParams = parseQueryParams(fullPath)

            // Read HTTP headers byte-by-byte so binary body bytes remain untouched
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readHttpLine(inputStream) ?: break
                if (line.isEmpty()) break
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                }
            }

            if (method == "OPTIONS") {
                sendCorsPreflight(outputStream)
                return
            }

            when {
                (method == "GET" || method == "HEAD") && (
                    path == "/" || path == "/index.html" ||
                    path == "/hotspot-detect.html" ||
                    path == "/library/test/success.html" ||
                    path == "/success.html" ||
                    path == "/canonical.html" ||
                    path == "/ncsi.txt" ||
                    path == "/connecttest.txt" ||
                    path == "/generate_204" ||
                    path == "/gen_204"
                ) -> {
                    val bytes = webAssetHtml.toByteArray(Charsets.UTF_8)
                    sendResponse(outputStream, 200, "OK", "text/html; charset=UTF-8", bytes)
                }

                method == "GET" && path == "/meta" -> {
                    val file = activeStagedFile
                    val jsonObj = JSONObject().apply {
                        put("mode", if (isReceiveMode) "upload" else "download")
                        put("sender", "Google Pixel 6a")
                        if (file != null) {
                            put("name", file.name)
                            put("size", file.size)
                            put("type", file.mimeType)
                            put("token", file.sessionToken)
                        }
                    }
                    sendResponse(outputStream, 200, "OK", "application/json", jsonObj.toString().toByteArray(Charsets.UTF_8))
                }

                method == "GET" && path == "/progress" -> {
                    val file = activeStagedFile
                    if (file == null || queryParams["token"] != file.sessionToken) {
                        sendResponse(outputStream, 404, "Not Found", "application/json", "{\"error\":\"No active transfer\"}".toByteArray())
                        return
                    }
                    val pJson = JSONObject().apply {
                        put("bytesSent", activeTransferBytesSent)
                        put("totalBytes", if (activeTransferTotalBytes > 0) activeTransferTotalBytes else file.size)
                        put("speedMbps", activeTransferSpeedMbps)
                        put("done", activeTransferDone)
                    }
                    sendResponse(outputStream, 200, "OK", "application/json", pJson.toString().toByteArray(Charsets.UTF_8))
                }

                (method == "GET" || method == "HEAD") && path == "/stream" -> {
                    val file = activeStagedFile
                    if (file == null) {
                        sendResponse(outputStream, 404, "Not Found", "text/plain", "No active file".toByteArray())
                        return
                    }
                    val reqToken = queryParams["token"]
                    if (reqToken != file.sessionToken) {
                        sendResponse(outputStream, 403, "Forbidden", "text/plain", "Invalid session token".toByteArray())
                        return
                    }
                    val forceOctet = queryParams["download"] == "1"
                    streamFile(outputStream, file, headers["range"], isHead = (method == "HEAD"), forceOctet = forceOctet)
                }

                method == "POST" && path == "/ask" -> {
                    handleIncomingAsk(inputStream, outputStream, headers)
                }

                method == "POST" && path == "/upload" -> {
                    handleIncomingUpload(inputStream, outputStream, headers, queryParams)
                }

                else -> {
                    sendResponse(outputStream, 404, "Not Found", "text/plain", "404 Not Found".toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client connection", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleIncomingAsk(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>
    ) {
        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceIn(0, 1024 * 1024) ?: 0
        val bodyBytes = if (contentLength > 0) readExactBytes(input, contentLength) else ByteArray(0)
        val bodyStr = String(bodyBytes, Charsets.UTF_8)

        val filesList = mutableListOf<IncomingFileInfo>()
        var senderName = "Apple Device"
        var totalSize = 0L

        try {
            if (bodyStr.isNotBlank()) {
                val json = JSONObject(bodyStr)
                senderName = json.optString("sender", "Apple Device")
                totalSize = json.optLong("totalSize", 0L)
                val filesJson: JSONArray? = json.optJSONArray("files")
                if (filesJson != null) {
                    for (i in 0 until filesJson.length()) {
                        val item = filesJson.getJSONObject(i)
                        filesList.add(
                            IncomingFileInfo(
                                name = item.optString("name", "Incoming_File"),
                                size = item.optLong("size", 0L),
                                type = item.optString("type", "application/octet-stream")
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Malformed /ask JSON: $bodyStr", e)
        }

        if (filesList.isEmpty()) {
            filesList.add(IncomingFileInfo("Incoming_File", totalSize, "application/octet-stream"))
        }

        val callback = onIncomingRequest
        val accepted = if (callback != null) {
            val future = CompletableFuture<Boolean>()
            val req = IncomingTransferRequest(
                id = UUID.randomUUID().toString().substring(0, 8),
                senderName = senderName,
                files = filesList,
                totalSize = totalSize,
                decisionFuture = future
            )
            callback.invoke(req)
            try {
                future.get(65, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "Incoming transfer request timed out or cancelled", e)
                false
            }
        } else {
            true
        }

        val respJson = JSONObject().put("accepted", accepted).toString().toByteArray(Charsets.UTF_8)
        sendResponse(output, 200, "OK", "application/json", respJson)
    }

    private fun handleIncomingUpload(
        input: InputStream,
        output: OutputStream,
        headers: Map<String, String>,
        queryParams: Map<String, String>
    ) {
        val rawName = queryParams["name"] ?: headers["x-file-name"] ?: "PixelBump_${System.currentTimeMillis()}"
        val safeName = rawName.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "PixelBump_File" }
        val declaredSize = queryParams["size"]?.toLongOrNull() ?: headers["content-length"]?.toLongOrNull() ?: -1L
        val contentLength = headers["content-length"]?.toLongOrNull() ?: declaredSize
        val rawMime = queryParams["type"] ?: headers["content-type"] ?: "application/octet-stream"
        val resolvedMime = resolveMimeType(safeName, rawMime)

        if (contentLength < 0L) {
            sendResponse(output, 411, "Length Required", "application/json", "{\"error\":\"Missing Content-Length\"}".toByteArray())
            return
        }

        return try {
            val savedBytes = saveStreamToDevice(input, safeName, resolvedMime, contentLength)
            onReceiveFileComplete?.invoke(safeName, savedBytes)
            val resp = JSONObject()
                .put("status", "saved")
                .put("name", safeName)
                .put("bytes", savedBytes)
                .toString()
                .toByteArray(Charsets.UTF_8)
            sendResponse(output, 200, "OK", "application/json", resp)
        } catch (e: Exception) {
            Log.e(TAG, "Error saving uploaded file: $safeName", e)
            val errJson = JSONObject().put("error", e.message ?: "Upload failed").toString().toByteArray(Charsets.UTF_8)
            sendResponse(output, 500, "Internal Server Error", "application/json", errJson)
        }
    }

    private fun saveStreamToDevice(
        input: InputStream,
        fileName: String,
        mimeType: String,
        contentLength: Long
    ): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val (collectionUri, relativeDir) = when {
                mimeType.startsWith("image/") -> Pair(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_PICTURES + "/PixelBump"
                )
                mimeType.startsWith("video/") -> Pair(
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_MOVIES + "/PixelBump"
                )
                else -> Pair(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    Environment.DIRECTORY_DOWNLOADS + "/PixelBump"
                )
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val itemUri = resolver.insert(collectionUri, values)
                ?: throw IOException("Unable to create MediaStore entry for $fileName")

            var totalRead = 0L
            try {
                resolver.openOutputStream(itemUri)?.use { out ->
                    totalRead = copyExactBytes(input, out, contentLength, fileName)
                } ?: throw IOException("Unable to open output stream for $itemUri")

                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(itemUri, publishValues, null, null)
                Log.i(TAG, "Saved $fileName ($totalRead bytes) to $relativeDir")
                return totalRead
            } catch (e: Exception) {
                try { resolver.delete(itemUri, null, null) } catch (_: Exception) {}
                throw e
            }
        } else {
            // Fallback for Android 8-9 (API 26-28)
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val targetFile = File(dir, fileName)
            FileOutputStream(targetFile).use { out ->
                return copyExactBytes(input, out, contentLength, fileName)
            }
        }
    }

    private fun copyExactBytes(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        fileName: String
    ): Long {
        val buffer = ByteArray(256 * 1024)
        var remaining = totalBytes
        var totalRead = 0L
        var lastCallbackTime = 0L

        while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read == -1) {
                throw EOFException("Unexpected EOF while receiving $fileName ($totalRead / $totalBytes bytes)")
            }
            output.write(buffer, 0, read)
            totalRead += read
            remaining -= read

            val now = System.currentTimeMillis()
            if (now - lastCallbackTime >= 80 || remaining == 0L) {
                onReceiveProgress?.invoke(fileName, totalRead, totalBytes)
                lastCallbackTime = now
            }
        }
        output.flush()
        return totalRead
    }

    private fun resolveMimeType(fileName: String, declaredMime: String): String {
        if (declaredMime.isNotBlank() && declaredMime != "application/octet-stream") {
            return declaredMime
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
            when (ext) {
                "heic", "heif" -> return "image/heic"
                "dng" -> return "image/x-adobe-dng"
                "mov" -> return "video/quicktime"
            }
        }
        return "application/octet-stream"
    }

    private fun readHttpLine(input: InputStream): String? {
        val baos = ByteArrayOutputStream(128)
        var b = input.read()
        if (b == -1) return null
        while (b != -1 && b != '\n'.code) {
            if (b != '\r'.code) {
                baos.write(b)
            }
            b = input.read()
        }
        return String(baos.toByteArray(), Charsets.UTF_8)
    }

    private fun readExactBytes(input: InputStream, length: Int): ByteArray {
        val data = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(data, offset, length - offset)
            if (read == -1) break
            offset += read
        }
        return if (offset == length) data else data.copyOf(offset)
    }

    private fun parseQueryParams(fullPath: String): Map<String, String> {
        val query = fullPath.substringAfter('?', "")
        if (query.isEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        for (pair in query.split('&')) {
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun sendCorsPreflight(out: OutputStream) {
        val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
        writer.print("HTTP/1.1 204 No Content\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
    }

    private fun sendResponse(out: OutputStream, code: Int, statusText: String, contentType: String, body: ByteArray) {
        val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
        writer.print("HTTP/1.1 $code $statusText\r\n")
        writer.print("Content-Type: $contentType\r\n")
        writer.print("Content-Length: ${body.size}\r\n")
        writer.print("X-Content-Type-Options: nosniff\r\n")
        writer.print("Cache-Control: no-store\r\n")
        writer.print("Connection: close\r\n")
        writer.print("\r\n")
        writer.flush()
        out.write(body)
        out.flush()
    }

    private fun streamFile(out: OutputStream, file: StagedFile, rangeHeader: String? = null, isHead: Boolean = false, forceOctet: Boolean = false) {
        var afd: android.content.res.AssetFileDescriptor? = null
        var inputStream: InputStream? = null
        try {
            val totalSize = file.size
            var rangeStart = 0L
            var rangeEnd = if (totalSize > 0) totalSize - 1 else -1L
            var isPartial = false

            if (!rangeHeader.isNullOrBlank() && totalSize > 0 && rangeHeader.lowercase().startsWith("bytes=")) {
                val spec = rangeHeader.substringAfter("bytes=").substringBefore(',').trim()
                val dashIdx = spec.indexOf('-')
                if (dashIdx >= 0) {
                    val startPart = spec.substring(0, dashIdx).trim()
                    val endPart = spec.substring(dashIdx + 1).trim()
                    val parsedStart = startPart.toLongOrNull()
                    if (parsedStart != null) {
                        val parsedEnd = if (endPart.isNotEmpty()) endPart.toLongOrNull() else (totalSize - 1)
                        if (parsedEnd != null) {
                            if (parsedStart >= totalSize || parsedStart < 0 || parsedEnd < parsedStart) {
                                val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
                                writer.print("HTTP/1.1 416 Range Not Satisfiable\r\n")
                                writer.print("Content-Range: bytes */$totalSize\r\n")
                                writer.print("Connection: close\r\n")
                                writer.print("\r\n")
                                writer.flush()
                                return
                            }
                            rangeStart = parsedStart
                            rangeEnd = minOf(parsedEnd, totalSize - 1)
                            isPartial = true
                        }
                    }
                }
            }

            val contentLengthToSend = if (totalSize > 0) (rangeEnd - rangeStart + 1) else -1L
            val safeHeaderName = file.name.replace("\"", "_")
            val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")

            if (!isHead) {
                // Open stream BEFORE sending 200/206 headers so missing/revoked URIs return a clean 500
                var seekDone = false
                if (rangeStart > 0L) {
                    try {
                        afd = context.contentResolver.openAssetFileDescriptor(file.uri, "r")
                        if (afd != null && afd.length >= 0) {
                            val fis = afd.createInputStream()
                            fis.channel.position(afd.startOffset + rangeStart)
                            inputStream = fis
                            seekDone = true
                        } else {
                            afd?.close()
                            afd = null
                        }
                    } catch (_: Exception) {
                        try { afd?.close() } catch (_: Exception) {}
                        afd = null
                    }
                }

                if (inputStream == null) {
                    inputStream = context.contentResolver.openInputStream(file.uri)
                    if (inputStream == null) {
                        sendResponse(out, 500, "Internal Server Error", "text/plain", "Unable to open file stream".toByteArray())
                        return
                    }
                    if (rangeStart > 0L && !seekDone) {
                        skipFully(inputStream, rangeStart)
                    }
                }
            }

            val servedMimeType = if (forceOctet) "application/octet-stream" else file.mimeType
            val writer = PrintWriter(OutputStreamWriter(out, Charsets.UTF_8))
            writer.print(if (isPartial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: $servedMimeType\r\n")
            writer.print("Content-Disposition: attachment; filename=\"$safeHeaderName\"; filename*=UTF-8''$encodedName\r\n")
            writer.print("Accept-Ranges: bytes\r\n")
            writer.print("X-Content-Type-Options: nosniff\r\n")
            writer.print("Cache-Control: no-store\r\n")
            if (isPartial) {
                writer.print("Content-Range: bytes $rangeStart-$rangeEnd/$totalSize\r\n")
            }
            if (contentLengthToSend >= 0) {
                writer.print("Content-Length: $contentLengthToSend\r\n")
            }
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.flush()

            val activeInput = inputStream
            if (isHead || activeInput == null) return

            if (!isPartial || rangeStart == 0L) {
                activeTransferBytesSent = 0L
                activeTransferTotalBytes = totalSize
                activeTransferSpeedMbps = 0.0
                activeTransferDone = false
            }

            val buffer = ByteArray(256 * 1024)
            var bytesRead: Int
            var remaining = if (contentLengthToSend >= 0) contentLengthToSend else Long.MAX_VALUE
            var totalSentThisRequest = 0L
            var lastProgressTime = System.currentTimeMillis()
            var lastSpeedBytes = 0L

            while (remaining > 0L) {
                val maxRead = minOf(buffer.size.toLong(), remaining).toInt()
                bytesRead = activeInput.read(buffer, 0, maxRead)
                if (bytesRead == -1) break
                out.write(buffer, 0, bytesRead)
                totalSentThisRequest += bytesRead
                if (contentLengthToSend >= 0) {
                    remaining -= bytesRead
                }

                val now = System.currentTimeMillis()
                val dt = now - lastProgressTime
                if (dt >= 120 || remaining == 0L) {
                    val overallSent = rangeStart + totalSentThisRequest
                    if (dt > 0) {
                        val deltaBytes = totalSentThisRequest - lastSpeedBytes
                        activeTransferSpeedMbps = (deltaBytes.toDouble() / (1024.0 * 1024.0)) / (dt.toDouble() / 1000.0)
                        lastSpeedBytes = totalSentThisRequest
                    }
                    activeTransferBytesSent = overallSent
                    activeTransferTotalBytes = totalSize
                    onTransferProgress?.invoke(overallSent, totalSize)
                    lastProgressTime = now
                }
            }

            out.flush()
            if (!isPartial || (rangeEnd == totalSize - 1)) {
                activeTransferBytesSent = totalSize
                activeTransferDone = true
                onTransferComplete?.invoke()
            }
            Log.i(TAG, "Stream complete: $totalSentThisRequest bytes sent (offset=$rangeStart, partial=$isPartial)")
        } catch (e: Exception) {
            Log.e(TAG, "Exception streaming file", e)
        } finally {
            try { inputStream?.close() } catch (_: Exception) {}
            try { afd?.close() } catch (_: Exception) {}
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        val skipBuf = ByteArray(16 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                val read = input.read(skipBuf, 0, minOf(skipBuf.size.toLong(), remaining).toInt())
                if (read == -1) break
                remaining -= read
            }
        }
    }

    fun stageFile(fileUri: Uri): StagedFile {
        var name = "Shared_File"
        var size = 0L
        val mime = context.contentResolver.getType(fileUri) ?: "application/octet-stream"

        context.contentResolver.query(fileUri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }

        val staged = StagedFile(
            uri = fileUri,
            name = name,
            size = size,
            mimeType = mime
        )
        activeStagedFile = staged
        activeTransferBytesSent = 0L
        activeTransferTotalBytes = size
        activeTransferSpeedMbps = 0.0
        activeTransferDone = false
        return staged
    }

    @Suppress("DEPRECATION")
    private fun getClientWifiIp(): String? {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val props = cm.getLinkProperties(network) ?: continue
                    for (linkAddr in props.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying client Wi-Fi IP", e)
        }
        return null
    }

    fun getLocalIpAddress(preferHotspot: Boolean = false): String {
        try {
            val clientWifiIp = getClientWifiIp()
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            val candidates = mutableListOf<Pair<String, String>>()

            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                // Ignore cellular WAN, VPN tunnels, and virtual interfaces
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("wwan") ||
                    name.startsWith("pdp") || name.startsWith("tun") || name.startsWith("ppp") ||
                    name.startsWith("wg") || name.startsWith("dummy") || name.startsWith("v4-") ||
                    name.startsWith("clat")
                ) continue

                for (addr in iface.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        candidates.add(Pair(name, host))
                    }
                }
            }

            val preferred = if (preferHotspot) {
                // On Pixel, Hotspot uses ap*/softap*/swlan* or secondary wlan* whose IP differs from client Wi-Fi
                candidates.firstOrNull {
                    it.first.contains("ap") || it.first.contains("softap") || it.first.contains("swlan") ||
                        it.second.startsWith("192.168.43.") || it.second.startsWith("192.168.49.")
                } ?: candidates.firstOrNull {
                    (it.first.contains("wlan") || it.first.contains("eth")) && it.second != clientWifiIp
                } ?: candidates.firstOrNull {
                    it.first.contains("wlan") || it.first.contains("eth")
                } ?: candidates.firstOrNull()
            } else {
                if (clientWifiIp != null) {
                    return clientWifiIp
                }
                candidates.firstOrNull {
                    it.first.contains("wlan") && !it.second.startsWith("192.168.43.")
                } ?: candidates.firstOrNull {
                    it.first.contains("wlan") || it.first.contains("ap") || it.first.contains("softap") || it.first.contains("eth")
                } ?: candidates.firstOrNull()
            }

            if (preferred != null) {
                Log.i(TAG, "Selected network interface (preferHotspot=$preferHotspot): ${preferred.first} -> ${preferred.second}")
                return preferred.second
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error determining local IP", e)
        }
        return "127.0.0.1"
    }

    fun buildTransferUrl(port: Int, preferHotspot: Boolean = false): String {
        val ip = getLocalIpAddress(preferHotspot)
        val portPart = if (port == 80) "" else ":$port"
        val file = activeStagedFile ?: return "http://$ip$portPart/?mode=upload"
        val encName = URLEncoder.encode(file.name, "UTF-8")
        val encMime = URLEncoder.encode(file.mimeType, "UTF-8")
        return "http://$ip$portPart/?token=${file.sessionToken}&name=$encName&size=${file.size}&type=$encMime&sender=Google+Pixel+6a"
    }

    fun buildReceiveUrl(port: Int, preferHotspot: Boolean = false): String {
        val ip = getLocalIpAddress(preferHotspot)
        val portPart = if (port == 80) "" else ":$port"
        return "http://$ip$portPart/?mode=upload"
    }
}
