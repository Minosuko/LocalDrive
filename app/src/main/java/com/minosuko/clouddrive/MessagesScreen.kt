package com.minosuko.clouddrive

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SmsConversation(
    val threadId: Long,
    val address: String,
    val snippet: String,
    val date: Long,
    val unread: Int,
)

data class SmsRecord(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val type: Int,
    val read: Boolean,
    val status: Int,
) {
    val outgoing: Boolean get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
}

data class MessagesState(
    val conversations: List<SmsConversation> = emptyList(),
    val messages: List<SmsRecord> = emptyList(),
    val pendingMms: List<IncomingMms> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
)

class MessagesViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutableState = MutableStateFlow(MessagesState())
    val state: StateFlow<MessagesState> = mutableState.asStateFlow()
    private var selectedThread: Long? = null
    private var refreshVersion = 0L
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        refresh()
    }

    fun refresh() {
        val version = ++refreshVersion
        val requestedThread = selectedThread
        val canRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!canRead) {
            mutableState.value = MessagesState(pendingMms = AccountStore.incomingMms(context))
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(loading = it.conversations.isEmpty(), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val all = queryMessages(null)
                    val conversations = all.groupBy(SmsRecord::threadId).mapNotNull { (thread, messages) ->
                        val newest = messages.maxByOrNull(SmsRecord::date) ?: return@mapNotNull null
                        SmsConversation(
                            threadId = thread,
                            address = newest.address.ifBlank { "Unknown" },
                            snippet = newest.body,
                            date = newest.date,
                            unread = messages.count { it.type == Telephony.Sms.MESSAGE_TYPE_INBOX && !it.read },
                        )
                    }.sortedByDescending(SmsConversation::date)
                    val selected = requestedThread?.let { queryMessages(it).sortedBy(SmsRecord::date) }.orEmpty()
                    Triple(conversations, selected, AccountStore.incomingMms(context))
                }
            }.onSuccess { (conversations, messages, mms) ->
                if (version != refreshVersion || requestedThread != selectedThread) return@onSuccess
                mutableState.update {
                    it.copy(conversations = conversations, messages = messages, pendingMms = mms, loading = false, error = null)
                }
            }.onFailure { error ->
                if (version != refreshVersion || requestedThread != selectedThread) return@onFailure
                mutableState.update { it.copy(loading = false, error = error.message ?: "Could not load messages") }
            }
        }
    }

    fun openThread(threadId: Long) {
        selectedThread = threadId
        markThreadRead(context, threadId)
        refresh()
    }

    fun closeThread() {
        selectedThread = null
        refreshVersion++
        mutableState.update { it.copy(messages = emptyList()) }
    }

    fun send(address: String, body: String, onQueued: (QueuedSms) -> Unit = {}) {
        if (mutableState.value.sending) return
        mutableState.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { SmsTransport.send(context, address, body) } }
                .onSuccess { queued ->
                    selectedThread = queued.threadId
                    mutableState.update { it.copy(sending = false) }
                    onQueued(queued)
                    refresh()
                }
                .onFailure { error ->
                    mutableState.update { it.copy(sending = false, error = error.message ?: "Could not send message") }
                }
        }
    }

    fun markMmsRead(id: Long) {
        AccountStore.markIncomingMmsRead(context, id)
        refresh()
    }

    private fun queryMessages(threadId: Long?): List<SmsRecord> {
        val selection = threadId?.let { "${Telephony.Sms.THREAD_ID} = ?" }
        val arguments = threadId?.let { arrayOf(it.toString()) }
        val output = mutableListOf<SmsRecord>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.STATUS,
            ),
            selection,
            arguments,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                output += SmsRecord(
                    id = cursor.getLong(0),
                    threadId = cursor.getLong(1),
                    address = cursor.getString(2).orEmpty(),
                    body = cursor.getString(3).orEmpty(),
                    date = cursor.getLong(4),
                    type = cursor.getInt(5),
                    read = cursor.getInt(6) != 0,
                    status = cursor.getInt(7),
                )
            }
        }
        return output
    }

    override fun onCleared() {
        context.contentResolver.unregisterContentObserver(observer)
    }
}

