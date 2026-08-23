package com.minosuko.clouddrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DashboardScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val drives = remember { AppSettings.drives(context) }
    val model: DashboardViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(drives) { model.refresh(drives) }

    LazyColumn(
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("CloudDrive", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Private files on your devices", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
                IconButton(onClick = { model.refresh(drives, force = true) }) { Icon(Icons.Outlined.Refresh, "Refresh") }
            }
        }
        item { SectionHeading("Storage") }
        item { StorageUsageCard("Device storage", state.deviceStorage, Icons.Outlined.Smartphone, "Device storage unavailable") }
        if (state.loading) item { LinearProgressIndicator() }
        if (!state.loading && state.cloudStorage.isEmpty()) {
            item { ProductCard { Text("No CloudDrives", fontWeight = FontWeight.SemiBold); Text("Add one from the Files tab.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp) } }
        }
        items(state.cloudStorage, key = { it.drive.id }) { cloud ->
            StorageUsageCard(cloud.drive.name, cloud.storage, Icons.Outlined.Cloud, cloud.error ?: "Server unavailable")
        }
    }
}
