package com.jarves.mh.runtime

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * A checksum a download must match.
 *
 * Ubuntu and Node.js publish hex SHA-256; the npm registry publishes
 * Base64 SHA-512 in Subresource Integrity form, so both encodings are supported.
 */
internal data class ExpectedDigest(
    val algorithm: String,
    val value: String,
    val base64: Boolean,
) {
    fun matches(file: File): Boolean = encode(digest(file)).equals(value, ignoreCase = !base64)

    private fun digest(file: File): ByteArray {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest()
    }

    private fun encode(bytes: ByteArray): String = if (base64) {
        Base64.getEncoder().encodeToString(bytes)
    } else {
        bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Parses an npm `dist.integrity` value such as `sha512-<base64>`. */
        fun fromNpmIntegrity(integrity: String): ExpectedDigest {
            // Multiple hashes may be space-separated; the strongest is listed first.
            val entry = integrity.trim().split(' ').first()
            val prefix = entry.substringBefore('-', missingDelimiterValue = "")
            val encoded = entry.substringAfter('-', missingDelimiterValue = "")
            require(encoded.isNotBlank()) { "Unsupported package integrity value" }
            val algorithm = when (prefix.lowercase()) {
                "sha512" -> "SHA-512"
                "sha384" -> "SHA-384"
                "sha256" -> "SHA-256"
                "sha1" -> "SHA-1"
                else -> error("Unsupported package integrity algorithm: $prefix")
            }
            return ExpectedDigest(algorithm, encoded, base64 = true)
        }
    }
}
