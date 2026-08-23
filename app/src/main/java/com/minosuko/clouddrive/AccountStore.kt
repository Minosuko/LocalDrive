package com.minosuko.clouddrive

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class CloudAccount(
    val profileId: String,
    val serverOrigin: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String,
)

private data class StoredSession(
    val account: CloudAccount,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)

data class AccountSession(
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String,
    val accessToken: String,
    val accessExpiresAt: Long,
    val refreshToken: String,
    val refreshExpiresAt: Long,
)

data class IncomingMms(
    val id: Long,
    val receivedAt: Long,
    val subscriptionId: Int?,
    val mimeType: String,
    val status: String,
    val read: Boolean,
)

private class CloudDriveDatabase(context: Context) : SQLiteOpenHelper(context, "clouddrive.db", null, 3) {
    override fun onConfigure(database: SQLiteDatabase) {
        database.setForeignKeyConstraintsEnabled(true)
        database.enableWriteAheadLogging()
    }

    override fun onCreate(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE server_sessions (
                profile_id TEXT PRIMARY KEY,
                server_origin TEXT NOT NULL,
                user_id TEXT NOT NULL,
                username TEXT NOT NULL,
                display_name TEXT NOT NULL,
                role TEXT NOT NULL,
                access_token TEXT NOT NULL,
                access_expires_at INTEGER NOT NULL,
                refresh_token TEXT NOT NULL,
                refresh_expires_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE incoming_mms (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                received_at INTEGER NOT NULL,
                subscription_id INTEGER,
                mime_type TEXT NOT NULL,
                pdu BLOB NOT NULL,
                status TEXT NOT NULL,
                sender TEXT,
                subject TEXT,
                read INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            database.execSQL("ALTER TABLE incoming_mms ADD COLUMN sender TEXT")
            database.execSQL("ALTER TABLE incoming_mms ADD COLUMN subject TEXT")
            database.execSQL("ALTER TABLE incoming_mms ADD COLUMN read INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 3) database.execSQL("ALTER TABLE server_sessions ADD COLUMN role TEXT NOT NULL DEFAULT 'user'")
    }
}

private object SessionCipher {
    private const val KEY_ALIAS = "clouddrive-session-v1"

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val output = ByteBuffer.allocate(1 + cipher.iv.size + encrypted.size)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(encrypted)
            .array()
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    fun decrypt(value: String): String {
        val input = ByteBuffer.wrap(Base64.decode(value, Base64.NO_WRAP))
        val iv = ByteArray(input.get().toInt() and 0xff).also(input::get)
        val encrypted = ByteArray(input.remaining()).also(input::get)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
        }.generateKey()
    }
}

object AccountStore {
    private val refreshLocks = ConcurrentHashMap<String, Any>()
    @Volatile private var database: CloudDriveDatabase? = null

    fun hasSession(context: Context, profileId: String): Boolean = read(context, profileId)?.account?.role == "root"

    fun account(context: Context, profileId: String): CloudAccount? = read(context, profileId)?.account?.takeIf { it.role == "root" }

    fun accounts(context: Context): List<CloudAccount> {
        val result = mutableListOf<CloudAccount>()
        db(context).readableDatabase.query(
            "server_sessions",
            arrayOf("profile_id", "server_origin", "user_id", "username", "display_name", "role"),
            null,
            null,
            null,
            null,
            "updated_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val account = CloudAccount(
                    cursor.getString(0),
                    cursor.getString(1),
                    cursor.getString(2),
                    cursor.getString(3),
                    cursor.getString(4),
                    cursor.getString(5),
                )
                if (account.role == "root") result += account
            }
        }
        return result
    }

    fun save(context: Context, profileId: String, serverOrigin: String, session: AccountSession) {
        val values = ContentValues().apply {
            put("profile_id", profileId)
            put("server_origin", serverOrigin)
            put("user_id", session.userId)
            put("username", session.username)
            put("display_name", session.displayName)
            put("role", session.role)
            put("access_token", SessionCipher.encrypt(session.accessToken))
            put("access_expires_at", session.accessExpiresAt)
            put("refresh_token", SessionCipher.encrypt(session.refreshToken))
            put("refresh_expires_at", session.refreshExpiresAt)
            put("updated_at", System.currentTimeMillis())
        }
        db(context).writableDatabase.insertWithOnConflict(
            "server_sessions",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun remove(context: Context, profileId: String) {
        db(context).writableDatabase.delete("server_sessions", "profile_id = ?", arrayOf(profileId))
    }

    fun saveIncomingMms(context: Context, data: ByteArray, mimeType: String, subscriptionId: Int?): Long {
        val values = ContentValues().apply {
            put("received_at", System.currentTimeMillis())
            if (subscriptionId == null) putNull("subscription_id") else put("subscription_id", subscriptionId)
            put("mime_type", mimeType)
            put("pdu", data)
            put("status", "Received - download pending")
            put("read", 0)
        }
        return db(context).writableDatabase.insertOrThrow("incoming_mms", null, values)
    }

    fun incomingMms(context: Context): List<IncomingMms> {
        val result = mutableListOf<IncomingMms>()
        db(context).readableDatabase.query(
            "incoming_mms",
            arrayOf("id", "received_at", "subscription_id", "mime_type", "status", "read"),
            null,
            null,
            null,
            null,
            "received_at DESC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += IncomingMms(
                    id = cursor.getLong(0),
                    receivedAt = cursor.getLong(1),
                    subscriptionId = if (cursor.isNull(2)) null else cursor.getInt(2),
                    mimeType = cursor.getString(3),
                    status = cursor.getString(4),
                    read = cursor.getInt(5) != 0,
                )
            }
        }
        return result
    }

