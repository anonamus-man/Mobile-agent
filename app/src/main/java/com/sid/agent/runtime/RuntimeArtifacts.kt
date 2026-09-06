package com.sid.agent.runtime

/**
 * Every architecture-dependent download the guest runtime needs.
 *
 * Kept free of Android APIs so the URL/version rules stay unit-testable.
 */
internal object RuntimeArtifacts {

    const val UBUNTU_RELEASE = "20.04"
    const val UBUNTU_POINT_RELEASE = "20.04.5"

    /** Marker written into the rootfs; changing arch invalidates an old install. */
    fun rootfsVersion(arch: HostArch): String = "ubuntu-$UBUNTU_POINT_RELEASE-${arch.debianArch}"

    fun rootfsFileName(arch: HostArch): String =
        "ubuntu-base-$UBUNTU_POINT_RELEASE-base-${arch.debianArch}.tar.gz"

    fun rootfsUrl(arch: HostArch): String =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$UBUNTU_RELEASE/release/${rootfsFileName(arch)}"

    /** From cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/SHA256SUMS. */
    fun rootfsSha256(arch: HostArch): String = when (arch) {
        HostArch.ARM64 -> "f9b999afb4c4b10193087ea8c11be36d688f19e609b05179b571f29357954b52"
        HostArch.ARM32 -> "6bcbfa7f603d79d368d40e138dad98938907d2fb0d6416521417cf8702c2f5de"
    }

    /**
     * Node.js 24 dropped 32-bit ARM binaries, so ARMv7 stays on the newest LTS
     * line that still publishes `linux-armv7l` archives.
     */
    fun nodeVersion(arch: HostArch): String = when (arch) {
        HostArch.ARM64 -> "v24.19.0"
        HostArch.ARM32 -> "v22.20.0"
    }

    fun nodeFileName(arch: HostArch): String =
        "node-${nodeVersion(arch)}-linux-${arch.nodeArch}.tar.gz"

    fun nodeBaseUrl(arch: HostArch): String = "https://nodejs.org/dist/${nodeVersion(arch)}"

    /** Core apt packages installed on first setup. */
    fun coreAptPackages(arch: HostArch): List<String> = buildList {
        add("git")
        add("ca-certificates")
        // Claude Code bundles ripgrep for arm64/x64 only; ARMv7 uses the distro build.
        if (arch.claudeDelivery == ClaudeDelivery.NODE_PACKAGE) add("ripgrep")
        addAll(nodeRuntimeAptPackages(arch))
    }

    /**
     * Shared libraries the official Node.js build for [arch] links against but
     * `ubuntu-base` does not ship.
     *
     * ARMv7 has no native 64-bit atomic instructions, so V8 resolves them
     * through libatomic; the aarch64 build has the instructions and does not
     * link it. `ubuntu-base` carries libstdc++6 and libgcc-s1 already, but
     * never libatomic1, so the armhf `node` binary dies at startup with
     * "error while loading shared libraries: libatomic.so.1".
     */
    fun nodeRuntimeAptPackages(arch: HostArch): List<String> = when (arch) {
        HostArch.ARM32 -> listOf("libatomic1")
        HostArch.ARM64 -> emptyList()
    }

    /**
     * Newest `@anthropic-ai/claude-code` release that still ships a runnable
     * `cli.js`. From 2.1.113 onwards the npm package is only a thin installer
     * that downloads a native binary, and no `linux-arm` binary is published.
     * Used as the fallback when the registry lookup cannot run.
     */
    const val FALLBACK_JS_CLAUDE_VERSION = "2.1.112"

    const val NPM_PACKAGE = "@anthropic-ai/claude-code"
    const val NPM_REGISTRY = "https://registry.npmjs.org"

    /** Guest paths for the JavaScript delivery channel. */
    const val JS_CLAUDE_HOME = "usr/local/lib/claude-code"
    const val JS_CLAUDE_ENTRY = "$JS_CLAUDE_HOME/cli.js"

    /**
     * Launcher installed at /usr/local/bin/claude when Claude Code runs from
     * JavaScript. A 32-bit process tops out near 3 GB of address space, so the
     * V8 heap is capped well below that to keep allocation failures out of the
     * middle of a long agent turn.
     */
    fun jsClaudeLauncherScript(): String = """
        #!/bin/sh
        # Mobile Agent: Claude Code (JavaScript build) for 32-bit ARM guests.
        exec /usr/local/bin/node \
            --max-old-space-size="${'$'}{MH_NODE_HEAP_MB:-1024}" \
            /${JS_CLAUDE_ENTRY} "${'$'}@"
    """.trimIndent() + "\n"
}
