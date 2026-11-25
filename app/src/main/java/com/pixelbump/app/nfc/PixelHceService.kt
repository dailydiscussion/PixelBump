package com.pixelbump.app.nfc

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.util.Log

/**
 * HostApduService implementing NFC Forum Type 4 Tag emulation.
 * Compliant with ISO/IEC 7816-4 and NFC Forum Type 4 Tag Operation Specification (v2.0 & v1.0).
 * Handles iPhone CoreNFC and background tag reader APDU exchanges.
 */
class PixelHceService : HostApduService() {

    companion object {
        private const val TAG = "PixelHceService"

        // Status Words (ISO/IEC 7816-4)
        private val SW_SUCCESS = byteArrayOf(0x90.toByte(), 0x00.toByte())
        private val SW_FILE_NOT_FOUND = byteArrayOf(0x6A.toByte(), 0x82.toByte())
        private val SW_WRONG_P1P2 = byteArrayOf(0x6A.toByte(), 0x86.toByte())
        private val SW_ERROR = byteArrayOf(0x6F.toByte(), 0x00.toByte())

        // NFC Forum Type 4 Application Identifiers
        // v2.0 AID: D2 76 00 00 85 01 01
        // v1.0 AID: D2 76 00 00 85 01 00
        private val AID_PREFIX = byteArrayOf(
            0xD2.toByte(), 0x76.toByte(), 0x00.toByte(), 0x00.toByte(), 0x85.toByte(), 0x01.toByte()
        )

        // File IDs (2 bytes)
        private const val FILE_ID_CC = 0xE103
        private const val FILE_ID_NDEF = 0xE104

        // Capability Container (CC) File content (15 bytes, Type 4 Tag v2.0)
        // CCLEN: 0x000F (15 bytes)
        // Mapping Version: 0x20 (Version 2.0)
        // MLe (Max Read): 0x00FF (255 bytes per APDU)
        // MLc (Max Write): 0x00FF (255 bytes)
        // NDEF File Control TLV:
        //   T: 0x04 (NDEF File Control)
        //   L: 0x06 (Length = 6 bytes)
        //   File ID: 0xE104
        //   Max NDEF Size: 0x0800 (2048 bytes)
        //   Read Access: 0x00 (Free read without security)
        //   Write Access: 0xFF (Read only)
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F,
            0x20,
            0x00, 0xFF.toByte(),
            0x00, 0xFF.toByte(),
            0x04, 0x06,
            0xE1.toByte(), 0x04.toByte(),
            0x08, 0x00,
            0x00,
            0xFF.toByte()
        )

        // Active dynamic payload
        @Volatile
        var currentTransferUri: String = "https://pixelbump.local"
            set(value) {
                field = value
                updateNdefFile(value)
            }

        private var activeNdefFile: ByteArray = byteArrayOf()

        init {
            updateNdefFile(currentTransferUri)
        }

