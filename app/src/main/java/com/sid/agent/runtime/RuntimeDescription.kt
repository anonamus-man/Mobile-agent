package com.sid.agent.runtime

/**
 * Human-readable summaries of the guest runtime.
 *
 * The toolchain differs per CPU — 32-bit ARM pins Node.js to the newest LTS that
 * still publishes `armv7l` builds and runs the JavaScript Claude Code — so these
 * strings are derived rather than hard-coded in the UI.
 */
object RuntimeDescription {

    fun nodeMajor(arch: HostArch?): String =
        arch?.let { RuntimeArtifacts.nodeVersion(it).removePrefix("v").substringBefore('.') } ?: "24"

    fun agentSummary(arch: HostArch? = HostArchitecture.current): String = when (arch?.claudeDelivery) {
        null -> "Claude Code"
        ClaudeDelivery.NATIVE_BINARY -> "Claude Code + Node.js ${nodeMajor(arch)}"
        ClaudeDelivery.NODE_PACKAGE -> "Claude Code (JS build) + Node.js ${nodeMajor(arch)}"
    }

    fun developerTools(arch: HostArch? = HostArchitecture.current): String =
        "${agentSummary(arch)} + Python 3"

    fun unameBanner(arch: HostArch? = HostArchitecture.current): String {
        val machine = arch?.unameMachine ?: "unknown"
        return "Linux pocket-dev 6.1.0-$machine #1 SMP $machine GNU/Linux (PRoot Sandbox)"
    }
}
