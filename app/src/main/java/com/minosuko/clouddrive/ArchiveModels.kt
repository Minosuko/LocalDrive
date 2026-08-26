package com.minosuko.clouddrive

data class ArchiveEntry(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val encrypted: Boolean = false,
)

data class ArchiveViewerContent(
    val title: String,
    val format: String,
    val entries: List<ArchiveEntry>,
)

data class PendingApkInstall(val path: String)
