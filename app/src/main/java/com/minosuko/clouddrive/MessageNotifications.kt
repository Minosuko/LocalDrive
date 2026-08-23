package com.minosuko.clouddrive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

object MessageNotifier {
    private const val CHANNEL_ID = "messages"
    private const val GROUP = "clouddrive_messages"
    private const val REPLY_KEY = "message_reply"

    fun postSms(context: Context, address: String, body: String, threadId: Long) {
        ensureChannel(context)
        if (!canNotify(context)) return
        val person = Person.Builder().setName(address.ifBlank { "Unknown sender" }).build()
        val open = PendingIntent.getActivity(
            context,
            notificationId(threadId),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(address)}"), context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val markReadIntent = Intent(context, MessageActionReceiver::class.java).apply {
            action = ACTION_MARK_READ
            putExtra(EXTRA_THREAD_ID, threadId)
        }
        val markRead = PendingIntent.getBroadcast(
            context,
            notificationId(threadId) xor 0x45a1,
            markReadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val replyIntent = Intent(context, MessageActionReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra(EXTRA_THREAD_ID, threadId)
            putExtra(EXTRA_ADDRESS, address)
        }
        val reply = PendingIntent.getBroadcast(
            context,
            notificationId(threadId) xor 0x2f19,
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val remoteInput = RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.minosuko.clouddrive.R.drawable.ic_cloud_drive)
            .setColor(Color.rgb(43, 103, 232))
            .setContentTitle(person.name)
            .setContentText(body)
            .setStyle(NotificationCompat.MessagingStyle(Person.Builder().setName("You").build()).addMessage(body, System.currentTimeMillis(), person))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(NotificationCompat.Action.Builder(0, "Mark read", markRead).build())
            .addAction(NotificationCompat.Action.Builder(0, "Reply", reply).addRemoteInput(remoteInput).setAllowGeneratedReplies(true).build())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(threadId), notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked between the check and this call.
        }
    }

    fun postMms(context: Context, id: Long) {
        ensureChannel(context)
        if (!canNotify(context)) return
        val open = PendingIntent.getActivity(
            context,
            notificationId(id) xor 0x0ff5,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_MESSAGES, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.minosuko.clouddrive.R.drawable.ic_cloud_drive)
            .setContentTitle("Multimedia message received")
            .setContentText("Open Messages to review the pending MMS download.")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId(id) xor 0x0ff5, notification)
        } catch (_: SecurityException) {
            // Notification permission can be revoked between the check and this call.
        }
    }

    fun cancel(context: Context, threadId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(threadId))
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Messages", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Incoming SMS and MMS notifications"
                    enableVibration(true)
                },
            )
        }
    }

    private fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun notificationId(id: Long): Int = (id xor (id ushr 32)).toInt() and 0x7fffffff

    internal fun replyText(intent: Intent): String = RemoteInput.getResultsFromIntent(intent)
        ?.getCharSequence(REPLY_KEY)?.toString().orEmpty()
}

class MessageActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(EXTRA_THREAD_ID, -1)
        when (intent.action) {
            ACTION_MARK_READ -> if (threadId >= 0) markThreadRead(context, threadId)
            ACTION_REPLY -> {
                val address = intent.getStringExtra(EXTRA_ADDRESS).orEmpty()
                val reply = MessageNotifier.replyText(intent)
                if (address.isNotBlank() && reply.isNotBlank()) runCatching { SmsTransport.send(context, address, reply) }
                if (threadId >= 0) markThreadRead(context, threadId)
            }
        }
    }
}

internal fun markThreadRead(context: Context, threadId: Long) {
    val values = ContentValues().apply {
        put(Telephony.Sms.READ, 1)
        put(Telephony.Sms.SEEN, 1)
    }
    context.contentResolver.update(
        Telephony.Sms.CONTENT_URI,
        values,
        "${Telephony.Sms.THREAD_ID} = ?",
        arrayOf(threadId.toString()),
    )
    MessageNotifier.cancel(context, threadId)
}

internal const val EXTRA_OPEN_MESSAGES = "open_messages"
internal const val EXTRA_THREAD_ID = "thread_id"
private const val EXTRA_ADDRESS = "address"
private const val ACTION_MARK_READ = "com.minosuko.clouddrive.MESSAGE_MARK_READ"
private const val ACTION_REPLY = "com.minosuko.clouddrive.MESSAGE_REPLY"
