package com.minosuko.clouddrive

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import java.io.File
import java.security.MessageDigest

enum class BrowserSource { Device, CloudDrive }
enum class ClipboardAction { Cut, Copy }

data class BrowserEntry(
    val source: BrowserSource,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long = 0,
    val mimeType: String,
    val cloudSegments: List<String> = emptyList(),
    val deviceUri: Uri? = null,
    val parentUri: Uri? = null,
    val driveProfileId: String? = null,
    val driveRoot: Boolean = false,
    val devicePath: String? = null,
    val thumbnailUri: Uri? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
) {
    val id: String = devicePath ?: deviceUri?.toString()
        ?: if (driveRoot) requireNotNull(driveProfileId) else "${driveProfileId.orEmpty()}:${cloudSegments.joinToString("/")}"
    val extension: String = name.substringAfterLast('.', "").lowercase()
}

data class FileClipboard(val action: ClipboardAction, val entry: BrowserEntry)
data class TrashEntry(
    val id: String,
    val name: String,
    val originalPath: String,
    val isDirectory: Boolean,
    val size: Long,
    val deletedAt: Long,
)
data class DeviceSnapshot(val bytes: Long, val fingerprint: String)

enum class FileSort { Name, Modified, Size }
enum class FileLayout { List, Grid }

class DeviceFileStore(private val context: Context) {
    fun list(path: String): List<BrowserEntry> {
        val directory = File(path)
        require(directory.isDirectory && directory.canRead()) { "Device storage permission is required" }
        return directory.listFiles().orEmpty().map { file ->
            BrowserEntry(
                source = BrowserSource.Device,
                name = file.name,
                isDirectory = file.isDirectory,
                size = if (file.isFile) file.length() else 0,
                modified = file.lastModified() / 1000,
                mimeType = if (file.isDirectory) "httpd/unix-directory" else guessMime(file.extension),
                deviceUri = Uri.fromFile(file),
                devicePath = file.absolutePath,
                parentUri = Uri.fromFile(directory),
                thumbnailUri = if (file.isFile && (guessMime(file.extension).startsWith("image/") || guessMime(file.extension).startsWith("video/"))) Uri.fromFile(file) else null,
            )
        }.sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
    }

    fun createDirectory(parentPath: String, name: String) {
        check(File(parentPath, validateName(name)).mkdir()) { "Cannot create folder" }
    }

    fun createFile(parentPath: String, name: String) {
        check(File(parentPath, validateName(name)).createNewFile()) { "Cannot create file" }
    }

    fun delete(path: String) {
        val target = File(path).canonicalFile
        require(target.absolutePath != File(deviceRootPath()).canonicalPath) { "Device storage root cannot be deleted" }
        require(target.exists()) { "File no longer exists" }
        check(if (target.isDirectory) target.deleteRecursively() else target.delete()) { "Cannot delete ${target.name}" }
    }

    fun paste(clipboard: FileClipboard, destinationPath: String, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val source = File(requireNotNull(clipboard.entry.devicePath))
        val sourcePath = source.canonicalFile
        val destination = File(destinationPath).canonicalFile
        if (source.isDirectory) {
            require(destination != sourcePath && !destination.toPath().startsWith(sourcePath.toPath())) {
                "A folder cannot be pasted inside itself"
            }
        }
        val target = File(destination, source.name)
        require(!target.exists()) { "A file with this name already exists" }
        val snapshot = snapshot(source.absolutePath)
        val total = snapshot.bytes
        if (clipboard.action == ClipboardAction.Cut && source.renameTo(target)) {
            onProgress(total, total)
            return
        }
        var copied = 0L
        try {
            copyEntry(source, target) { count ->
                copied += count
                onProgress(copied, total)
            }
        } catch (error: Exception) {
            if (target.isDirectory) target.deleteRecursively() else target.delete()
            throw error
        }
        if (clipboard.action == ClipboardAction.Cut) {
            require(snapshot(source.absolutePath) == snapshot) { "Copied, but the source changed and was not removed" }
            check(if (source.isDirectory) source.deleteRecursively() else source.delete()) { "Copied, but could not remove the source" }
        }
    }

    fun snapshot(path: String): DeviceSnapshot {
        val root = File(path)
        require(root.exists() && root.canRead()) { "Cannot read ${root.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        fun visit(file: File, relative: String) {
            digest.update("$relative|${file.isDirectory}|${file.length()}|${file.lastModified()}\n".toByteArray())
            if (file.isDirectory) {
                val children = file.listFiles()?.sortedBy { it.name } ?: error("Cannot read folder ${file.name}")
                children.forEach { visit(it, if (relative.isEmpty()) it.name else "$relative/${it.name}") }
            } else {
                bytes += file.length()
            }
        }
        visit(root, "")
        return DeviceSnapshot(bytes, digest.digest().joinToString("") { "%02x".format(it) })
    }

    private fun copyEntry(source: File, target: File, onBytes: (Long) -> Unit) {
        if (source.isDirectory) {
            check(target.mkdir()) { "Cannot create folder ${target.name}" }
            val children = source.listFiles() ?: error("Cannot read folder ${source.name}")
            children.forEach { copyEntry(it, File(target, it.name), onBytes) }
        } else {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        onBytes(count.toLong())
                    }
                }
            }
        }
        target.setLastModified(source.lastModified())
    }

    private fun validateName(name: String): String {
        val clean = name.trim()
        require(clean.isNotEmpty() && clean != "." && clean != "..") { "Invalid name" }
        return clean
    }

    private fun guessMime(extension: String): String = android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
}

fun deviceRootPath(): String = Environment.getExternalStorageDirectory().absolutePath

fun deviceStorageStats(context: Context): StorageStats {
    val directory = context.getExternalFilesDir(null) ?: context.filesDir
    val stats = StatFs(directory.absolutePath)
    val total = stats.totalBytes
    val free = stats.availableBytes
    return StorageStats(total - free, free, total)
}
