package com.minosuko.clouddrive

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private sealed interface MessageTextTarget {
    val start: Int
    val end: Int

    data class Url(override val start: Int, override val end: Int, val uri: String) : MessageTextTarget
    data class Phone(override val start: Int, override val end: Int, val text: String) : MessageTextTarget
}

private data class SimCallRoute(val label: String, val handle: PhoneAccountHandle?)

@Composable
fun MessageBodyText(
    body: String,
    color: Color,
    linksEnabled: Boolean,
    onPhone: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val targets = remember(body) { parseMessageTargets(body) }
    if (!linksEnabled || targets.isEmpty()) {
        Text(body, color = color)
        return
    }
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = if (color == MaterialTheme.colorScheme.onPrimary) Color.White else MaterialTheme.colorScheme.primary),
        pressedStyle = SpanStyle(background = color.copy(alpha = .16f)),
    )
    val annotated = buildAnnotatedString {
        var position = 0
        targets.forEach { target ->
            if (target.start > position) append(body.substring(position, target.start))
            when (target) {
                is MessageTextTarget.Phone -> withLink(
                    LinkAnnotation.Clickable(
                        tag = "phone:${target.start}",
                        styles = linkStyle,
                        linkInteractionListener = { onPhone(target.text) },
                    ),
                ) { append(body.substring(target.start, target.end)) }
                is MessageTextTarget.Url -> withLink(
                    LinkAnnotation.Clickable(
                        tag = "url:${target.start}",
                        styles = linkStyle,
                        linkInteractionListener = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))) }
                        },
                    ),
                ) { append(body.substring(target.start, target.end)) }
            }
            position = target.end
        }
        if (position < body.length) append(body.substring(position))
    }
    Text(annotated, color = color)
}

@Composable
fun PhoneActionsDialog(numberText: String, onDismiss: () -> Unit, onSendSms: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionVersion by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionVersion++
    }
    val normalized = remember(numberText) { PhoneNumberUtils.normalizeNumber(numberText).ifBlank { numberText.trim() } }
    val hasPhoneAccess = remember(permissionVersion) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }
    val routes = remember(permissionVersion, hasPhoneAccess) { if (hasPhoneAccess) simCallRoutes(context) else emptyList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(numberText.trim()) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                PhoneAction("Copy") {
                    context.getSystemService(ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("Phone number", numberText.trim()))
                    onDismiss()
                }
                if (routes.isEmpty()) {
                    PhoneAction("Call") { launchDialer(context, normalized, null); onDismiss() }
                } else {
                    routes.forEach { route ->
                        PhoneAction(route.label) { launchDialer(context, normalized, route.handle); onDismiss() }
                    }
                }
                if (!hasPhoneAccess) {
                    PhoneAction("Show SIM 1 and SIM 2") {
                        permissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                    }
                }
                PhoneAction("Send SMS") { onDismiss(); onSendSms(normalized) }
                PhoneAction("Add to contact") { launchContactEditor(context, normalized); onDismiss() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun PhoneAction(label: String, action: () -> Unit) {
    TextButton(onClick = action, modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, modifier = Modifier.fillMaxWidth())
    }
}

private fun parseMessageTargets(body: String): List<MessageTextTarget> {
    val targets = mutableListOf<MessageTextTarget>()
    MESSAGE_URL.findAll(body).forEach { match ->
        var end = match.range.last + 1
        while (end > match.range.first && body[end - 1] in URL_TRAILING_PUNCTUATION) end--
        if (end <= match.range.first) return@forEach
        val raw = body.substring(match.range.first, end)
        val uri = if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) raw else "https://$raw"
        targets += MessageTextTarget.Url(match.range.first, end, uri)
    }
    MESSAGE_PHONE.findAll(body).forEach { match ->
        var start = match.range.first
        var end = match.range.last + 1
        while (start < end && body[start].isWhitespace()) start++
        while (end > start && body[end - 1].isWhitespace()) end--
        val text = body.substring(start, end)
        if (text.count(Char::isDigit) < 4 || targets.any { start < it.end && end > it.start }) return@forEach
        targets += MessageTextTarget.Phone(start, end, text)
    }
    return targets.sortedBy(MessageTextTarget::start)
}

private fun simCallRoutes(context: Context): List<SimCallRoute> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
        return emptyList()
    }
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return emptyList()
    val handles = runCatching { telecom.callCapablePhoneAccounts }.getOrDefault(emptyList())
    val subscriptions = runCatching {
        context.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
    }.getOrDefault(emptyList()).sortedBy { it.simSlotIndex }
    if (subscriptions.isEmpty()) return handles.mapIndexed { index, handle -> SimCallRoute("Call with SIM ${index + 1}", handle) }
    return subscriptions.mapIndexed { index, subscription ->
        val handle = handles.firstOrNull { it.id == subscription.subscriptionId.toString() } ?: handles.getOrNull(index)
        SimCallRoute("Call with SIM ${subscription.simSlotIndex.takeIf { it >= 0 }?.plus(1) ?: index + 1}", handle)
    }
}

private fun launchDialer(context: Context, number: String, handle: PhoneAccountHandle?) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).apply {
        if (handle != null) putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, handle)
    }
    runCatching { context.startActivity(intent) }
}

private fun launchContactEditor(context: Context, number: String) {
    val intent = Intent(Intent.ACTION_INSERT_OR_EDIT).apply {
        type = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        putExtra(ContactsContract.Intents.Insert.PHONE, number)
    }
    runCatching { context.startActivity(intent) }
}

private val MESSAGE_URL = Regex(
    "(?i)(?<![@\\p{L}\\p{N}_])(?:https?://|www\\.|[a-z0-9](?:[a-z0-9-]{0,62}\\.)+[a-z]{2,})(?:[^\\s<>]*)",
)
private val MESSAGE_PHONE = Regex("(?<![\\p{L}\\p{N}])\\+?[\\p{Nd}][\\p{Nd}() .-]{2,}[\\p{Nd}](?![\\p{L}\\p{N}])")
private const val URL_TRAILING_PUNCTUATION = ".,!?;:'\")]}"
