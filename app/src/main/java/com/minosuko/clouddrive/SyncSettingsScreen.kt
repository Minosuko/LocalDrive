package com.minosuko.clouddrive

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionVersion by remember { mutableIntStateOf(0) }
    var direction by remember { mutableStateOf(AppSettings.syncDirection(context)) }
    var selectedCategories by remember { mutableStateOf(AppSettings.syncCategories(context)) }
    var syncDriveId by remember { mutableStateOf(AppSettings.syncDriveId(context)) }
    var restoreDevice by remember { mutableStateOf(AppSettings.restoreDevice(context)) }
    var autoSync by remember { mutableStateOf(AppSettings.autoSync(context)) }
    var schedule by remember { mutableStateOf(AppSettings.syncSchedule(context)) }
    var amountText by remember(schedule.amount) { mutableStateOf(schedule.amount.toString()) }
    var hourText by remember(schedule.hour) { mutableStateOf(schedule.hour.toString().padStart(2, '0')) }
    var minuteText by remember(schedule.minute) { mutableStateOf(schedule.minute.toString().padStart(2, '0')) }
    var backupDevices by remember { mutableStateOf(emptyList<String>()) }
    var loadingBackups by remember { mutableStateOf(false) }
    var backupLoadError by remember { mutableStateOf<String?>(null) }
    val drives = AppSettings.drives(context)
    val signedInDriveIds = drives.filter { AccountStore.hasSession(context, it.id) }.mapTo(hashSetOf()) { it.id }
    val selectedDrive = drives.firstOrNull { it.id == syncDriveId }
    val cloudAccountReady = if (selectedDrive == null) signedInDriveIds.isNotEmpty() else selectedDrive.id in signedInDriveIds

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionVersion++ }
    val fileAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionVersion++ }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { permissionVersion++ }
    val legacyStorageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissionVersion++ }
    val categoryAccess = remember(permissionVersion, direction) {
        SyncCategory.entries.associateWith { hasSyncCategoryPermission(context, it, direction) }
    }
    val selectedAccess = selectedCategories.all { categoryAccess[it] == true }

    val work by WorkManager.getInstance(context)
        .getWorkInfosForUniqueWorkLiveData(AppSettings.MANUAL_WORK)
        .observeAsState(emptyList())
    val info = work.lastOrNull()
    val running = info?.state in setOf(WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)
    val done = info?.progress?.getInt(MediaSyncWorker.KEY_DONE, 0) ?: 0
    val total = info?.progress?.getInt(MediaSyncWorker.KEY_TOTAL, 0) ?: 0
    val completedForCurrentDirection = info?.outputData?.getString(MediaSyncWorker.KEY_DIRECTION) == direction.name
    val syncStatus = when (info?.state) {
        WorkInfo.State.RUNNING -> "Syncing $done of $total"
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting for Wi-Fi"
        WorkInfo.State.SUCCEEDED -> if (completedForCurrentDirection) info.outputData.getString(MediaSyncWorker.KEY_MESSAGE) ?: "Sync complete"
            else AppSettings.lastSyncStatus(context, direction) ?: if (direction == SyncDirection.DeviceToCloud) "Ready to back up" else "Ready to restore"
        WorkInfo.State.FAILED -> if (completedForCurrentDirection) info.outputData.getString(MediaSyncWorker.KEY_MESSAGE) ?: "Sync needs attention"
            else AppSettings.lastSyncStatus(context, direction) ?: if (direction == SyncDirection.DeviceToCloud) "Ready to back up" else "Ready to restore"
        else -> AppSettings.lastSyncStatus(context, direction) ?: if (direction == SyncDirection.DeviceToCloud) "Ready to back up" else "Ready to restore"
    }
    val blockedReason = when {
        drives.isEmpty() -> "Add a CloudDrive from Files first"
        !cloudAccountReady -> if (selectedDrive == null) "Sign in to at least one CloudDrive" else "Sign in to ${selectedDrive.name}"
        selectedCategories.isEmpty() -> "Select at least one data type"
        !selectedAccess -> "Allow access for each selected data type"
        else -> null
    }

    LaunchedEffect(direction, syncDriveId, drives) {
        if (direction != SyncDirection.CloudToDevice) {
            backupDevices = emptyList()
            backupLoadError = null
            loadingBackups = false
            return@LaunchedEffect
        }
        val drive = drives.firstOrNull { it.id == syncDriveId } ?: drives.firstOrNull()
        if (drive == null) {
            backupDevices = emptyList()
            backupLoadError = "Add a CloudDrive first"
            return@LaunchedEffect
        }
        loadingBackups = true
        backupLoadError = null
        val result = withContext(Dispatchers.IO) {
            runCatching {
                davClient(context, drive).listCloud(listOf("Sync"), force = true)
                    .filter(BrowserEntry::isDirectory)
                    .map(BrowserEntry::name)
                    .sortedWith(String.CASE_INSENSITIVE_ORDER)
            }
        }
        result.onSuccess { backupDevices = it }
            .onFailure {
                backupDevices = emptyList()
                backupLoadError = if (it is DavException && it.status == 404) {
                    "No backups found on this CloudDrive"
                } else {
                    it.message ?: "Could not load backups"
                }
            }
        loadingBackups = false
    }

    BackHandler(onBack = onBack)

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 118.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Settings") }
                    Column {
                        Text("Sync", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (direction == SyncDirection.DeviceToCloud) "Back up selected Android data" else "Restore from an existing backup",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            item {
                Text("Choose a mode", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SyncModeTile(
                        title = "Back up",
                        detail = "Device to cloud",
                        icon = Icons.Outlined.CloudUpload,
                        selected = direction == SyncDirection.DeviceToCloud,
                        modifier = Modifier.weight(1f),
                    ) {
                        direction = SyncDirection.DeviceToCloud
                        AppSettings.saveSyncDirection(context, direction)
                    }
                    SyncModeTile(
                        title = "Restore",
                        detail = "Cloud to device",
                        icon = Icons.Outlined.Restore,
                        selected = direction == SyncDirection.CloudToDevice,
                        modifier = Modifier.weight(1f),
                    ) {
                        direction = SyncDirection.CloudToDevice
                        AppSettings.saveSyncDirection(context, direction)
                    }
                }
            }

            item { SyncSectionTitle("CloudDrive", Icons.Outlined.Cloud) }
            item {
                SyncPanel {
                    Text(
                        if (direction == SyncDirection.DeviceToCloud) "Backup destination" else "Backup location",
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (direction == SyncDirection.DeviceToCloud) "Use the first signed-in drive or pin one below." else "Choose a signed-in drive and device backup to restore.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.size(10.dp))
                    SyncDriveDropdown(drives, signedInDriveIds, syncDriveId) {
                        syncDriveId = it
                        AppSettings.saveSyncDriveId(context, it)
                    }
                    if (direction == SyncDirection.DeviceToCloud) {
                        Text(
                            "Media keeps its device folders under media/. Other data uses separate cloud folders.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                    if (direction == SyncDirection.CloudToDevice) {
                        Spacer(Modifier.size(10.dp))
                        BackupDeviceDropdown(
                            selected = restoreDevice,
                            devices = backupDevices,
                            loading = loadingBackups,
                            onSelected = {
                                restoreDevice = it
                                AppSettings.saveRestoreDevice(context, it)
                            },
                        )
                        val sourceNote = backupLoadError ?: when {
                            loadingBackups -> "Finding device backups..."
                            backupDevices.isEmpty() -> "No device backup folders found"
                            restoreDevice.isBlank() -> "Uses this device when available, otherwise the newest backup"
                            else -> "Restore source: $restoreDevice"
                        }
                        Text(
                            sourceNote,
                            color = if (backupLoadError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }

            item { SyncSectionTitle("What to sync", Icons.Outlined.Folder) }
            item {
                SyncPanel(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                    SYNC_CATEGORY_GROUPS.forEachIndexed { groupIndex, group ->
                        if (groupIndex > 0) HorizontalDivider(Modifier.padding(top = 4.dp))
                        Text(
                            group.first,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                        )
                        group.second.forEachIndexed { index, category ->
                            if (index > 0) HorizontalDivider()
                            val enabled = category in selectedCategories
                            val hasAccess = categoryAccess[category] == true
                            SyncCategoryRow(
                                category = category,
                                enabled = enabled,
                                hasAccess = hasAccess,
                                direction = direction,
                                onPermission = {
                                    val fileRestore = direction == SyncDirection.CloudToDevice && category in setOf(
                                        SyncCategory.Photos,
                                        SyncCategory.Videos,
                                        SyncCategory.Downloads,
                                    )
                                    when {
                                        category == SyncCategory.Downloads || fileRestore -> {
                                            if (Build.VERSION.SDK_INT >= 30) fileAccessLauncher.launch(deviceFileAccessIntent(context))
                                            else legacyStorageLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                                        }
                                        direction == SyncDirection.CloudToDevice && category == SyncCategory.SmsMessages && !hasSmsRole(context) -> {
                                            smsRoleIntent(context)?.let(roleLauncher::launch)
                                        }
                                        else -> permissionLauncher.launch(syncCategoryPermissions(category, direction).toTypedArray())
                                    }
                                },
                                onEnabled = { checked ->
                                    selectedCategories = if (checked) selectedCategories + category else selectedCategories - category
                                    AppSettings.saveSyncCategories(context, selectedCategories)
                                },
                            )
                        }
                    }
                }
            }

            if (direction == SyncDirection.DeviceToCloud) {
                item { SyncSectionTitle("Automatic backup", Icons.Outlined.Schedule) }
                item {
                    SyncPanel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (autoSync) scheduleSummary(schedule) else "Automatic backup is off", fontWeight = FontWeight.SemiBold)
                                Text("Runs over Wi-Fi only", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Switch(
                                checked = autoSync,
                                onCheckedChange = {
                                    autoSync = it
                                    AppSettings.saveAutoSync(context, it)
                                },
                            )
                        }
                        AnimatedVisibility(autoSync) {
                            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                HorizontalDivider()
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = schedule.mode == SyncScheduleMode.Interval,
                                        onClick = { schedule = schedule.copy(mode = SyncScheduleMode.Interval) },
                                        label = { Text("Interval") },
                                    )
                                    FilterChip(
                                        selected = schedule.mode == SyncScheduleMode.Daily,
                                        onClick = { schedule = schedule.copy(mode = SyncScheduleMode.Daily) },
                                        label = { Text("Daily") },
                                    )
                                }
                                if (schedule.mode == SyncScheduleMode.Interval) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        OutlinedTextField(
                                            value = amountText,
                                            onValueChange = { amountText = it.filter(Char::isDigit).take(6) },
                                            label = { Text("Every") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                        SyncUnitDropdown(schedule.unit, { schedule = schedule.copy(unit = it) }, Modifier.weight(1f))
                                    }
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = hourText,
                                            onValueChange = { hourText = it.filter(Char::isDigit).take(2) },
                                            label = { Text("Hour") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                        OutlinedTextField(
                                            value = minuteText,
                                            onValueChange = { minuteText = it.filter(Char::isDigit).take(2) },
                                            label = { Text("Minute") },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                                OutlinedButton(
                                    onClick = {
                                        schedule = schedule.copy(
                                            amount = amountText.toIntOrNull()?.coerceAtLeast(1) ?: 24,
                                            hour = hourText.toIntOrNull()?.coerceIn(0, 23) ?: 0,
                                            minute = minuteText.toIntOrNull()?.coerceIn(0, 59) ?: 0,
                                        )
                                        amountText = schedule.amount.toString()
                                        hourText = schedule.hour.toString().padStart(2, '0')
                                        minuteText = schedule.minute.toString().padStart(2, '0')
                                        AppSettings.saveSyncSchedule(context, schedule)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Apply schedule") }
                            }
                        }
                    }
                }
            } else {
                item {
                    SyncPanel(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Restore, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text("Restore is always manual", fontWeight = FontWeight.SemiBold)
                                Text("CloudDrive will never restore Android data on a schedule.", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                if (running) LinearProgressIndicator(
                    progress = { if (total > 0) done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                )
                Text(
                    blockedReason ?: syncStatus,
                    color = if (blockedReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 5.dp),
                )
                Button(
                    onClick = { enqueueMediaSync(context) },
                    enabled = !running && blockedReason == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (direction == SyncDirection.DeviceToCloud) "Sync to CloudDrive" else "Sync to device")
                }
            }
        }
    }
}

@Composable
private fun SyncModeTile(
    title: String,
    detail: String,
    icon: ImageVector,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Box(
                Modifier.size(34.dp).background(
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(11.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SyncSectionTitle(title: String, icon: ImageVector) {
    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(7.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

@Composable
private fun SyncPanel(
    containerColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke = CardDefaults.outlinedCardBorder(),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = border,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
private fun SyncCategoryRow(
    category: SyncCategory,
    enabled: Boolean,
    hasAccess: Boolean,
    direction: SyncDirection,
    onPermission: () -> Unit,
    onEnabled: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(categoryIcon(category), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(category.label, fontWeight = FontWeight.Medium)
            Text(categoryDescription(category), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            if (enabled && !hasAccess) {
                TextButton(onClick = onPermission, contentPadding = PaddingValues(0.dp)) {
                    Text(
                        if (direction == SyncDirection.CloudToDevice && category == SyncCategory.SmsMessages) "Allow SMS role" else "Allow access",
                        fontSize = 12.sp,
                    )
                }
            } else if (enabled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.size(3.dp))
                    Text("Ready", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                }
            }
        }
        Switch(checked = enabled, onCheckedChange = onEnabled)
    }
}

private fun categoryIcon(category: SyncCategory): ImageVector = when (category) {
    SyncCategory.Photos -> Icons.Outlined.PhotoLibrary
    SyncCategory.Videos -> Icons.Outlined.VideoLibrary
    SyncCategory.Downloads -> Icons.Outlined.Download
    SyncCategory.Contacts -> Icons.Outlined.Contacts
    SyncCategory.SmsMessages -> Icons.Outlined.Sms
    SyncCategory.CallHistory -> Icons.Outlined.Call
}

private fun categoryDescription(category: SyncCategory): String = when (category) {
    SyncCategory.Photos -> "Keeps paths such as media/DCIM/Camera"
    SyncCategory.Videos -> "Keeps paths such as media/Videos/Messenger"
    SyncCategory.Downloads -> "Files and folders under downloads/"
    SyncCategory.Contacts -> "Names, numbers, and details"
    SyncCategory.SmsMessages -> "Text message history"
    SyncCategory.CallHistory -> "Incoming and outgoing calls"
}

private val SYNC_CATEGORY_GROUPS = listOf(
    "Media" to listOf(SyncCategory.Photos, SyncCategory.Videos),
    "Files" to listOf(SyncCategory.Downloads),
    "Android records" to listOf(SyncCategory.Contacts, SyncCategory.SmsMessages, SyncCategory.CallHistory),
)

@Composable
private fun SyncDriveDropdown(
    drives: List<DriveProfile>,
    signedInDriveIds: Set<String>,
    selectedId: String?,
    onSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = drives.firstOrNull { it.id == selectedId }
    val label = when {
        selected == null -> "First signed-in CloudDrive"
        selected.id !in signedInDriveIds -> "${selected.name} (sign in required)"
        else -> selected.name
    }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("First signed-in CloudDrive") },
                onClick = { onSelected(null); expanded = false },
            )
            drives.forEach { drive ->
                DropdownMenuItem(
                    text = { Text(if (drive.id in signedInDriveIds) drive.name else "${drive.name} (sign in required)") },
                    onClick = { onSelected(drive.id); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun BackupDeviceDropdown(
    selected: String,
    devices: List<String>,
    loading: Boolean,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when {
                    loading -> "Finding backups..."
                    selected.isBlank() -> "Automatic device selection"
                    else -> selected
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Automatic device selection") },
                onClick = { onSelected(""); expanded = false },
            )
            devices.forEach { device ->
                DropdownMenuItem(
                    text = { Text(device) },
                    onClick = { onSelected(device); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun SyncUnitDropdown(unit: SyncIntervalUnit, onUnit: (SyncIntervalUnit) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(unit.label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SyncIntervalUnit.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = { onUnit(option); expanded = false },
                )
            }
        }
    }
}

private fun scheduleSummary(schedule: SyncSchedule): String = when (schedule.mode) {
    SyncScheduleMode.Interval -> "Every ${schedule.amount} ${schedule.unit.label}"
    SyncScheduleMode.Daily -> "Every day at ${schedule.hour.toString().padStart(2, '0')}:${schedule.minute.toString().padStart(2, '0')}"
}
