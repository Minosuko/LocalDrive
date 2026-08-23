package com.minosuko.clouddrive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountManagementScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val drives = remember { AppSettings.drives(context) }
    var accounts by remember { mutableStateOf(AccountStore.accounts(context)) }
    var selectedProfileId by remember {
        mutableStateOf(
            AppSettings.syncDriveId(context)?.takeIf { id -> drives.any { it.id == id } }
                ?: accounts.firstOrNull { account -> drives.any { it.id == account.profileId } }?.profileId
                ?: drives.firstOrNull()?.id,
        )
    }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var signInDriveId by remember { mutableStateOf<String?>(null) }
    val selectedDrive = drives.firstOrNull { it.id == selectedProfileId }
    val account = accounts.firstOrNull { it.profileId == selectedProfileId }

    BackHandler(onBack = onBack)

    fun connectRoot(drive: DriveProfile, username: String, password: String, createRoot: Boolean) {
        scope.launch {
            busy = true
            error = null
            runCatching {
                withContext(Dispatchers.IO) {
                    val (origin, session) = MobileApiClient.connect(context, drive.address, username, password, createRoot)
                    DavClient.clearCache(drive.address)
                    AccountStore.save(context, drive.id, origin, session)
                }
            }.onSuccess {
                accounts = AccountStore.accounts(context)
                signInDriveId = null
            }.onFailure { error = it.message ?: "Could not sign in as root" }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text("Root accounts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("One root owner for each CloudDrive", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }

        if (drives.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(drives, key = DriveProfile::id) { drive ->
                    val driveAccount = accounts.firstOrNull { it.profileId == drive.id }
                    FilterChip(
                        selected = drive.id == selectedProfileId,
                        onClick = { selectedProfileId = drive.id },
                        label = { Text(if (driveAccount == null) "${drive.name} - Sign in" else "${drive.name} - @${driveAccount.username}", maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                if (driveAccount == null) Icons.Outlined.Lock else Icons.Outlined.AdminPanelSettings,
                                null,
                                Modifier.size(17.dp),
                            )
                        },
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                selectedDrive == null -> item {
                    ProductCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(12.dp))
                            Column {
                                Text("No connected CloudDrive", fontWeight = FontWeight.SemiBold)
                                Text("Connect one from Files and sign in with its root account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                account == null -> item {
                    ProductCard {
                        DriveIdentity(selectedDrive)
                        Spacer(Modifier.size(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Root sign-in required", fontWeight = FontWeight.SemiBold)
                                Text("Files, previews, storage details, and sync remain locked.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.size(12.dp))
                        Button(
                            onClick = { error = null; signInDriveId = selectedDrive.id },
                            enabled = !busy,
                        ) { Text("Sign in as root") }
                    }
                }
                else -> {
                    item { ProductCard { DriveIdentity(selectedDrive) } }
                    item {
                        ProductCard {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.AdminPanelSettings, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(account.displayName, fontWeight = FontWeight.Bold)
                                    Text("@${account.username} · Root owner", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Only account on this CloudDrive", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.size(12.dp))
                            OutlinedButton(
                                onClick = { error = null; signInDriveId = selectedDrive.id },
                                enabled = !busy,
                            ) { Text("Sign in again") }
                        }
                    }
                }
            }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp)) } }
        }
    }

    signInDriveId?.let { profileId ->
        val drive = drives.firstOrNull { it.id == profileId }
        if (drive != null) {
            CloudAccountSignInDialog(
                driveName = drive.name,
                busy = busy,
                error = error,
                onDismiss = { signInDriveId = null; error = null },
                onSubmit = { username, password, root -> connectRoot(drive, username, password, root) },
            )
        } else {
            LaunchedEffect(profileId) { signInDriveId = null }
        }
    }
}

@Composable
private fun DriveIdentity(drive: DriveProfile) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Cloud, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(drive.name, fontWeight = FontWeight.Bold)
            Text(drive.address, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.AccountCircle, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
