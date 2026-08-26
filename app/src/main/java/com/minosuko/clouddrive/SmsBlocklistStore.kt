package com.minosuko.clouddrive

import android.content.Context
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class SmsBlocklistEntry(
    val id: String,
    val displayText: String,
    val createdAtMillis: Long,
)

object SmsBlocklistStore {
    const val MAX_SENDER_LENGTH = 128

    private const val PREFERENCES = "sms_blocklist"
    private const val KEY_DATA = "data"
    private const val SCHEMA_VERSION = 1
    private const val MAX_ENTRIES = 500
    private const val MAX_SHORT_CODE_DIGITS = 6
    private val lock = Any()

    fun entries(context: Context): List<SmsBlocklistEntry> = synchronized(lock) {
        readEntriesFailOpen(context).map { entry ->
            SmsBlocklistEntry(entry.id, entry.displayText, entry.createdAtMillis)
        }
    }

    fun count(context: Context): Int = synchronized(lock) {
        readEntriesFailOpen(context).size
    }

    fun add(context: Context, sender: String): SmsBlocklistEntry {
        val displayText = sender.trim()
        val countryIso = preferredCountryIso(context, null)
        val identity = senderIdentity(displayText, countryIso)
        return synchronized(lock) {
            val current = readEntriesFailOpen(context)
            require(current.none { matches(it, identity, countryIso) }) {
                "This sender is already blocked"
            }
            require(current.size < MAX_ENTRIES) { "The blocked sender list is full" }

            val stored = StoredEntry(
                id = UUID.randomUUID().toString(),
                displayText = displayText,
                createdAtMillis = System.currentTimeMillis().coerceAtLeast(1L),
                kind = identity.kind,
                normalized = identity.normalized,
                e164 = identity.e164,
                region = countryIso.takeIf {
                    identity.kind == SenderKind.PhoneNumber &&
                        identity.e164 != null &&
                        !identity.normalized.startsWith("+")
                },
            )
            writeEntries(context, current + stored)
            SmsBlocklistEntry(stored.id, stored.displayText, stored.createdAtMillis)
        }
    }

    fun remove(context: Context, id: String): Boolean = synchronized(lock) {
        val current = readEntriesFailOpen(context)
        val updated = current.filterNot { it.id == id }
        if (updated.size == current.size) return@synchronized false
        writeEntries(context, updated)
        true
    }

    fun clear(context: Context) = synchronized(lock) {
        check(preferences(context).edit().remove(KEY_DATA).commit()) {
            "Could not save the blocked sender list"
        }
    }

    fun isBlocked(context: Context, sender: String, subscriptionId: Int? = null): Boolean = synchronized(lock) {
        try {
            val countryIso = preferredCountryIso(context, subscriptionId)
            val identity = senderIdentity(sender, countryIso)
            readEntriesStrict(context).any { matches(it, identity, countryIso) }
        } catch (_: Exception) {
            false
        }
    }

    private fun matches(stored: StoredEntry, candidate: SenderIdentity, countryIso: String?): Boolean {
        val savedIdentity = SenderIdentity(stored.kind, stored.normalized, stored.e164)
        if (sameSender(savedIdentity, candidate)) return true
        if (stored.kind != SenderKind.PhoneNumber || candidate.kind != SenderKind.PhoneNumber) return false

        // Re-resolve a locally entered number for the receiving SIM without relying on suffix matching.
        val currentIdentity = runCatching { senderIdentity(stored.displayText, countryIso) }.getOrNull()
            ?: return false
        return sameSender(currentIdentity, candidate)
    }

    private fun sameSender(first: SenderIdentity, second: SenderIdentity): Boolean {
        if (first.kind == SenderKind.SenderId || second.kind == SenderKind.SenderId) {
            return first.kind == SenderKind.SenderId &&
                second.kind == SenderKind.SenderId &&
                first.normalized == second.normalized
        }
        if (first.kind == SenderKind.ShortCode || second.kind == SenderKind.ShortCode) {
            return first.kind == SenderKind.ShortCode &&
                second.kind == SenderKind.ShortCode &&
                first.normalized == second.normalized
        }
        return first.normalized == second.normalized ||
            (first.e164 != null && (first.e164 == second.normalized || first.e164 == second.e164)) ||
            (second.e164 != null && second.e164 == first.normalized)
    }

    private fun senderIdentity(value: String, countryIso: String?): SenderIdentity {
        val displayText = value.trim()
        require(displayText.isNotEmpty()) { "Enter a phone number or sender ID" }
        require(displayText.length <= MAX_SENDER_LENGTH) { "Sender is too long" }

        val comparisonText = Normalizer.normalize(displayText, Normalizer.Form.NFKC)
        val numeric = numericParts(comparisonText)
        if (numeric != null) {
            require(numeric.digits.isNotEmpty()) { "Enter a valid sender" }
            if (numeric.digits.length <= MAX_SHORT_CODE_DIGITS) {
                return SenderIdentity(SenderKind.ShortCode, numeric.digits, null)
            }
            val normalized = when {
                numeric.hasLeadingPlus -> "+${numeric.digits}"
                numeric.digits.startsWith("00") && numeric.digits.length > 2 -> "+${numeric.digits.drop(2)}"
                else -> numeric.digits
            }
            return SenderIdentity(
                kind = SenderKind.PhoneNumber,
                normalized = normalized,
                e164 = normalizedE164(normalized, countryIso),
            )
        }

        val normalized = comparisonText.uppercase(Locale.ROOT)
        require(normalized.any(Char::isLetterOrDigit)) { "Enter a valid sender" }
        return SenderIdentity(SenderKind.SenderId, normalized, null)
    }