    fun markIncomingMmsRead(context: Context, id: Long) {
        val values = ContentValues().apply { put("read", 1) }
        db(context).writableDatabase.update("incoming_mms", values, "id = ?", arrayOf(id.toString()))
    }

    fun authorization(context: Context, profileId: String): String? = accessToken(context, profileId)?.let { "Bearer $it" }

    fun accessToken(context: Context, profileId: String): String? {
        val initial = read(context, profileId) ?: return null
        val now = System.currentTimeMillis()
        if (initial.accessExpiresAt > now + 60_000) return initial.accessToken
        return synchronized(refreshLocks.computeIfAbsent(profileId) { Any() }) {
            val current = read(context, profileId) ?: return@synchronized null
            if (current.accessExpiresAt > System.currentTimeMillis() + 60_000) return@synchronized current.accessToken
            if (current.refreshExpiresAt <= System.currentTimeMillis()) {
                remove(context, profileId)
                error("CloudDrive session expired. Sign in again.")
            }
            val refreshed = MobileApiClient.refresh(current.account.serverOrigin, current.refreshToken)
            save(context, profileId, current.account.serverOrigin, refreshed)
            refreshed.accessToken
        }
    }

    private fun read(context: Context, profileId: String): StoredSession? = runCatching {
        db(context).readableDatabase.query(
            "server_sessions",
            arrayOf(
                "server_origin",
                "user_id",
                "username",
                "display_name",
                "role",
                "access_token",
                "access_expires_at",
                "refresh_token",
                "refresh_expires_at",
            ),
            "profile_id = ?",
            arrayOf(profileId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            StoredSession(
                account = CloudAccount(profileId, cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4)),
                accessToken = SessionCipher.decrypt(cursor.getString(5)),
                accessExpiresAt = cursor.getLong(6),
                refreshToken = SessionCipher.decrypt(cursor.getString(7)),
                refreshExpiresAt = cursor.getLong(8),
            )
        }
    }.getOrElse {
        remove(context, profileId)
        null
    }

    private fun db(context: Context): CloudDriveDatabase = database ?: synchronized(this) {
        database ?: CloudDriveDatabase(context.applicationContext).also { database = it }
    }
}

object MobileApiClient {
    fun connect(
        context: Context,
        address: String,
        username: String,
        password: String,
        createAccount: Boolean,
    ): Pair<String, AccountSession> {
        val origin = origin(address)
        val body = JSONObject()
            .put("username", username.trim())
            .put("password", password)
            .put("display_name", username.trim())
            .put("device_id", Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty())
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}")
        val endpoint = if (createAccount) "register" else "login"
        return origin to parseSession(post("$origin/api/mobile/v1/auth/$endpoint/", body))
    }

    fun refresh(origin: String, refreshToken: String): AccountSession = parseSession(
        post("$origin/api/mobile/v1/auth/refresh/", JSONObject().put("refresh_token", refreshToken)),
    )

    fun origin(address: String): String {
        val uri = URI(AppSettings.normalizeAddress(address))
        return "${uri.scheme}://${uri.rawAuthority}"
    }

    private fun post(url: String, body: JSONObject): JSONObject {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "CloudDrive-Android/1.0")
            setFixedLengthStreamingMode(bytes.size)
        }
        BufferedOutputStream(connection.outputStream).use { it.write(bytes) }
        val status = connection.responseCode
        val response = try {
            (if (status >= 400) connection.errorStream else connection.inputStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        } finally {
            connection.disconnect()
        }
        val json = runCatching { JSONObject(response) }.getOrElse { error("CloudDrive returned an invalid response ($status)") }
        if (status !in 200..299 || !json.optBoolean("success")) {
            error(json.optString("error", "CloudDrive account request failed ($status)"))
        }
        return json.getJSONObject("data")
    }

    private fun parseSession(data: JSONObject): AccountSession {
        val user = data.getJSONObject("user")
        val role = user.optString("role", "")
        require(role == "root") { "CloudDrive requires the root account" }
        return AccountSession(
            userId = user.getString("id"),
            username = user.getString("username"),
            displayName = user.optString("display_name", user.getString("username")),
            role = role,
            accessToken = data.getString("access_token"),
            accessExpiresAt = data.getLong("access_expires_at") * 1_000,
            refreshToken = data.getString("refresh_token"),
            refreshExpiresAt = data.getLong("refresh_expires_at") * 1_000,
        )
    }
}

fun davClient(context: Context, drive: DriveProfile): DavClient {
    require(AccountStore.hasSession(context, drive.id)) { "Sign in to ${drive.name} to continue" }
    return DavClient(drive.address, drive.id, context.applicationContext)
}
