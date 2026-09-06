package com.sid.agent.device

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Serves device requests coming from inside the Linux guest.
 *
 * PRoot gives the guest no way to call Android APIs, so the `phone` command
 * installed in the rootfs drops a JSON `.cmd` file into the shared bridge
 * directory and waits for a matching `.result`. This class is the Android half
 * of that conversation.
 *
 * The same directory already carries Claude Code's permission hook, so no new
 * mount point is needed.
 */
class DeviceBridge(private val context: Context) {

    private val apps = DeviceApps(context)

    /**
     * Polls the bridge directory until the coroutine is cancelled.
     *
     * Runs on IO because every handler touches the filesystem or PackageManager.
     */
    suspend fun serve(bridgeDir: File) = withContext(Dispatchers.IO) {
        bridgeDir.mkdirs()
        while (coroutineContext.isActive) {
            bridgeDir.listFiles { file -> file.name.endsWith(".cmd") }
                .orEmpty()
                .forEach { request -> handle(request) }
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun handle(request: File) {
        val id = request.name.removeSuffix(".cmd")
        val response = File(request.parentFile, "$id.result")
        val reply = runCatching {
            val body = JSONObject(request.readText())
            dispatch(body.optString("action"), body)
        }.getOrElse { error ->
            JSONObject().put("ok", false).put("error", error.message ?: "device call failed")
        }
        // Write to a temporary name first: the guest polls for `.result` and
        // must never observe a half-written file.
        val staging = File(request.parentFile, "$id.result.part")
        staging.writeText(reply.toString())
        staging.renameTo(response)
        request.delete()
    }

    private fun dispatch(action: String, body: JSONObject): JSONObject = when (action) {
        "apps.list" -> ok().put(
            "apps",
            DeviceApps.toJson(apps.installedApps(includeSystem = body.optBoolean("system", false))),
        )

        "app.launch" -> {
            val target = body.getString("package")
            if (apps.launch(target)) ok() else fail("$target has no launcher activity")
        }

        "uri.open" ->
            if (apps.openUri(body.getString("uri"))) ok() else fail("no app handled that URI")

        "text.share" ->
            if (apps.shareText(body.getString("text"))) ok() else fail("share failed")

        "notifications.list" ->
            if (!DeviceNotificationService.connected) {
                fail("notification access is not enabled for Mobile Agent")
            } else {
                ok().put(
                    "notifications",
                    DeviceNotificationService.toJson(DeviceNotificationService.snapshots()),
                )
            }

        "screen.dump" -> control()?.let { ok().put("nodes", it.dumpScreen()) }
            ?: fail(ACCESSIBILITY_HINT)

        "screen.tap" -> control()
            ?.let { if (it.tap(body.getInt("x"), body.getInt("y"))) ok() else fail("tap rejected") }
            ?: fail(ACCESSIBILITY_HINT)

        "screen.swipe" -> control()?.let {
            val done = it.swipe(
                body.getInt("x1"), body.getInt("y1"),
                body.getInt("x2"), body.getInt("y2"),
                body.optLong("duration", 300L),
            )
            if (done) ok() else fail("swipe rejected")
        } ?: fail(ACCESSIBILITY_HINT)

        "screen.type" -> control()
            ?.let { if (it.typeText(body.getString("text"))) ok() else fail("no focused text field") }
            ?: fail(ACCESSIBILITY_HINT)

        "screen.press" -> control()
            ?.let { if (it.pressGlobal(body.getString("key"))) ok() else fail("unknown key") }
            ?: fail(ACCESSIBILITY_HINT)

        "device.info" -> ok()
            .put("model", android.os.Build.MODEL)
            .put("manufacturer", android.os.Build.MANUFACTURER)
            .put("androidRelease", android.os.Build.VERSION.RELEASE)
            .put("sdkInt", android.os.Build.VERSION.SDK_INT)
            .put("abis", android.os.Build.SUPPORTED_ABIS.orEmpty().joinToString(","))
            .put("storageGranted", storageGranted())
            .put("notificationsGranted", DeviceNotificationService.isEnabled(context))
            .put("controlGranted", DeviceControlService.isEnabled(context))

        else -> fail("unknown action '$action'")
    }

    private fun storageGranted(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()

    private fun control(): DeviceControlService? = DeviceControlService.instance

    private fun ok() = JSONObject().put("ok", true)

    private fun fail(message: String) = JSONObject().put("ok", false).put("error", message)

    companion object {
        private const val POLL_INTERVAL_MS = 150L

        private const val ACCESSIBILITY_HINT =
            "screen control is off - enable Mobile Agent under Settings > Accessibility"

        /**
         * The `phone` command installed into the guest.
         *
         * Kept dependency-free (POSIX sh + the Node.js already present) so it
         * works on both delivery channels and needs nothing from apt.
         */
        fun phoneCommandScript(): String = """
            #!/bin/sh
            # Mobile Agent: talk to the Android host from inside the guest.
            set -e
            BRIDGE=/pocket-bridge
            [ -d "${'$'}BRIDGE" ] || { echo "device bridge unavailable" >&2; exit 1; }

            usage() {
              cat >&2 <<'USAGE'
            phone <command> [args]

              info                        device model, Android version, granted permissions
              apps [--system]             list installed apps as JSON
              open <package>              launch an app
              url <uri>                   open a URL, tel: or mailto:
              share <text>                share text through the system chooser
              notifications               list current notifications
              screen                      dump on-screen text and tappable nodes
              tap <x> <y>                 tap a coordinate
              swipe <x1> <y1> <x2> <y2>   swipe between two points
              type <text>                 type into the focused field
              press <back|home|recents>   press a system button
            USAGE
              exit 2
            }

            [ ${'$'}# -ge 1 ] || usage

            cmd="${'$'}1"; shift
            case "${'$'}cmd" in
              info)          payload='{"action":"device.info"}' ;;
              apps)          if [ "${'$'}1" = "--system" ]; then
                               payload='{"action":"apps.list","system":true}'
                             else
                               payload='{"action":"apps.list"}'
                             fi ;;
              open)          payload=${'$'}(printf '{"action":"app.launch","package":"%s"}' "${'$'}1") ;;
              url)           payload=${'$'}(printf '{"action":"uri.open","uri":"%s"}' "${'$'}1") ;;
              share)         payload=${'$'}(printf '{"action":"text.share","text":"%s"}' "${'$'}*") ;;
              notifications) payload='{"action":"notifications.list"}' ;;
              screen)        payload='{"action":"screen.dump"}' ;;
              tap)           payload=${'$'}(printf '{"action":"screen.tap","x":%s,"y":%s}' "${'$'}1" "${'$'}2") ;;
              swipe)         payload=${'$'}(printf '{"action":"screen.swipe","x1":%s,"y1":%s,"x2":%s,"y2":%s}' "${'$'}1" "${'$'}2" "${'$'}3" "${'$'}4") ;;
              type)          payload=${'$'}(printf '{"action":"screen.type","text":"%s"}' "${'$'}*") ;;
              press)         payload=${'$'}(printf '{"action":"screen.press","key":"%s"}' "${'$'}1") ;;
              *)             usage ;;
            esac

            id="${'$'}${'$'}-${'$'}(date +%s%N 2>/dev/null || date +%s)"
            printf '%s' "${'$'}payload" > "${'$'}BRIDGE/${'$'}id.cmd.part"
            mv "${'$'}BRIDGE/${'$'}id.cmd.part" "${'$'}BRIDGE/${'$'}id.cmd"

            # The host answers in well under a second; cap the wait so a stopped
            # app cannot hang a shell forever.
            waited=0
            while [ ! -f "${'$'}BRIDGE/${'$'}id.result" ]; do
              sleep 0.1
              waited=${'$'}((waited + 1))
              if [ ${'$'}waited -gt 150 ]; then
                rm -f "${'$'}BRIDGE/${'$'}id.cmd"
                echo "device bridge timed out" >&2
                exit 1
              fi
            done

            cat "${'$'}BRIDGE/${'$'}id.result"
            echo
            rm -f "${'$'}BRIDGE/${'$'}id.result"
        """.trimIndent() + "\n"
    }
}
