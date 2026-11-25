package com.pixelbump.app.nfc

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Builds standard NFC Data Exchange Format (NDEF) Type 4 messages for iOS background tag dispatch.
 * Fully compliant with NFC Forum NDEF Specification v1.0 and URI Record Type Definition.
 */
object NdefMessageBuilder {

    // URI Identifier Codes according to NFC Forum URI Record Type Definition Table 3
    private const val URI_PREFIX_HTTP_WWW = 0x01.toByte()  // http://www.
    private const val URI_PREFIX_HTTPS_WWW = 0x02.toByte() // https://www.
    private const val URI_PREFIX_HTTP = 0x03.toByte()      // http://
    private const val URI_PREFIX_HTTPS = 0x04.toByte()     // https://
    private const val URI_PREFIX_NONE = 0x00.toByte()      // No prefix

    /**
     * Creates an NDEF message wrapping a URI.
     * When read by an iPhone, iOS automatically triggers the native notification banner to open Safari.
     */
    fun createUriRecord(uriString: String): ByteArray {
        val (prefixByte, cleanUri) = when {
            uriString.startsWith("https://www.") -> Pair(URI_PREFIX_HTTPS_WWW, uriString.removePrefix("https://www."))
            uriString.startsWith("http://www.") -> Pair(URI_PREFIX_HTTP_WWW, uriString.removePrefix("http://www."))
            uriString.startsWith("https://") -> Pair(URI_PREFIX_HTTPS, uriString.removePrefix("https://"))
            uriString.startsWith("http://") -> Pair(URI_PREFIX_HTTP, uriString.removePrefix("http://"))
            else -> Pair(URI_PREFIX_NONE, uriString)
        }

        val uriBytes = cleanUri.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + uriBytes.size).apply {
            this[0] = prefixByte
            System.arraycopy(uriBytes, 0, this, 1, uriBytes.size)
        }

        val type = "U".toByteArray(StandardCharsets.US_ASCII) // 'U' (0x55) for URI
        val out = ByteArrayOutputStream()

        if (payload.size <= 255) {
            // Short Record (SR = 1): 1-byte payload length
            // MB = 1, ME = 1, CF = 0, SR = 1, IL = 0, TNF = 0x01
            val flags = 0xD1.toByte()
            out.write(flags.toInt())
            out.write(type.size)
            out.write(payload.size)
            out.write(type)
            out.write(payload)
        } else {
            // Normal Record (SR = 0): 4-byte payload length
            // MB = 1, ME = 1, CF = 0, SR = 0, IL = 0, TNF = 0x01
            val flags = 0xC1.toByte()
            out.write(flags.toInt())
            out.write(type.size)
            out.write((payload.size ushr 24) and 0xFF)
            out.write((payload.size ushr 16) and 0xFF)
            out.write((payload.size ushr 8) and 0xFF)
            out.write(payload.size and 0xFF)
            out.write(type)
            out.write(payload)
        }

        return out.toByteArray()
    }

    /**
     * Formats raw NDEF payload into the Type 4 Tag file format with a 2-byte big-endian length prefix.
     */
    fun formatType4File(ndefMessage: ByteArray): ByteArray {
        val len = ndefMessage.size
        val file = ByteArray(2 + len)
        file[0] = ((len ushr 8) and 0xFF).toByte()
        file[1] = (len and 0xFF).toByte()
        System.arraycopy(ndefMessage, 0, file, 2, len)
        return file
    }
}
