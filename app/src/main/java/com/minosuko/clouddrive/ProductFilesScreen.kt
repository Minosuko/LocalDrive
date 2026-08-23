package com.minosuko.clouddrive

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.ImageLoader
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import okhttp3.Headers
import coil.request.videoFrameMillis
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductFilesScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val model: FileBrowserViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var addMenu by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var createDirectory by remember { mutableStateOf<Boolean?>(null) }
    var createName by remember { mutableStateOf("") }
    var addDriveDialog by remember { mutableStateOf(false) }
    var driveName by remember { mutableStateOf("") }
    var driveAddress by remember { mutableStateOf("") }
    var accountUsername by remember { mutableStateOf("") }
    var accountPassword by remember { mutableStateOf("") }
    var createAccount by remember { mutableStateOf(false) }
    var signInDriveId by remember { mutableStateOf<String?>(null) }
    var signInAttempted by remember { mutableStateOf(false) }
    var disconnectDriveId by remember { mutableStateOf<String?>(null) }
    var infoEntry by remember { mutableStateOf<BrowserEntry?>(null) }
    var deleteEntry by remember { mutableStateOf<BrowserEntry?>(null) }
    var trashDriveId by remember { mutableStateOf<String?>(null) }
    val thumbnailLoader = remember(context.applicationContext) { CloudDriveImageLoader.get(context) }
    val visibleItems = remember(state.items, state.query, state.sort) { state.visibleItems }
    val currentLocation = remember(state.source, state.activeDriveId, state.cloudPath, state.deviceStack, state.drives) {
        locationTitle(state)
    }
    val activeDrive = state.drives.firstOrNull { it.id == state.activeDriveId }
    val activeAccount = remember(activeDrive?.id, state.drives, state.message) {
        activeDrive?.let { AccountStore.account(context, it.id) }
    }
    val trashDrive = state.drives.firstOrNull { it.id == trashDriveId }

    if (trashDrive != null) {
        TrashManagementScreen(trashDrive) {
            trashDriveId = null
            model.forceRefresh()
        }
        return
    }
    val canPaste = state.clipboard != null && when (state.source) {
        BrowserSource.CloudDrive -> state.activeDriveId != null && activeAccount != null &&
            (state.clipboard?.entry?.source == BrowserSource.Device ||
                (state.clipboard?.entry?.driveProfileId == state.activeDriveId &&
                    state.clipboard?.entry?.cloudSegments?.dropLast(1) != state.cloudPath))
        BrowserSource.Device -> state.deviceStack.isNotEmpty()
    }

    val allFilesLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (hasDeviceFileAccess(context)) model.enableDeviceRoot()
    }
    val storagePermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (hasDeviceFileAccess(context)) model.enableDeviceRoot()
    }
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.upload(uris)
    }

    LaunchedEffect(Unit) { model.reloadDrives() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); model.consumeMessage() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            state.clipboard?.let { clipboard ->
                androidx.compose.material3.Surface(shadowElevation = 6.dp) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${if (clipboard.action == ClipboardAction.Cut) "Move" else "Copy"} ${clipboard.entry.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 13.sp,
                                )
                                Text(
                                    transferDetail(state.transferProgress, canPaste, currentLocation),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(onClick = model::clearClipboard, enabled = !state.operationRunning) { Icon(Icons.Outlined.Close, "Cancel paste") }
                            Button(onClick = model::paste, enabled = canPaste && !state.operationRunning) {
                                Icon(Icons.Outlined.ContentPaste, null)
                                Spacer(Modifier.size(4.dp))
                                Text("Paste")
                            }
                        }
                        state.transferProgress?.let { progress ->
                            val total = progress.totalBytes
                            if (total != null) {
                                LinearProgressIndicator(
                                    progress = { (progress.completedBytes.toFloat() / total).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SecondaryTabRow(selectedTabIndex = if (state.source == BrowserSource.Device) 0 else 1) {
                Tab(state.source == BrowserSource.Device, { model.setSource(BrowserSource.Device) }, text = { Text("Device", fontSize = 13.sp) })
                Tab(state.source == BrowserSource.CloudDrive, { model.setSource(BrowserSource.CloudDrive) }, text = { Text("CloudDrive", fontSize = 13.sp) })
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val canGoUp = if (state.source == BrowserSource.CloudDrive) state.activeDriveId != null else state.deviceStack.size > 1
                if (canGoUp) IconButton(onClick = model::up) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Up") }
                Text(
                    currentLocation,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
                IconButton(onClick = { searchExpanded = !searchExpanded }) { Icon(Icons.Outlined.Search, "Search") }
                IconButton(onClick = model::forceRefresh) { Icon(Icons.Outlined.Refresh, "Refresh") }
                Box {
                    IconButton(onClick = { addMenu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                        if (state.source == BrowserSource.CloudDrive) {
                            DropdownMenuItem(
                                text = { Text("Add CloudDrive") },
                                leadingIcon = { Icon(Icons.Outlined.Add, null) },
                                onClick = { addMenu = false; addDriveDialog = true },
                            )
                        }
                        if (state.source == BrowserSource.CloudDrive && state.activeDriveId != null) {
                            if (activeAccount == null) {
                                DropdownMenuItem(
                                    text = { Text("Sign in to CloudDrive") },
                                    leadingIcon = { Icon(Icons.Outlined.AccountCircle, null) },
                                    onClick = {
                                        addMenu = false
                                        signInAttempted = false
                                        signInDriveId = state.activeDriveId
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Upload to CloudDrive") },
                                    leadingIcon = { Icon(Icons.Outlined.UploadFile, null) },
                                    onClick = { addMenu = false; uploadLauncher.launch(arrayOf("*/*")) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Trash") },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                                    onClick = { addMenu = false; trashDriveId = state.activeDriveId },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Disconnect CloudDrive") },
                                leadingIcon = { Icon(Icons.Outlined.LinkOff, null) },
                                onClick = { addMenu = false; disconnectDriveId = state.activeDriveId },
                            )
                        } else {
                            if (state.source == BrowserSource.Device) {
                                DropdownMenuItem(
                                    text = { Text("Allow device storage") },
                                    leadingIcon = { Icon(Icons.Outlined.Folder, null) },
                                    onClick = {
                                        addMenu = false
                                        if (Build.VERSION.SDK_INT >= 30) allFilesLauncher.launch(deviceFileAccessIntent(context))
                                        else storagePermissionLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                                    },
                                )
                            }
                        }
                        if ((state.source == BrowserSource.CloudDrive && state.activeDriveId != null && activeAccount != null)
                            || (state.source == BrowserSource.Device && hasDeviceFileAccess(context))) {
                            DropdownMenuItem(
                                text = { Text("New folder") },
                                leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, null) },
                                onClick = { addMenu = false; createDirectory = true },
                            )
                            DropdownMenuItem(
                                text = { Text("New file") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.NoteAdd, null) },
                                onClick = { addMenu = false; createDirectory = false },
                            )
                        }
                        HorizontalDivider()
                        FileSort.entries.forEach { sort ->
                            DropdownMenuItem(
                                text = { Text("Sort by ${sort.name.lowercase()}") },
                                trailingIcon = { if (state.sort == sort) Text("Selected", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary) },
                                onClick = { model.setSort(sort); addMenu = false },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(if (state.layout == FileLayout.List) "Grid view" else "List view") },
                            leadingIcon = { Icon(if (state.layout == FileLayout.List) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.List, null) },
                            onClick = { model.toggleLayout(); addMenu = false },
                        )
                    }
                }
            }

            AnimatedVisibility(searchExpanded) {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = model::setQuery,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                    placeholder = { Text("Search this folder") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
            }

            if (state.loading || (state.operationRunning && state.transferProgress == null)) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                state.source == BrowserSource.Device && !hasDeviceFileAccess(context) -> FilesEmptyState(
                    "Device access required",
                    "Open the top-right menu and allow storage access.",
                )
                state.source == BrowserSource.CloudDrive && activeDrive != null && activeAccount == null -> FilesEmptyState(
                    "Sign in required",
                    "Sign in to ${activeDrive.name} before browsing files or using sync.",
                    action = "Sign in",
                    onAction = {
                        signInAttempted = false
                        signInDriveId = activeDrive.id
                    },
                )
                state.error != null -> FilesEmptyState("Unable to open folder", state.error ?: "Unknown error")
                visibleItems.isEmpty() && !state.loading -> FilesEmptyState(
                    if (state.query.isBlank()) "This folder is empty" else "No matching files",
                    if (state.query.isBlank()) "Add files or create a folder to get started." else "Try another search.",
                )
                state.layout == FileLayout.List -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    items(visibleItems, key = { it.id }, contentType = { if (it.isDirectory) "folder" else "file" }) { entry ->
                        FileListRow(
                            entry,
                            thumbnailLoader,
                            { model.open(entry) },
                            { model.putClipboard(entry, it) },
                            { disconnectDriveId = entry.driveProfileId },
                            { infoEntry = entry },
                            { deleteEntry = entry },
                        )
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(visibleItems, key = { it.id }, contentType = { if (it.isDirectory) "folder" else "file" }) { entry ->
                        FileGridCard(
                            entry,
                            thumbnailLoader,
                            { model.open(entry) },
                            { model.putClipboard(entry, it) },
                            { disconnectDriveId = entry.driveProfileId },
                            { infoEntry = entry },
                            { deleteEntry = entry },
                        )
                    }
                }
            }
        }
    }

    if (addDriveDialog) {
        AlertDialog(
            onDismissRequest = { addDriveDialog = false },
            title = { Text("Connect CloudDrive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = driveName,
                        onValueChange = { driveName = it },
                        label = { Text("Name") },
                        placeholder = { Text("CloudDrive ${state.drives.size + 1}") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = driveAddress,
                        onValueChange = { driveAddress = it },
                        label = { Text("Address") },
                        placeholder = { Text("http://192.168.1.10:8080/CloudDrive") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!createAccount) Button(onClick = { createAccount = false }, modifier = Modifier.weight(1f)) { Text("Root sign in") }
                        else OutlinedButton(onClick = { createAccount = false }, modifier = Modifier.weight(1f)) { Text("Root sign in") }
                        if (createAccount) Button(onClick = { createAccount = true }, modifier = Modifier.weight(1f)) { Text("Create root") }
                        else OutlinedButton(onClick = { createAccount = true }, modifier = Modifier.weight(1f)) { Text("Create root") }
                    }
                    Text(
                        if (createAccount) "First-time setup only. Each CloudDrive has one root owner."
                        else "Each CloudDrive has one root owner. Sign in with that root account.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                    OutlinedTextField(
                        value = accountUsername,
                        onValueChange = { accountUsername = it },
                        label = { Text("Username") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = accountPassword,
                        onValueChange = { accountPassword = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                    model.addDrive(driveName, driveAddress, accountUsername, accountPassword, createAccount)
                    driveName = ""
                    driveAddress = ""
                    accountUsername = ""
                    accountPassword = ""
                    createAccount = false
                    addDriveDialog = false
                    },
                    enabled = driveAddress.isNotBlank() && accountUsername.isNotBlank() && accountPassword.isNotBlank() && !state.operationRunning,
                ) { Text(if (createAccount) "Create root" else "Sign in as root") }
            },
            dismissButton = { OutlinedButton(onClick = { addDriveDialog = false }) { Text("Cancel") } },
        )
    }

    createDirectory?.let { directory ->
        AlertDialog(
            onDismissRequest = { createDirectory = null; createName = "" },
            title = { Text(if (directory) "Create folder" else "Create file") },
            text = { OutlinedTextField(createName, { createName = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    model.create(createName, directory)
                    createName = ""
                    createDirectory = null
                }) { Text("Create") }
            },
            dismissButton = { OutlinedButton(onClick = { createDirectory = null }) { Text("Cancel") } },
        )
    }
    disconnectDriveId?.let { id ->
        val drive = state.drives.firstOrNull { it.id == id }
        if (drive != null) {
            AlertDialog(
                onDismissRequest = { disconnectDriveId = null },
                title = { Text("Disconnect ${drive.name}?") },
                text = { Text("This removes the server from the app. Files on the server are not deleted.") },
                confirmButton = {
                    Button(onClick = { model.disconnectDrive(id); disconnectDriveId = null }) { Text("Disconnect") }
                },
                dismissButton = { OutlinedButton(onClick = { disconnectDriveId = null }) { Text("Cancel") } },
            )
        } else {
            LaunchedEffect(id) { disconnectDriveId = null }
        }
    }
    infoEntry?.let { entry ->
        AlertDialog(
            onDismissRequest = { infoEntry = null },
            title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    InfoRow("Type", if (entry.isDirectory) "Folder" else entry.mimeType)
                    InfoRow("Location", entryLocation(entry, state.drives))
                    if (!entry.isDirectory) InfoRow("Size", formatBytes(entry.size))
                    if (entry.modified > 0) InfoRow("Modified", formatModified(entry.modified))
                    InfoRow("Source", if (entry.source == BrowserSource.Device) "Device" else "CloudDrive")
                }
            },
            confirmButton = { Button(onClick = { infoEntry = null }) { Text("Close") } },
        )
    }
    deleteEntry?.let { entry ->
        val cloudEntry = entry.source == BrowserSource.CloudDrive
        AlertDialog(
            onDismissRequest = { deleteEntry = null },
            title = { Text(if (cloudEntry) "Move to Trash?" else "Delete ${if (entry.isDirectory) "folder" else "file"}?") },
            text = {
                Text(
                    if (cloudEntry) "${entry.name} will move to CloudDrive Trash."
                    else "${entry.name} will be permanently deleted${if (entry.isDirectory) " with everything inside it" else ""}.",
                )
            },
            confirmButton = {
                Button(onClick = { model.delete(entry); deleteEntry = null }) {
                    Icon(Icons.Outlined.Delete, null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (cloudEntry) "Move to Trash" else "Delete")
                }
            },
            dismissButton = { OutlinedButton(onClick = { deleteEntry = null }) { Text("Cancel") } },
        )
    }
    state.viewer?.let { MediaViewer(it, thumbnailLoader, model::closeViewer) }
}

@Composable
private fun FileListRow(
    entry: BrowserEntry,
    imageLoader: ImageLoader,
    open: () -> Unit,
    clipboard: (ClipboardAction) -> Unit,
    disconnect: () -> Unit,
    info: () -> Unit,
    delete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilePreview(entry, imageLoader, Modifier.size(40.dp))
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (entry.isDirectory) "Folder" else "${formatBytes(entry.size)}  ${formatModified(entry.modified)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        if (entry.driveRoot) DriveActionMenu(menu, { menu = it }, disconnect)
        else FileActionMenu(menu, { menu = it }, clipboard, info, delete)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = .5f))
}

