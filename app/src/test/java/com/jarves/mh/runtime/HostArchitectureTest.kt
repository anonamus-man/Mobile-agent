package com.jarves.mh.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostArchitectureTest {

    @Test
    fun `64-bit process on an arm64 device uses the arm64 profile`() {
        val arch = HostArchitecture.detect(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), is64BitProcess = true)
        assertEquals(HostArch.ARM64, arch)
    }

    @Test
    fun `32-bit build on a 64-bit device uses the arm32 profile`() {
        // An armeabi-v7a APK still sees arm64-v8a in SUPPORTED_ABIS, but PRoot is
        // a 32-bit tracer there and must supervise an armhf guest.
        val arch = HostArchitecture.detect(listOf("arm64-v8a", "armeabi-v7a", "armeabi"), is64BitProcess = false)
        assertEquals(HostArch.ARM32, arch)
    }

    @Test
    fun `32-bit only device uses the arm32 profile`() {
        val arch = HostArchitecture.detect(listOf("armeabi-v7a", "armeabi"), is64BitProcess = false)
        assertEquals(HostArch.ARM32, arch)
    }

    @Test
    fun `non-ARM devices are unsupported`() {
        assertNull(HostArchitecture.detect(listOf("x86_64", "x86"), is64BitProcess = true))
        assertNull(HostArchitecture.detect(listOf("x86"), is64BitProcess = false))
    }

    @Test
    fun `arm64-only device cannot run a 32-bit process`() {
        assertNull(HostArchitecture.detect(listOf("arm64-v8a"), is64BitProcess = false))
    }

    @Test
    fun `each profile advertises a distinct guest toolchain`() {
        assertEquals("arm64", HostArch.ARM64.debianArch)
        assertEquals("armhf", HostArch.ARM32.debianArch)
        assertEquals("arm64", HostArch.ARM64.nodeArch)
        assertEquals("armv7l", HostArch.ARM32.nodeArch)
        assertTrue(HostArch.ARM64.is64Bit)
        assertTrue(!HostArch.ARM32.is64Bit)
    }
}
