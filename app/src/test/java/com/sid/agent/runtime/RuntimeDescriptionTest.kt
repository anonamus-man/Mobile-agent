package com.sid.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDescriptionTest {

    @Test
    fun `node major tracks the per-architecture pin`() {
        assertEquals("24", RuntimeDescription.nodeMajor(HostArch.ARM64))
        assertEquals("22", RuntimeDescription.nodeMajor(HostArch.ARM32))
    }

    @Test
    fun `arm32 is labelled as the javascript build`() {
        assertEquals("Claude Code + Node.js 24", RuntimeDescription.agentSummary(HostArch.ARM64))
        assertEquals("Claude Code (JS build) + Node.js 22", RuntimeDescription.agentSummary(HostArch.ARM32))
    }

    @Test
    fun `uname banner reports the guest machine name`() {
        assertTrue(RuntimeDescription.unameBanner(HostArch.ARM64).contains("aarch64"))
        assertTrue(RuntimeDescription.unameBanner(HostArch.ARM32).contains("armv7l"))
    }

    @Test
    fun `unsupported cpus still render something`() {
        assertEquals("Claude Code", RuntimeDescription.agentSummary(null))
        assertTrue(RuntimeDescription.unameBanner(null).contains("unknown"))
    }
}
