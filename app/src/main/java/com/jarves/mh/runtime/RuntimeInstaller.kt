package com.jarves.mh.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.coroutineContext
import com.jarves.mh.model.DevStack
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

data class InstalledRuntime(
    val proot: File,
    val rootfs: File,
    val claude: File,
    val version: String,
)

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
)

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val marker = File(rootfs, ".pocket-runtime-ready")
    private val rootfsMarker = File(rootfs, ".pocket-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".pocket-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".pocket-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".pocket-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".pocket-dev-stacks.json")

    /**
     * Null on a CPU Mobile Harness cannot host. Resolved lazily rather than in the
     * constructor so the app still starts and can explain itself on such devices.
     */
    private val hostArch: HostArch? get() = HostArchitecture.current

    fun isInstalled(): Boolean {
        val arch = hostArch ?: return false
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        // Devices set up before staged toolchains keep working through the legacy marker;
        // fresh installs require the new core-tools marker instead.
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() &&
            coreToolsMarker.readTextOrNull() == CORE_TOOLS_VERSION
        return proot.canExecute() &&
            File(rootfs, "usr/bin/bash").exists() &&
            rootfsMarker.readTextOrNull() == RuntimeArtifacts.rootfsVersion(arch) &&
            File(rootfs, "usr/local/bin/claude").exists() &&
            claudePayloadPresent(arch) &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            marker.exists()
    }

    /**
     * The JavaScript channel installs a launcher plus a payload directory; a
     * launcher on its own means an interrupted install that must be repaired.
     */
    private fun claudePayloadPresent(arch: HostArch): Boolean = when (arch.claudeDelivery) {
        ClaudeDelivery.NATIVE_BINARY -> true
        ClaudeDelivery.NODE_PACKAGE -> File(rootfs, RuntimeArtifacts.JS_CLAUDE_ENTRY).isFile
    }

    /** Returns the already verified runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { "Claude Code setup is incomplete. Reopen Mobile Harness to repair it." }
        return InstalledRuntime(
            proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so"),
            rootfs = rootfs,
            claude = File(rootfs, "usr/local/bin/claude"),
            version = marker.readText().trim(),
        )
    }

    /** Removes only scaffolding written automatically by earlier PocketDev alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        val arch = HostArchitecture.requireSupported()
        val proot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        require(proot.canExecute()) { "The embedded PRoot launcher is unavailable" }

        val rootfsVersion = RuntimeArtifacts.rootfsVersion(arch)
        if (!File(rootfs, "usr/bin/bash").exists() || rootfsMarker.readTextOrNull() != rootfsVersion) {
            onProgress(RuntimeInstallProgress("Downloading the private Ubuntu runtime", 0.03f))
            downloads.mkdirs()
            val archive = File(downloads, RuntimeArtifacts.rootfsFileName(arch))
            downloadVerified(
                RuntimeArtifacts.rootfsUrl(arch),
                archive,
                RuntimeArtifacts.rootfsSha256(arch),
            ) { downloaded, total ->
                val ratio = if (total > 0) downloaded.toFloat() / total else 0f
                onProgress(RuntimeInstallProgress("Downloading Ubuntu", 0.03f + ratio * 0.22f, downloaded, total.takeIf { it > 0 }))
            }
            onProgress(RuntimeInstallProgress("Verifying and unpacking Ubuntu", 0.28f))
            val staging = File(runtimeDir, "ubuntu.installing")
            staging.deleteRecursively()
            staging.mkdirs()
            extractRootfs(archive, staging)
            File(staging, ".pocket-rootfs-version").writeText(rootfsVersion)
            rootfs.deleteRecursively()
            check(staging.renameTo(rootfs)) { "Could not activate the Linux environment" }
            writeResolver()
            archive.delete()
        }

        val coreNeeded = !File(rootfs, "usr/bin/git").exists() || coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION

        // On 32-bit ARM the agent itself is JavaScript, so the guest Node.js has
        // to be staged before Claude Code can be installed at all.
        if (arch.claudeDelivery == ClaudeDelivery.NODE_PACKAGE) {
            installNodeIfNeeded(arch, proot, 0.30f, 0.40f, onProgress)
        }

        onProgress(RuntimeInstallProgress("Checking the latest Claude Code release", 0.42f))
        when (arch.claudeDelivery) {
            ClaudeDelivery.NATIVE_BINARY -> installNativeClaude(onProgress)
            ClaudeDelivery.NODE_PACKAGE -> installJavaScriptClaude(onProgress)
        }

        // Stage developer tools one group at a time so first setup only downloads
        // what the user actually picked. Node.js + Git are the always-needed core
        // because Claude Code itself runs on Node.
        if (coreNeeded) {
            installNodeIfNeeded(arch, proot, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            runSystemMaintenance(proot, onProgress)
            systemUpgradeMarker.writeText(SYSTEM_UPGRADE_VERSION)
        }
        if (coreNeeded) {
            aptInstall(
                proot,
                RuntimeArtifacts.coreAptPackages(arch),
                "Installing Git and base tools",
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(proot, "git --version", "Base tools could not be verified")
            coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        }

        val missingStacks = selectedStacks.filterNot(::isStackInstalled)
        missingStacks.forEachIndexed { index, stack ->
            val slice = 0.26f / maxOf(1, missingStacks.size)
            val from = 0.72f + index * slice
            applyStack(proot, stack, from, from + slice, onProgress)
        }

        // Downloads were checksum-verified above. Running a separate
        // `claude --version` probe under PRoot can leave inherited output pipes
        // open on some Android kernels, so the real user session is the launch check.
        onProgress(RuntimeInstallProgress("Setup complete", 1f))
        return InstalledRuntime(
            proot = proot,
            rootfs = rootfs,
            claude = File(rootfs, "usr/local/bin/claude"),
            version = marker.readText().trim(),
        )
    }

    /**
     * ARM64: Anthropic's signed standalone build, verified against the checksum
     * in the release manifest.
     */
    private suspend fun installNativeClaude(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        val version = fetchText("${RuntimeArtifacts.NPM_REGISTRY}/${RuntimeArtifacts.NPM_PACKAGE}/latest")
            .let { JSONObject(it).getString("version") }
            .also { require(it.matches(VERSION_PATTERN)) }
        val claude = File(rootfs, "usr/local/bin/claude")
        if (marker.exists() && marker.readText().trim() == version && claude.exists()) return

        onProgress(RuntimeInstallProgress("Downloading Claude Code $version from Anthropic", 0.35f))
        val base = "https://downloads.claude.ai/claude-code-releases/$version"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val staged = File(downloads, "claude-$version")
        downloadVerified("$base/linux-arm64/claude", staged, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress("Downloading Claude Code $version", 0.35f + ratio * 0.20f, downloaded, total.takeIf { it > 0 }))
        }
        onProgress(RuntimeInstallProgress("Verifying Claude Code", 0.56f))
        claude.parentFile?.mkdirs()
        if (claude.exists()) claude.delete()
        check(staged.renameTo(claude)) { "Could not activate Claude Code" }
        Os.chmod(claude.absolutePath, 0b111101101)
        ensureSettingsAndHooks()
        marker.writeText(version)
    }

    /**
     * ARM32: Anthropic publishes no `linux-arm` binary, so the npm tarball is
     * used instead and executed by the guest Node.js. Only releases up to
     * [RuntimeArtifacts.FALLBACK_JS_CLAUDE_VERSION] carry a real `cli.js`;
     * later ones are installers for binaries that do not exist for this CPU.
     */
    private suspend fun installJavaScriptClaude(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        val release = resolveJavaScriptClaudeRelease()
        val claude = File(rootfs, "usr/local/bin/claude")
        val entry = File(rootfs, RuntimeArtifacts.JS_CLAUDE_ENTRY)
        if (marker.exists() && marker.readText().trim() == release.version && claude.exists() && entry.isFile) return

        onProgress(RuntimeInstallProgress("Downloading Claude Code ${release.version}", 0.44f))
        downloads.mkdirs()
        val staged = File(downloads, "claude-code-${release.version}.tgz")
        downloadVerified(release.tarball, staged, release.digest) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    "Downloading Claude Code ${release.version}",
                    0.44f + ratio * 0.10f,
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }

        onProgress(RuntimeInstallProgress("Installing Claude Code ${release.version}", 0.55f))
        val staging = File(runtimeDir, "claude-code.installing")
        staging.deleteRecursively()
        staging.mkdirs()
        // npm tarballs nest everything under package/.
        extractTarStrippingRoot(staged, staging, "Claude Code")
        check(File(staging, "cli.js").isFile) {
            "Claude Code ${release.version} does not contain a JavaScript entry point"
        }
        val home = File(rootfs, RuntimeArtifacts.JS_CLAUDE_HOME)
        home.deleteRecursively()
        home.parentFile?.mkdirs()
        check(staging.renameTo(home)) { "Could not activate Claude Code" }
        Os.chmod(File(home, "cli.js").absolutePath, 0b111101101)
        staged.delete()

        claude.parentFile?.mkdirs()
        if (claude.exists() || java.nio.file.Files.isSymbolicLink(claude.toPath())) claude.delete()
        claude.writeText(RuntimeArtifacts.jsClaudeLauncherScript())
        Os.chmod(claude.absolutePath, 0b111101101)
        ensureSettingsAndHooks()
        marker.writeText(release.version)
    }

    private data class NpmRelease(val version: String, val tarball: String, val digest: ExpectedDigest)

    /**
     * Picks the newest published release that still exposes `bin.claude = cli.js`,
     * falling back to the known-good pin if the registry cannot be reached or its
     * shape changes. The abbreviated packument keeps this to a single request.
     */
    private fun resolveJavaScriptClaudeRelease(): NpmRelease {
        val discovered = runCatching {
            val document = JSONObject(
                fetchText(
                    "${RuntimeArtifacts.NPM_REGISTRY}/${RuntimeArtifacts.NPM_PACKAGE}",
                    accept = "application/vnd.npm.install-v1+json",
                ),
            )
            val versions = document.getJSONObject("versions")
            versions.keys()
                .asSequence()
                .filter { it.matches(VERSION_PATTERN) }
                .filter { version ->
                    versions.getJSONObject(version)
                        .optJSONObject("bin")
                        ?.optString("claude")
                        ?.endsWith("cli.js") == true
                }
                .maxWithOrNull(SEMVER_ORDER)
                ?.let { version -> npmRelease(versions.getJSONObject(version), version) }
        }.getOrNull()
        if (discovered != null) return discovered

        val pinned = RuntimeArtifacts.FALLBACK_JS_CLAUDE_VERSION
        val metadata = JSONObject(
            fetchText("${RuntimeArtifacts.NPM_REGISTRY}/${RuntimeArtifacts.NPM_PACKAGE}/$pinned"),
        )
        return npmRelease(metadata, pinned)
    }

    private fun npmRelease(metadata: JSONObject, version: String): NpmRelease {
        val dist = metadata.getJSONObject("dist")
        val tarball = dist.getString("tarball")
        require(tarball.startsWith("https://")) { "Claude Code download URL is not secure" }
        val digest = dist.optString("integrity").takeIf { it.isNotBlank() }
            ?.let(ExpectedDigest::fromNpmIntegrity)
            ?: ExpectedDigest("SHA-1", dist.getString("shasum"), base64 = false)
        return NpmRelease(version, tarball, digest)
    }


    /**
     * Installs one optional development stack inside Ubuntu. Safe to call again:
     * already-installed stacks return immediately without network access.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        if (isStackInstalled(stack)) return
        applyStack(runtime.proot, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress("${stack.label} tools are ready", 1f))
    }

    private suspend fun applyStack(
        proot: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress("Checking Node.js and npm", from))
                verifyGuest(proot, "node --version && npm --version", "Node.js tools could not be verified")
            }
            DevStack.PYTHON -> {
                aptInstall(proot, listOf("python3", "python3-pip", "python3-venv", "build-essential"), "Installing Python, pip, and build tools", from, onProgress)
                verifyGuest(proot, "python3 --version && pip3 --version", "Python tools could not be verified")
            }
            DevStack.ANDROID -> {
                onProgress(RuntimeInstallProgress("Installing the Java development kit", from))
                val jdk17 = runCatching {
                    aptInstallInternal(proot, listOf("openjdk-17-jdk-headless"), from, onProgress)
                }
                if (jdk17.isFailure) {
                    // Older Ubuntu mirrors may not carry JDK 17; JDK 11 still builds
                    // classic Android projects and keeps setup from hard-failing.
                    aptInstallInternal(proot, listOf("openjdk-11-jdk-headless"), from, onProgress)
                }
                verifyGuest(proot, "java -version", "Java could not be verified")
            }
            DevStack.CPP -> {
                aptInstall(
                    proot,
                    listOf("build-essential", "cmake", "gdb"),
                    "Installing C/C++ compilers and build tools",
                    from,
                    onProgress,
                )
                verifyGuest(
                    proot,
                    "gcc --version && g++ --version && make --version && cmake --version",
                    "C/C++ tools could not be verified",
                )
            }
            DevStack.PHP -> {
                aptInstall(
                    proot,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                    "Installing PHP and common extensions",
                    from,
                    onProgress,
                )
                installComposer(proot, from, onProgress)
                verifyGuest(proot, "php --version && composer --version", "PHP tools could not be verified")
            }
        }
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress("${stack.label} installed", to))
    }

    /**
     * Installs Composer into Ubuntu from the official latest-stable release,
     * verified against getcomposer.org's published SHA-256 checksum.
     */
    private suspend fun installComposer(
        proot: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile) return
        onProgress(RuntimeInstallProgress("Downloading Composer", fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksum = fetchText("https://getcomposer.org/download/latest-stable/composer.phar.sha256sum")
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.substringBefore(' ')
            ?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("Composer checksum was not found")
        downloadVerified(
            "https://getcomposer.org/download/latest-stable/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        if (composer.exists()) composer.delete()
        // cacheDir and filesDir can live on different mounts: copy instead of rename.
        staged.inputStream().use { input -> FileOutputStream(composer).use { input.copyTo(it) } }
        staged.delete()
        Os.chmod(composer.absolutePath, 0b111101101)
        onProgress(RuntimeInstallProgress("Installing Composer", fraction))
    }

    /**
     * One-time upgrade path from the old single-bundle layout: devices that already
     * installed every tool keep all stacks without re-downloading anything.
     */
    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeText(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .toSet()

    fun isStackInstalled(stack: DevStack): Boolean = readDevStackState()[stack.name] == true

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        devStacksFile.writeText(obj.toString())
    }

    private suspend fun installNodeIfNeeded(
        arch: HostArch,
        proot: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        val nodeVersion = RuntimeArtifacts.nodeVersion(arch)
        onProgress(RuntimeInstallProgress("Downloading Node.js $nodeVersion LTS", from))
        downloads.mkdirs()
        val nodeFileName = RuntimeArtifacts.nodeFileName(arch)
        val nodeBaseUrl = RuntimeArtifacts.nodeBaseUrl(arch)
        val checksum = fetchText("$nodeBaseUrl/SHASUMS256.txt")
            .lineSequence()
            .map(String::trim)
            .firstOrNull { it.endsWith("  $nodeFileName") }
            ?.substringBefore(' ')
            ?: error("Node.js checksum was not found")
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    "Downloading Node.js $nodeVersion LTS",
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing Node.js and npm", to))
        val nodeStaging = File(runtimeDir, "node.installing")
        nodeStaging.deleteRecursively()
        nodeStaging.mkdirs()
        extractTarStrippingRoot(nodeArchive, nodeStaging, "Node.js")
        val nodeHome = File(rootfs, "usr/local/lib/nodejs")
        nodeHome.deleteRecursively()
        nodeHome.parentFile?.mkdirs()
        check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
        val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
        listOf("node", "npm", "npx", "corepack").forEach { command ->
            val link = File(localBin, command)
            if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
            Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
        }
        nodeArchive.delete()
    }

    private suspend fun aptInstall(
        proot: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(proot, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        proot: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = "Updating the private Ubuntu environment",
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Ubuntu maintenance could not be completed",
        )
    }

    private suspend fun aptInstallInternal(
        proot: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            proot = proot,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install: $packageNames",
        )
    }

    private suspend fun runGuestCommand(
        proot: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@pocket:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            input.seek(offset)
                            val available = (input.length() - offset).coerceAtMost(256 * 1024).toInt()
                            val bytes = ByteArray(available)
                            input.readFully(bytes)
                            offset += available
                            pending += bytes.toString(Charsets.UTF_8).replace('\r', '\n')
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } finally {
            if (running.isAlive) running.destroy()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) "Command completed" else "Command failed (exit $exit)",
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { collected.toString().trim().takeLast(1_000).ifBlank { failureMessage } }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(proot: File, command: String, failureMessage: String) {
        val verify = process(
            proot = proot,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
        )
        withTimeout(60_000L) {
            while (verify.isAlive) delay(50)
        }
        val exit = verify.waitFor()
        val output = (verify as? NativeSpawnProcess)?.outputFile?.readText().orEmpty().trim()
        check(exit == 0) { output.ifBlank { failureMessage } }
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        val proot = installed.proot
        val claude = installed.claude
        val version = installed.version
        onProgress(RuntimeInstallProgress("Checking private runtime files", 0.15f))
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing the Android runtime bridge", 0.42f))
        val probe = process(proot, rootfs, File(rootfs, "root"), emptyMap(), listOf("/usr/local/bin/claude", "--version"))
        onProgress(RuntimeInstallProgress("Starting Claude Code $version", 0.68f))
        withTimeout(20_000) {
            while (probe.isAlive) delay(50)
        }
        val exit = probe.waitFor()
        val output = (probe as? NativeSpawnProcess)?.outputFile?.readText().orEmpty().trim()
        check(exit == 0) { output.ifBlank { "Claude Code initialization failed (exit $exit)" } }
        onProgress(RuntimeInstallProgress("Claude Code is ready", 1f))
        return installed
    }

    fun process(
        proot: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
    ): Process {
        require(
            guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        workspace.mkdirs()
        File(rootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        ensureWorkspaceTrust(guestWorkspacePath)
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        val args = buildList {
            add(proot.absolutePath)
            add("--link2symlink")
            add("-0")
            add("-r")
            add(rootfs.absolutePath)
            add("-b")
            add("/dev")
            add("-b")
            add("/proc")
            add("-b")
            add("/sys")
            add("-b")
            add("${workspace.absolutePath}:$guestWorkspacePath")
            add("-b")
            add("${bridge.absolutePath}:/pocket-bridge")
            add("-w")
            add(guestWorkspacePath)
            addAll(guestCommand)
        }
        val prootTemp = File(context.cacheDir, "proot-tmp").apply { mkdirs() }
        return NativeSpawnProcess.start(
            argv = args,
            environment = buildMap {
                put("HOME", "/root")
                put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
                put("LANG", "C.UTF-8")
                put("TERM", "xterm-256color")
                put("LD_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir)
                put("PROOT_NO_SECCOMP", "1")
                put("PROOT_TMP_DIR", prootTemp.absolutePath)
                put("PROOT_LOADER", File(context.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath)
                // Also protects any glibc helper Claude starts later.
                put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
                // Claude Code only vendors ripgrep for arm64/x64, so 32-bit ARM
                // guests use the ripgrep installed from the Ubuntu archive.
                if (hostArch?.claudeDelivery == ClaudeDelivery.NODE_PACKAGE) {
                    put("USE_BUILTIN_RIPGREP", "0")
                }
                putAll(environment)
            },
            cwd = context.filesDir.absolutePath,
            outputFile = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        )
    }

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/pocket/permission-hook.sh")
        hook.parentFile?.mkdirs()
        hook.writeText(
            """#!/bin/sh
cat > /dev/null
printf '%s\n' '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}'
""",
        )
        Os.chmod(hook.absolutePath, 0b111101101)

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject().put(
                    "PermissionRequest",
                    org.json.JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                org.json.JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/pocket/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/pocket-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            target.parentFile?.mkdirs()
            target.writeText(settingsContent)
        }
        ensureWorkspaceTrust("/workspace")
    }

    private fun ensureWorkspaceTrust(workspacePath: String) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = runCatching { JSONObject(stateFile.readText()) }.getOrElse { JSONObject() }
        // Older alpha builds incorrectly wrote settings into Claude's state file.
        // Keep Claude's generated state, but remove only those stale settings keys.
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach(state::remove)
        val projects = state.optJSONObject("projects") ?: JSONObject()
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject()
        workspace.put("hasTrustDialogAccepted", true)
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        stateFile.writeText(state.toString())
    }

    private fun claudeWorkspaceToolRules() = org.json.JSONArray().apply {
        put("Bash")
        put("Edit")
        put("Write")
        put("NotebookEdit")
        put("Read")
        put("Glob")
        put("Grep")
    }

    private fun writeResolver() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val servers = dns.mapNotNull { it.hostAddress }.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it" } + "\n")
    }

    private fun extractRootfs(archive: File, destination: File) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val cleanName = entry.name.removePrefix("./")
                val target = safeChild(destination, cleanName)
                when {
                    entry.isDirectory -> target.mkdirs()
                    entry.isSymbolicLink -> {
                        target.parentFile?.mkdirs()
                        if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        Os.symlink(entry.linkName, target.absolutePath)
                    }
                    entry.isLink -> {
                        target.parentFile?.mkdirs()
                        val linkTarget = safeChild(destination, entry.linkName.removePrefix("./"))
                        if (linkTarget.exists()) {
                            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                        } else {
                            deferredLinks += target to linkTarget
                        }
                    }
                    entry.isFile -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { output -> tar.copyTo(output) }
                        runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "Archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    /**
     * Extracts a tar.gz whose payload sits under a single top-level directory
     * (Node.js distributions and npm tarballs both do), dropping that prefix.
     */
    private fun extractTarStrippingRoot(archive: File, destination: File, label: String) {
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextEntry
            while (entry != null) {
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isNotBlank()) {
                    val target = safeChild(destination, relative)
                    when {
                        entry.isDirectory -> target.mkdirs()
                        entry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(entry.linkName, target.absolutePath)
                        }
                        entry.isLink -> {
                            val relativeLink = entry.linkName.removePrefix("./").substringAfter('/', "")
                            val linkTarget = safeChild(destination, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists()) {
                                linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        entry.isFile -> {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, entry.mode and 0b111111111) }
                        }
                    }
                }
                entry = tar.nextEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile) { "$label archive hard-link target is missing" }
            target.parentFile?.mkdirs()
            linkTarget.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it) } }
            runCatching { Os.chmod(target.absolutePath, android.system.Os.stat(linkTarget.absolutePath).st_mode) }
        }
    }

    private fun safeChild(root: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith('/')) { "Unsafe archive path" }
        val file = File(root, relative)
        val rootPath = root.canonicalFile.toPath()
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Archive path escapes runtime" }
        return file
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedSha256: String,
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) = downloadVerified(url, destination, ExpectedDigest("SHA-256", expectedSha256, base64 = false), onBytes)

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expected: ExpectedDigest,
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
        check(connection.responseCode in 200..299) { "Download failed with HTTP ${connection.responseCode}" }
        val resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL && existing > 0L
        if (!resumed) {
            temporary.delete()
            existing = 0L
        }
        val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
        connection.inputStream.use { input ->
            FileOutputStream(temporary, resumed).use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = existing
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onBytes(downloaded, total)
                }
            }
        }
        connection.disconnect()
        if (!expected.matches(temporary)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String, accept: String = "application/json"): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", accept)
        check(connection.responseCode in 200..299) { "Request failed with HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-v1"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private val VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")

        /** Orders semantic versions numerically so 2.1.112 sorts above 2.1.99. */
        private val SEMVER_ORDER = Comparator<String> { left, right ->
            val a = left.split('.').map { it.toIntOrNull() ?: 0 }
            val b = right.split('.').map { it.toIntOrNull() ?: 0 }
            (0 until maxOf(a.size, b.size)).asSequence()
                .map { (a.getOrElse(it) { 0 }).compareTo(b.getOrElse(it) { 0 }) }
                .firstOrNull { it != 0 } ?: 0
        }

        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
    }
}
