package com.miloai.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.border
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.miloai.AppContainer
import com.miloai.core.catalog.ModelCatalog
import com.miloai.core.mode.AppMode
import com.miloai.core.model.ProviderId
import com.miloai.core.storage.SettingsRepository
import com.miloai.R
import com.miloai.core.storage.ThemeMode
import com.miloai.ui.agents.AgentOnboardingDialog
import com.miloai.ui.rememberContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch

private enum class SettingsSection(@androidx.annotation.StringRes val titleRes: Int) {
    PROVIDER(R.string.settings_agents),
    CONVERSATION(R.string.settings_conversation),
    SKILLS(R.string.settings_skills),
    PERMISSIONS(R.string.settings_permissions),
    STATS(R.string.settings_stats),
    MEMORIES(R.string.settings_memories),
    DEVELOPER(R.string.settings_developer)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = rememberContainer()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var section by remember { mutableStateOf<SettingsSection?>(null) }

    BackHandler(enabled = section != null) {
        section = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section?.let { stringResource(it.titleRes) } ?: stringResource(R.string.settings_settings)) },
                navigationIcon = {
                    IconButton(onClick = { if (section == null) onBack() else section = null }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        AnimatedContent(
            targetState = section,
            transitionSpec = {
                (fadeIn(tween(160)) + slideInHorizontally { it / 8 })
                    .togetherWith(fadeOut(tween(120)) + slideOutHorizontally { -it / 8 })
            },
            label = "settings-section"
        ) { target ->
        when (target) {
            null -> SettingsMenu(
                modifier = Modifier.fillMaxSize().padding(padding),
                onOpenSection = { section = it }
            )
            SettingsSection.PROVIDER -> AgentSection(
                modifier = Modifier.fillMaxSize().padding(padding),
                container = container,
                scope = scope,
                snackbar = snackbar
            )
            SettingsSection.CONVERSATION -> ConversationSection(
                modifier = Modifier.fillMaxSize().padding(padding),
                container = container,
                settings = container.settings,
                scope = scope,
                snackbar = snackbar
            )
            SettingsSection.SKILLS -> SkillsSection(
                modifier = Modifier.fillMaxSize().padding(padding),
                container = container,
                scope = scope,
                snackbar = snackbar
            )
            SettingsSection.PERMISSIONS -> PermissionsSection(
                modifier = Modifier.fillMaxSize().padding(padding),
                container = container,
                scope = scope,
                snackbar = snackbar
            )
            SettingsSection.STATS -> StatsSection(
                modifier = Modifier.fillMaxSize().padding(padding),
                container = container
            )
            SettingsSection.MEMORIES -> MemoriesSection(
                modifier = Modifier.fillMaxSize().padding(padding),
                container = container,
                scope = scope,
                snackbar = snackbar
            )
            SettingsSection.DEVELOPER -> DeveloperSection(
                modifier = Modifier.fillMaxSize().padding(padding),
                scope = scope,
                snackbar = snackbar
            )
        }
        }
    }
}

