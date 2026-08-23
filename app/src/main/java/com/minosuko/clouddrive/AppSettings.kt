package com.minosuko.clouddrive

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

object AppSettings {
    private const val PREFS = "cloud_drive_sync"
    private const val KEY_ADDRESS = "drive_address"
    private const val KEY_DRIVES = "drives"
    private const val KEY_AUTO_SYNC = "auto_sync"
    private const val KEY_SYNC_MODE = "sync_mode"
    private const val KEY_SYNC_AMOUNT = "sync_amount"
    private const val KEY_SYNC_UNIT = "sync_unit"
    private const val KEY_SYNC_HOUR = "sync_hour"
    private const val KEY_SYNC_MINUTE = "sync_minute"
    private const val KEY_SYNC_DRIVE = "sync_drive"
    private const val KEY_LAST_SYNC_STATUS = "last_sync_status"
    private const val KEY_SYNC_CATEGORIES = "sync_categories"
    private const val KEY_SYNC_DIRECTION = "sync_direction"
    private const val KEY_RESTORE_DEVICE = "restore_device"
    private const val KEY_SYNC_GENERATION = "sync_generation"
    private const val KEY_THEME = "theme"
    private const val KEY_DEVICE_TREE = "device_tree"
    private const val KEY_PHOTO_LAYOUT = "photo_layout"
    const val PERIODIC_WORK = "cloud_drive_media_sync_periodic"
    const val MANUAL_WORK = "cloud_drive_media_sync_manual"

    fun address(context: Context): String = drives(context).firstOrNull()?.address
        ?: "http://192.168.1.100:8080/CloudDrive"