@Composable
private fun FileGridCard(
    entry: BrowserEntry,
    imageLoader: ImageLoader,
    open: () -> Unit,
    clipboard: (ClipboardAction) -> Unit,
    disconnect: () -> Unit,
    info: () -> Unit,
    delete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    androidx.compose.material3.Card(
        modifier = Modifier.clickable(onClick = open),
        shape = RoundedCornerShape(16.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.material3.CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(7.dp)) {
            Box {
                FilePreview(entry, imageLoader, Modifier.fillMaxWidth().aspectRatio(1.25f))
                Box(Modifier.align(Alignment.TopEnd)) {
                    if (entry.driveRoot) DriveActionMenu(menu, { menu = it }, disconnect)
                    else FileActionMenu(menu, { menu = it }, clipboard, info, delete)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(entry.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
            Text(if (entry.isDirectory) "Folder" else formatBytes(entry.size), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

@Composable
private fun FilePreview(entry: BrowserEntry, imageLoader: ImageLoader, modifier: Modifier) {
    Box(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val thumbnail = entry.thumbnailUri
        if (thumbnail == null) {
            FileTypeIcon(entry)
        } else {
            val context = androidx.compose.ui.platform.LocalContext.current
            val request = remember(thumbnail, entry.mimeType, entry.requestHeaders) {
                ImageRequest.Builder(context).data(thumbnail).apply {
                    if (entry.source == BrowserSource.Device && entry.mimeType.startsWith("video/")) videoFrameMillis(1_000)
                    if (entry.requestHeaders.isNotEmpty()) {
                        headers(Headers.Builder().apply {
                            entry.requestHeaders.forEach { (name, value) -> set(name, value) }
                        }.build())
                    }
                }.crossfade(true).build()
            }
            SubcomposeAsyncImage(
                model = request,
                imageLoader = imageLoader,
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { FileTypeIcon(entry) },
                error = { FileTypeIcon(entry) },
                success = { SubcomposeAsyncImageContent() },
            )
        }
    }
}

@Composable
private fun FileActionMenu(
    expanded: Boolean,
    setExpanded: (Boolean) -> Unit,
    action: (ClipboardAction) -> Unit,
    info: () -> Unit,
    delete: () -> Unit,
) {
    Box {
        IconButton(onClick = { setExpanded(true) }) { Icon(Icons.Outlined.MoreVert, "Actions") }
        DropdownMenu(expanded, onDismissRequest = { setExpanded(false) }) {
            DropdownMenuItem(
                text = { Text("Cut") },
                leadingIcon = { Icon(Icons.Outlined.ContentCut, null) },
                onClick = { setExpanded(false); action(ClipboardAction.Cut) },
            )
            DropdownMenuItem(
                text = { Text("Copy") },
                leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
                onClick = { setExpanded(false); action(ClipboardAction.Copy) },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Info") },
                leadingIcon = { Icon(Icons.Outlined.Info, null) },
                onClick = { setExpanded(false); info() },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                leadingIcon = { Icon(Icons.Outlined.Delete, null) },
                onClick = { setExpanded(false); delete() },
            )
        }
    }
}

@Composable
private fun DriveActionMenu(expanded: Boolean, setExpanded: (Boolean) -> Unit, disconnect: () -> Unit) {
    Box {
        IconButton(onClick = { setExpanded(true) }) { Icon(Icons.Outlined.MoreVert, "CloudDrive actions") }
        DropdownMenu(expanded, onDismissRequest = { setExpanded(false) }) {
            DropdownMenuItem(
                text = { Text("Disconnect") },
                leadingIcon = { Icon(Icons.Outlined.LinkOff, null) },
                onClick = { setExpanded(false); disconnect() },
            )
        }
    }
}

@Composable
fun FilesEmptyState(
    title: String,
    detail: String,
    action: String? = null,
    onAction: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(54.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun locationTitle(state: FileBrowserState): String = when {
    state.source == BrowserSource.CloudDrive && state.activeDriveId == null -> "CloudDrives"
    state.source == BrowserSource.CloudDrive && state.cloudPath.isEmpty() -> state.drives.firstOrNull { it.id == state.activeDriveId }?.name ?: "CloudDrive"
    state.source == BrowserSource.CloudDrive -> "${state.drives.firstOrNull { it.id == state.activeDriveId }?.name ?: "CloudDrive"} / ${state.cloudPath.joinToString(" / ")}"
    state.deviceStack.isEmpty() -> "Device"
    else -> deviceLocation(state.deviceStack.last())
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Text(value, fontSize = 13.sp)
    }
}

private fun deviceLocation(path: String): String {
    val relative = runCatching {
        File(path).canonicalFile.relativeTo(File(deviceRootPath()).canonicalFile).invariantSeparatorsPath
    }.getOrDefault("")
    return if (relative.isBlank() || relative == ".") "Device" else "Device / ${relative.split('/').joinToString(" / ")}"
}

private fun entryLocation(entry: BrowserEntry, drives: List<DriveProfile>): String = if (entry.source == BrowserSource.Device) {
    entry.devicePath.orEmpty()
} else {
    val drive = drives.firstOrNull { it.id == entry.driveProfileId }?.name ?: "CloudDrive"
    "$drive / ${entry.cloudSegments.joinToString(" / ")}"
}

private fun transferDetail(progress: FileTransferProgress?, canPaste: Boolean, currentLocation: String): String {
    if (progress == null) return if (canPaste) "Destination: $currentLocation" else "Choose a destination folder"
    val total = progress.totalBytes
    return if (total != null) {
        val percent = ((progress.completedBytes.toDouble() / total) * 100).toInt().coerceIn(0, 100)
        "${formatBytes(progress.completedBytes)} / ${formatBytes(total)} ($percent%)"
    } else {
        progress.label
    }
}