@Composable
private fun SettingsMenu(
    modifier: Modifier,
    onOpenSection: (SettingsSection) -> Unit
) {
    LazyColumn(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            SettingsMenuItem(
                title = stringResource(com.miloai.R.string.settings_agents),
                subtitle = stringResource(com.miloai.R.string.settings_agents_sub),
                icon = { Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onOpenSection(SettingsSection.PROVIDER) }
            )
        }
        item {
            SettingsMenuItem(
                title = stringResource(com.miloai.R.string.settings_conversation),
                subtitle = stringResource(com.miloai.R.string.settings_conversation_sub),
                icon = { Icon(Icons.Filled.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onOpenSection(SettingsSection.CONVERSATION) }
            )
        }
        item {
            SettingsMenuItem(
                title = stringResource(com.miloai.R.string.settings_skills),
                subtitle = stringResource(com.miloai.R.string.settings_skills_sub),
                icon = { Icon(Icons.Filled.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onOpenSection(SettingsSection.SKILLS) }
            )
        }
        item {
            SettingsMenuItem(
                title = stringResource(com.miloai.R.string.settings_permissions),
                subtitle = stringResource(com.miloai.R.string.settings_permissions_sub),
                icon = { Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onOpenSection(SettingsSection.PERMISSIONS) }
            )
        }
        item {
            SettingsMenuItem(
                title = stringResource(com.miloai.R.string.settings_stats),
                subtitle = stringResource(com.miloai.R.string.settings_stats_sub),
                icon = { Icon(Icons.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onOpenSection(SettingsSection.STATS) }
            )
        }
        item {
            SettingsMenuItem(
                title = stringResource(com.miloai.R.string.settings_memories),
                subtitle = stringResource(com.miloai.R.string.settings_memories_sub),
                icon = { Icon(Icons.Filled.Face, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onOpenSection(SettingsSection.MEMORIES) }
            )
        }
        item {
            SettingsMenuItem(
                title = stringResource(com.miloai.R.string.settings_developer),
                subtitle = stringResource(com.miloai.R.string.settings_developer_sub),
                icon = { Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = { onOpenSection(SettingsSection.DEVELOPER) }
            )
        }
    }
}

@Composable
private fun SettingsMenuItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
                modifier = Modifier.size(40.dp)
            ) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) { icon() }
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun AgentSection(
    modifier: Modifier,
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    val agents by container.agentRepository.observeAll()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var showOnboarding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<com.miloai.core.db.AgentEntity?>(null) }
    var deleting by remember { mutableStateOf<com.miloai.core.db.AgentEntity?>(null) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(com.miloai.R.string.agents_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (agents.isEmpty()) {
            Text(
                stringResource(com.miloai.R.string.agents_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            agents.forEach { agent ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(agent.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            if (agent.isDefault) {
                                Spacer(Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        stringResource(com.miloai.R.string.agents_default),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            if (!agent.isDefault) {
                                TextButton(onClick = {
                                    scope.launch {
                                        container.agentRepository.setDefault(agent.id)
                                        snackbar.showSnackbar(context.getString(com.miloai.R.string.agents_set_default, agent.name))
                                    }
                                }) {
                                    Text(stringResource(com.miloai.R.string.agents_set_as_default), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Text(
                            "${agent.provider} · ${agent.model}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (agent.systemPrompt.isNotBlank()) {
                            Text(
                                agent.systemPrompt.take(120),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 2
                            )
                        }
                        Row {
                            TextButton(onClick = { editing = agent }) {
                                Text(stringResource(com.miloai.R.string.agents_edit))
                            }
                            TextButton(onClick = { deleting = agent }) {
                                Text(stringResource(com.miloai.R.string.memories_delete), color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
        Button(
            onClick = { showOnboarding = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(com.miloai.R.string.agents_new))
        }
        Spacer(Modifier.height(24.dp))
    }

    if (showOnboarding) {
        AgentOnboardingDialog(
            onDismiss = { showOnboarding = false },
            onSaved = {
                showOnboarding = false
                scope.launch { snackbar.showSnackbar(context.getString(com.miloai.R.string.agent_saved)) }
            }
        )
    }
    editing?.let { agent ->
        AgentOnboardingDialog(
            isEdit = true,
            editingId = agent.id,
            editingIsDefault = agent.isDefault,
            originalApiKey = runCatching { container.agentRepository.toConfig(agent).apiKey }.getOrDefault(""),
            initialName = agent.name,
            initialProvider = runCatching { ProviderId.valueOf(agent.provider) }.getOrDefault(ProviderId.OPENAI_COMPAT),
            initialBaseUrl = agent.baseUrl,
            initialModel = agent.model,
            initialTemperature = agent.temperature,
            initialMaxTokens = agent.maxTokens,
            initialReasoning = agent.reasoning,
            initialSystemPrompt = agent.systemPrompt,
            onDismiss = { editing = null },
            onSaved = {
                editing = null
                scope.launch { snackbar.showSnackbar(context.getString(com.miloai.R.string.agent_saved)) }
            }
        )
    }
    deleting?.let { agent ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(com.miloai.R.string.agents_delete_title)) },
            text = { Text(stringResource(com.miloai.R.string.agents_delete_body, agent.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        container.agentRepository.delete(agent.id)
                        deleting = null
                        snackbar.showSnackbar(context.getString(com.miloai.R.string.agents_deleted, agent.name))
                    }
                }) { Text(stringResource(com.miloai.R.string.confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(com.miloai.R.string.cancel)) }
            }
        )
    }
}
@Composable
private fun ConversationSection(
    modifier: Modifier,
    container: AppContainer,
    settings: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    var defaultMode by remember { mutableStateOf(settings.getDefaultMode()) }
    var themeMode by remember { mutableStateOf(settings.getThemeMode()) }
    var autoSpeak by remember { mutableStateOf(settings.getAutoSpeak()) }
    var voiceAssistant by remember { mutableStateOf(settings.getVoiceAssistant()) }
    var accentColor by remember { mutableStateOf(settings.getAccentColor()) }
    var toolLimit by remember { mutableStateOf(settings.getToolCallLimit()) }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.settings_default_mode), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.settings_default_mode_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppMode.entries.forEach { m ->
                FilterChip(
                    selected = defaultMode == m,
                    onClick = {
                        defaultMode = m
                        settings.setDefaultMode(m)
                    },
                    label = { Text(m.displayName) }
                )
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.settings_tool_limit), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.settings_tool_limit_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(0, 50, 100, 200, 500).forEach { limit ->
                FilterChip(
                    selected = toolLimit == limit,
                    onClick = {
                        toolLimit = limit
                        settings.setToolCallLimit(limit)
                    },
                    label = {
                        Text(
                            if (limit == 0) stringResource(R.string.tool_limit_unlimited)
                            else limit.toString()
                        )
                    }
                )
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = {
                        themeMode = mode
                        settings.setThemeMode(mode)
                        container.bumpTheme()
                    },
                    label = { Text(mode.displayName) }
                )
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.settings_accent), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            com.miloai.core.storage.AccentColor.entries.forEach { c ->
                FilterChip(
                    selected = accentColor == c,
                    onClick = {
                        accentColor = c
                        settings.setAccentColor(c)
                        container.bumpTheme()
                    },
                    label = { Text(c.displayName) }
                )
            }
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_voice_assistant), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_voice_assistant_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = voiceAssistant,
                onCheckedChange = {
                    voiceAssistant = it
                    settings.setVoiceAssistant(it)
                }
            )
        }

        HorizontalDivider()

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_auto_speak), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.settings_auto_speak_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = autoSpeak,
                onCheckedChange = {
                    autoSpeak = it
                    settings.setAutoSpeak(it)
                }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SkillsSection(
    modifier: Modifier,
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    var skills by remember {
        mutableStateOf<List<com.miloai.core.skills.Skill>>(emptyList())
    }
    LaunchedEffect(Unit) {
        skills = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            container.skillRepository.loadAll()
        }
    }
    val reloadSkills: () -> Unit = {
        scope.launch {
            skills = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                container.skillRepository.loadAll()
            }
        }
    }
    val skillLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = context.contentResolver.query(
                uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else "skill.md" } ?: "skill.md"
            val content = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: ""
            if (content.isNotBlank()) {
                val result = container.skillRepository.import(fileName, content)
                result.onSuccess { scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_skill_imported, it.name)) } }
                    .onFailure { scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_skill_import_failed, it.message ?: "")) } }
                reloadSkills()
            }
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(R.string.settings_skills_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = { skillLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_import_skill))
        }
        if (skills.isEmpty()) {
            Text(
                stringResource(R.string.settings_skills_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            skills.forEach { skill ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(skill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                skill.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                        TextButton(onClick = {
                            container.skillRepository.delete(skill.name)
                            reloadSkills()
                            scope.launch { snackbar.showSnackbar(context.getString(R.string.settings_skill_deleted, skill.name)) }
                        }) {
                            Text(stringResource(com.miloai.R.string.memories_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PermissionsSection(
    modifier: Modifier,
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    var notificationEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { notificationEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled() }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.settings_system_permissions), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        PermissionRow(
            title = context.getString(R.string.perm_notification),
            status = if (notificationEnabled) context.getString(R.string.perm_granted) else context.getString(R.string.perm_denied),
            buttonText = context.getString(R.string.perm_action_grant),
            onAction = {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    scope.launch { snackbar.showSnackbar(context.getString(R.string.perm_notification_legacy)) }
                }
            }
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatsSection(
    modifier: Modifier,
    container: AppContainer
) {
    val db = container.db
    var conversations by remember { mutableStateOf(0L) }
    var userMessages by remember { mutableStateOf(0L) }
    var assistantMessages by remember { mutableStateOf(0L) }
    var inputTokens by remember { mutableStateOf(0L) }
    var outputTokens by remember { mutableStateOf(0L) }
    var toolCalls by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val c = db.conversationDao().getAllCount()
            val userM = db.messageDao().countUserMessages()
            val assistantM = db.messageDao().countAssistantMessages()
            val t = db.messageDao().tokenTotals()
            val tc = db.messageDao().countToolCallMessages()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                conversations = c
                userMessages = userM
                assistantMessages = assistantM
                inputTokens = t.totalInput
                outputTokens = t.totalOutput
                toolCalls = tc
            }
        }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        StatRow(stringResource(R.string.stats_conversations), conversations.toString())
        StatRow(stringResource(R.string.stats_user_msgs), userMessages.toString())
        StatRow(stringResource(R.string.stats_ai_replies), assistantMessages.toString())
        StatRow(stringResource(R.string.stats_input_tokens), formatCount(inputTokens))
        StatRow(stringResource(R.string.stats_output_tokens), formatCount(outputTokens))
        StatRow(stringResource(R.string.stats_tool_calls), toolCalls.toString())
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fK".format(n / 1000.0)
    else -> n.toString()
}

@Composable
private fun PermissionRow(
    title: String,
    status: String,
    buttonText: String,
    onAction: () -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status == stringResource(R.string.perm_granted)
                    ) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
            }
            Button(onClick = onAction) { Text(buttonText) }
        }
    }
}

@Composable
private fun DeveloperSection(
    modifier: Modifier,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val appVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: ""
    }
    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
    fun copyEmail(email: String) {
        clipboard.setText(AnnotatedString(email))
        scope.launch { snackbar.showSnackbar(context.getString(R.string.dev_email_copied, email)) }
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Milo AI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.dev_about_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dev_version, appVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.dev_developer), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                Text(DEV_NAME, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.dev_about_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.dev_about), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.dev_stack),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        HorizontalDivider()

        Text(stringResource(R.string.dev_support), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            onClick = { openUrl(DEV_SUPPORT_URL) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Saweria", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text(
                        stringResource(R.string.dev_support_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider()

        Text(stringResource(R.string.dev_email), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.dev_email_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        EmailRow(DEV_EMAIL, context.getString(R.string.dev_primary)) { copyEmail(it) }
        Spacer(Modifier.height(24.dp))
    }
}

private const val DEV_NAME = "Hanz Dev Official"
private const val DEV_EMAIL = "hanzzcodee@gmail.com"
private const val DEV_SUPPORT_URL = "https://saweria.co/hanzreally"

@Composable
private fun EmailRow(
    email: String,
    label: String,
    onCopy: (String) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = { onCopy(email) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(email, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                stringResource(R.string.copy),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun MemoriesSection(
    modifier: Modifier,
    container: AppContainer,
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState
) {
    val context = LocalContext.current
    var memories by remember { mutableStateOf(emptyList<com.miloai.core.db.MemoryEntity>()) }
    var snapshots by remember { mutableStateOf(emptyList<com.miloai.core.db.MemoryEntity>()) }
    LaunchedEffect(Unit) {
        val all = runCatching { container.db.memoryDao().observeAll() }.getOrDefault(emptyList())
        memories = all.filter { it.type == "memory" }
        snapshots = all.filter { it.type == "snapshot" }
    }
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            stringResource(com.miloai.R.string.memories_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (memories.isEmpty()) {
            Text(
                stringResource(com.miloai.R.string.memories_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                stringResource(com.miloai.R.string.memories_count, memories.size),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            memories.forEach { m ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            m.content,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = {
                            scope.launch {
                                container.db.memoryDao().delete(m.id)
                                memories = memories.filter { it.id != m.id }
                                snackbar.showSnackbar(context.getString(com.miloai.R.string.memories_deleted))
                            }
                        }) {
                            Text(stringResource(com.miloai.R.string.memories_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        Text(stringResource(com.miloai.R.string.memories_snapshots_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (snapshots.isEmpty()) {
            Text(
                stringResource(com.miloai.R.string.memories_snapshots_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            snapshots.forEach { snap ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(com.miloai.R.string.memories_snapshot_item, snap.content.length),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = {
                            scope.launch {
                                container.db.memoryDao().delete(snap.id)
                                snapshots = snapshots.filter { it.id != snap.id }
                                snackbar.showSnackbar(context.getString(com.miloai.R.string.memories_snapshot_deleted))
                            }
                        }) {
                            Text(stringResource(com.miloai.R.string.memories_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
