package com.minosuko.clouddrive

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.net.Uri
import android.app.role.RoleManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

private const val WRITE_SMS_PERMISSION = "android.permission.WRITE_SMS"

fun mediaPermissions(): List<String> = if (Build.VERSION.SDK_INT >= 33) {
    buildList {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
} else {
    listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

fun hasMediaPermission(context: Context): Boolean = mediaPermissions()
    .filterNot { it == Manifest.permission.POST_NOTIFICATIONS }
    .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

fun photoPermissions(): Array<String> = buildList {
    add(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)
    if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
}.toTypedArray()

fun visualMediaPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= 33) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.READ_MEDIA_VIDEO)
        if (Build.VERSION.SDK_INT >= 34) add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}.toTypedArray()

fun hasPhotoPermission(context: Context): Boolean {
    val full = ContextCompat.checkSelfPermission(
        context,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED
    val selected = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED
    return full || selected
}

fun hasVideoPermission(context: Context): Boolean {
    val full = ContextCompat.checkSelfPermission(
        context,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED
    val selected = Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED
    return full || selected
}

fun syncDeviceFolder(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
    return sanitizeCloudSegment("${Build.MANUFACTURER} ${Build.MODEL}-$androidId")
}

fun sanitizeCloudSegment(value: String): String {
    val cleaned = value.replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1f]"), "_").trim().trim('.')
    val named = cleaned.ifEmpty { "Unnamed" }
    return if (named.startsWith(".clouddrive-stage-", ignoreCase = true)) "_$named" else named
}

fun syncCategoryPermissions(category: SyncCategory, direction: SyncDirection = SyncDirection.DeviceToCloud): List<String> = when {
    direction == SyncDirection.CloudToDevice && category == SyncCategory.Contacts -> listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    direction == SyncDirection.CloudToDevice && category == SyncCategory.SmsMessages -> listOf(Manifest.permission.READ_SMS, WRITE_SMS_PERMISSION)
    direction == SyncDirection.CloudToDevice && category == SyncCategory.CallHistory -> listOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG)
    direction == SyncDirection.CloudToDevice && category in setOf(SyncCategory.Photos, SyncCategory.Videos, SyncCategory.Downloads) -> emptyList()
    else -> when (category) {
    SyncCategory.Photos -> listOf(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) + (if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()) +
        (if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACCESS_MEDIA_LOCATION) else emptyList())
    SyncCategory.Videos -> listOf(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) + if (Build.VERSION.SDK_INT >= 34) listOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) else emptyList()
    SyncCategory.Contacts -> listOf(Manifest.permission.READ_CONTACTS)
    SyncCategory.SmsMessages -> listOf(Manifest.permission.READ_SMS)
    SyncCategory.CallHistory -> listOf(Manifest.permission.READ_CALL_LOG)
    SyncCategory.Downloads -> emptyList()
    }
}

fun hasSyncCategoryPermission(context: Context, category: SyncCategory, direction: SyncDirection = SyncDirection.DeviceToCloud): Boolean = when {
    direction == SyncDirection.CloudToDevice && category in setOf(SyncCategory.Photos, SyncCategory.Videos, SyncCategory.Downloads) -> hasDeviceFileAccess(context)
    direction == SyncDirection.CloudToDevice && category == SyncCategory.SmsMessages -> syncCategoryPermissions(category, direction).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    } && hasSmsRole(context)
    direction == SyncDirection.CloudToDevice -> syncCategoryPermissions(category, direction).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    else -> when (category) {
    SyncCategory.Downloads -> hasDeviceFileAccess(context)
    SyncCategory.Photos -> {
        val canRead = ContextCompat.checkSelfPermission(
            context,
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        ) == PackageManager.PERMISSION_GRANTED)
        val canReadOriginal = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        canRead && canReadOriginal
    }
    SyncCategory.Videos -> ContextCompat.checkSelfPermission(
        context,
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    ) == PackageManager.PERMISSION_GRANTED)
    else -> syncCategoryPermissions(category).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    }
}

fun selectedSyncPermissions(categories: Set<SyncCategory>, direction: SyncDirection = SyncDirection.DeviceToCloud): Array<String> = categories
    .flatMap { syncCategoryPermissions(it, direction) }
    .plus(if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
    .distinct()
    .toTypedArray()

fun hasSmsRole(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 29) {
    context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
} else {
    Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
}

fun smsRoleIntent(context: Context): Intent? = if (Build.VERSION.SDK_INT >= 29) {
    context.getSystemService(RoleManager::class.java)
        ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_SMS) }
        ?.createRequestRoleIntent(RoleManager.ROLE_SMS)
} else {
    Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
}

fun isWifiConnected(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java)
    val network = manager.activeNetwork ?: return false
    return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
}

fun hasDeviceFileAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) {
    Environment.isExternalStorageManager()
} else {
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}

fun deviceFileAccessIntent(context: Context): Intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
    data = Uri.parse("package:${context.packageName}")
}

fun enqueueMediaSync(context: Context) {
    val input = Data.Builder()
        .putString(MediaSyncWorker.KEY_DIRECTION, AppSettings.syncDirection(context).name)
        .putString(MediaSyncWorker.KEY_CATEGORIES, AppSettings.syncCategories(context).joinToString(",") { it.name })
        .putString(MediaSyncWorker.KEY_DRIVE_ID, AppSettings.syncDriveId(context).orEmpty())
        .putString(MediaSyncWorker.KEY_RESTORE_DEVICE, AppSettings.restoreDevice(context))
        .build()
    val request = OneTimeWorkRequestBuilder<MediaSyncWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
        .setInputData(input)
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        AppSettings.MANUAL_WORK,
        ExistingWorkPolicy.REPLACE,
        request,
    )
}
