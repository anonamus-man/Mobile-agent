package com.jarves.mh.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpectedDigestTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun fileOf(content: String): File =
        temporaryFolder.newFile().apply { writeText(content) }

    @Test
    fun `a mismatched digest is rejected`() {
        val file = fileOf("mobile harness")
        // sha256 of "mobile harness" with the final nibble flipped.
        val wrong = "f4ae015c6d5adfd3e69bd3543803ccc47b3ff9e822a2d357797afac3c15ff4fd"
        assertFalse(ExpectedDigest("SHA-256", wrong, base64 = false).matches(file))
        val right = "f4ae015c6d5adfd3e69bd3543803ccc47b3ff9e822a2d357797afac3c15ff4fc"
        assertTrue(ExpectedDigest("SHA-256", right, base64 = false).matches(file))
    }

    @Test
    fun `sha256 round trips through the matcher`() {
        val file = fileOf("hello")
        val digest = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertTrue(ExpectedDigest("SHA-256", digest, base64 = false).matches(file))
        assertTrue(ExpectedDigest("SHA-256", digest.uppercase(), base64 = false).matches(file))
    }

    @Test
    fun `npm sha512 integrity is parsed and verified`() {
        val file = fileOf("hello")
        val digest = ExpectedDigest.fromNpmIntegrity(
            "sha512-m3HSJL1i83hdltRq0+o9czGb+8KJDKra4t/3JRlnPKcjI8PZm6XBHXx6zG4UuMXaDEZjR1wuXDre9G9zvN7AQw==",
        )
        assertEquals("SHA-512", digest.algorithm)
        assertTrue(digest.base64)
        assertTrue(digest.matches(file))
        assertFalse(digest.matches(fileOf("hello world")))
    }

    @Test
    fun `integrity picks the first of several published hashes`() {
        val digest = ExpectedDigest.fromNpmIntegrity("sha256-abc== sha1-def=")
        assertEquals("SHA-256", digest.algorithm)
        assertEquals("abc==", digest.value)
    }

    @Test(expected = IllegalStateException::class)
    fun `unknown integrity algorithms are rejected`() {
        ExpectedDigest.fromNpmIntegrity("md5-abc==")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed integrity values are rejected`() {
        ExpectedDigest.fromNpmIntegrity("sha512")
    }
}
