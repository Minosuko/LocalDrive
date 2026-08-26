package com.minosuko.clouddrive

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionManager
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mms
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SimCard
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
    val subscriptionId: Int?,
)

data class SmsRecord(
    val id: Long,
    val threadId: Long,
    val address: String,
    val body: String,
    val date: Long,
    val dateSent: Long,
    val type: Int,
    val read: Boolean,
    val seen: Boolean,
    val status: Int,
    val errorCode: Int,
    val subscriptionId: Int?,
) {
    val outgoing: Boolean get() = type != Telephony.Sms.MESSAGE_TYPE_INBOX
}

data class MessagesState(
    val conversations: List<SmsConversation> = emptyList(),
    val allMessages: List<SmsRecord> = emptyList(),
    val messages: List<SmsRecord> = emptyList(),
    val pendingMms: List<IncomingMms> = emptyList(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val deleting: Boolean = false,
    val error: String? = null,
    val operationMessage: String? = null,
)

private data class MessagesSnapshot(
    val conversations: List<SmsConversation>,
    val allMessages: List<SmsRecord>,
    val selectedMessages: List<SmsRecord>,
    val pendingMms: List<IncomingMms>,
)

class MessagesViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutableState = MutableStateFlow(MessagesState())
    val state: StateFlow<MessagesState> = mutableState.asStateFlow()
    private var selectedThread: Long? = null
    private var refreshVersion = 0L
    private var observerRegistered = false
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = refresh()
    }

    init {
        observerRegistered = runCatching {
            context.contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        }.isSuccess
        refresh()
    }

    fun refresh() {
        val version = ++refreshVersion
        val requestedThread = selectedThread
        val canRead = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (!canRead) {
            viewModelScope.launch {
                val mms = withContext(Dispatchers.IO) { AccountStore.incomingMms(context) }
                if (version == refreshVersion) mutableState.value = MessagesState(pendingMms = mms)
            }
            return
        }
        viewModelScope.launch {
            mutableState.update { it.copy(loading = it.conversations.isEmpty(), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val all = queryMessages()
                    val conversations = all.groupBy(SmsRecord::threadId).mapNotNull { (thread, messages) ->
                        val newest = messages.maxByOrNull(SmsRecord::date) ?: return@mapNotNull null
                        SmsConversation(
                            threadId = thread,
                            address = newest.address.ifBlank { "Unknown" },
                            snippet = newest.body,
                            date = newest.date,
                            unread = messages.count { it.type == Telephony.Sms.MESSAGE_TYPE_INBOX && !it.read },
                            subscriptionId = newest.subscriptionId,
                        )
                    }.sortedByDescending(SmsConversation::date)
                    val selected = requestedThread?.let { thread ->
                        all.filter { it.threadId == thread }.sortedBy(SmsRecord::date)
                    }.orEmpty()
                    MessagesSnapshot(conversations, all, selected, AccountStore.incomingMms(context))
                }
            }.onSuccess { (conversations, allMessages, messages, mms) ->
                if (version != refreshVersion || requestedThread != selectedThread) return@onSuccess
                mutableState.update {
                    it.copy(
                        conversations = conversations,
                        allMessages = allMessages,
                        messages = messages,
                        pendingMms = mms,
                        loading = false,
                        error = null,
                    )
                }
            }.onFailure { error ->
                if (version != refreshVersion || requestedThread != selectedThread) return@onFailure
                mutableState.update { it.copy(loading = false, error = error.message ?: "Could not load messages") }
            }
        }
    }

    fun openThread(threadId: Long) {
        selectedThread = threadId
        val selected = mutableState.value.allMessages
            .asSequence()
            .filter { it.threadId == threadId }
            .sortedBy(SmsRecord::date)
            .map { it.copy(read = true) }
            .toList()
        mutableState.update { state ->
            state.copy(
                messages = selected,
                conversations = state.conversations.map { conversation ->
                    if (conversation.threadId == threadId) conversation.copy(unread = 0) else conversation
                },
            )
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { markThreadRead(context, threadId) } }
                .onFailure { refresh() }
        }
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
        mutableState.update { state ->
            state.copy(pendingMms = state.pendingMms.map { if (it.id == id) it.copy(read = true) else it })
        }
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { AccountStore.markIncomingMmsRead(context, id) } }
                .onFailure { refresh() }
        }
    }

    fun deleteMessages(ids: List<Long>) {
        if (ids.isEmpty() || mutableState.value.deleting) return
        mutableState.update { it.copy(deleting = true, error = null, operationMessage = null) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    require(hasSmsRole(context)) { "Make CloudDrive the default SMS app before deleting messages" }
                    ids.distinct().chunked(400).sumOf { batch ->
                        val placeholders = batch.joinToString(",") { "?" }
                        context.contentResolver.delete(
                            Telephony.Sms.CONTENT_URI,
                            "${Telephony.Sms._ID} IN ($placeholders)",
                            batch.map(Long::toString).toTypedArray(),
                        )
                    }
                }
            }.onSuccess { deleted ->
                mutableState.update {
                    it.copy(
                        deleting = false,
                        operationMessage = if (deleted == 1) "Message deleted" else "$deleted messages deleted",
                    )
                }
                refresh()
            }.onFailure { error ->
                mutableState.update { it.copy(deleting = false, error = error.message ?: "Could not delete messages") }
            }
        }
    }

    fun consumeOperationMessage() = mutableState.update { it.copy(operationMessage = null) }

    private fun queryMessages(): List<SmsRecord> {
        val output = mutableListOf<SmsRecord>()
        context.contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.THREAD_ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.DATE_SENT,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ,
                Telephony.Sms.SEEN,
                Telephony.Sms.STATUS,
                Telephony.Sms.ERROR_CODE,
                Telephony.Sms.SUBSCRIPTION_ID,
            ),
            null,
            null,
            "${Telephony.Sms.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                output += SmsRecord(
                    id = cursor.getLong(0),
                    threadId = cursor.getLong(1),
                    address = cursor.getString(2).orEmpty(),
                    body = cursor.getString(3).orEmpty(),
                    date = cursor.getLong(4),
                    dateSent = cursor.getLong(5),
                    type = cursor.getInt(6),
                    read = cursor.getInt(7) != 0,
                    seen = cursor.getInt(8) != 0,
                    status = cursor.getInt(9),
                    errorCode = cursor.getInt(10),
                    subscriptionId = if (cursor.isNull(11)) null else cursor.getInt(11).takeIf { it >= 0 },
                )
            }
        }
        return output
    }

    override fun onCleared() {
        if (observerRegistered) context.contentResolver.unregisterContentObserver(observer)
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
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionVersion++
                model.refresh()
            }
        }
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
    LaunchedEffect(state.operationMessage) {
        state.operationMessage?.let {
            snackbar.showSnackbar(it)
            model.consumeOperationMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
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
                    deleting = state.deleting,
                    initialBody = initialBody.orEmpty(),
                    onAddressChanged = { composeAddress = it },
                    onBack = {
                        selectedThread = null
                        composeAddress = null
                        model.closeThread()
                    },
                    onDelete = model::deleteMessages,
                    onComposeNumber = { number ->
                        selectedThread = null
                        composeAddress = number
                        model.closeThread()
                    },
                    onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
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
                    query = searchQuery,
                    onQueryChanged = { searchQuery = it },
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
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ConversationList(
    state: MessagesState,
    query: String,
    onQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onNew: () -> Unit,
    onOpen: (SmsConversation) -> Unit,
    onMmsRead: (Long) -> Unit,
) {
    val conversations = remember(state.conversations, state.allMessages, query) {
        filteredConversations(state.conversations, state.allMessages, query)
    }
    val pendingMms = remember(state.pendingMms, query) { filteredMms(state.pendingMms, query) }
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
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChanged,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("Search messages") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = if (query.isNotEmpty()) {
                    { IconButton(onClick = { onQueryChanged("") }) { Icon(Icons.Outlined.Close, "Clear search") } }
                } else null,
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                state.conversations.isEmpty() && state.pendingMms.isEmpty() -> MessagesPermissionState(
                    "Your inbox is clear",
                    "New conversations will appear here.",
                    "New message",
                    onNew,
                )
                conversations.isEmpty() && pendingMms.isEmpty() -> MessagesPermissionState(
                    "No matching messages",
                    "Try a phone number or words from the conversation.",
                    "Clear search",
                ) { onQueryChanged("") }
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 10.dp, end = 10.dp, bottom = 92.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    items(pendingMms, key = { "mms:${it.id}" }) { mms ->
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
                                Column(horizontalAlignment = Alignment.End) {
                                    SimBadge(mms.subscriptionId, MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatConversationTime(mms.receivedAt), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    items(conversations, key = SmsConversation::threadId) { conversation ->
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
                                        SimBadge(conversation.subscriptionId, MaterialTheme.colorScheme.onSurfaceVariant)
                                        Spacer(Modifier.width(5.dp))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationScreen(
    title: String,
    initialAddress: String,
    messages: List<SmsRecord>,
    error: String?,
    sending: Boolean,
    deleting: Boolean,
    initialBody: String,
    onAddressChanged: (String) -> Unit,
    onBack: () -> Unit,
    onDelete: (List<Long>) -> Unit,
    onComposeNumber: (String) -> Unit,
    onMessage: (String) -> Unit,
    onSend: (String, String, () -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var address by remember(initialAddress, title) { mutableStateOf(initialAddress.ifBlank { title }) }
    var body by remember(initialBody) { mutableStateOf(initialBody) }
    var selectedIds by rememberSaveable(title) { mutableStateOf(emptyList<Long>()) }
    var deleteConfirm by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(false) }
    var phoneActions by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val selectedMessages = remember(messages, selectedIds) { messages.filter { it.id in selectedIds.toSet() } }
    val selectionActive = selectedMessages.isNotEmpty()
    BackHandler { if (selectionActive) selectedIds = emptyList() else onBack() }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex)
    }
    LaunchedEffect(messages) {
        val available = messages.mapTo(hashSetOf(), SmsRecord::id)
        selectedIds = selectedIds.filter { it in available }
    }
    val toggleSelection: (SmsRecord) -> Unit = { message ->
        selectedIds = if (message.id in selectedIds) selectedIds - message.id else selectedIds + message.id
    }
    Column(Modifier.fillMaxSize()) {
        if (selectionActive) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selectedIds = emptyList() }, enabled = !deleting) { Icon(Icons.Outlined.Close, "Clear selection") }
                    Text("${selectedMessages.size} selected", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(
                        onClick = {
                            val copied = selectedMessages.sortedBy(SmsRecord::date).joinToString("\n\n", transform = SmsRecord::body)
                            context.getSystemService(ClipboardManager::class.java)
                                ?.setPrimaryClip(ClipData.newPlainText("SMS messages", copied))
                            selectedIds = emptyList()
                            onMessage(if (selectedMessages.size == 1) "Message copied" else "${selectedMessages.size} messages copied")
                        },
                        enabled = !deleting,
                    ) { Icon(Icons.Outlined.ContentCopy, "Copy selected messages") }
                    IconButton(onClick = { infoVisible = true }, enabled = !deleting) { Icon(Icons.Outlined.Info, "Message info") }
                    IconButton(onClick = { deleteConfirm = true }, enabled = !deleting) { Icon(Icons.Outlined.Delete, "Delete selected messages") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(title.ifBlank { "New message" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (messages.isNotEmpty()) Text("SMS conversation", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                }
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
                    val selected = message.id in selectedIds
                    val bubbleColor = when {
                        selected -> MaterialTheme.colorScheme.secondaryContainer
                        message.outgoing -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val bodyColor = when {
                        selected -> MaterialTheme.colorScheme.onSecondaryContainer
                        message.outgoing -> MaterialTheme.colorScheme.onPrimary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Surface(
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (message.outgoing) 18.dp else 5.dp,
                            bottomEnd = if (message.outgoing) 5.dp else 18.dp,
                        ),
                        color = bubbleColor,
                        modifier = Modifier
                            .widthIn(max = 292.dp)
                            .combinedClickable(
                                onClick = { if (selectionActive) toggleSelection(message) },
                                onLongClick = { toggleSelection(message) },
                            ),
                    ) {
                        Column(Modifier.padding(horizontal = 13.dp, vertical = 9.dp)) {
                            MessageBodyText(message.body, bodyColor, linksEnabled = !selectionActive) { phoneActions = it }
                            val detailColor = bodyColor.copy(alpha = .72f)
                            Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                                SimBadge(message.subscriptionId, detailColor)
                                Spacer(Modifier.width(5.dp))
                                Text(formatMessageTime(message.date), color = detailColor, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)) }
        if (!selectionActive) {
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
    if (deleteConfirm) {
        AlertDialog(
            onDismissRequest = { deleteConfirm = false },
            title = { Text(if (selectedMessages.size == 1) "Delete message?" else "Delete ${selectedMessages.size} messages?") },
            text = { Text("Selected messages will be permanently deleted from this device.") },
            confirmButton = {
                Button(onClick = {
                    val ids = selectedMessages.map(SmsRecord::id)
                    deleteConfirm = false
                    selectedIds = emptyList()
                    onDelete(ids)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteConfirm = false }) { Text("Cancel") } },
        )
    }
    if (infoVisible) {
        SmsInfoDialog(selectedMessages, onDismiss = { infoVisible = false })
    }
    phoneActions?.let { number ->
        PhoneActionsDialog(number, onDismiss = { phoneActions = null }, onSendSms = onComposeNumber)
    }
}

@Composable
private fun SmsInfoDialog(messages: List<SmsRecord>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (messages.size == 1) "Message info" else "${messages.size} messages") },
        text = {
            if (messages.size == 1) {
                val message = messages.first()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmsInfoRow("Address", message.address.ifBlank { "Unknown" })
                    SmsInfoRow("Direction", smsTypeLabel(message.type))
                    SmsInfoRow("Stored", formatMessageInfoTime(message.date))
                    if (message.dateSent > 0) SmsInfoRow("Sent", formatMessageInfoTime(message.dateSent))
                    SmsInfoRow("Read / seen", "${if (message.read) "Read" else "Unread"} / ${if (message.seen) "Seen" else "Unseen"}")
                    SmsInfoRow("Status", smsStatusLabel(message.status, message.errorCode))
                    SmsInfoRow("SIM", simLabel(message.subscriptionId).ifBlank { "Default or unknown" })
                    SmsInfoRow("Message ID", message.id.toString())
                    SmsInfoRow("Thread ID", message.threadId.toString())
                }
            } else {
                val incoming = messages.count { !it.outgoing }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmsInfoRow("Selected", messages.size.toString())
                    SmsInfoRow("Direction", "$incoming incoming / ${messages.size - incoming} outgoing")
                    messages.minOfOrNull(SmsRecord::date)?.let { SmsInfoRow("Earliest", formatMessageInfoTime(it)) }
                    messages.maxOfOrNull(SmsRecord::date)?.let { SmsInfoRow("Latest", formatMessageInfoTime(it)) }
                    SmsInfoRow("Characters", messages.sumOf { it.body.length }.toString())
                    val sims = messages.mapNotNull(SmsRecord::subscriptionId).distinct().map(::simLabel).filter(String::isNotBlank)
                    SmsInfoRow("SIMs", sims.joinToString().ifBlank { "Default or unknown" })
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun SmsInfoRow(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
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

private fun filteredConversations(
    conversations: List<SmsConversation>,
    messages: List<SmsRecord>,
    query: String,
): List<SmsConversation> {
    val term = query.trim()
    if (term.isEmpty()) return conversations
    val normalized = PhoneNumberUtils.normalizeNumber(term).takeIf { term.any(Char::isDigit) }.orEmpty()
    val matchingThreads = messages.asSequence()
        .filter { it.body.contains(term, ignoreCase = true) || addressMatchesSearch(it.address, term, normalized) }
        .map(SmsRecord::threadId)
        .toHashSet()
    return conversations.filter {
        it.threadId in matchingThreads || it.snippet.contains(term, ignoreCase = true) ||
            addressMatchesSearch(it.address, term, normalized)
    }
}

private fun filteredMms(messages: List<IncomingMms>, query: String): List<IncomingMms> {
    val term = query.trim()
    if (term.isEmpty()) return messages
    return messages.filter { message ->
        "Multimedia message".contains(term, ignoreCase = true) ||
            message.status.contains(term, ignoreCase = true) ||
            simLabel(message.subscriptionId).contains(term, ignoreCase = true)
    }
}

private fun addressMatchesSearch(address: String, term: String, normalizedTerm: String): Boolean =
    address.contains(term, ignoreCase = true) || (normalizedTerm.isNotEmpty() && PhoneNumberUtils.normalizeNumber(address).contains(normalizedTerm))

@Composable
private fun SimBadge(subscriptionId: Int?, color: androidx.compose.ui.graphics.Color) {
    val label = remember(subscriptionId) { simLabel(subscriptionId) }
    if (label.isEmpty()) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.SimCard, null, Modifier.size(12.dp), tint = color)
        Spacer(Modifier.width(2.dp))
        Text(label, color = color, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun simLabel(subscriptionId: Int?): String {
    if (subscriptionId == null || subscriptionId < 0) return ""
    val slot = runCatching { SubscriptionManager.getSlotIndex(subscriptionId) }.getOrDefault(-1)
    return if (slot >= 0) "SIM ${slot + 1}" else ""
}

private val conversationTime = DateTimeFormatter.ofPattern("MMM d")
private val messageTime = DateTimeFormatter.ofPattern("h:mm a")
private val messageInfoTime = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm:ss a")

private fun formatConversationTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(conversationTime)

private fun formatMessageTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(messageTime)

private fun formatMessageInfoTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault()).format(messageInfoTime)

private fun smsTypeLabel(type: Int): String = when (type) {
    Telephony.Sms.MESSAGE_TYPE_INBOX -> "Incoming"
    Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent"
    Telephony.Sms.MESSAGE_TYPE_DRAFT -> "Draft"
    Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "Outbox"
    Telephony.Sms.MESSAGE_TYPE_FAILED -> "Failed"
    Telephony.Sms.MESSAGE_TYPE_QUEUED -> "Queued"
    else -> "Type $type"
}

private fun smsStatusLabel(status: Int, errorCode: Int): String {
    val label = when (status) {
        Telephony.Sms.STATUS_NONE -> "None"
        Telephony.Sms.STATUS_COMPLETE -> "Delivered"
        Telephony.Sms.STATUS_PENDING -> "Pending"
        Telephony.Sms.STATUS_FAILED -> "Failed"
        else -> "Status $status"
    }
    return if (errorCode > 0) "$label (error $errorCode)" else label
}
