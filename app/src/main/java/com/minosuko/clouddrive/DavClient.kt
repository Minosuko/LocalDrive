package com.minosuko.clouddrive

import android.content.Context
import android.util.Xml
import android.util.JsonReader
import android.net.Uri
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

class DavClient(
    private val baseAddress: String,
    private val profileId: String,
    context: Context,
) {
    private val context = context.applicationContext
    private val accountId = AccountStore.account(this.context, profileId)?.userId
        ?: error("Sign in to CloudDrive to continue")
    private val origin = URI(baseAddress).let { "${it.scheme}://${it.rawAuthority}" }
    private val davBaseAddress = "$origin/api/mobile/v1/dav"
    private val cacheNamespace = "$baseAddress|$profileId|$accountId"
    private val accountQuery = "&account=${URLEncoder.encode(accountId, Charsets.UTF_8.name())}"

    fun requestHeaders(): Map<String, String> = mapOf("Authorization" to authorization())

    fun cachedCloud(segments: List<String>): List<BrowserEntry>? =
        synchronized(listCache) { listCache[listingKey(segments)]?.items }

    fun listCloud(segments: List<String>, force: Boolean = false): List<BrowserEntry> {
        val virtualPath = "/" + segments.joinToString("/")
        val cacheKey = listingKey(segments)
        val lock = listingLocks[(cacheKey.hashCode() and Int.MAX_VALUE) % listingLocks.size]
        return synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val cached = synchronized(listCache) { listCache[cacheKey] }
            val generation = cacheGeneration.get()
            if (!force && cached != null && now - cached.cachedAt < LIST_FRESH_MILLIS) {
                return@synchronized cached.items
            }
            try {
                val refresh = if (force) "&refresh=1" else ""
                val url = URL("${apiEndpoint("files")}/?path=${URLEncoder.encode(virtualPath, Charsets.UTF_8.name())}$refresh")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 30_000
                    useCaches = false
                    authorize()
                    if (!force && !cached?.etag.isNullOrEmpty()) setRequestProperty("If-None-Match", cached?.etag)
                }
                if (connection.responseCode == 304 && cached != null) {
                    connection.disconnect()
                    synchronized(listCache) {
                        if (cacheGeneration.get() == generation) {
                            listCache[cacheKey] = cached.copy(cachedAt = SystemClock.elapsedRealtime())
                        }
                    }
                    rememberDirectory(segments)
                    return@synchronized cached.items
                }
                val status = connection.responseCode
                val etag = connection.getHeaderField("ETag").orEmpty()
                val body = connection.responseBody()
                if (status != 200) throw DavException("Cannot list folder ($status)", status)
                val files = JSONObject(body).getJSONObject("data").getJSONArray("files")
                val result = buildList {
                    for (index in 0 until files.length()) {
                        val file = files.getJSONObject(index)
                        val name = file.getString("name")
                        val directory = file.getString("type") == "folder"
                        add(
                            BrowserEntry(
                                source = BrowserSource.CloudDrive,
                                name = name,
                                isDirectory = directory,
                                size = file.optLong("size"),
                                modified = file.optLong("modified"),
                                mimeType = file.optString("mime", if (directory) "httpd/unix-directory" else "application/octet-stream"),
                                cloudSegments = segments + name,
                            ),
                        )
                    }
                }
                synchronized(listCache) {
                    if (cacheGeneration.get() == generation) {
                        listCache[cacheKey] = CachedListing(etag, result, SystemClock.elapsedRealtime())
                    }
                }
                rememberDirectory(segments)
                result.filter(BrowserEntry::isDirectory).forEach { rememberDirectory(it.cloudSegments) }
                result
            } catch (error: Exception) {
                if (error is DavException && error.status == 404) forgetDirectoryPrefix(segments)
                if (!force && cached != null && (error !is DavException || error.status >= 500)) cached.items else throw error
            }
        }
    }

    fun listCloudTree(segments: List<String>, force: Boolean = false): List<BrowserEntry> {
        val result = buildList { forEachCloudTree(segments, force) { add(it) } }
        result.filter(BrowserEntry::isDirectory).forEach { rememberDirectory(it.cloudSegments) }
        return result
    }

    fun forEachCloudTree(
        segments: List<String>,
        force: Boolean = false,
        mediaOnly: Boolean = false,
        excludedPrefix: List<String> = emptyList(),
        action: (BrowserEntry) -> Unit,
    ) {
        val virtualPath = "/" + segments.joinToString("/")
        val refresh = if (force) "&refresh=1" else ""
        val media = if (mediaOnly) "&media=1" else ""
        val excluded = if (excludedPrefix.isEmpty()) "" else {
            "&exclude=${URLEncoder.encode("/${excludedPrefix.joinToString("/")}", Charsets.UTF_8.name())}"
        }
        val url = URL("${apiEndpoint("files/manifest")}/?path=${URLEncoder.encode(virtualPath, Charsets.UTF_8.name())}&stream=1$refresh$media$excluded")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 120_000
            useCaches = false
            authorize()
        }
        val status = connection.responseCode
        if (status == 404) forgetDirectoryPrefix(segments)
        if (status != 200) {
            connection.responseBody()
            throw DavException("Cannot read folder tree ($status)", status)
        }
        var stable = false
        val discoveredDirectories = mutableListOf<List<String>>()
        try {
            JsonReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { reader ->
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() != "data") {
                        reader.skipValue()
                        continue
                    }
                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "files" -> {
                                reader.beginArray()
                                while (reader.hasNext()) readTreeEntry(reader, segments)?.let { entry ->
                                    if (entry.isDirectory) discoveredDirectories += entry.cloudSegments
                                    action(entry)
                                }
                                reader.endArray()
                            }
                            "stable" -> stable = reader.nextBoolean()
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                }
                reader.endObject()
            }
        } finally {
            connection.disconnect()
        }
        if (!stable) throw DavException("Folder changed during scan; try again", 409)
        rememberDirectory(segments)
        discoveredDirectories.forEach(::rememberDirectory)
    }

    fun list(segments: List<String>): List<DriveItem> {
        val connection = open("PROPFIND", segments).apply {
            setRequestProperty("Depth", "1")
        }
        val status = connection.responseCode
        val body = connection.responseBody()
        if (status != 207) throw DavException("Cannot list folder ($status)", status)
        return parseMultiStatus(body).drop(1)
    }

    fun storageStats(force: Boolean = false): StorageStats {
        val cacheKey = "$cacheNamespace|storage"
        val now = SystemClock.elapsedRealtime()
        val cached = synchronized(storageCache) { storageCache[cacheKey] }
        if (!force && cached != null && now - cached.cachedAt < STORAGE_FRESH_MILLIS) return cached.stats
        val connection = (URL("${apiEndpoint("storage")}/").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            useCaches = false
            authorize()
        }
        return try {
            val status = connection.responseCode
            val body = connection.responseBody()
            if (status != 200) throw DavException("Cannot load storage ($status)", status)
            val data = JSONObject(body).getJSONObject("data")
            StorageStats(data.getLong("used_space"), data.getLong("free_space"), data.getLong("total_space")).also {
                synchronized(storageCache) { storageCache[cacheKey] = CachedStorage(it, SystemClock.elapsedRealtime()) }
            }
        } catch (error: Exception) {
            if (!force && cached != null) cached.stats else throw error
        }
    }

    fun ensureDirectories(segments: List<String>): Boolean {
        var created = false
        var count = 1
        var retriedMissingParent = false
        while (count <= segments.size) {
            val directory = segments.take(count)
            if (isKnownDirectory(directory)) {
                count++
                continue
            }
            val lockKey = directoryKey(directory)
            val directoryLock = directoryLocks[(lockKey.hashCode() and Int.MAX_VALUE) % directoryLocks.size]
            val status = synchronized(directoryLock) {
                if (isKnownDirectory(directory)) return@synchronized 204
                val connection = open("MKCOL", directory)
                connection.responseCode.also { connection.closeResponse() }
            }
            if (status == 409 && !retriedMissingParent) {
                forgetDirectoryPrefix(emptyList())
                retriedMissingParent = true
                count = 1
                continue
            }
            if (status != 405 && status !in setOf(200, 201, 204)) {
                throw DavException("Cannot create remote folder (${status})", status)
            }
            if (status == 201) created = true
            rememberDirectory(directory)
            count++
        }
        return created
    }

    fun upload(
        directorySegments: List<String>,
        fileName: String,
        mimeType: String,
        size: Long?,
        input: InputStream,
        overwrite: Boolean = true,
        continueTransfer: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {},
    ) = withUploadPermit {
        val validatedFileName = validateName(fileName)
        ensureDirectories(directorySegments)
        val connection = open("PUT", directorySegments + validatedFileName).apply {
            doOutput = true
            setRequestProperty("Content-Type", mimeType)
            if (!overwrite) setRequestProperty("If-None-Match", "*")
            if (size != null && size >= 0) setFixedLengthStreamingMode(size) else setChunkedStreamingMode(256 * 1024)
        }
        var transferred = 0L
        try {
            BufferedOutputStream(connection.outputStream, TRANSFER_BUFFER_SIZE).use { output ->
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                var nextConnectionCheck = 0L
                while (true) {
                    if (transferred >= nextConnectionCheck) {
                        if (!continueTransfer()) throw IOException("Transfer stopped")
                        nextConnectionCheck = transferred + CONNECTION_CHECK_BYTES
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    transferred += count
                    onProgress(transferred)
                }
            }
            if (size != null && size >= 0 && transferred != size) {
                throw EOFException("Upload ended at $transferred of $size bytes")
            }
            val status = connection.responseCode
            connection.closeResponse()
            if (status == 409) forgetDirectoryPrefix(directorySegments)
            if (status !in setOf(200, 201, 204)) {
                throw DavException(if (status == 412) "A file with this name already exists" else "Upload failed (${status})", status)
            }
            clearListingCache(directorySegments)
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
    }

    fun download(
        segments: List<String>,
        output: OutputStream,
        expectedSize: Long? = null,
        continueTransfer: () -> Boolean = { true },
        onProgress: (Long) -> Unit = {},
    ) {
        val connection = (URL(downloadUrl(segments).toString()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
            authorize()
        }
        val status = connection.responseCode
        if (status != 200) {
            connection.closeResponse()
            throw DavException("Download failed ($status)", status)
        }
        val declaredLength = connection.getHeaderFieldLong("Content-Length", -1L)
        if (expectedSize != null && expectedSize >= 0 && declaredLength >= 0 && declaredLength != expectedSize) {
            connection.closeResponse()
            throw EOFException("Remote file changed from $expectedSize to $declaredLength bytes")
        }
        var transferred = 0L
        try {
            BufferedInputStream(connection.inputStream, TRANSFER_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
                var nextConnectionCheck = 0L
                while (true) {
                    if (transferred >= nextConnectionCheck) {
                        if (!continueTransfer()) throw IOException("Transfer stopped")
                        nextConnectionCheck = transferred + CONNECTION_CHECK_BYTES
                    }
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    transferred += count
                    onProgress(transferred)
                }
            }
            if (declaredLength >= 0 && transferred != declaredLength) {
                throw EOFException("Download ended at $transferred of $declaredLength bytes")
            }
            if (expectedSize != null && expectedSize >= 0 && transferred != expectedSize) {
                throw EOFException("Download ended at $transferred of $expectedSize bytes")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun delete(segments: List<String>) {
        val connection = open("DELETE", segments)
        val status = connection.responseCode
        connection.closeResponse()
        if (status !in setOf(200, 204)) throw DavException("Cannot remove cloud source ($status)", status)
        forgetDirectoryPrefix(segments)
        clearListingCache(segments.dropLast(1))
    }

    fun moveToTrash(segments: List<String>) {
        moveManyToTrash(listOf(segments))
    }

    fun moveManyToTrash(paths: List<List<String>>) {
        require(paths.isNotEmpty() && paths.all { it.isNotEmpty() }) { "Cannot move the CloudDrive root to Trash" }
        val bodyPaths = JSONArray()
        paths.forEach { bodyPaths.put("/${it.joinToString("/")}") }
        val response = mobileJsonRequest(
            path = "files",
            method = "POST",
            body = JSONObject().put("paths", bodyPaths),
            methodOverride = "DELETE",
        )
        requireApiSuccess(response, "Could not move item to Trash")
        paths.forEach { segments ->
            forgetDirectoryPrefix(segments)
            clearListingCache(segments.dropLast(1))
        }
    }

    fun listTrash(): List<TrashEntry> {
        val response = mobileJsonRequest("trash", "GET")
        requireApiSuccess(response, "Could not load Trash")
        val files = response.getJSONObject("data").getJSONArray("files")
        return buildList {
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                add(
                    TrashEntry(
                        id = file.getString("id"),
                        name = file.getString("name"),
                        originalPath = file.optString("path", "/"),
                        isDirectory = file.optString("type") == "folder",
                        size = file.optLong("size"),
                        deletedAt = file.optLong("modified"),
                    ),
                )
            }
        }
    }

    fun restoreTrash(ids: List<String>) {
        require(ids.isNotEmpty()) { "Choose at least one Trash item" }
        val response = mobileJsonRequest("trash/restore", "POST", JSONObject().put("ids", JSONArray(ids)))
        requireApiSuccess(response, "Could not restore Trash item")
        clearCache(baseAddress)
    }

    fun deleteTrashItems(ids: List<String>) {
        require(ids.isNotEmpty()) { "Choose at least one Trash item" }
        val response = mobileJsonRequest(
            path = "trash/empty",
            method = "POST",
            body = JSONObject().put("ids", JSONArray(ids)),
            methodOverride = "DELETE",
        )
        requireApiSuccess(response, "Could not permanently delete Trash item")
    }

    fun emptyTrash() {
        val response = mobileJsonRequest(
            path = "trash/empty",
            method = "POST",
            body = JSONObject().put("ids", JSONArray()),
            methodOverride = "DELETE",
        )
        requireApiSuccess(response, "Could not empty Trash")
    }

    fun existingFileSizes(paths: List<List<String>>): Map<List<String>, Long> {
        require(paths.size <= 500) { "At most 500 paths can be checked at once" }
        if (paths.isEmpty()) return emptyMap()
        val byVirtualPath = paths.associateBy { "/${it.joinToString("/")}" }
        val bodyPaths = JSONArray()
        byVirtualPath.keys.forEach { bodyPaths.put(it) }
        val response = mobileJsonRequest("files/status", "POST", JSONObject().put("paths", bodyPaths))
        requireApiSuccess(response, "Could not verify cloud files")
        val files = response.getJSONObject("data").getJSONArray("files")
        return buildMap {
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                val path = byVirtualPath[file.optString("path")] ?: continue
                val size = file.optLong("size", -1)
                if (size >= 0) put(path, size)
            }
        }
    }

    fun listArchive(segments: List<String>): ArchiveViewerContent {
        require(segments.isNotEmpty()) { "Choose an archive" }
        val path = "/${segments.joinToString("/")}"
        val url = URL("${apiEndpoint("files/archive")}/?path=${URLEncoder.encode(path, Charsets.UTF_8.name())}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 120_000
            useCaches = false
            authorize()
        }
        val status = connection.responseCode
        val response = runCatching { JSONObject(connection.responseBody()) }.getOrElse {
            throw DavException("CloudDrive returned an invalid archive response ($status)", status)
        }
        if (status !in 200..299) {
            throw DavException(response.optString("error", "Could not read archive ($status)"), status)
        }
        val data = response.getJSONObject("data")
        val files = data.getJSONArray("files")
        val entries = buildList {
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                add(
                    ArchiveEntry(
                        path = item.getString("name"),
                        isDirectory = item.optString("type") == "folder",
                        size = item.optLong("size").coerceAtLeast(0),
                        modified = item.optLong("mtime").coerceAtLeast(0),
                        encrypted = item.optBoolean("encrypted"),
                    ),
                )
            }
        }
        return ArchiveViewerContent(segments.last(), data.optString("format"), entries)
    }

    fun exists(segments: List<String>): Boolean {
        val connection = open("HEAD", segments)
        val status = connection.responseCode
        connection.closeResponse()
        return when (status) {
            200, 204 -> true
            404 -> false
            else -> throw DavException("Cannot verify remote item ($status)", status)
        }
    }

    fun createDirectory(parent: List<String>, name: String) {
        val directory = parent + validateName(name)
        val connection = open("MKCOL", directory)
        val status = connection.responseCode
        connection.closeResponse()
        if (status != 201) throw DavException("Cannot create folder ($status)", status)
        rememberDirectory(directory)
        clearListingCache(parent)
    }

    fun createStagingDirectory(parent: List<String>, name: String) {
        val stagingName = validateStagingName(name)
        val directory = parent + stagingName
        val connection = open("MKCOL", directory).apply { setRequestProperty(INTERNAL_HEADER, "1") }
        val status = connection.responseCode
        connection.closeResponse()
        if (status != 201) throw DavException("Cannot create upload staging folder ($status)", status)
        rememberDirectory(directory)
    }

    fun deleteStaging(segments: List<String>) {
        require(segments.isNotEmpty()) { "Invalid staging folder" }
        validateStagingName(segments.last())
        val connection = open("DELETE", segments).apply { setRequestProperty(INTERNAL_HEADER, "1") }
        val status = connection.responseCode
        connection.closeResponse()
        if (status !in setOf(200, 204, 404)) throw DavException("Cannot clean upload staging folder ($status)", status)
        forgetDirectoryPrefix(segments)
    }

    fun createFile(parent: List<String>, name: String) = withUploadPermit {
        val connection = open("PUT", parent + validateName(name)).apply {
            doOutput = true
            setRequestProperty("If-None-Match", "*")
            setFixedLengthStreamingMode(0)
        }
        val status = connection.responseCode
        connection.closeResponse()
        if (status !in setOf(200, 201, 204)) throw DavException("Cannot create file ($status)", status)
        clearListingCache(parent)
    }

    fun paste(clipboard: FileClipboard, destination: List<String>, conflictPolicy: FileConflictPolicy): Boolean {
        val entry = clipboard.entry
        require(entry.source == BrowserSource.CloudDrive) { "Clipboard source is not CloudDrive" }
        require(entry.cloudSegments.dropLast(1) != destination) { "Choose a different destination folder" }
        val method = if (clipboard.action == ClipboardAction.Copy) "COPY" else "MOVE"
        val connection = open(method, entry.cloudSegments).apply {
            setRequestProperty("Destination", fileUrl(destination + entry.name).toString())
            setRequestProperty("Overwrite", if (conflictPolicy == FileConflictPolicy.Skip) "F" else "T")
            setRequestProperty("X-CloudDrive-Conflict", conflictPolicy.headerValue)
        }
        val status = connection.responseCode
        val skipped = connection.getHeaderField("X-CloudDrive-Conflict-Result") == "skipped"
        connection.closeResponse()
        if (status !in setOf(201, 204)) throw DavException(
            if (status == 412) "A file with this name already exists" else "$method failed ($status)",
            status,
        )
        if (skipped) return false
        clearListingCache(destination)
        if (entry.isDirectory) rememberDirectory(destination + entry.name)
        if (clipboard.action == ClipboardAction.Cut) {
            forgetDirectoryPrefix(entry.cloudSegments)
            clearListingCache(entry.cloudSegments.dropLast(1))
        }
        return true
    }

    fun move(
        source: List<String>,
        destinationParent: List<String>,
        destinationName: String,
        conflictPolicy: FileConflictPolicy = FileConflictPolicy.Overwrite,
    ): Boolean = moveItem(source, destinationParent, destinationName, conflictPolicy, directory = true)

    fun moveFile(
        source: List<String>,
        destinationParent: List<String>,
        destinationName: String,
        conflictPolicy: FileConflictPolicy,
    ): Boolean = moveItem(source, destinationParent, destinationName, conflictPolicy, directory = false)

    private fun moveItem(
        source: List<String>,
        destinationParent: List<String>,
        destinationName: String,
        conflictPolicy: FileConflictPolicy,
        directory: Boolean,
    ): Boolean {
        val destination = destinationParent + validateName(destinationName)
        val connection = open("MOVE", source).apply {
            setRequestProperty("Destination", fileUrl(destination).toString())
            setRequestProperty("Overwrite", if (conflictPolicy == FileConflictPolicy.Skip) "F" else "T")
            setRequestProperty("X-CloudDrive-Conflict", conflictPolicy.headerValue)
            setRequestProperty(INTERNAL_HEADER, "1")
        }
        val status = connection.responseCode
        val skipped = connection.getHeaderField("X-CloudDrive-Conflict-Result") == "skipped"
        connection.closeResponse()
        if (status !in setOf(201, 204)) throw DavException(
            if (status == 412) "A file with this name already exists" else "MOVE failed ($status)",
            status,
        )
        if (skipped) return false
        forgetDirectoryPrefix(source)
        if (directory) rememberDirectory(destination)
        clearListingCache(source.dropLast(1))
        clearListingCache(destination.dropLast(1))
        return true
    }

    fun fileUrl(segments: List<String>): Uri = Uri.parse(
        davAddress().trimEnd('/') + segments.joinToString(separator = "", prefix = "") { "/${encodeSegment(it)}" },
    )

    fun previewUrl(segments: List<String>, modified: Long = 0, size: Long = 0): Uri {
        val path = "/" + segments.joinToString("/")
        val version = if (modified > 0 || size > 0) "&v=$modified-$size" else ""
        return Uri.parse("${apiEndpoint("view")}/?path=${URLEncoder.encode(path, Charsets.UTF_8.name())}$version$accountQuery")
    }

    fun downloadUrl(segments: List<String>, modified: Long = 0, size: Long = 0): Uri {
        val path = "/" + segments.joinToString("/")
        val version = if (modified > 0 || size > 0) "&v=$modified-$size" else ""
        return Uri.parse("${apiEndpoint("files/download")}/?path=${URLEncoder.encode(path, Charsets.UTF_8.name())}&view=1$version$accountQuery")
    }

    fun thumbnailUrl(segments: List<String>, directory: Boolean = false, modified: Long = 0, size: Long = 0): Uri {
        val path = "/" + segments.joinToString("/")
        val type = if (directory) "&type=folder" else ""
        val version = if (modified > 0 || size > 0) "&v=$modified-$size" else ""
        return Uri.parse("${apiEndpoint("thumbnails")}/?path=${URLEncoder.encode(path, Charsets.UTF_8.name())}$type$version$accountQuery")
    }

    private fun open(method: String, segments: List<String>): HttpURLConnection {
        val suffix = segments.joinToString("/") { encodeSegment(it) }
        val base = davAddress()
        val url = if (suffix.isEmpty()) base else "$base/$suffix"
        return (URL(url).openConnection() as HttpURLConnection).apply {
            val overridden = method in setOf("MKCOL", "PROPFIND", "COPY", "MOVE", "DELETE")
            requestMethod = if (overridden) "POST" else method
            connectTimeout = 15_000
            readTimeout = 120_000
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "CloudDrive-Android/1.0")
            authorize()
            if (segments.any { it.startsWith(RESERVED_PREFIX, ignoreCase = true) }) {
                setRequestProperty(INTERNAL_HEADER, "1")
            }
            if (overridden) {
                setRequestProperty("X-HTTP-Method-Override", method)
                doOutput = true
                setFixedLengthStreamingMode(0)
            }
        }
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun apiEndpoint(path: String): String = "$origin/api/mobile/v1/$path"

    private fun mobileJsonRequest(
        path: String,
        method: String,
        body: JSONObject? = null,
        methodOverride: String? = null,
    ): JSONObject {
        val connection = (URL("${apiEndpoint(path.trim('/'))}/").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 30_000
            useCaches = false
            authorize()
            if (methodOverride != null) setRequestProperty("X-HTTP-Method-Override", methodOverride)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                setFixedLengthStreamingMode(bytes.size)
                outputStream.use { it.write(bytes) }
            }
        }
        val status = connection.responseCode
        val responseText = connection.responseBody()
        val response = runCatching { JSONObject(responseText) }.getOrElse {
            throw DavException("CloudDrive returned an invalid response ($status)", status)
        }
        if (status !in 200..299) throw DavException(response.optString("error", "CloudDrive request failed ($status)"), status)
        return response
    }

    private fun requireApiSuccess(response: JSONObject, fallback: String) {
        if (response.optBoolean("success")) return
        val errors = response.optJSONArray("errors")
        val message = if (errors != null && errors.length() > 0) errors.optString(0) else response.optString("error", fallback)
        error(message.ifBlank { fallback })
    }

    private fun davAddress(): String = davBaseAddress

    private fun authorization(): String = AccountStore.authorization(context, profileId)
        ?: error("Sign in to CloudDrive to continue")

    private fun HttpURLConnection.authorize() {
        setRequestProperty("Authorization", authorization())
    }

    private fun validateName(value: String): String {
        val name = value.trim()
        require(name.isNotEmpty() && name != "." && name != "..") { "Invalid name" }
        require(!name.contains(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"))) { "Name contains invalid characters" }
        require(!name.startsWith(RESERVED_PREFIX, ignoreCase = true)) { "Name uses a reserved CloudDrive prefix" }
        return name
    }

    private fun validateStagingName(value: String): String {
        val name = value.trim()
        require(name.startsWith(RESERVED_PREFIX, ignoreCase = true) && !name.contains('/') && !name.contains('\\')) {
            "Invalid upload staging folder"
        }
        return name
    }

    private fun readTreeEntry(reader: JsonReader, segments: List<String>): BrowserEntry? {
        var path = ""
        var directory = false
        var size = 0L
        var modified = 0L
        var mimeType = "application/octet-stream"
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "path" -> path = reader.nextString()
                "type" -> directory = reader.nextString() == "folder"
                "size" -> size = reader.nextLong()
                "modified" -> modified = reader.nextLong()
                "mime" -> mimeType = reader.nextString()
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val relative = path.split('/').filter(String::isNotEmpty)
        if (relative.isEmpty()) return null
        return BrowserEntry(
            source = BrowserSource.CloudDrive,
            name = relative.last(),
            isDirectory = directory,
            size = size,
            modified = modified,
            mimeType = if (directory) "httpd/unix-directory" else mimeType,
            cloudSegments = segments + relative,
        )
    }

    private fun listingKey(segments: List<String>) = "$cacheNamespace|/${segments.joinToString("/")}"

    private fun directoryKey(segments: List<String>) = "$cacheNamespace|/${segments.joinToString("/")}"

    private fun isKnownDirectory(segments: List<String>): Boolean = synchronized(knownDirectories) {
        val key = directoryKey(segments)
        if (key !in knownDirectories) return@synchronized false
        knownDirectories.remove(key)
        knownDirectories.add(key)
        true
    }

    private fun rememberDirectory(segments: List<String>) {
        val key = directoryKey(segments)
        synchronized(knownDirectories) {
            knownDirectories.remove(key)
            knownDirectories.add(key)
            while (knownDirectories.size > KNOWN_DIRECTORY_LIMIT) {
                val iterator = knownDirectories.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    private fun forgetDirectoryPrefix(segments: List<String>) {
        val prefix = directoryKey(segments)
        synchronized(knownDirectories) {
            knownDirectories.removeAll {
                if (segments.isEmpty()) it.startsWith(prefix) else it == prefix || it.startsWith("$prefix/")
            }
        }
    }

    private inline fun <T> withUploadPermit(block: () -> T): T {
        transferPermits.acquire()
        return try {
            block()
        } finally {
            transferPermits.release()
        }
    }

    private fun clearListingCache(segments: List<String>) {
        val key = "$cacheNamespace|/${segments.joinToString("/")}"
        synchronized(listCache) {
            listCache.remove(key)
            cacheGeneration.incrementAndGet()
        }
    }

    private fun HttpURLConnection.closeResponse() {
        try {
            (if (responseCode >= 400) errorStream else inputStream)?.close()
        } catch (_: Exception) {
        } finally {
            disconnect()
        }
    }

    private fun HttpURLConnection.responseBody(): String = try {
        (if (responseCode >= 400) errorStream else inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
    } finally {
        disconnect()
    }

    private fun parseMultiStatus(xml: String): List<DriveItem> {
        val parser = Xml.newPullParser().apply {
            setFeature("http://xmlpull.org/v1/doc/features.html#process-namespaces", true)
            setInput(xml.reader())
        }
        val items = mutableListOf<DriveItem>()
        var href = ""
        var name = ""
        var size = 0L
        var mime = "application/octet-stream"
        var directory = false
        var insideResponse = false
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) {
                when (parser.name) {
                    "response" -> {
                        insideResponse = true
                        href = ""
                        name = ""
                        size = 0
                        mime = "application/octet-stream"
                        directory = false
                    }
                    "href" -> if (insideResponse) href = parser.nextText()
                    "displayname" -> if (insideResponse) name = parser.nextText()
                    "getcontentlength" -> if (insideResponse) size = parser.nextText().toLongOrNull() ?: 0
                    "getcontenttype" -> if (insideResponse) mime = parser.nextText()
                    "collection" -> if (insideResponse) directory = true
                }
            } else if (parser.eventType == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "response") {
                if (href.isNotEmpty()) items += DriveItem(href, name.ifEmpty { "Unnamed" }, directory, size, mime)
                insideResponse = false
            }
            parser.next()
        }
        return items
    }

    private data class CachedListing(val etag: String, val items: List<BrowserEntry>, val cachedAt: Long)
    private data class CachedStorage(val stats: StorageStats, val cachedAt: Long)

    companion object {
        private const val LIST_FRESH_MILLIS = 3_000L
        private const val STORAGE_FRESH_MILLIS = 15_000L
        private const val LIST_CACHE_LIMIT = 256
        private const val KNOWN_DIRECTORY_LIMIT = 2_048
        private const val TRANSFER_BUFFER_SIZE = 1024 * 1024
        private const val CONNECTION_CHECK_BYTES = 2L * 1024 * 1024
        private const val RESERVED_PREFIX = ".clouddrive-stage-"
        private const val INTERNAL_HEADER = "X-CloudDrive-Internal"
        private val listCache = object : LinkedHashMap<String, CachedListing>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedListing>?): Boolean = size > LIST_CACHE_LIMIT
        }
        private val cacheGeneration = AtomicLong()
        private val listingLocks = Array(64) { Any() }
        private val directoryLocks = Array(64) { Any() }
        private val knownDirectories = linkedSetOf<String>()
        private val storageCache = mutableMapOf<String, CachedStorage>()
        private val transferPermits = Semaphore(3, true)

        fun clearCache(address: String) {
            synchronized(listCache) {
                listCache.keys.removeAll { it.startsWith("$address|") }
                cacheGeneration.incrementAndGet()
            }
            synchronized(knownDirectories) { knownDirectories.removeAll { it.startsWith("$address|") } }
            synchronized(storageCache) { storageCache.keys.removeAll { it.startsWith("$address|") } }
        }
    }
}

class DavException(message: String, val status: Int) : Exception(message)

data class DriveItem(
    val href: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val mimeType: String,
)

data class StorageStats(val used: Long, val free: Long, val total: Long)
