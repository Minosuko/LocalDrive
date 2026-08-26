package com.minosuko.clouddrive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private data class ArchiveNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val encrypted: Boolean,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveTreeViewer(content: ArchiveViewerContent, onClose: () -> Unit) {
    var currentPath by rememberSaveable(content.title) { mutableStateOf("") }
    val goBack = {
        if (currentPath.isEmpty()) onClose()
        else currentPath = currentPath.substringBeforeLast('/', "")
    }
    BackHandler(onBack = goBack)
    val nodes = archiveChildren(content.entries, currentPath)

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(content.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            content.format.uppercase() + " archive",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, if (currentPath.isEmpty()) "Close archive" else "Up")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Text(
                        if (currentPath.isEmpty()) "/" else "/$currentPath",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${nodes.size} ${if (nodes.size == 1) "item" else "items"} | metadata only",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (nodes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("This folder is empty", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(nodes, key = ArchiveNode::path) { node ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = node.isDirectory) {
                                    currentPath = node.path
                                }
                                .padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(42.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = if (node.isDirectory) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        if (node.isDirectory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                                        contentDescription = null,
                                        tint = if (node.isDirectory) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        node.name,
                                        modifier = Modifier.weight(1f, fill = false),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (node.encrypted) {
                                        Icon(
                                            Icons.Rounded.Lock,
                                            contentDescription = "Encrypted",
                                            modifier = Modifier.padding(start = 6.dp).size(15.dp),
                                            tint = MaterialTheme.colorScheme.tertiary,
                                        )
                                    }
                                }
                                val details = buildList {
                                    if (!node.isDirectory) add(formatArchiveSize(node.size))
                                    if (node.modified > 0) add(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(node.modified * 1000)))
                                }.joinToString(" | ")
                                if (details.isNotEmpty()) {
                                    Text(
                                        details,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }
            }
        }
    }
}

private fun archiveChildren(entries: List<ArchiveEntry>, currentPath: String): List<ArchiveNode> {
    val prefix = currentPath.split('/').filter(String::isNotEmpty)
    val nodes = linkedMapOf<String, ArchiveNode>()
    entries.forEach { entry ->
        val segments = entry.path.trim('/').split('/').filter(String::isNotEmpty)
        if (segments.size <= prefix.size || segments.take(prefix.size) != prefix) return@forEach
        val name = segments[prefix.size]
        val path = (prefix + name).joinToString("/")
        val direct = segments.size == prefix.size + 1
        val directory = !direct || entry.isDirectory
        val existing = nodes[path]
        nodes[path] = ArchiveNode(
            path = path,
            name = name,
            isDirectory = directory || existing?.isDirectory == true,
            size = if (direct) entry.size else existing?.size ?: 0,
            modified = maxOf(if (direct) entry.modified else 0, existing?.modified ?: 0),
            encrypted = (direct && entry.encrypted) || existing?.encrypted == true,
        )
    }
    return nodes.values.sortedWith(compareByDescending<ArchiveNode> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

private fun formatArchiveSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024 && unit < units.lastIndex)
    return if (value >= 10) "%.0f %s".format(value, units[unit]) else "%.1f %s".format(value, units[unit])
}
