package com.miloai.ui.chat

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.draw.scale
import androidx.compose.ui.window.Dialog
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.miloai.R
import com.miloai.core.catalog.ModelCatalog
import com.miloai.core.model.ChatRole
import com.miloai.core.mode.AppMode
import com.miloai.ui.agents.AgentPickerDialog
import com.miloai.ui.rememberContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(conversationId: Long, onBack: () -> Unit) {
    val container = rememberContainer()
    val vm: ChatViewModel = viewModel(
        key = "chat_$conversationId",
        factory = ChatViewModelFactory(conversationId, container)
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showMaxConfirm by remember { mutableStateOf(false) }
    var showClearContext by remember { mutableStateOf(false) }
    var showCompressConfirm by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var searchMode by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var matchIndex by remember { mutableStateOf(0) }
    var viewImageB64 by remember { mutableStateOf<String?>(null) }
    var editingMessage by remember { mutableStateOf<com.miloai.ui.chat.UiMessage?>(null) }
    var editText by remember { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var speechActive by remember { mutableStateOf(false) }
    val speechHelper = remember { com.miloai.tools.SpeechInputHelper(context) }
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            speechActive = true
            speechHelper.start(
                onPartial = { vm.onInputChange(it) },
                onFinal = { text ->
                    speechActive = false
                    vm.onInputChange(text)
                },
                onError = { message ->
                    speechActive = false
                    scope.launch { snackbarHostState.showSnackbar(message) }
                }
            )
        } else {
            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.speech_perm_required)) }
        }
    }
    DisposableEffect(Unit) {
        onDispose { speechHelper.destroy() }
    }
    val toggleSpeech: () -> Unit = {
        if (speechActive) {
            speechHelper.stop()
            speechActive = false
        } else {
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                speechActive = true
                speechHelper.start(
                    onPartial = { vm.onInputChange(it) },
                    onFinal = { text ->
                        speechActive = false
                        vm.onInputChange(text)
                    },
                    onError = { message ->
                        speechActive = false
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    }
                )
            } else {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    val imagePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(4)
    ) { uris ->
        if (uris.isNotEmpty()) vm.addPendingImages(uris, context.contentResolver)
    }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.addPendingFile(uri, context.contentResolver)
    }

    val totalPromptTokens = state.messages.lastOrNull { it.usageInput > 0 }?.usageInput ?: 0
    val contextWindow = ModelCatalog.entryFor(state.provider, state.model).contextWindow
    val usagePercent = if (contextWindow > 0 && totalPromptTokens > 0) {
        ((totalPromptTokens * 100) / contextWindow).toInt().coerceIn(0, 100)
    } else 0
    val usageText = if (totalPromptTokens > 0) {
        " · ${formatTokens(totalPromptTokens)} ($usagePercent%)"
    } else ""

    var wasAtBottom by remember { mutableStateOf(true) }
    var forceFollow by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) {
                wasAtBottom = false
                forceFollow = false
            }
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling) {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull { it.index == info.totalItemsCount - 1 }
                    val pinned = last != null && last.offset >= 0 &&
                        last.offset + last.size <= info.viewportEndOffset + 1
                    if (pinned) wasAtBottom = true
                }
            }
    }

    var initialScrollDone by remember { mutableStateOf(false) }
    val streaming = state.messages.lastOrNull()?.streaming == true

    LaunchedEffect(initialScrollDone, forceFollow, wasAtBottom, state.messages.size) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        if (initialScrollDone && !forceFollow && !wasAtBottom) return@LaunchedEffect
        runCatching { listState.scrollToItem(state.messages.size - 1, Int.MAX_VALUE) }
        initialScrollDone = true
    }

    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                val total = info.totalItemsCount
                if (total == 0) return@collect
                if (!forceFollow && !wasAtBottom) return@collect
                if (listState.isScrollInProgress) return@collect
                val last = info.visibleItemsInfo.lastOrNull { it.index == total - 1 }
                if (last == null || last.offset + last.size > info.viewportEndOffset + 2) {
                    runCatching { listState.scrollToItem(total - 1, Int.MAX_VALUE) }
                }
            }
    }

    LaunchedEffect(state.sendTick) {
        if (state.sendTick > 0 && state.messages.isNotEmpty()) {
            forceFollow = true
            runCatching { listState.animateScrollToItem(state.messages.size - 1) }
        }
    }

    LaunchedEffect(streaming) {
        if (streaming) {
            forceFollow = true
        } else if (forceFollow) {
            delay(400)
            forceFollow = false
        }
    }

    LaunchedEffect(state.notification) {
        state.notification?.let {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(it)
            vm.dismissNotification()
        }
    }

    val searchableMessages = state.messages.filter { it.role != ChatRole.TOOL }
    val searchMatches = remember(searchQuery, searchableMessages) {
        if (searchQuery.isBlank()) emptyList()
        else searchableMessages.indices.filter { i ->
            searchableMessages[i].content.contains(searchQuery, ignoreCase = true)
        }
    }
    LaunchedEffect(searchMode) {
        if (searchMode) wasAtBottom = false
    }
    LaunchedEffect(matchIndex, searchMatches) {
        if (searchMatches.isNotEmpty()) {
            val target = searchMatches[matchIndex.coerceIn(0, searchMatches.size - 1)]
            runCatching { listState.animateScrollToItem(target) }
        }
    }

    Scaffold(
        topBar = {
            if (searchMode) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = {
                                searchQuery = it
                                matchIndex = 0
                            },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(com.miloai.R.string.chat_search_msg)) },
                            singleLine = true
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            searchMode = false
                            searchQuery = ""
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(com.miloai.R.string.cancel))
                        }
                    },
                    actions = {
                        Text(
                            if (searchMatches.isEmpty()) "0/0"
                            else "${matchIndex.coerceIn(0, searchMatches.size - 1) + 1}/${searchMatches.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        IconButton(
                            onClick = {
                                if (searchMatches.isNotEmpty()) {
                                    matchIndex = (matchIndex - 1 + searchMatches.size) % searchMatches.size
                                }
                            },
                            enabled = searchMatches.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(com.miloai.R.string.chat_search_prev))
                        }
                        IconButton(
                            onClick = {
                                if (searchMatches.isNotEmpty()) {
                                    matchIndex = (matchIndex + 1) % searchMatches.size
                                }
                            },
                            enabled = searchMatches.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(com.miloai.R.string.chat_search_next))
                        }
                    }
                )
            } else {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        val subtitle = buildString {
                            if (state.agentName.isNotBlank()) append(state.agentName)
                            if (usageText.isNotBlank()) append(" · ").append(usageText.trim())
                            if (state.isRunning && state.toolCallCount > 0) {
                                append(" · ")
                                append(stringResource(com.miloai.R.string.chat_tool_calls, state.toolCallCount))
                            }
                        }
                        if (subtitle.isNotBlank()) {
                            Text(
                                subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(com.miloai.R.string.settings_back))
                    }
                },
                actions = {
                    IconButton(onClick = { searchMode = true }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(com.miloai.R.string.chat_search_msg)
                        )
                    }
                    ModeSelector(
                        current = state.mode,
                        onSelect = { target ->
                            if (target == AppMode.MAX && state.mode != AppMode.MAX) {
                                showMaxConfirm = true
                            } else {
                                vm.updateMode(target)
                            }
                        }
                    )
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(com.miloai.R.string.chat_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(com.miloai.R.string.chat_select_agent) + "(${state.agentName.ifBlank { state.model }})") },
                                onClick = {
                                    menuOpen = false
                                    showAgentPicker = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(com.miloai.R.string.chat_save_skill)) },
                                onClick = {
                                    menuOpen = false
                                    vm.saveAsSkill()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(com.miloai.R.string.chat_distill_memory)) },
                                onClick = {
                                    menuOpen = false
                                    vm.distillMemory()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(com.miloai.R.string.chat_import_recent)) },
                                onClick = {
                                    menuOpen = false
                                    vm.importRecentConversation()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(com.miloai.R.string.chat_compress)) },
                                onClick = {
                                    menuOpen = false
                                    showCompressConfirm = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(com.miloai.R.string.chat_export)) },
                                onClick = {
                                    menuOpen = false
                                    shareConversationInternal(context, vm.buildExportText())
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(com.miloai.R.string.chat_clear)) },
                                onClick = {
                                    menuOpen = false
                                    showClearContext = true
                                }
                            )
                        }
                    }
                }
            )
            }
        },
        bottomBar = {
            InputBar(
                input = state.input,
                isRunning = state.isRunning,
                mode = state.mode,
                pendingAttachments = state.pendingAttachments,
                attachmentError = state.attachmentError,
                processing = state.processing,
                speechActive = speechActive,
                onToggleSpeech = toggleSpeech,
                onInputChange = vm::onInputChange,
                onPickImages = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onPickFile = { filePicker.launch("*/*") },
                onRemoveAttachment = vm::removePendingAttachment,
                onSend = vm::send,
                onStop = vm::stop
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (state.messages.isEmpty() && state.error == null) {
                WelcomePanel(
                    mode = state.mode,
                    onPickExample = { vm.onInputChange(it) },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                val displayMessages = state.messages.filter { it.role != ChatRole.TOOL }
                items(displayMessages.size, key = { displayMessages[it].id }) { idx ->
                    val msg = displayMessages[idx]
                    MessageItem(
                        msg = msg,
                        modifier = Modifier.animateItem(),
                        onDelete = vm::deleteMessage,
                        onSpeak = { container.speechPlayer.speak(it) },
                        onToggleStar = vm::toggleStarred,
                        onEdit = { id ->
                            val target = state.messages.firstOrNull { it.id == id }
                            if (target != null) {
                                editingMessage = target
                                editText = target.content
                            }
                        },
                        onOpenLink = { link ->
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        onCopied = {
                            val msg = context.getString(com.miloai.R.string.chat_copied)
                            scope.launch { snackbarHostState.showSnackbar(msg) }
                        },
                        onRetry = { vm.retryLast() },
                        onViewImage = { viewImageB64 = it }
                    )
                }
                state.error?.let { error ->
                    item {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(com.miloai.R.string.chat_error_prefix) + error,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                TextButton(onClick = { vm.retryLast() }) {
                                    Text(stringResource(com.miloai.R.string.chat_retry), color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                                TextButton(onClick = { vm.dismissError() }) {
                                    Text(stringResource(com.miloai.R.string.chat_ignore), color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            }
            }
            AnimatedVisibility(
                visible = !wasAtBottom && initialScrollDone && state.messages.isNotEmpty(),
                enter = androidx.compose.animation.fadeIn() +
                    androidx.compose.animation.scaleIn(initialScale = 0.6f),
                exit = androidx.compose.animation.fadeOut() +
                    androidx.compose.animation.scaleOut(targetScale = 0.6f)
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                listState.animateScrollToItem(
                                    listState.layoutInfo.totalItemsCount - 1,
                                    Int.MAX_VALUE
                                )
                            }
                            wasAtBottom = true
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    androidx.compose.material3.BadgedBox(
                        badge = {
                            if (streaming) {
                                androidx.compose.material3.Badge(
                                    containerColor = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    ) {
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(com.miloai.R.string.chat_back_to_bottom))
                    }
                }
            }
        }
    }

    viewImageB64?.let { b64 ->
        var bitmap by remember(b64) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(b64) {
            bitmap = withContext(kotlinx.coroutines.Dispatchers.Default) {
                runCatching {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()
            }
        }
        if (bitmap != null) {
            Dialog(onDismissRequest = { viewImageB64 = null }) {
                var scale by remember(b64) { mutableStateOf(1f) }
                var offset by remember(b64) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                val transformState = rememberTransformableState { zoomChange, panChange, _ ->
                    scale = (scale * zoomChange).coerceIn(1f, 5f)
                    offset = if (scale <= 1.01f) androidx.compose.ui.geometry.Offset.Zero
                    else offset + panChange
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .transformable(transformState)
                        .pointerInput(b64) {
                            detectTapGestures(
                                onTap = { viewImageB64 = null },
                                onDoubleTap = {
                                    if (scale > 1.01f) {
                                        scale = 1f
                                        offset = androidx.compose.ui.geometry.Offset.Zero
                                    } else {
                                        scale = 2.5f
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = (bitmap ?: return@Dialog).asImageBitmap(),
                        contentDescription = stringResource(R.string.chat_view_image),
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y
                            )
                    )
                }
            }
        } else {
            LaunchedEffect(Unit) { viewImageB64 = null }
        }
    }

    if (showAgentPicker) {
        AgentPickerDialog(
            currentAgentId = state.agentId,
            onSelect = vm::updateAgent,
            onDismiss = { showAgentPicker = false }
        )
    }

    if (showCompressConfirm) {
        var compressLevel by remember { mutableStateOf(2) }
        AlertDialog(
            onDismissRequest = { showCompressConfirm = false },
            title = { Text(stringResource(com.miloai.R.string.chat_compress_title)) },
            text = {
                Column {
                    Text(
                        stringResource(com.miloai.R.string.chat_compress_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    listOf(1 to stringResource(com.miloai.R.string.chat_compress_light), 2 to stringResource(com.miloai.R.string.chat_compress_medium), 3 to stringResource(com.miloai.R.string.chat_compress_deep)).forEach { (lv, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            RadioButton(selected = compressLevel == lv, onClick = { compressLevel = lv })
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showCompressConfirm = false
                    vm.compressContext(compressLevel)
                    showCompressConfirm = false
                }) { Text(stringResource(R.string.chat_compress_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showCompressConfirm = false }) { Text(stringResource(com.miloai.R.string.cancel)) }
            }
        )
    }

    editingMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { editingMessage = null },
            title = { Text(stringResource(com.miloai.R.string.chat_edit_msg)) },
            text = {
                OutlinedTextField(
                    value = editText,
                    onValueChange = { editText = it },
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.editAndResend(msg.id, editText)
                    editingMessage = null
                }) { Text(stringResource(R.string.chat_resend)) }
            },
            dismissButton = {
                TextButton(onClick = { editingMessage = null }) { Text(stringResource(com.miloai.R.string.cancel)) }
            }
        )
    }

    if (showClearContext) {
        AlertDialog(
            onDismissRequest = { showClearContext = false },
            title = { Text(stringResource(com.miloai.R.string.chat_clear_confirm_title)) },
            text = {
                Text(
                    stringResource(R.string.chat_clear_confirm_body),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearContext()
                    showClearContext = false
                }) { Text(stringResource(R.string.chat_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearContext = false }) { Text(stringResource(com.miloai.R.string.cancel)) }
            }
        )
    }

    if (showMaxConfirm) {
        AlertDialog(
            onDismissRequest = { showMaxConfirm = false },
            title = { Text(stringResource(com.miloai.R.string.chat_max_confirm_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.chat_max_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.chat_max_body2),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.updateMode(AppMode.MAX)
                    showMaxConfirm = false
                }) { Text(stringResource(R.string.chat_switch_max)) }
            },
            dismissButton = {
                TextButton(onClick = { showMaxConfirm = false }) { Text(stringResource(com.miloai.R.string.cancel)) }
            }
        )
    }

    state.confirmRequest?.let { req ->
        AlertDialog(
            onDismissRequest = { vm.respondConfirm(false) },
            title = { Text(stringResource(R.string.chat_confirm_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.chat_confirm_tool, req.call.name), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.chat_confirm_params), style = MaterialTheme.typography.bodySmall)
                    Text(
                        prettyJson(req.call.arguments),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.chat_confirm_mode, req.mode.displayName),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.size(8.dp))
                    TextButton(
                        onClick = { vm.stop() },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text(stringResource(R.string.chat_confirm_stop_task), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.respondConfirm(true) }) { Text(stringResource(R.string.chat_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { vm.respondConfirm(false) }) { Text(stringResource(R.string.chat_deny)) }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WelcomePanel(
    mode: AppMode,
    onPickExample: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val examples = when (mode) {
        AppMode.CHAT -> listOf(
            "Halo, perkenalkan dirimu",
            "Tulis penjelasan teknik Pomodoro dalam markdown",
            "Ceritakan lelucon receh"
        )
        AppMode.PLAN -> listOf(
            "Bandingkan dua pendekatan belajar bahasa dan beri sarannya",
            "Susun rencana olahraga minggu ini"
        )
        AppMode.BUILD -> listOf(
            "Hitung (12 + 5) * 3",
            "Konversi 5 mil ke kilometer",
            "Cek waktu sekarang"
        )
        AppMode.MAX -> listOf(
            "Cari berita terpopuler hari ini dan rangkum",
            "Cek cuaca Jakarta sekarang dan rangkum",
            "Berapa kurs dolar AS ke rupiah hari ini?"
        )
    }
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "AI",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Text("Milo AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.welcome_current_mode, mode.displayName),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.size(4.dp))
        Text(
            mode.description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 320.dp)
        )
        Spacer(Modifier.size(24.dp))
        Text(
            stringResource(R.string.welcome_examples),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            examples.forEach { example ->
                AssistChip(
                    onClick = { onPickExample(example) },
                    label = { Text(example, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            stringResource(R.string.welcome_hint_longpress),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(Modifier.size(24.dp))
        Text(
            stringResource(R.string.welcome_prompt_templates),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            PromptTemplates.forEach { template ->
                AssistChip(
                    onClick = { onPickExample(template.prompt) },
                    label = { Text(template.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                )
            }
        }
    }
}

private data class PromptTemplate(val label: String, val prompt: String)

private val PromptTemplates = listOf(
    PromptTemplate("Terjemahkan", "Terjemahkan teks berikut ke bahasa Inggris (pertahankan makna dan nada):\n\n"),
    PromptTemplate("Rangkum", "Rangkum poin-poin penting teks berikut dalam 3 kalimat:\n\n"),
    PromptTemplate("Poles", "Poles tulisan berikut agar lebih lancar dan profesional, pertahankan maknanya:\n\n"),
    PromptTemplate("Menulis", "Tolong tuliskan artikel tentang topik berikut (sekitar 300 kata):\n\n"),
    PromptTemplate("Jelaskan kode", "Tolong jelaskan fungsi dan logika utama kode berikut:\n\n"),
    PromptTemplate("Curah gagasan", "Berikan 10 ide kreatif untuk topik berikut:\n\n")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputBar(
    input: String,
    isRunning: Boolean,
    mode: AppMode,
    pendingAttachments: List<PendingAttachment>,
    attachmentError: String?,
    processing: Boolean,
    speechActive: Boolean,
    onToggleSpeech: () -> Unit,
    onInputChange: (String) -> Unit,
    onPickImages: () -> Unit,
    onPickFile: () -> Unit,
    onRemoveAttachment: (Long) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    val context = LocalContext.current
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var attachMenu by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (pendingAttachments.isNotEmpty()) {
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                pendingAttachments.forEach { att ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (att.kind == "image") {
                                var bitmap by remember(att.uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
                                LaunchedEffect(att.uri) {
                                    bitmap = withContext(kotlinx.coroutines.Dispatchers.Default) {
                                        runCatching {
                                            context.contentResolver.openInputStream(att.uri)?.use {
                                                android.graphics.BitmapFactory.decodeStream(it)
                                            }?.let {
                                                val scale = 120f / it.width
                                                android.graphics.Bitmap.createScaledBitmap(
                                                    it, 120, (it.height * scale).toInt().coerceAtLeast(1), true
                                                )
                                            }
                                        }.getOrNull()
                                    }
                                }
                                val bmp = bitmap
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = att.name,
                                        modifier = Modifier.size(44.dp).clip(RoundedCornerShape(8.dp))
                                    )
                                }
                            }
                            Text(
                                att.name,
                                modifier = Modifier.padding(horizontal = 8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(onClick = { onRemoveAttachment(att.id) }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chat_remove_att), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
        attachmentError?.let {
            Text(
                it,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (processing) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.input_parsing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (speechActive) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.input_listening),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.navigationBars.union(WindowInsets.ime).only(WindowInsetsSides.Bottom)
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Box {
                IconButton(onClick = { attachMenu = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Tambah lampiran")
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.input_attach_image)) },
                        onClick = {
                            attachMenu = false
                            onPickImages()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.input_attach_file)) },
                        onClick = {
                            attachMenu = false
                            onPickFile()
                        }
                    )
                }
            }
            IconButton(onClick = onToggleSpeech) {
                Icon(
                    MicIcon,
                    contentDescription = if (speechActive) stringResource(R.string.chat_stop) else stringResource(R.string.chat_voice_input),
                    tint = if (speechActive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f).animateContentSize()
                    .focusRequester(inputFocus),
                placeholder = {
                    Text(stringResource(com.miloai.R.string.input_hint))
                },
                maxLines = 6,
                minLines = 1,
                shape = RoundedCornerShape(22.dp),
                trailingIcon = {
                    if (input.isNotEmpty() && !isRunning) {
                        IconButton(onClick = { onInputChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.input_clear))
                        }
                    }
                }
            )
            Spacer(Modifier.width(8.dp))
            val haptic = LocalHapticFeedback.current
            val interactionSource = remember { MutableInteractionSource() }
            val pressed by interactionSource.collectIsPressedAsState()
            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (isRunning) {
                        onStop()
                    } else {
                        onSend()
                        runCatching { inputFocus.requestFocus() }
                    }
                },
                enabled = !processing && (isRunning || input.isNotBlank() || pendingAttachments.isNotEmpty()),
                interactionSource = interactionSource,
                modifier = Modifier.scale(if (pressed) 0.88f else 1f)
            ) {
                if (isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Filled.Send, contentDescription = stringResource(com.miloai.R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(current: AppMode, onSelect: (AppMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 6.dp),
            modifier = Modifier.padding(0.dp)
        ) {
            Text(current.displayName, maxLines = 1)
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AppMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(mode.displayName, fontWeight = if (mode == current) FontWeight.Bold else FontWeight.Normal)
                            Text(mode.description, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        onSelect(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}


private val prettyJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

private val MicIcon: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        name = "Mic",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        path(
            fill = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color.Black)
        ) {
            moveTo(12f, 14f)
            curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
            verticalLineTo(5f)
            curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
            curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
            verticalLineTo(11f)
            curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
            close()
            moveTo(17f, 11f)
            curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
            curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f)
            horizontalLineTo(5f)
            curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.92f)
            verticalLineTo(21f)
            horizontalLineTo(13f)
            verticalLineTo(17.92f)
            curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f)
            horizontalLineTo(17f)
            close()
        }
    }.build()
}

private fun shareConversationInternal(context: android.content.Context, text: String) {
    if (text.isBlank()) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Ekspor percakapan"))
}

private fun formatTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1000.0)
    else -> n.toString()
}

private fun prettyJson(input: String): String = runCatching {
    prettyJson.encodeToString(
        kotlinx.serialization.json.JsonElement.serializer(),
        prettyJson.parseToJsonElement(input)
    )
}.getOrDefault(input)
