package com.sid.agent.data

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File

/**
 * Where Mobile Agent keeps project folders on the *host* (Android) side.
 *
 * Historically every project lived in `filesDir/workspaces/<id>`, which is
 * private to the app: invisible to file managers, wiped on uninstall, and
 * unreachable from any other app. Users asked for their projects to live
 * somewhere real, so the location is now a choice made during setup.
 */
enum class WorkspaceLocationKind {
    /** `filesDir/workspaces` — always writable, private, removed on uninstall. */
    APP_PRIVATE,

    /** A folder in shared storage, visible to file managers and other apps. */
    SHARED_STORAGE,
}

/**
 * Resolves the projects root and the per-project directory beneath it.
 *
 * Everything funnels through here so the guest bind mount, project creation and
 * the settings screen can never disagree about where a project lives.
 */
class WorkspaceLocations(
    private val context: Context,
    private val preferences: AppPreferences,
) {

    /** Folder name created inside shared storage. */
    private val sharedFolderName = "MobileAgent"

    /** The always-available fallback. */
    fun appPrivateRoot(): File = File(context.filesDir, "workspaces")

    /**
     * Default shared location: `/storage/emulated/0/MobileAgent/projects`.
     *
     * Deliberately not inside `Android/data`, which Android 11+ hides from file
     * managers and from other apps even with All Files Access.
     */
    fun defaultSharedRoot(): File =
        File(Environment.getExternalStorageDirectory(), "$sharedFolderName/projects")

    /**
     * True when the process can actually create files in shared storage.
     *
     * On Android 11+ this needs the All Files Access special permission; below
     * that the legacy storage permissions are enough.
     */
    fun canUseSharedStorage(): Boolean {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    /** The configured kind, downgraded to app-private if the permission is gone. */
    fun activeKind(): WorkspaceLocationKind {
        val configured = runCatching {
            WorkspaceLocationKind.valueOf(preferences.workspaceLocationKind)
        }.getOrDefault(WorkspaceLocationKind.APP_PRIVATE)
        if (configured == WorkspaceLocationKind.SHARED_STORAGE && !canUseSharedStorage()) {
            return WorkspaceLocationKind.APP_PRIVATE
        }
        return configured
    }

    /**
     * The directory that holds every project folder.
     *
     * Never throws: if the chosen shared folder cannot be created — permission
     * revoked, storage ejected, path taken by a file — this silently falls back
     * to app-private storage so the app stays usable.
     */
    fun projectsRoot(): File {
        if (activeKind() == WorkspaceLocationKind.SHARED_STORAGE) {
            val custom = preferences.workspaceLocationPath.takeIf { it.isNotBlank() }
            val target = custom?.let(::File) ?: defaultSharedRoot()
            if (runCatching { target.mkdirs(); target.isDirectory && target.canWrite() }
                    .getOrDefault(false)
            ) {
                return target
            }
        }
        return appPrivateRoot().apply { mkdirs() }
    }

    /** Host directory for one project. */
    fun workspaceFor(projectId: String): File {
        val preferred = File(projectsRoot(), projectId)
        if (preferred.isDirectory) return preferred
        // A project created before the location was changed keeps working from
        // wherever its files actually are. Without this, switching the root
        // would silently present every existing project as empty.
        val existing = listOf(appPrivateRoot(), defaultSharedRoot())
            .asSequence()
            .map { File(it, projectId) }
            .firstOrNull { it.isDirectory && !it.list().isNullOrEmpty() }
        return (existing ?: preferred).apply { mkdirs() }
    }

    /**
     * Human-readable path for the UI.
     *
     * Shared paths are shown relative to the storage root because
     * `/storage/emulated/0/...` means nothing to most people.
     */
    fun displayPath(): String {
        val root = projectsRoot()
        val external = Environment.getExternalStorageDirectory().absolutePath
        return when {
            root.absolutePath.startsWith(external) ->
                "Phone storage/" + root.absolutePath.removePrefix(external).trimStart('/')
            else -> "App private storage"
        }
    }

    /** Records the user's choice. Returns the root that will actually be used. */
    fun select(kind: WorkspaceLocationKind, path: File? = null): File {
        preferences.workspaceLocationKind = kind.name
        preferences.workspaceLocationPath = when (kind) {
            WorkspaceLocationKind.SHARED_STORAGE -> (path ?: defaultSharedRoot()).absolutePath
            WorkspaceLocationKind.APP_PRIVATE -> ""
        }
        return projectsRoot()
    }
}
