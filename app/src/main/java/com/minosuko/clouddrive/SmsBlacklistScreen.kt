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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date

@Composable
fun SmsBlacklistScreen(
    onBack: () -> Unit,
    onCountChanged: (Int) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var entries by remember { mutableStateOf(SmsBlocklistStore.entries(context)) }
    var senderText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var addConfirmation by remember { mutableStateOf<String?>(null) }
    var removeConfirmation by remember { mutableStateOf<SmsBlocklistEntry?>(null) }
    var clearConfirmation by remember { mutableStateOf(false) }

    fun reload() {
        entries = SmsBlocklistStore.entries(context)
        onCountChanged(entries.size)
    }

    BackHandler(onBack = onBack)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Settings")
                }
                Column(Modifier.weight(1f)) {
                    Text("Blocked SMS senders", fontSize = 19.sp, fontWeight = FontWeight.Bold)
                    Text("Stored only on this device", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }

        item {
            ProductCard {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        Text("How blocking works", fontWeight = FontWeight.SemiBold)
                        Text(
                            "CloudDrive blocks future incoming SMS from these senders only while it is your default SMS app. Existing messages are unchanged, and MMS is not affected.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                        Text(
                            "You can manage this app-local list without making CloudDrive the default SMS app.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 5.dp),
                        )
                    }
                }
            }
        }

        errorMessage?.let { message ->
            item {
                Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }

        item {
            ProductCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Sms, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(10.dp))
                    Text("Add a sender", fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = senderText,
                    onValueChange = { value ->
                        if (value.length <= SmsBlocklistStore.MAX_SENDER_LENGTH) senderText = value
                        errorMessage = null
                    },
                    label = { Text("Phone number or sender ID") },
                    supportingText = { Text("Short codes match exactly; sender IDs ignore letter case.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { addConfirmation = senderText.trim() },
                    enabled = senderText.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Add sender") }
            }
        }

        item {
            SectionHeading("Blocked senders (${entries.size})") {
                TextButton(onClick = { clearConfirmation = true }, enabled = entries.isNotEmpty()) {
                    Text("Clear all")
                }
            }
        }

        if (entries.isEmpty()) {
            item {
                ProductCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(10.dp))
                        Column {
                            Text("No blocked senders", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Numbers and alphanumeric sender IDs you add will appear here.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        } else {
            items(entries, key = SmsBlocklistEntry::id) { entry ->
                ProductCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Block, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                entry.displayText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "Added ${formatBlocklistTime(entry.createdAtMillis)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp,
                            )
                        }
                        IconButton(onClick = { removeConfirmation = entry }) {
                            Icon(Icons.Outlined.Delete, "Remove ${entry.displayText}")
                        }
                    }
                }
            }
        }
    }

    addConfirmation?.let { sender ->
        AlertDialog(
            onDismissRequest = { addConfirmation = null },
            title = { Text("Block this sender?") },
            text = {
                Text("Future incoming SMS from \"$sender\" will be blocked while CloudDrive is the default SMS app.")
            },
            confirmButton = {
                Button(onClick = {
                    addConfirmation = null
                    runCatching { SmsBlocklistStore.add(context, sender) }
                        .onSuccess {
                            senderText = ""
                            errorMessage = null
                            reload()
                        }
                        .onFailure { error -> errorMessage = error.message ?: "Could not add this sender" }
                }) { Text("Block sender") }
            },
            dismissButton = {
                OutlinedButton(onClick = { addConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    removeConfirmation?.let { entry ->
        AlertDialog(
            onDismissRequest = { removeConfirmation = null },
            title = { Text("Remove blocked sender?") },
            text = { Text("Future incoming SMS from \"${entry.displayText}\" will no longer be blocked.") },
            confirmButton = {
                Button(onClick = {
                    removeConfirmation = null
                    runCatching { SmsBlocklistStore.remove(context, entry.id) }
                        .onSuccess {
                            errorMessage = null
                            reload()
                        }
                        .onFailure { error -> errorMessage = error.message ?: "Could not remove this sender" }
                }) { Text("Remove") }
            },
            dismissButton = {
                OutlinedButton(onClick = { removeConfirmation = null }) { Text("Cancel") }
            },
        )
    }

    if (clearConfirmation) {
        AlertDialog(
            onDismissRequest = { clearConfirmation = false },
            title = { Text("Clear blocked senders?") },
            text = { Text("All ${entries.size} blocked senders will be removed from this device.") },
            confirmButton = {
                Button(onClick = {
                    clearConfirmation = false
                    runCatching { SmsBlocklistStore.clear(context) }
                        .onSuccess {
                            errorMessage = null
                            reload()
                        }
                        .onFailure { error -> errorMessage = error.message ?: "Could not clear blocked senders" }
                }) { Text("Clear all") }
            },
            dismissButton = {
                OutlinedButton(onClick = { clearConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

private fun formatBlocklistTime(timestampMillis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestampMillis))
