package com.sid.agent.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sid.agent.data.AppPreferences
import com.sid.agent.data.WorkspaceLocationKind
import com.sid.agent.data.WorkspaceLocations
import com.sid.agent.device.DeviceControlService
import com.sid.agent.device.DeviceNotificationService
import com.sid.agent.ui.theme.PocketGreen
import com.sid.agent.ui.theme.PocketOrange

/**
 * Settings section for everything the agent can reach outside its own sandbox.
 *
 * Android deliberately offers no API to grant notification access or
 * accessibility from inside an app — the user must flip each switch in system
 * settings. All this screen can do is state the current status honestly and
 * take them straight to the right page.
 */
@Composable
fun DeviceAccessSection() {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val locations = remember { WorkspaceLocations(context, preferences) }

    var storageGranted by remember { mutableStateOf(locations.canUseSharedStorage()) }
    var notificationsGranted by remember {
        mutableStateOf(DeviceNotificationService.isEnabled(context))
    }
    var controlGranted by remember { mutableStateOf(DeviceControlService.isEnabled(context)) }
    var projectPath by remember { mutableStateOf(locations.displayPath()) }
    var locationKind by remember { mutableStateOf(locations.activeKind()) }

    // System settings live in another activity, so the only reliable moment to
    // re-read these is when the user comes back to us.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = locations.canUseSharedStorage()
                notificationsGranted = DeviceNotificationService.isEnabled(context)
                controlGranted = DeviceControlService.isEnabled(context)
                projectPath = locations.displayPath()
                locationKind = locations.activeKind()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun open(action: String, withPackage: Boolean = false) {
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) intent.data = Uri.parse("package:${context.packageName}")
        runCatching { context.startActivity(intent) }.onFailure {
            // Some OEM builds drop the per-app variant; fall back to the list.
            runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "DEVICE ACCESS",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
        )
        Text(
            "What the agent can reach outside its own sandbox. Each switch lives " +
                "in Android settings — Mobile Agent cannot turn them on for you.",
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AccessRow(
            icon = Icons.Default.Folder,
            title = "Your files",
            granted = storageGranted,
            grantedText = "Mounted at /sdcard · projects in $projectPath",
            missingText = "The agent only sees its own workspace. Grant All files " +
                "access to work on your real photos, Downloads and Documents.",
            actionLabel = "Open all files access",
            enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            onAction = {
                open(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, withPackage = true)
            },
        )

        // Honours the promise made during setup that this can be changed later.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "New projects are created in",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    projectPath,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.padding(top = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LocationChip(
                        label = "Phone storage",
                        selected = locationKind == WorkspaceLocationKind.SHARED_STORAGE,
                        enabled = storageGranted,
                    ) {
                        locations.select(WorkspaceLocationKind.SHARED_STORAGE)
                        preferences.deviceAccessEnabled = true
                        locationKind = WorkspaceLocationKind.SHARED_STORAGE
                        projectPath = locations.displayPath()
                    }
                    LocationChip(
                        label = "App private",
                        selected = locationKind == WorkspaceLocationKind.APP_PRIVATE,
                        enabled = true,
                    ) {
                        locations.select(WorkspaceLocationKind.APP_PRIVATE)
                        preferences.deviceAccessEnabled = false
                        locationKind = WorkspaceLocationKind.APP_PRIVATE
                        projectPath = locations.displayPath()
                    }
                }
                Spacer(Modifier.padding(top = 6.dp))
                Text(
                    "Projects you already created stay where they are and keep working. " +
                        "Restart a chat for a location change to take effect.",
                    fontSize = 10.sp,
                    lineHeight = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AccessRow(
            icon = Icons.Default.Apps,
            title = "Installed apps",
            granted = true,
            grantedText = "phone apps · phone open <package> · phone share",
            missingText = "",
            actionLabel = null,
            onAction = {},
        )

        AccessRow(
            icon = Icons.Default.Notifications,
            title = "Notifications",
            granted = notificationsGranted,
            grantedText = "phone notifications reads the shade",
            missingText = "Turn on Mobile Agent under Notification access so the " +
                "agent can read what your phone is showing.",
            actionLabel = "Open notification access",
            onAction = { open("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS") },
        )

        AccessRow(
            icon = Icons.Default.TouchApp,
            title = "Screen control",
            granted = controlGranted,
            grantedText = "phone screen · tap · swipe · type · press",
            missingText = "Turn on Mobile Agent under Accessibility so it can read " +
                "the screen and tap, swipe and type in other apps.",
            actionLabel = "Open accessibility settings",
            onAction = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "Other apps' private data cannot be reached on an unrooted phone. " +
                    "Android enforces that in the kernel and no permission unlocks it.",
                modifier = Modifier.padding(12.dp),
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AccessRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    grantedText: String,
    missingText: String,
    actionLabel: String?,
    enabled: Boolean = true,
    onAction: () -> Unit,
) {
    val accent: Color = if (granted) PocketGreen else PocketOrange
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(shape = RoundedCornerShape(5.dp), color = accent.copy(alpha = 0.15f)) {
                        Text(
                            if (granted) "On" else "Off",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                        )
                    }
                }
                Spacer(Modifier.padding(top = 3.dp))
                Text(
                    if (granted) grantedText else missingText,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!granted && actionLabel != null && enabled) {
                    TextButton(
                        onClick = onAction,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 0.dp,
                            vertical = 4.dp,
                        ),
                    ) {
                        Text(actionLabel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) PocketGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        color = if (selected) PocketGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, if (selected) PocketGreen.copy(alpha = 0.5f) else Color.Transparent),
    ) {
        Text(
            if (enabled) label else "$label (needs permission)",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
    }
}
