package com.minosuko.clouddrive

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ProductSettingsScreen(theme: ThemeMode, onThemeChanged: (ThemeMode) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var syncOpen by remember { mutableStateOf(false) }
    var accountsOpen by remember { mutableStateOf(false) }
    var smsBlacklistOpen by remember { mutableStateOf(false) }
    var blockedSenderCount by remember { mutableIntStateOf(SmsBlocklistStore.count(context)) }
    if (syncOpen) {
        SyncSettingsScreen(onBack = { syncOpen = false })
        return
    }
    if (accountsOpen) {
        AccountManagementScreen(onBack = { accountsOpen = false })
        return
    }
    if (smsBlacklistOpen) {
        SmsBlacklistScreen(
            onBack = {
                blockedSenderCount = SmsBlocklistStore.count(context)
                smsBlacklistOpen = false
            },
            onCountChanged = { blockedSenderCount = it },
        )
        return
    }

    var permissionVersion by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionVersion++ }
    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionVersion++ }
    val storageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionVersion++ }
    val mediaAccess = remember(permissionVersion) { hasMediaPermission(context) }
    val fileAccess = remember(permissionVersion) { hasDeviceFileAccess(context) }
    val direction = AppSettings.syncDirection(context)
    val categories = AppSettings.syncCategories(context)
    val drives = AppSettings.drives(context)
    val accounts = AccountStore.accounts(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column {
                Text("Settings", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Appearance, permissions, and app tools", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
        item {
            ProductCard(Modifier.clickable { syncOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sync, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Sync", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (direction == SyncDirection.DeviceToCloud) "Back up to CloudDrive · ${categories.size} selected"
                            else "Restore from CloudDrive · ${categories.size} selected",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, "Open Sync")
                }
            }
        }
        item { SectionHeading("Accounts") }
        item {
            ProductCard(Modifier.clickable { accountsOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AccountCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                drives.isEmpty() -> "No connected CloudDrive"
                                else -> "${accounts.count { account -> drives.any { it.id == account.profileId } }} of ${drives.size} signed in"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            if (drives.isEmpty()) "Connect a CloudDrive from Files"
                            else drives.joinToString(" · ") { drive ->
                                val account = accounts.firstOrNull { it.profileId == drive.id }
                                if (account == null) "${drive.name}: sign in required" else "${drive.name}: @${account.username}"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, "Open Account management")
                }
            }
        }
        item { SectionHeading("Messages") }
        item {
            ProductCard(Modifier.clickable { smsBlacklistOpen = true }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Blocked SMS senders", fontWeight = FontWeight.SemiBold)
                        Text(
                            when (blockedSenderCount) {
                                0 -> "No blocked senders"
                                1 -> "1 sender blocked on this device"
                                else -> "$blockedSenderCount senders blocked on this device"
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                    Icon(Icons.Outlined.ChevronRight, "Open blocked SMS senders")
                }
            }
        }
        item { SectionHeading("Appearance") }
        item {
            ProductCard {
                Row {
                    Icon(Icons.Outlined.DarkMode, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text("Theme", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        if (mode == theme) Button({ onThemeChanged(mode) }, Modifier.weight(1f)) { Text(mode.name) }
                        else OutlinedButton({ onThemeChanged(mode) }, Modifier.weight(1f)) { Text(mode.name) }
                    }
                }
            }
        }
        item { SectionHeading("Permissions") }
        item {
            PermissionCard(
                icon = if (fileAccess) Icons.Outlined.CheckCircle else Icons.Outlined.Folder,
                title = "Device files",
                detail = if (fileAccess) "Full file-manager access granted" else "Required to browse all device folders",
                button = if (fileAccess) "Granted" else "Allow",
                enabled = !fileAccess,
            ) {
                if (Build.VERSION.SDK_INT >= 30) allFilesLauncher.launch(deviceFileAccessIntent(context))
                else storageLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
            }
        }
        item {
            PermissionCard(
                icon = if (mediaAccess) Icons.Outlined.CheckCircle else Icons.Outlined.Image,
                title = "Photos and videos",
                detail = if (mediaAccess) "Media sync access granted" else "Required for automatic media sync",
                button = if (mediaAccess) "Manage" else "Allow",
            ) { permissionLauncher.launch(mediaPermissions().toTypedArray()) }
        }
        item { SectionHeading("About") }
        item {
            ProductCard {
                Row {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text("CloudDrive 1.0", fontWeight = FontWeight.SemiBold)
                        Text("com.minosuko.clouddrive", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Android ${Build.VERSION.RELEASE} / Android 10+", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(
    icon: ImageVector,
    title: String,
    detail: String,
    button: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ProductCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            OutlinedButton(onClick = onClick, enabled = enabled) { Text(button) }
        }
    }
}