    fun drives(context: Context): List<DriveProfile> {
        val stored = preferences(context).getString(KEY_DRIVES, null)
        if (stored != null) {
            return runCatching {
                val array = JSONArray(stored)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(DriveProfile(item.getString("id"), item.getString("name"), item.getString("address")))
                    }
                }
            }.getOrDefault(emptyList())
        }
        val legacy = preferences(context).getString(KEY_ADDRESS, null) ?: return emptyList()
        val migrated = listOf(DriveProfile(UUID.randomUUID().toString(), "CloudDrive 1", normalizeAddress(legacy)))
        saveDrives(context, migrated)
        return migrated
    }

    fun addDrive(context: Context, name: String, address: String): DriveProfile {
        val current = drives(context).toMutableList()
        val profile = DriveProfile(
            UUID.randomUUID().toString(),
            name.trim().ifEmpty { "CloudDrive ${current.size + 1}" },
            normalizeAddress(address),
        )
        current += profile
        saveDrives(context, current)
        return profile
    }

    fun removeDrive(context: Context, id: String) {
        saveDrives(context, drives(context).filterNot { it.id == id })
        if (syncDriveId(context) == id) preferences(context).edit().remove(KEY_SYNC_DRIVE).apply()
    }

    fun syncDriveId(context: Context): String? = preferences(context).getString(KEY_SYNC_DRIVE, null)

    fun saveSyncDriveId(context: Context, id: String?) {
        preferences(context).edit().apply {
            if (id == null) remove(KEY_SYNC_DRIVE) else putString(KEY_SYNC_DRIVE, id)
        }.apply()
    }

    fun lastSyncStatus(
        context: Context,
        direction: SyncDirection = syncDirection(context),
    ): String? = preferences(context).getString("${KEY_LAST_SYNC_STATUS}_${direction.name}", null)

    fun saveLastSyncStatus(
        context: Context,
        status: String,
        direction: SyncDirection = syncDirection(context),
    ) {
        preferences(context).edit().putString("${KEY_LAST_SYNC_STATUS}_${direction.name}", status).apply()
    }

    fun syncCategories(context: Context): Set<SyncCategory> {
        val stored = preferences(context).getStringSet(KEY_SYNC_CATEGORIES, null)
            ?: return setOf(SyncCategory.Photos, SyncCategory.Videos)
        return stored.mapNotNullTo(linkedSetOf()) { name -> SyncCategory.entries.firstOrNull { it.name == name } }
    }

    fun saveSyncCategories(context: Context, categories: Set<SyncCategory>) {
        preferences(context).edit().putStringSet(KEY_SYNC_CATEGORIES, categories.mapTo(linkedSetOf()) { it.name }).apply()
    }

    fun syncDirection(context: Context): SyncDirection = runCatching {
        SyncDirection.valueOf(preferences(context).getString(KEY_SYNC_DIRECTION, SyncDirection.DeviceToCloud.name)!!)
    }.getOrDefault(SyncDirection.DeviceToCloud)

    fun saveSyncDirection(context: Context, direction: SyncDirection) {
        preferences(context).edit().putString(KEY_SYNC_DIRECTION, direction.name).apply()
        if (direction == SyncDirection.CloudToDevice) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        } else {
            schedule(context, replace = true)
        }
    }

    fun restoreDevice(context: Context): String = preferences(context).getString(KEY_RESTORE_DEVICE, "").orEmpty()

    fun saveRestoreDevice(context: Context, device: String) {
        preferences(context).edit().putString(KEY_RESTORE_DEVICE, device.trim()).apply()
    }

    fun autoSync(context: Context): Boolean = preferences(context)
        .getBoolean(KEY_AUTO_SYNC, true)

    fun theme(context: Context): ThemeMode = runCatching {
        ThemeMode.valueOf(preferences(context).getString(KEY_THEME, ThemeMode.Light.name)!!)
    }.getOrDefault(ThemeMode.Light)

    fun saveTheme(context: Context, theme: ThemeMode) {
        preferences(context).edit().putString(KEY_THEME, theme.name).apply()
    }

    fun photoLayout(context: Context): PhotoLayoutMode = runCatching {
        PhotoLayoutMode.valueOf(preferences(context).getString(KEY_PHOTO_LAYOUT, PhotoLayoutMode.Grid.name)!!)
    }.getOrDefault(PhotoLayoutMode.Grid)

    fun savePhotoLayout(context: Context, layout: PhotoLayoutMode) {
        preferences(context).edit().putString(KEY_PHOTO_LAYOUT, layout.name).apply()
    }

    fun deviceTree(context: Context): String? = preferences(context).getString(KEY_DEVICE_TREE, null)

    fun saveDeviceTree(context: Context, uri: String) {
        preferences(context).edit().putString(KEY_DEVICE_TREE, uri).apply()
    }

    fun save(context: Context, address: String, autoSync: Boolean) {
        val current = drives(context).toMutableList()
        val normalized = normalizeAddress(address)
        if (current.isEmpty()) current += DriveProfile(UUID.randomUUID().toString(), "CloudDrive 1", normalized)
        else current[0] = current[0].copy(address = normalized)
        saveDrives(context, current)
        preferences(context).edit().putBoolean(KEY_AUTO_SYNC, autoSync).apply()
        schedule(context, autoSync)
    }

    fun saveAutoSync(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
        schedule(context, enabled, replace = true)
    }

    fun syncSchedule(context: Context): SyncSchedule {
        val preferences = preferences(context)
        return SyncSchedule(
            mode = runCatching { SyncScheduleMode.valueOf(preferences.getString(KEY_SYNC_MODE, SyncScheduleMode.Daily.name)!!) }
                .getOrDefault(SyncScheduleMode.Daily),
            amount = preferences.getInt(KEY_SYNC_AMOUNT, 24).coerceAtLeast(1),
            unit = runCatching { SyncIntervalUnit.valueOf(preferences.getString(KEY_SYNC_UNIT, SyncIntervalUnit.Hours.name)!!) }
                .getOrDefault(SyncIntervalUnit.Hours),
            hour = preferences.getInt(KEY_SYNC_HOUR, 0).coerceIn(0, 23),
            minute = preferences.getInt(KEY_SYNC_MINUTE, 0).coerceIn(0, 59),
        )
    }

    fun saveSyncSchedule(context: Context, schedule: SyncSchedule) {
        preferences(context).edit()
            .putString(KEY_SYNC_MODE, schedule.mode.name)
            .putInt(KEY_SYNC_AMOUNT, schedule.amount.coerceAtLeast(1))
            .putString(KEY_SYNC_UNIT, schedule.unit.name)
            .putInt(KEY_SYNC_HOUR, schedule.hour.coerceIn(0, 23))
            .putInt(KEY_SYNC_MINUTE, schedule.minute.coerceIn(0, 59))
            .apply()
        schedule(context, replace = true)
    }

    fun normalizeAddress(value: String): String {
        var address = value.trim()
        require(address.isNotEmpty()) { "Drive address is required" }
        if (!address.startsWith("http://", true) && !address.startsWith("https://", true)) {
            address = "http://$address"
        }
        val uri = Uri.parse(address)
        require(uri.scheme == "http" || uri.scheme == "https") { "Use an HTTP or HTTPS address" }
        require(!uri.host.isNullOrBlank()) { "Drive address has no host" }
        return address.trimEnd('/')
    }

    fun schedule(context: Context, enabled: Boolean = autoSync(context), replace: Boolean = false) {
        val manager = WorkManager.getInstance(context)
        if (!enabled || syncDirection(context) == SyncDirection.CloudToDevice) {
            manager.cancelUniqueWork(PERIODIC_WORK)
            return
        }
        val generation = if (replace) UUID.randomUUID().toString() else
            preferences(context).getString(KEY_SYNC_GENERATION, null) ?: UUID.randomUUID().toString()
        preferences(context).edit().putString(KEY_SYNC_GENERATION, generation).apply()
        val request = automaticRequest(context, generation)
        manager.enqueueUniqueWork(PERIODIC_WORK, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    fun scheduleNext(context: Context, generation: String) {
        if (!autoSync(context) || preferences(context).getString(KEY_SYNC_GENERATION, null) != generation) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            PERIODIC_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            automaticRequest(context, generation),
        )
    }

    private fun automaticRequest(context: Context, generation: String): androidx.work.OneTimeWorkRequest {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        return OneTimeWorkRequestBuilder<MediaSyncWorker>()
            .setConstraints(constraints)
            .setInputData(Data.Builder().putBoolean(MediaSyncWorker.KEY_AUTOMATIC, true).putString(MediaSyncWorker.KEY_GENERATION, generation).build())
            .setInitialDelay(nextDelay(syncSchedule(context)), TimeUnit.MILLISECONDS)
            .build()
    }

    private fun nextDelay(schedule: SyncSchedule): Long = when (schedule.mode) {
        SyncScheduleMode.Interval -> schedule.unit.toMillis(schedule.amount).coerceAtLeast(1_000L)
        SyncScheduleMode.Daily -> {
            val now = ZonedDateTime.now()
            var next = now.withHour(schedule.hour).withMinute(schedule.minute).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun saveDrives(context: Context, drives: List<DriveProfile>) {
        val array = JSONArray()
        drives.forEach { drive ->
            array.put(JSONObject().put("id", drive.id).put("name", drive.name).put("address", drive.address))
        }
        preferences(context).edit().putString(KEY_DRIVES, array.toString()).apply()
    }
}

enum class ThemeMode { Light, Dark }
enum class PhotoLayoutMode(val label: String) {
    LargeGrid("Large grid"),
    Grid("Grid"),
    SmallGrid("Small grid"),
    List("List"),
}
data class DriveProfile(val id: String, val name: String, val address: String)
enum class SyncScheduleMode { Interval, Daily }
enum class SyncDirection(val label: String) {
    DeviceToCloud("Device to CloudDrive"),
    CloudToDevice("CloudDrive to device"),
}
enum class SyncCategory(val label: String, val folder: String, val cloudFolder: String = folder) {
    Photos("Photos", "photos", "media"),
    Videos("Videos", "videos", "media"),
    Downloads("Downloads", "downloads"),
    Contacts("Contacts", "contacts"),
    SmsMessages("SMS messages", "sms-messages"),
    CallHistory("Call history", "call-history"),
}
enum class SyncIntervalUnit(val label: String, private val milliseconds: Long) {
    Seconds("seconds", 1_000L),
    Minutes("minutes", 60_000L),
    Hours("hours", 3_600_000L),
    Days("days", 86_400_000L);

    fun toMillis(amount: Int): Long = milliseconds * amount.coerceAtLeast(1).toLong()
}
data class SyncSchedule(
    val mode: SyncScheduleMode = SyncScheduleMode.Daily,
    val amount: Int = 24,
    val unit: SyncIntervalUnit = SyncIntervalUnit.Hours,
    val hour: Int = 0,
    val minute: Int = 0,
)
