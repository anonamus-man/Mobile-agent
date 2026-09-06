package com.anonamus.mobileagent.ui

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.anonamus.mobileagent.data.AppPreferences
import com.anonamus.mobileagent.data.WorkspaceLocationKind
import com.anonamus.mobileagent.data.WorkspaceLocations
import com.anonamus.mobileagent.ui.theme.PocketGreen
import com.anonamus.mobileagent.ui.theme.PocketOrange

/**
 * Setup step that asks where project folders should live.
 *
 * Presented before the runtime download so the choice is made once, up front:
 * every project created afterwards becomes a folder inside the selected root.
 */
@Composable
fun ProjectLocationStep(
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val preferences = remember { AppPreferences(context) }
    val locations = remember { WorkspaceLocations(context, preferences) }

    var selected by remember {
        mutableStateOf(
            runCatching { WorkspaceLocationKind.valueOf(preferences.workspaceLocationKind) }
                .getOrDefault(WorkspaceLocationKind.APP_PRIVATE),
        )
    }
    // Recomputed whenever the user returns from the system permission screen.
    var granted by remember { mutableStateOf(locations.canUseSharedStorage()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = locations.canUseSharedStorage()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    val sharedPath = remember { locations.defaultSharedRoot().absolutePath }
    val displayShared = sharedPath
        .removePrefix(Environment.getExternalStorageDirectory().absolutePath)
        .trimStart('/')

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            "Where should your projects live?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            "Every new project becomes its own folder inside this location. " +
                "You can change it later in Settings.",
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(2.dp))

        LocationOption(
            icon = Icons.Default.PhoneAndroid,
            title = "Phone storage",
            subtitle = "Phone storage/$displayShared",
            detail = "Visible in your file manager and to other apps. Survives " +
                "uninstalling Mobile Agent. Needs All files access.",
            selected = selected == WorkspaceLocationKind.SHARED_STORAGE,
            accent = PocketGreen,
            badge = if (granted) "Ready" else "Permission needed",
            badgeColor = if (granted) PocketGreen else PocketOrange,
            onClick = {
                selected = WorkspaceLocationKind.SHARED_STORAGE
                if (!granted) requestAllFilesAccess()
            },
        )

        LocationOption(
            icon = Icons.Default.Lock,
            title = "App private storage",
            subtitle = "Inside Mobile Agent only",
            detail = "No permissions required, but your projects are invisible to " +
                "other apps and are deleted if you uninstall.",
            selected = selected == WorkspaceLocationKind.APP_PRIVATE,
            accent = MaterialTheme.colorScheme.primary,
            badge = "Always available",
            badgeColor = MaterialTheme.colorScheme.primary,
            onClick = { selected = WorkspaceLocationKind.APP_PRIVATE },
        )

        if (selected == WorkspaceLocationKind.SHARED_STORAGE && !granted) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = PocketOrange.copy(alpha = 0.10f),
                border = BorderStroke(1.dp, PocketOrange.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Grant \"All files access\" to Mobile Agent, then come back to " +
                        "this screen. Without it, projects fall back to private storage.",
                    modifier = Modifier.padding(12.dp),
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        Button(
            onClick = {
                locations.select(selected)
                // Device access follows the same grant, so the agent can reach
                // the user's files once they have opted into shared storage.
                preferences.deviceAccessEnabled =
                    selected == WorkspaceLocationKind.SHARED_STORAGE && granted
                preferences.workspaceLocationChosen = true
                onContinue()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text(
                if (selected == WorkspaceLocationKind.SHARED_STORAGE && !granted) {
                    "Continue with private storage"
                } else {
                    "Continue"
                },
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
            )
        }
    }
}

@Composable
private fun LocationOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    detail: String,
    selected: Boolean,
    accent: Color,
    badge: String,
    badgeColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) accent.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) accent.copy(alpha = 0.55f) else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(5.dp),
                        color = badgeColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            badge,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = badgeColor,
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Folder,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        subtitle,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    detail,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Default.Check, null, tint = accent, modifier = Modifier.size(18.dp))
            }
        }
    }
}