@Composable
fun MessagesScreen(initialAddress: String? = null, initialBody: String? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val model: MessagesViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var permissionVersion by remember { mutableStateOf(0) }
    var selectedThread by rememberSaveable { mutableStateOf<Long?>(null) }
    var composeAddress by rememberSaveable { mutableStateOf<String?>(null) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        permissionVersion++
        model.refresh()
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionVersion++
        model.refresh()
    }
    val roleHeld = remember(permissionVersion, state.conversations.size) { hasSmsRole(context) }
    val canRead = remember(permissionVersion, state.conversations.size) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(initialAddress) {
        val address = initialAddress?.trim().orEmpty()
        if (address.isNotEmpty()) {
            selectedThread = null
            composeAddress = address
            model.closeThread()
        }
    }

    LaunchedEffect(composeAddress, state.conversations, selectedThread) {
        if (selectedThread == null) {
            val address = composeAddress?.trim().orEmpty()
            val existing = state.conversations.firstOrNull { addressesMatch(context, it.address, address) }
            if (existing != null) {
                selectedThread = existing.threadId
                model.openThread(existing.threadId)
            }
        }
    }

    when {
        !roleHeld -> MessagesPermissionState(
            "Make CloudDrive your messaging app",
            "Receive SMS and MMS alerts, reply from notifications, and keep conversations in one polished inbox.",
            "Set as SMS app",
        ) { smsRoleIntent(context)?.let(roleLauncher::launch) }
        !canRead -> MessagesPermissionState(
            "Messages access needed",
            "Allow the SMS permissions granted with the messaging role.",
            "Allow access",
        ) { permissionLauncher.launch(messagePermissions()) }
        else -> AnimatedContent(
            targetState = selectedThread != null || composeAddress != null,
            transitionSpec = {
                if (targetState) {
                    (slideInHorizontally(tween(280)) { width -> width } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(220)) { width -> -width / 4 } + fadeOut(tween(120)))
                } else {
                    (slideInHorizontally(tween(220)) { width -> -width / 4 } + fadeIn(tween(180)))
                        .togetherWith(slideOutHorizontally(tween(280)) { width -> width } + fadeOut(tween(120)))
                }
            },
            label = "conversation",
        ) { conversationOpen ->
            if (conversationOpen) {
                ConversationScreen(
                    title = state.conversations.firstOrNull { it.threadId == selectedThread }?.address ?: composeAddress.orEmpty(),
                    initialAddress = composeAddress.orEmpty(),
                    messages = state.messages,
                    error = state.error,
                    sending = state.sending,
                    initialBody = initialBody.orEmpty(),
                    onAddressChanged = { composeAddress = it },
                    onBack = {
                        selectedThread = null
                        composeAddress = null
                        model.closeThread()
                    },
                    onSend = { address, body, clearBody ->
                        model.send(address, body) { queued ->
                            selectedThread = queued.threadId
                            composeAddress = queued.recipient
                            clearBody()
                        }
                    },
                )
            } else {
                ConversationList(
                    state = state,
                    onRefresh = model::refresh,
                    onNew = { composeAddress = "" },
                    onOpen = {
                        selectedThread = it.threadId
                        model.openThread(it.threadId)
                    },
                    onMmsRead = model::markMmsRead,
                )
            }
        }
    }
}

