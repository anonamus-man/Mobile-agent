package com.anonamus.mobileagent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeArtifactsTest {

    @Test
    fun `rootfs urls follow the ubuntu cdimage layout`() {
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-arm64.tar.gz",
            RuntimeArtifacts.rootfsUrl(HostArch.ARM64),
        )
        assertEquals(
            "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/ubuntu-base-20.04.5-base-armhf.tar.gz",
            RuntimeArtifacts.rootfsUrl(HostArch.ARM32),
        )
    }

    @Test
    fun `rootfs markers differ per architecture so switching abi reinstalls`() {
        assertEquals("ubuntu-20.04.5-arm64", RuntimeArtifacts.rootfsVersion(HostArch.ARM64))
        assertEquals("ubuntu-20.04.5-armhf", RuntimeArtifacts.rootfsVersion(HostArch.ARM32))
    }

    @Test
    fun `checksums are distinct 64 character hex digests`() {
        HostArch.entries.forEach { arch ->
            val checksum = RuntimeArtifacts.rootfsSha256(arch)
            assertTrue(arch.name, checksum.matches(Regex("[0-9a-f]{64}")))
        }
        assertTrue(
            RuntimeArtifacts.rootfsSha256(HostArch.ARM64) != RuntimeArtifacts.rootfsSha256(HostArch.ARM32),
        )
    }

    @Test
    fun `node archives match the published nodejs file names`() {
        assertEquals("node-v24.19.0-linux-arm64.tar.gz", RuntimeArtifacts.nodeFileName(HostArch.ARM64))
        // Node.js 24 dropped armv7l, so 32-bit ARM stays on the 22 LTS line.
        assertEquals("node-v22.20.0-linux-armv7l.tar.gz", RuntimeArtifacts.nodeFileName(HostArch.ARM32))
        assertEquals("https://nodejs.org/dist/v22.20.0", RuntimeArtifacts.nodeBaseUrl(HostArch.ARM32))
    }

    @Test
    fun `only the javascript channel installs ripgrep from the distro`() {
        assertFalse(RuntimeArtifacts.coreAptPackages(HostArch.ARM64).contains("ripgrep"))
        assertTrue(RuntimeArtifacts.coreAptPackages(HostArch.ARM32).contains("ripgrep"))
        HostArch.entries.forEach { arch ->
            assertTrue(RuntimeArtifacts.coreAptPackages(arch).containsAll(listOf("git", "ca-certificates")))
        }
    }

    @Test
    fun `javascript launcher execs the guest node against the extracted cli`() {
        val script = RuntimeArtifacts.jsClaudeLauncherScript()
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("/usr/local/bin/node"))
        assertTrue(script.contains("/usr/local/lib/claude-code/cli.js"))
        // Unexpanded shell placeholders would silently break argument passing.
        assertTrue(script.contains("\"\$@\""))
        assertTrue(script.contains("\${MH_NODE_HEAP_MB:-1024}"))
        assertTrue(script.endsWith("\n"))
    }

    @Test
    fun `claude delivery is native only where anthropic publishes a binary`() {
        assertEquals(ClaudeDelivery.NATIVE_BINARY, HostArch.ARM64.claudeDelivery)
        assertEquals(ClaudeDelivery.NODE_PACKAGE, HostArch.ARM32.claudeDelivery)
    }
}
