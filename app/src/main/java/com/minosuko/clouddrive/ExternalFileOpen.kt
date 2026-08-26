package com.minosuko.clouddrive

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

data class PreparedExternalFile(
    val id: String = UUID.randomUUID().toString(),
    val uri: Uri,
    val mimeType: String,
    val displayName: String,
    val installPackage: Boolean,
)

fun prepareDeviceExternalFile(context: Context, entry: BrowserEntry, installPackage: Boolean): PreparedExternalFile {
    require(entry.source == BrowserSource.Device && !entry.isDirectory) { "Choose a device file" }
    val file = File(requireNotNull(entry.devicePath)).canonicalFile
    val root = File(deviceRootPath()).canonicalFile
    require(file.isFile && file.canRead() && (file == root || file.path.startsWith(root.path + File.separator))) {
        "Cannot open this device file"
    }
    return PreparedExternalFile(
        uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file),
        mimeType = externalMimeType(entry, installPackage),
        displayName = entry.name,
        installPackage = installPackage,
    )
}

fun stageCloudExternalFile(
    context: Context,
    client: DavClient,
    entry: BrowserEntry,
    installPackage: Boolean,
    onProgress: (Long) -> Unit,
): PreparedExternalFile {
    require(entry.source == BrowserSource.CloudDrive && !entry.isDirectory) { "Choose a cloud file" }
    val cacheRoot = File(context.cacheDir, "external-open")
    check(cacheRoot.exists() || cacheRoot.mkdirs()) { "Could not prepare temporary storage" }
    removeStaleExternalFiles(cacheRoot)
    val requestDirectory = File(cacheRoot, UUID.randomUUID().toString())
    check(requestDirectory.mkdir()) { "Could not prepare temporary storage" }
    val safeName = entry.name.replace('/', '_').replace('\\', '_').replace('\u0000', '_').ifBlank { "file" }
    val target = File(requestDirectory, safeName)
    val partial = File(requestDirectory, "$safeName.part")
    try {
        partial.outputStream().use { output ->
            client.download(
                segments = entry.cloudSegments,
                output = output,
                expectedSize = entry.size.takeIf { it >= 0 },
                onProgress = onProgress,
            )
        }
        check(partial.renameTo(target)) { "Could not finalize downloaded file" }
        return PreparedExternalFile(
            uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target),
            mimeType = externalMimeType(entry, installPackage),
            displayName = entry.name,
            installPackage = installPackage,
        )
    } catch (error: Exception) {
        requestDirectory.deleteRecursively()
        throw error
    }
}

fun externalOpenIntent(file: PreparedExternalFile): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(file.uri, file.mimeType)
    clipData = ClipData.newRawUri(file.displayName, file.uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

private fun externalMimeType(entry: BrowserEntry, installPackage: Boolean): String {
    if (installPackage) return "application/vnd.android.package-archive"
    val declared = entry.mimeType.substringBefore(';').trim().lowercase()
    if (declared.isNotEmpty() && declared != "application/octet-stream" && declared != "httpd/unix-directory" && '/' in declared) {
        return declared
    }
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.extension).orEmpty().ifBlank { "*/*" }
}

private fun removeStaleExternalFiles(root: File) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
    root.listFiles()?.forEach { file ->
        if (file.lastModified() in 1 until cutoff) file.deleteRecursively()
    }
}
