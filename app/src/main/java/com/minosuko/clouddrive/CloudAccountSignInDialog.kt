package com.minosuko.clouddrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CloudAccountSignInDialog(
    driveName: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (username: String, password: String, createRoot: Boolean) -> Unit,
) {
    var username by remember(driveName) { mutableStateOf("") }
    var password by remember(driveName) { mutableStateOf("") }
    var createRoot by remember(driveName) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Root access to $driveName") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!createRoot) Button(onClick = { createRoot = false }, modifier = Modifier.weight(1f)) { Text("Root sign in") }
                    else OutlinedButton(onClick = { createRoot = false }, modifier = Modifier.weight(1f)) { Text("Root sign in") }
                    if (createRoot) Button(onClick = { createRoot = true }, modifier = Modifier.weight(1f)) { Text("Create root") }
                    else OutlinedButton(onClick = { createRoot = true }, modifier = Modifier.weight(1f)) { Text("Create root") }
                }
                Text(
                    if (createRoot) "First-time setup only. This CloudDrive can have one root owner."
                    else "Each CloudDrive has one root account. Root sign-in is required before files or sync can be used.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit(username.trim(), password, createRoot) },
                enabled = !busy && username.isNotBlank() && password.isNotBlank() && (!createRoot || password.length >= 10),
            ) { Text(if (busy) "Connecting..." else if (createRoot) "Create root" else "Sign in as root") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
