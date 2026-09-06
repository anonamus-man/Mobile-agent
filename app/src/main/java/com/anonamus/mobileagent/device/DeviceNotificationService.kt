package com.anonamus.mobileagent.device

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Mirrors the notification shade so the agent can read what the phone is
 * showing.
 *
 * Android only binds this service after the user grants notification access in
 * system settings, so every accessor degrades to "empty" rather than failing.
 */
class DeviceNotificationService : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = true
        runCatching { activeNotifications }.getOrNull()?.forEach(::record)
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::record)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let(current::remove)
    }

    private fun record(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras
        current[sbn.key] = Snapshot(
            packageName = sbn.packageName.orEmpty(),
            title = extras?.getCharSequence("android.title")?.toString().orEmpty(),
            text = extras?.getCharSequence("android.text")?.toString().orEmpty(),
            postedAtMillis = sbn.postTime,
        )
    }

    data class Snapshot(
        val packageName: String,
        val title: String,
        val text: String,
        val postedAtMillis: Long,
    )

    companion object {
        private val current = ConcurrentHashMap<String, Snapshot>()

        @Volatile
        var connected: Boolean = false
            private set

        fun snapshots(): List<Snapshot> = current.values.sortedByDescending { it.postedAtMillis }

        /** Whether the user has granted notification access to this app. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ).orEmpty()
            val component = ComponentName(context, DeviceNotificationService::class.java)
            return flat.split(':').any {
                runCatching { ComponentName.unflattenFromString(it) }.getOrNull() == component
            }
        }

        fun toJson(items: List<Snapshot>): JSONArray = JSONArray().apply {
            items.forEach {
                put(
                    JSONObject()
                        .put("package", it.packageName)
                        .put("title", it.title)
                        .put("text", it.text)
                        .put("postedAtMillis", it.postedAtMillis),
                )
            }
        }
    }
}