    private fun numericParts(value: String): NumericParts? {
        val digits = StringBuilder(value.length)
        var hasLeadingPlus = false
        value.forEach { character ->
            val digit = Character.digit(character, 10)
            when {
                digit >= 0 -> digits.append(digit)
                character == '+' && digits.isEmpty() && !hasLeadingPlus -> hasLeadingPlus = true
                character.isWhitespace() || character in PHONE_SEPARATORS -> Unit
                else -> return null
            }
        }
        return NumericParts(digits.toString(), hasLeadingPlus)
    }

    private fun normalizedE164(normalized: String, countryIso: String?): String? {
        if (normalized.startsWith("+")) return normalized.takeIf(::isE164)
        if (countryIso == null) return null
        return runCatching { PhoneNumberUtils.formatNumberToE164(normalized, countryIso) }
            .getOrNull()
            ?.takeIf(::isE164)
    }

    private fun preferredCountryIso(context: Context, subscriptionId: Int?): String? {
        val manager = runCatching {
            context.applicationContext.getSystemService(TelephonyManager::class.java)
        }.getOrNull()
        val subscriptionManager = if (manager != null && subscriptionId != null && subscriptionId >= 0) {
            runCatching { manager.createForSubscriptionId(subscriptionId) }.getOrNull() ?: manager
        } else {
            manager
        }
        val candidates = listOf(
            runCatching { subscriptionManager?.networkCountryIso }.getOrNull(),
            runCatching { subscriptionManager?.simCountryIso }.getOrNull(),
            runCatching { manager?.networkCountryIso }.getOrNull(),
            runCatching { manager?.simCountryIso }.getOrNull(),
            runCatching { context.resources.configuration.locales[0].country }.getOrNull(),
        )
        return candidates.asSequence()
            .mapNotNull { it?.trim()?.uppercase(Locale.ROOT) }
            .firstOrNull { it.length == 2 && it.all(Char::isLetter) }
    }

    private fun readEntriesFailOpen(context: Context): List<StoredEntry> =
        runCatching { readEntriesStrict(context) }.getOrDefault(emptyList())

    private fun readEntriesStrict(context: Context): List<StoredEntry> {
        val encoded = preferences(context).getString(KEY_DATA, null) ?: return emptyList()
        val root = JSONObject(encoded)
        require(root.getInt("version") == SCHEMA_VERSION)
        val array = root.getJSONArray("entries")
        require(array.length() <= MAX_ENTRIES)
        val ids = hashSetOf<String>()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val displayText = item.getString("display_text")
                val createdAtMillis = item.getLong("created_at")
                val kind = SenderKind.valueOf(item.getString("kind"))
                val normalized = item.getString("normalized")
                val e164 = item.optionalString("e164")
                val region = item.optionalString("region")

                require(id.length <= 64 && ids.add(id))
                UUID.fromString(id)
                require(displayText == displayText.trim())
                require(createdAtMillis > 0L)
                require(region == null || (region.length == 2 && region.all(Char::isLetter)))
                val reconstructed = senderIdentity(displayText, region)
                require(reconstructed.kind == kind)
                require(reconstructed.normalized == normalized)
                require(reconstructed.e164 == e164)
                add(StoredEntry(id, displayText, createdAtMillis, kind, normalized, e164, region))
            }
        }
    }

    private fun writeEntries(context: Context, entries: List<StoredEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val item = JSONObject()
                .put("id", entry.id)
                .put("display_text", entry.displayText)
                .put("created_at", entry.createdAtMillis)
                .put("kind", entry.kind.name)
                .put("normalized", entry.normalized)
            entry.e164?.let { item.put("e164", it) }
            entry.region?.let { item.put("region", it) }
            array.put(item)
        }
        val encoded = JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("entries", array)
            .toString()
        check(preferences(context).edit().putString(KEY_DATA, encoded).commit()) {
            "Could not save the blocked sender list"
        }
    }

    private fun JSONObject.optionalString(name: String): String? =
        if (has(name) && !isNull(name)) getString(name) else null

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun isE164(value: String): Boolean =
        value.startsWith("+") && value.length in 8..16 && value.drop(1).all(Char::isDigit)

    private enum class SenderKind { ShortCode, PhoneNumber, SenderId }

    private data class SenderIdentity(
        val kind: SenderKind,
        val normalized: String,
        val e164: String?,
    )

    private data class NumericParts(val digits: String, val hasLeadingPlus: Boolean)

    private data class StoredEntry(
        val id: String,
        val displayText: String,
        val createdAtMillis: Long,
        val kind: SenderKind,
        val normalized: String,
        val e164: String?,
        val region: String?,
    )

    private val PHONE_SEPARATORS = setOf('-', '(', ')', '.', '/', '\\')
}
