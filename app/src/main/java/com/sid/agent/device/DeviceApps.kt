package com.sid.agent.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only view of the apps installed on the device, plus the ability to
 * launch them.
 *
 * Requires `QUERY_ALL_PACKAGES` on Android 11+; without it the platform only
 * reports a filtered subset and the listing silently shrinks rather than
 * failing, which is why [installedApps] never throws.
 */
class DeviceApps(private val context: Context) {

    data class AppEntry(
        val packageName: String,
        val label: String,
        val versionName: String,
        val system: Boolean,
        val launchable: Boolean,
    )

    fun installedApps(includeSystem: Boolean = false): List<AppEntry> {
        val pm = context.packageManager
        val packages = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledPackages(0)
            }
        }.getOrDefault(emptyList())

        return packages.mapNotNull { info ->
            val app = info.applicationInfo ?: return@mapNotNull null
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem && !includeSystem) return@mapNotNull null
            AppEntry(
                packageName = info.packageName,
                label = runCatching { pm.getApplicationLabel(app).toString() }
                    .getOrDefault(info.packageName),
                versionName = info.versionName.orEmpty(),
                system = isSystem,
                launchable = pm.getLaunchIntentForPackage(info.packageName) != null,
            )
        }.sortedBy { it.label.lowercase() }
    }

    /** Starts an app by package name. Returns false when it has no launcher entry. */
    fun launch(packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent); true }.getOrDefault(false)
    }

    /** Opens a URL, `tel:`, `mailto:` or any other handled URI. */
    fun openUri(uri: String): Boolean = runCatching {
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** Shares plain text through the system chooser. */
    fun shareText(text: String): Boolean = runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(
            Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    }.getOrDefault(false)

    companion object {
        fun toJson(entries: List<AppEntry>): JSONArray = JSONArray().apply {
            entries.forEach { entry ->
                put(
                    JSONObject()
                        .put("package", entry.packageName)
                        .put("label", entry.label)
                        .put("version", entry.versionName)
                        .put("system", entry.system)
                        .put("launchable", entry.launchable),
                )
            }
        }
    }
}
