package com.minosuko.clouddrive

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.util.concurrent.Executors

data class QueuedSms(
    val uri: Uri,
    val messageId: Long,
    val threadId: Long,
    val recipient: String,
)

class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        receiverExecutor.execute {
            try {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty()
                if (messages.isEmpty()) error("Empty SMS delivery")
                val first = messages.first()
                val address = first.displayOriginatingAddress ?: first.originatingAddress.orEmpty()
                val body = messages.joinToString("") { it.displayMessageBody ?: it.messageBody.orEmpty() }
                val subscriptionId = intent.subscriptionId()
                val values = ContentValues().apply {
                    put(Telephony.Sms.ADDRESS, address)
                    put(Telephony.Sms.BODY, body)
                    put(Telephony.Sms.DATE, System.currentTimeMillis())
                    put(Telephony.Sms.DATE_SENT, first.timestampMillis)
                    put(Telephony.Sms.PROTOCOL, first.protocolIdentifier)
                    put(Telephony.Sms.SERVICE_CENTER, first.serviceCenterAddress)
                    put(Telephony.Sms.REPLY_PATH_PRESENT, if (first.isReplyPathPresent) 1 else 0)
                    put(Telephony.Sms.READ, 0)
                    put(Telephony.Sms.SEEN, 0)
                    subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
                }
                val inserted = context.contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                    ?: error("Could not save SMS")
                val threadId = context.contentResolver.query(
                    inserted,
                    arrayOf(Telephony.Sms.THREAD_ID),
                    null,
                    null,
                    null,
                )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L } ?: 0L
                MessageNotifier.postSms(context, address, body, threadId)
                pending.resultCode = Telephony.Sms.Intents.RESULT_SMS_HANDLED
            } catch (_: Exception) {
                pending.resultCode = Telephony.Sms.Intents.RESULT_SMS_GENERIC_ERROR
            } finally {
                pending.finish()
            }
        }
    }
}

class MmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pending = goAsync()
        receiverExecutor.execute {
            try {
                val data = intent.getByteArrayExtra("data") ?: error("Empty MMS delivery")
                val subscription = intent.subscriptionId()
                val id = AccountStore.saveIncomingMms(
                    context,
                    data,
                    intent.type ?: "application/vnd.wap.mms-message",
                    subscription,
                )
                MessageNotifier.postMms(context, id)
                pending.resultCode = Telephony.Sms.Intents.RESULT_SMS_HANDLED
            } catch (_: Exception) {
                pending.resultCode = Telephony.Sms.Intents.RESULT_SMS_GENERIC_ERROR
            } finally {
                pending.finish()
            }
        }
    }
}

class RespondViaMessageService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.data?.schemeSpecificPart.orEmpty()
        val message = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        if (address.isNotBlank() && message.isNotBlank()) {
            receiverExecutor.execute {
                runCatching { SmsTransport.send(applicationContext, address, message) }
                stopSelf(startId)
            }
        } else {
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object SmsTransport {
    fun send(context: Context, address: String, body: String): QueuedSms {
        require(hasSmsRole(context)) { "Make CloudDrive the default SMS app before sending" }
        val recipient = address.trim()
        val text = body.trim()
        require(recipient.isNotEmpty()) { "Enter a recipient" }
        require(text.isNotEmpty()) { "Enter a message" }
        val threadId = Telephony.Threads.getOrCreateThreadId(context, recipient)
        val subscriptionId = SmsManager.getDefaultSmsSubscriptionId().takeIf { it >= 0 }
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, recipient)
            put(Telephony.Sms.BODY, text)
            put(Telephony.Sms.DATE, System.currentTimeMillis())
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.SEEN, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            put(Telephony.Sms.THREAD_ID, threadId)
            subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        val messageUri = context.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, values)
            ?: error("Could not queue SMS")
        val messageId = ContentUris.parseId(messageUri)
        try {
            val manager = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(SmsManager::class.java)?.let { manager ->
                    subscriptionId?.let(manager::createForSubscriptionId) ?: manager
                }
            } else {
                @Suppress("DEPRECATION")
                subscriptionId?.let(SmsManager::getSmsManagerForSubscriptionId) ?: SmsManager.getDefault()
            } ?: error("SMS is unavailable on this device")
            val parts = manager.divideMessage(text)
            if (parts.size <= 1) {
                manager.sendTextMessage(
                    recipient,
                    null,
                    text,
                    statusIntent(context, ACTION_SMS_SENT, messageId, 0, 1),
                    statusIntent(context, ACTION_SMS_DELIVERED, messageId, 0, 1),
                )
            } else {
                val sent = ArrayList<PendingIntent>(parts.size)
                val delivered = ArrayList<PendingIntent>(parts.size)
                parts.indices.forEach { part ->
                    sent += statusIntent(context, ACTION_SMS_SENT, messageId, part, parts.size)
                    delivered += statusIntent(context, ACTION_SMS_DELIVERED, messageId, part, parts.size)
                }
                manager.sendMultipartTextMessage(recipient, null, parts, sent, delivered)
            }
        } catch (error: Exception) {
            updateSmsState(context, messageId, Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_FAILED)
            throw error
        }
        return QueuedSms(messageUri, messageId, threadId, recipient)
    }

    private fun statusIntent(context: Context, action: String, messageId: Long, part: Int, count: Int): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_MESSAGE_ID, messageId)
            putExtra(EXTRA_PART_INDEX, part)
            putExtra(EXTRA_PART_COUNT, count)
        }
        val requestCode = ((messageId xor (messageId ushr 32)).toInt() * 31 + part) xor action.hashCode()
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class SmsStatusReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val messageId = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        if (messageId < 0) return
        when (intent.action) {
            ACTION_SMS_SENT -> if (resultCode == Activity.RESULT_OK) {
                if (intent.getIntExtra(EXTRA_PART_INDEX, 0) == intent.getIntExtra(EXTRA_PART_COUNT, 1) - 1) {
                    updateSmsState(context, messageId, Telephony.Sms.MESSAGE_TYPE_SENT, Telephony.Sms.STATUS_NONE)
                }
            } else {
                updateSmsState(context, messageId, Telephony.Sms.MESSAGE_TYPE_FAILED, Telephony.Sms.STATUS_FAILED)
            }
            ACTION_SMS_DELIVERED -> if (resultCode == Activity.RESULT_OK) {
                updateSmsState(context, messageId, null, Telephony.Sms.STATUS_COMPLETE)
            }
        }
    }
}

internal fun updateSmsState(context: Context, messageId: Long, type: Int?, status: Int) {
    val values = ContentValues().apply {
        if (type != null) put(Telephony.Sms.TYPE, type)
        put(Telephony.Sms.STATUS, status)
    }
    context.contentResolver.update(ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, messageId), values, null, null)
}

internal const val ACTION_SMS_SENT = "com.minosuko.clouddrive.SMS_SENT"
internal const val ACTION_SMS_DELIVERED = "com.minosuko.clouddrive.SMS_DELIVERED"
internal const val EXTRA_MESSAGE_ID = "message_id"
private const val EXTRA_PART_INDEX = "part_index"
private const val EXTRA_PART_COUNT = "part_count"
private val receiverExecutor = Executors.newSingleThreadExecutor()

private fun Intent.subscriptionId(): Int? {
    val invalid = SubscriptionManager.INVALID_SUBSCRIPTION_ID
    return getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, invalid)
        .takeIf { it >= 0 }
        ?: getIntExtra("subscription", invalid).takeIf { it >= 0 }
}