@Composable
private fun ConversationList(
    state: MessagesState,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onOpen: (SmsConversation) -> Unit,
    onMmsRead: (Long) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, top = 14.dp, end = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Messages", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("Private conversations on this device", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh messages") }
            }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.conversations.isEmpty() && state.pendingMms.isEmpty() -> MessagesPermissionState(
                    "Your inbox is clear",
                    "New conversations will appear here.",
                    "New message",
                    onNew,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(state.pendingMms, key = { "mms:${it.id}" }) { mms ->
                        Card(
                            onClick = { onMmsRead(mms.id) },
                            colors = CardDefaults.cardColors(containerColor = if (mms.read) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondaryContainer),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Mms, null, Modifier.size(30.dp))
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Multimedia message", fontWeight = FontWeight.SemiBold)
                                    Text(mms.status, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                                Text(formatConversationTime(mms.receivedAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(state.conversations, key = SmsConversation::threadId) { conversation ->
                        Card(
                            onClick = { onOpen(conversation) },
                            colors = CardDefaults.cardColors(containerColor = if (conversation.unread > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
                            border = CardDefaults.outlinedCardBorder(),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.padding(11.dp).size(22.dp))
                                }
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(conversation.address, Modifier.weight(1f), fontWeight = if (conversation.unread > 0) FontWeight.Bold else FontWeight.SemiBold, maxLines = 1)
                                        Text(formatConversationTime(conversation.date), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(conversation.snippet, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)) {
            Icon(Icons.Outlined.AddComment, "New message")
        }
    }
}

@Composable
private fun ConversationScreen(
    title: String,
    initialAddress: String,
    messages: List<SmsRecord>,
    error: String?,
    sending: Boolean,
    initialBody: String,
    onAddressChanged: (String) -> Unit,
    onBack: () -> Unit,
    onSend: (String, String, () -> Unit) -> Unit,
) {
    var address by remember(initialAddress, title) { mutableStateOf(initialAddress.ifBlank { title }) }
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    val listState = rememberLazyListState()
    BackHandler(onBack = onBack)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Column(Modifier.weight(1f)) {
                Text(title.ifBlank { "New message" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                if (messages.isNotEmpty()) Text("SMS conversation", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        if (messages.isEmpty()) {
            OutlinedTextField(
                value = address,
                onValueChange = {
                    address = it
                    onAddressChanged(it)
                },
                label = { Text("Phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            items(messages, key = SmsRecord::id) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.outgoing) Arrangement.End else Arrangement.Start) {
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (message.outgoing) 18.dp else 5.dp,
                            bottomEnd = if (message.outgoing) 5.dp else 18.dp,
                        ),
                        color = if (message.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.widthIn(max = 292.dp),
                    ) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                            Text(message.body, color = if (message.outgoing) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                formatMessageTime(message.date),
                                color = if (message.outgoing) MaterialTheme.colorScheme.onPrimary.copy(alpha = .72f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .72f),
                                fontSize = 10.sp,
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) }
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                placeholder = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
            )
            Spacer(Modifier.size(6.dp))
            IconButton(
                onClick = {
                    if (address.isNotBlank() && body.isNotBlank()) {
                        onSend(address, body) { body = "" }
                    }
                },
                enabled = address.isNotBlank() && body.isNotBlank() && !sending,
            ) { Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
        }
    }
}

@Composable
private fun MessagesPermissionState(title: String, detail: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(30.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.padding(18.dp).size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

private fun messagePermissions(): Array<String> = buildList {
    add(Manifest.permission.READ_SMS)
    add(Manifest.permission.SEND_SMS)
    add(Manifest.permission.RECEIVE_SMS)
    add(Manifest.permission.RECEIVE_MMS)
    add(Manifest.permission.RECEIVE_WAP_PUSH)
    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun addressesMatch(context: Context, existing: String, candidate: String): Boolean {
    val normalizedExisting = PhoneNumberUtils.normalizeNumber(existing)
    val normalizedCandidate = PhoneNumberUtils.normalizeNumber(candidate)
    if (normalizedCandidate.isBlank()) return false
    if (normalizedExisting == normalizedCandidate) return true
    if (normalizedCandidate.count(Char::isDigit) < 10) return false
    @Suppress("DEPRECATION")
    return PhoneNumberUtils.compare(context, existing, candidate)
}
private val conversationTime = DateTimeFormatter.ofPattern("MMM d")
private val messageTime = DateTimeFormatter.ofPattern("h:mm a")

private fun formatConversationTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(conversationTime)

private fun formatMessageTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(messageTime)
