package com.minosuko.clouddrive

import com.github.junrar.Archive
import com.github.junrar.rarfile.HostSystem
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.tukaani.xz.SeekableFileInputStream
import org.tukaani.xz.SeekableXZInputStream

private const val MAX_ARCHIVE_ENTRIES = 100_000
private const val METADATA_MEMORY_LIMIT_KIB = 64 * 1024
private const val UNIX_FILE_TYPE_MASK = 0xF000
private const val UNIX_SYMLINK_TYPE = 0xA000

class LocalArchiveException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Throws(LocalArchiveException::class)
fun readLocalArchive(file: File): ArchiveViewerContent {
    if (!file.isFile || !file.canRead()) {
        throw LocalArchiveException("Local archive is not a readable file")
    }

    val format = try {
        detectArchiveFormat(file)
    } catch (error: LocalArchiveException) {
        throw error
    } catch (error: Exception) {
        throw LocalArchiveException("Could not identify local archive format", error)
    }
    val collector = ArchiveEntryCollector()

    try {
        when (format) {
            LocalArchiveFormat.ZIP -> readZip(file, collector)
            LocalArchiveFormat.SEVEN_Z -> readSevenZ(file, collector)
            LocalArchiveFormat.RAR -> readRar(file, collector)
            LocalArchiveFormat.TAR -> readTar(file, collector)
            LocalArchiveFormat.GZIP -> readGzip(file, collector)
            LocalArchiveFormat.XZ -> readXz(file, collector)
        }
    } catch (error: LocalArchiveException) {
        throw error
    } catch (error: Exception) {
        throw LocalArchiveException("Could not read ${format.id} archive metadata", error)
    }

    return ArchiveViewerContent(
        title = file.name,
        format = format.id,
        entries = collector.entries,
    )
}

private enum class LocalArchiveFormat(val id: String) {
    ZIP("zip"),
    SEVEN_Z("7z"),
    RAR("rar"),
    TAR("tar"),
    GZIP("gz"),
    XZ("xz"),
}

private class ArchiveEntryCollector {
    private val collected = ArrayList<ArchiveEntry>()
    private var encountered = 0

    val entries: List<ArchiveEntry>
        get() = collected

    fun add(
        rawPath: String?,
        isDirectory: Boolean,
        size: Long,
        modified: Long,
        encrypted: Boolean = false,
        isLink: Boolean = false,
    ) {
        encountered++
        if (encountered > MAX_ARCHIVE_ENTRIES) {
            throw LocalArchiveException("Archive contains more than $MAX_ARCHIVE_ENTRIES entries")
        }
        if (isLink) {
            throw LocalArchiveException("Archive contains an unsupported link entry")
        }

        val path = normalizeArchivePath(rawPath)
            ?: throw LocalArchiveException("Archive contains an unsafe entry path")
        collected += ArchiveEntry(
            path = if (isDirectory) "$path/" else path,
            isDirectory = isDirectory,
            size = if (isDirectory) 0 else size.coerceAtLeast(0),
            modified = modified.coerceAtLeast(0),
            encrypted = encrypted,
        )
    }
}

private fun readZip(file: File, collector: ArchiveEntryCollector) {
    ZipFile.builder()
        .setFile(file)
        .setIgnoreLocalFileHeader(true)
        .get()
        .use { archive ->
            val entries = archive.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                collector.add(
                    rawPath = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    modified = epochSeconds(entry.time),
                    encrypted = entry.generalPurposeBit.usesEncryption(),
                    isLink = entry.isUnixSymlink,
                )
            }
        }
}

private fun readSevenZ(file: File, collector: ArchiveEntryCollector) {
    SevenZFile.builder()
        .setFile(file)
        .setPassword(CharArray(0))
        .setMaxMemoryLimitKb(METADATA_MEMORY_LIMIT_KIB)
        .get()
        .use { archive ->
            val entries = archive.entries.toList()
            if (entries.size > MAX_ARCHIVE_ENTRIES) {
                throw LocalArchiveException("Archive contains more than $MAX_ARCHIVE_ENTRIES entries")
            }

            val encrypted = BooleanArray(entries.size)
            try {
                for (index in entries.indices) {
                    val entry = archive.nextEntry ?: break
                    encrypted[index] = entry.contentMethods?.any {
                        it.method == SevenZMethod.AES256SHA256
                    } == true
                }
            } catch (_: IOException) {
                // Entry streams are never read; unsupported coders only make encryption unknown.
            } catch (_: RuntimeException) {
                // Preserve parsed metadata if optional coder initialization is unavailable.
            }

            entries.forEachIndexed { index, entry ->
                if (entry.isAntiItem) {
                    throw LocalArchiveException("Archive contains an unsupported anti-item entry")
                }
                collector.add(
                    rawPath = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    modified = if (entry.hasLastModifiedDate) {
                        epochSeconds(entry.lastModifiedTime.toMillis())
                    } else {
                        0
                    },
                    encrypted = encrypted[index],
                )
            }
        }
}

