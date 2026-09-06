package com.sid.agent.runtime

import android.os.Build
import android.os.Process

/**
 * CPU profile Mobile Agent is executing under.
 *
 * PRoot is a ptrace supervisor, so the guest userspace must match the bitness of
 * the *app process*, not just the hardware: a 32-bit tracer cannot single-step a
 * 64-bit tracee. Everything the runtime downloads — Ubuntu rootfs, Node.js, and
 * the Claude Code delivery channel — is therefore selected from this enum.
 */
enum class HostArch(
    /** Android ABI name for this profile. */
    val abi: String,
    /** Ubuntu/Debian port name used by cdimage.ubuntu.com. */
    val debianArch: String,
    /** Suffix used by nodejs.org release archives. */
    val nodeArch: String,
    /** `uname -m` value reported inside the guest. */
    val unameMachine: String,
    /** Label shown in Settings and the compatibility checks. */
    val displayName: String,
    /** Practical RAM floor before the agent starts thrashing. */
    val minimumRamGb: Int,
    /** How Claude Code is obtained for this profile. */
    val claudeDelivery: ClaudeDelivery,
) {
    /** 64-bit ARM: Anthropic publishes a native `linux-arm64` Claude Code binary. */
    ARM64(
        abi = "arm64-v8a",
        debianArch = "arm64",
        nodeArch = "arm64",
        unameMachine = "aarch64",
        displayName = "ARM64 (aarch64)",
        minimumRamGb = 4,
        claudeDelivery = ClaudeDelivery.NATIVE_BINARY,
    ),

    /**
     * 32-bit ARM. Anthropic ships no `linux-arm` binary, so Claude Code is
     * installed from the last npm release that still bundles the JavaScript
     * `cli.js` entry point and runs on the 32-bit Node.js build.
     */
    ARM32(
        abi = "armeabi-v7a",
        debianArch = "armhf",
        nodeArch = "armv7l",
        unameMachine = "armv7l",
        displayName = "ARM32 (armv7l)",
        minimumRamGb = 2,
        claudeDelivery = ClaudeDelivery.NODE_PACKAGE,
    ),
    ;

    val is64Bit: Boolean get() = this == ARM64
}

/** Where the `claude` executable inside the guest comes from. */
enum class ClaudeDelivery {
    /** Signed standalone binary from downloads.claude.ai. */
    NATIVE_BINARY,

    /** npm tarball whose `cli.js` is launched through the guest Node.js. */
    NODE_PACKAGE,
}

object HostArchitecture {

    /** Profile for the running process, or `null` on an unsupported CPU. */
    val current: HostArch? by lazy { detect(Build.SUPPORTED_ABIS.orEmpty().toList(), Process.is64Bit()) }

    /** Convenience label for UI surfaces that must render something on any device. */
    val displayName: String
        get() = current?.displayName ?: (Build.SUPPORTED_ABIS.orEmpty().firstOrNull() ?: "Unknown")

    fun requireSupported(): HostArch = current ?: error(UNSUPPORTED_MESSAGE)

    /**
     * Pure resolver so the rule is unit-testable.
     *
     * [is64BitProcess] is deliberately the *process* bitness. An armeabi-v7a APK
     * installed on a 64-bit phone still reports `arm64-v8a` in
     * [Build.SUPPORTED_ABIS] while running as a 32-bit process, and in that case
     * the guest must be armhf.
     */
    fun detect(supportedAbis: List<String>, is64BitProcess: Boolean): HostArch? = when {
        is64BitProcess -> HostArch.ARM64.takeIf { supportedAbis.contains(it.abi) }
        else -> HostArch.ARM32.takeIf { supportedAbis.contains(it.abi) }
    }

    const val UNSUPPORTED_MESSAGE: String =
        "Mobile Agent needs an ARM CPU (arm64-v8a or armeabi-v7a). This device is not supported."
}