        private fun updateNdefFile(uri: String) {
            val record = NdefMessageBuilder.createUriRecord(uri)
            activeNdefFile = NdefMessageBuilder.formatType4File(record)
            Log.d(TAG, "Updated HCE payload with URI: $uri (File size: ${activeNdefFile.size} bytes)")
        }
    }

    private enum class SelectedFile {
        NONE,
        CC,
        NDEF
    }

    private var currentFile = SelectedFile.NONE

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        if (commandApdu == null || commandApdu.size < 4) {
            return SW_ERROR
        }

        val cla = commandApdu[0].toInt() and 0xFF
        val ins = commandApdu[1].toInt() and 0xFF
        val p1 = commandApdu[2].toInt() and 0xFF
        val p2 = commandApdu[3].toInt() and 0xFF

        // 1. SELECT (INS = 0xA4)
        if (cla == 0x00 && ins == 0xA4) {
            // Case A: SELECT by AID (P1 = 0x04)
            if (p1 == 0x04) {
                val lc = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0
                if (lc >= 6 && commandApdu.size >= 5 + lc) {
                    val aidBytes = commandApdu.sliceArray(5 until 5 + lc)
                    if (matchesAid(aidBytes)) {
                        Log.d(TAG, "APDU: SELECT AID matched (${bytesToHex(aidBytes)})")
                        currentFile = SelectedFile.NONE
                        return SW_SUCCESS
                    }
                }
            }

            // Case B: SELECT by File ID (P1 = 0x00, P2 = 0x00 or 0x0C)
            if (p1 == 0x00 && (p2 == 0x00 || p2 == 0x0C)) {
                val lc = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0
                if (lc == 2 && commandApdu.size >= 7) {
                    val fileId = ((commandApdu[5].toInt() and 0xFF) shl 8) or (commandApdu[6].toInt() and 0xFF)
                    return when (fileId) {
                        FILE_ID_CC -> {
                            Log.d(TAG, "APDU: SELECT CC file (E103)")
                            currentFile = SelectedFile.CC
                            SW_SUCCESS
                        }
                        FILE_ID_NDEF -> {
                            Log.d(TAG, "APDU: SELECT NDEF file (E104)")
                            currentFile = SelectedFile.NDEF
                            SW_SUCCESS
                        }
                        else -> {
                            Log.w(TAG, "APDU: SELECT unknown fileId: %04X".format(fileId))
                            SW_FILE_NOT_FOUND
                        }
                    }
                }
            }
        }

        // 2. READ BINARY (CLA = 0x00, INS = 0xB0)
        if (cla == 0x00 && ins == 0xB0) {
            val offset = (p1 shl 8) or p2
            val length = if (commandApdu.size >= 5) commandApdu[4].toInt() and 0xFF else 0
            return handleReadBinary(offset, length)
        }

        Log.w(TAG, "Unhandled APDU: ${bytesToHex(commandApdu)}")
        return SW_ERROR
    }

    private fun handleReadBinary(offset: Int, length: Int): ByteArray {
        val targetData = when (currentFile) {
            SelectedFile.CC -> CC_FILE
            SelectedFile.NDEF -> activeNdefFile
            SelectedFile.NONE -> return SW_FILE_NOT_FOUND
        }

        if (offset > targetData.size) {
            return SW_WRONG_P1P2
        }

        if (offset == targetData.size) {
            // EOF reached with 0 bytes requested
            return SW_SUCCESS
        }

        val available = targetData.size - offset
        // If Le = 0, standard ISO 7816-4 indicates up to 256 bytes
        val requestedLength = if (length == 0) 256 else length
        val readLength = requestedLength.coerceAtMost(available)

        val response = ByteArray(readLength + 2)
        System.arraycopy(targetData, offset, response, 0, readLength)
        response[readLength] = SW_SUCCESS[0]
        response[readLength + 1] = SW_SUCCESS[1]

        Log.d(TAG, "ReadBinary: file=$currentFile, offset=$offset, len=$readLength, total=${targetData.size}")
        return response
    }

    override fun onDeactivated(reason: Int) {
        currentFile = SelectedFile.NONE
        val reasonStr = when (reason) {
            DEACTIVATION_LINK_LOSS -> "LINK_LOSS"
            DEACTIVATION_DESELECTED -> "DESELECTED"
            else -> "UNKNOWN ($reason)"
        }
        Log.d(TAG, "HCE onDeactivated: $reasonStr")
    }

    private fun matchesAid(aid: ByteArray): Boolean {
        if (aid.size < AID_PREFIX.size) return false
        for (i in AID_PREFIX.indices) {
            if (aid[i] != AID_PREFIX[i]) return false
        }
        // Last byte should be 0x01 (v2.0) or 0x00 (v1.0)
        if (aid.size >= 7) {
            val versionByte = aid[6].toInt() and 0xFF
            return versionByte == 0x01 || versionByte == 0x00
        }
        return true
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02X".format(it) }
    }
}