private fun readRar(file: File, collector: ArchiveEntryCollector) {
    Archive(file).use { archive ->
        val entries = archive.fileHeaders
        if (entries.size > MAX_ARCHIVE_ENTRIES) {
            throw LocalArchiveException("Archive contains more than $MAX_ARCHIVE_ENTRIES entries")
        }

        entries.forEach { entry ->
            val isUnixSymlink = entry.hostOS == HostSystem.unix &&
                (entry.fileAttr and UNIX_FILE_TYPE_MASK) == UNIX_SYMLINK_TYPE
            collector.add(
                rawPath = entry.fileName,
                isDirectory = entry.isDirectory,
                size = if (entry.isUnpSizeUnknown) 0 else entry.fullUnpackSize,
                modified = entry.lastModifiedTime?.toMillis()?.let(::epochSeconds) ?: 0,
                encrypted = entry.isEncrypted,
                isLink = entry.redirection != null || isUnixSymlink,
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun readTar(file: File, collector: ArchiveEntryCollector) {
    TarArchiveInputStream(BufferedInputStream(FileInputStream(file))).use { archive ->
        while (true) {
            val entry = archive.nextTarEntry ?: break
            collector.add(
                rawPath = entry.name,
                isDirectory = entry.isDirectory,
                size = entry.realSize,
                modified = entry.lastModifiedTime?.toMillis()?.let(::epochSeconds) ?: 0,
                isLink = entry.isSymbolicLink || entry.isLink,
            )
        }
    }
}

private fun readGzip(file: File, collector: ArchiveEntryCollector) {
    val metadata = RandomAccessFile(file, "r").use { archive ->
        val length = archive.length()
        if (length < 18) throw IOException("Truncated gzip stream")

        archive.seek(0)
        if (archive.readUnsignedByte() != 0x1F || archive.readUnsignedByte() != 0x8B) {
            throw IOException("Invalid gzip signature")
        }
        if (archive.readUnsignedByte() != 8) {
            throw IOException("Unsupported gzip compression method")
        }
        val flags = archive.readUnsignedByte()
        if (flags and 0xE0 != 0) throw IOException("Invalid gzip flags")
        val modified = readUnsignedIntLittleEndian(archive)
        archive.skipBytes(2) // Extra flags and source operating system.

        val trailerOffset = length - 8
        if (flags and 0x04 != 0) {
            ensureAvailable(archive, trailerOffset, 2)
            val extraLength = archive.readUnsignedByte() or (archive.readUnsignedByte() shl 8)
            ensureAvailable(archive, trailerOffset, extraLength.toLong())
            archive.seek(archive.filePointer + extraLength)
        }
        if (flags and 0x08 != 0) skipZeroTerminatedField(archive, trailerOffset)
        if (flags and 0x10 != 0) skipZeroTerminatedField(archive, trailerOffset)
        if (flags and 0x02 != 0) {
            ensureAvailable(archive, trailerOffset, 2)
            archive.skipBytes(2)
        }
        if (archive.filePointer > trailerOffset) throw IOException("Invalid gzip header")

        archive.seek(length - 4)
        GzipMetadata(
            size = readUnsignedIntLittleEndian(archive),
            modified = modified,
        )
    }

    collector.add(
        rawPath = inferredStreamName(file.name, LocalArchiveFormat.GZIP),
        isDirectory = false,
        size = metadata.size,
        modified = metadata.modified,
    )
}

private fun readXz(file: File, collector: ArchiveEntryCollector) {
    val size = SeekableXZInputStream(
        SeekableFileInputStream(file),
        METADATA_MEMORY_LIMIT_KIB,
    ).use { it.length() }
    collector.add(
        rawPath = inferredStreamName(file.name, LocalArchiveFormat.XZ),
        isDirectory = false,
        size = size,
        modified = 0,
    )
}

private data class GzipMetadata(val size: Long, val modified: Long)

private fun normalizeArchivePath(rawPath: String?): String? {
    if (rawPath.isNullOrEmpty() || '\u0000' in rawPath) return null
    val separated = rawPath.replace('\\', '/')
    if (separated.startsWith('/')) return null

    val segments = separated.split('/').filter { it.isNotEmpty() && it != "." }
    if (segments.isEmpty() || segments.any { it == ".." }) return null
    val first = segments.first()
    if (first.length >= 2 && first[0].isAsciiLetter() && first[1] == ':') return null
    return segments.joinToString("/")
}

private fun Char.isAsciiLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

private fun detectArchiveFormat(file: File): LocalArchiveFormat {
    val signature = ByteArray(512)
    val length = BufferedInputStream(FileInputStream(file)).use { input ->
        var total = 0
        while (total < signature.size) {
            val read = input.read(signature, total, signature.size - total)
            if (read < 0) break
            total += read
        }
        total
    }

    return when {
        isZipSignature(signature, length) -> LocalArchiveFormat.ZIP
        startsWith(signature, length, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> LocalArchiveFormat.SEVEN_Z
        startsWith(signature, length, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07) ||
            startsWith(signature, length, 0x52, 0x45, 0x7E, 0x5E) -> LocalArchiveFormat.RAR
        startsWith(signature, length, 0x1F, 0x8B) -> LocalArchiveFormat.GZIP
        startsWith(signature, length, 0xFD, 0x37, 0x7A, 0x58, 0x5A, 0x00) -> LocalArchiveFormat.XZ
        length >= 262 && String(signature, 257, 5, Charsets.US_ASCII) == "ustar" -> LocalArchiveFormat.TAR
        else -> formatFromFileName(file.name)
            ?: throw LocalArchiveException("Unsupported local archive format")
    }
}

private fun formatFromFileName(fileName: String): LocalArchiveFormat? {
    val lower = fileName.lowercase(Locale.ROOT)
    return when {
        lower.endsWith(".zip") -> LocalArchiveFormat.ZIP
        lower.endsWith(".7z") -> LocalArchiveFormat.SEVEN_Z
        lower.endsWith(".rar") -> LocalArchiveFormat.RAR
        lower.endsWith(".tar") -> LocalArchiveFormat.TAR
        lower.endsWith(".tar.gz") || lower.endsWith(".tgz") ||
            lower.endsWith(".gz") || lower.endsWith(".gzip") -> LocalArchiveFormat.GZIP
        lower.endsWith(".tar.xz") || lower.endsWith(".txz") ||
            lower.endsWith(".xz") -> LocalArchiveFormat.XZ
        else -> null
    }
}

private fun inferredStreamName(fileName: String, format: LocalArchiveFormat): String {
    val inferred = when (format) {
        LocalArchiveFormat.GZIP -> when {
            fileName.endsWith(".tgz", ignoreCase = true) -> fileName.dropLast(4) + ".tar"
            fileName.endsWith(".gzip", ignoreCase = true) -> fileName.dropLast(5)
            fileName.endsWith(".gz", ignoreCase = true) -> fileName.dropLast(3)
            else -> fileName.substringBeforeLast('.', fileName)
        }
        LocalArchiveFormat.XZ -> when {
            fileName.endsWith(".txz", ignoreCase = true) -> fileName.dropLast(4) + ".tar"
            fileName.endsWith(".xz", ignoreCase = true) -> fileName.dropLast(3)
            else -> fileName.substringBeforeLast('.', fileName)
        }
        else -> error("Only single-stream formats have inferred names")
    }
    return inferred.ifEmpty { "data" }
}

private fun isZipSignature(bytes: ByteArray, length: Int): Boolean {
    if (!startsWith(bytes, length, 0x50, 0x4B) || length < 4) return false
    val third = bytes[2].toInt() and 0xFF
    val fourth = bytes[3].toInt() and 0xFF
    return (third == 0x03 && fourth == 0x04) ||
        (third == 0x05 && fourth == 0x06) ||
        (third == 0x07 && fourth == 0x08)
}

private fun startsWith(bytes: ByteArray, length: Int, vararg expected: Int): Boolean {
    if (length < expected.size) return false
    return expected.indices.all { (bytes[it].toInt() and 0xFF) == expected[it] }
}

private fun readUnsignedIntLittleEndian(file: RandomAccessFile): Long {
    return file.readUnsignedByte().toLong() or
        (file.readUnsignedByte().toLong() shl 8) or
        (file.readUnsignedByte().toLong() shl 16) or
        (file.readUnsignedByte().toLong() shl 24)
}

private fun ensureAvailable(file: RandomAccessFile, limit: Long, count: Long) {
    if (count < 0 || file.filePointer > limit - count) throw IOException("Invalid gzip header")
}

private fun skipZeroTerminatedField(file: RandomAccessFile, limit: Long) {
    while (file.filePointer < limit) {
        if (file.readUnsignedByte() == 0) return
    }
    throw IOException("Unterminated gzip header field")
}

private fun epochSeconds(milliseconds: Long): Long =
    if (milliseconds > 0) milliseconds / 1_000 else 0
