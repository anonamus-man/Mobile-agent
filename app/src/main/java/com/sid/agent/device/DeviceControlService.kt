package com.sid.agent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lets the agent see and drive the screen of *other* apps.
 *
 * This is the only supported way to automate another application without root:
 * Android exposes the on-screen node tree and synthetic gestures to an enabled
 * AccessibilityService and nothing else. The user must turn it on by hand in
 * system settings; until then [instance] is null and every operation is a
 * no-op that reports failure instead of throwing.
 */
class DeviceControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /** Flattens the visible node tree into something a model can reason about. */
    fun dumpScreen(limit: Int = 200): JSONArray {
        val out = JSONArray()
        val root = rootInActiveWindow ?: return out
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && out.length() < limit) {
            val node = queue.removeFirst()
            val text = node.text?.toString().orEmpty()
            val description = node.contentDescription?.toString().orEmpty()
            if (text.isNotBlank() || description.isNotBlank() || node.isClickable) {
                val bounds = Rect().also(node::getBoundsInScreen)
                out.put(
                    JSONObject()
                        .put("text", text)
                        .put("description", description)
                        .put("class", node.className?.toString().orEmpty())
                        .put("clickable", node.isClickable)
                        .put("editable", node.isEditable)
                        .put("x", bounds.centerX())
                        .put("y", bounds.centerY())
                        .put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"),
                )
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return out
    }

    /** Taps an absolute screen coordinate. */
    fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Swipes between two points; used for scrolling. */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 300L): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Types into the focused editable field. */
    fun typeText(text: String): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /** Presses a system button: back, home or recents. */
    fun pressGlobal(action: String): Boolean = when (action.lowercase()) {
        "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
        "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
        "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        "notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
        else -> false
    }

    companion object {
        @Volatile
        var instance: DeviceControlService? = null
            private set

        /** Whether the user has enabled the service in system settings. */
        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            val component = ComponentName(context, DeviceControlService::class.java)
            return flat.split(':').any {
                runCatching { ComponentName.unflattenFromString(it) }.getOrNull() == component
            }
        }
    }
}
