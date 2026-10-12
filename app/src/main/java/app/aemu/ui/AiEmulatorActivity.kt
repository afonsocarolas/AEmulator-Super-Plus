/* AEmulator Super Plus, 2026-10-12: AIemulator chat screen. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.aemu.AppPrefs
import app.aemu.R
import app.aemu.ai.AiAgent
import app.aemu.ai.AiEvents
import app.aemu.ai.AiFailure
import app.aemu.ai.AiTools
import app.aemu.ai.ChangeRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AiEmulatorActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { AiEmulatorScreen(onBack = ::finish) } }
    }
}

sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    class Assistant : ChatItem { var text by mutableStateOf("") }
    data class Tool(val name: String) : ChatItem
    class Approval(val request: ChangeRequest, val answer: CompletableDeferred<Boolean>) : ChatItem { var decided by mutableStateOf<Boolean?>(null) }
    data class Failure(val kind: AiFailure, val detail: String) : ChatItem
}

class AiEmulatorModel(app: Application) : AndroidViewModel(app) {
    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false); private set
    var configured by mutableStateOf(AppPrefs.aiServerUrl(app).isNotBlank()); private set
    private var agent: AiAgent? = null
    private var job: Job? = null

    private fun agent(): AiAgent? {
        val ctx = getApplication<Application>()
        val url = AppPrefs.aiServerUrl(ctx)
        if (url.isBlank()) return null
        return agent ?: AiAgent(url, AppPrefs.aiAccessCode(ctx), AiTools(ctx, ::approve)).also { agent = it }
    }

    fun configure(url: String, code: String) {
        val ctx = getApplication<Application>()
        AppPrefs.setAiServerUrl(ctx, url); AppPrefs.setAiAccessCode(ctx, code)
        configured = url.isNotBlank()
        // a new connection starts a new conversation
        stop(); agent = null; items.clear()
    }

    fun newChat() { stop(); agent = null; items.clear() }

    fun stop() {
        job?.cancel(); job = null; busy = false
        items.filterIsInstance<ChatItem.Approval>().filter { it.decided == null }.forEach { it.decided = false }
    }

    fun send(text: String) {
        val a = agent() ?: run { items += ChatItem.Failure(AiFailure.NOT_CONFIGURED, ""); return }
        if (busy || text.isBlank()) return
        items += ChatItem.User(text.trim())
        busy = true
        job = viewModelScope.launch {
            val me = coroutineContext[Job]
            var current: ChatItem.Assistant? = null
            val events = object : AiEvents {
                override fun text(delta: String) {
                    val bubble = current ?: ChatItem.Assistant().also { current = it; items += it }
                    bubble.text += delta
                }
                override fun toolStarted(name: String) { current = null; items += ChatItem.Tool(name) }
                override suspend fun failed(kind: AiFailure, detail: String) = withContext(Dispatchers.Main) {
                    // the turn was rolled back on the server side of the history: take its text off the screen too
                    if (kind == AiFailure.REFUSED) current?.let { items.remove(it) }
                    items += ChatItem.Failure(kind, detail)
                }
            }
            try { withContext(Dispatchers.IO) { a.send(text.trim(), events) } }
            finally { if (job === me) { busy = false; job = null } }
        }
    }

    private suspend fun approve(request: ChangeRequest): Boolean {
        val item = ChatItem.Approval(request, CompletableDeferred())
        withContext(Dispatchers.Main) { items += item }
        val ok = item.answer.await()
        if (ok) withContext(Dispatchers.IO) { request.apply() }
        return ok
    }

    fun decide(item: ChatItem.Approval, ok: Boolean) {
        if (item.decided != null) return
        item.decided = ok
        item.answer.complete(ok)
    }
}

@Composable
private fun AiEmulatorScreen(onBack: () -> Unit, model: AiEmulatorModel = viewModel()) {
    var setup by remember { mutableStateOf(!model.configured) }
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(model.items.size, (model.items.lastOrNull() as? ChatItem.Assistant)?.text?.length) {
        if (model.items.isNotEmpty()) list.animateScrollToItem(model.items.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.ai_title))
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = model::newChat, enabled = model.items.isNotEmpty()) { Icon(Icons.Rounded.EditNote, stringResource(R.string.ai_new_chat)) }
                    IconButton(onClick = { setup = true }) { Icon(Icons.Rounded.Dns, stringResource(R.string.ai_server)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding().navigationBarsPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (model.items.isEmpty()) Welcome(model.configured, onSetup = { setup = true }, onPick = { model.send(it) })
                else LazyColumn(state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(model.items) { _, item -> ChatRow(item, onDecide = model::decide, onSetup = { setup = true }) }
                    if (model.busy && model.items.lastOrNull() !is ChatItem.Assistant && model.items.lastOrNull() !is ChatItem.Approval) {
                        item { LoadingIndicator(Modifier.size(36.dp)) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text(stringResource(R.string.ai_input_hint)) }, maxLines = 5, shape = MaterialTheme.shapes.extraLarge)
                Spacer(Modifier.size(8.dp))
                if (model.busy) FilledIconButton(onClick = model::stop) { Icon(Icons.Rounded.Stop, stringResource(R.string.ai_stop)) }
                else FilledIconButton(onClick = { model.send(input); input = "" }, enabled = input.isNotBlank() && model.configured) {
                    Icon(Icons.AutoMirrored.Rounded.Send, stringResource(R.string.ai_send))
                }
            }
        }
    }

    if (setup) ServerDialog(onDismiss = { setup = false }, onSave = { url, code -> model.configure(url, code); setup = false })
}

@Composable
private fun Welcome(configured: Boolean, onSetup: () -> Unit, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(88.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.ai_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.ai_welcome), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
        if (!configured) Button(onClick = onSetup) { Text(stringResource(R.string.ai_setup)) }
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            for (res in listOf(R.string.ai_suggest_boot, R.string.ai_suggest_fast, R.string.ai_suggest_gpu, R.string.ai_suggest_look)) {
                val text = stringResource(res)
                AssistChip(onClick = { onPick(text) }, label = { Text(text) })
            }
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, onDecide: (ChatItem.Approval, Boolean) -> Unit, onSetup: () -> Unit) {
    when (item) {
        is ChatItem.User -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(item.text, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.widthIn(max = 320.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        is ChatItem.Assistant -> SelectionContainer {
            Text(item.text, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.large)
                .padding(horizontal = 14.dp, vertical = 10.dp))
        }
        is ChatItem.Tool -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Build, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(toolLabel(item.name)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is ChatItem.Approval -> ApprovalCard(item, onDecide)
        is ChatItem.Failure -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Column(Modifier.padding(14.dp)) {
                Text(stringResource(failureText(item.kind)), color = MaterialTheme.colorScheme.onErrorContainer)
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                if (item.kind == AiFailure.ACCESS_CODE || item.kind == AiFailure.NOT_CONFIGURED) TextButton(onClick = onSetup) { Text(stringResource(R.string.ai_server)) }
            }
        }
    }
}

@Composable
private fun ApprovalCard(item: ChatItem.Approval, onDecide: (ChatItem.Approval, Boolean) -> Unit) {
    Card(shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.ai_approve_title, item.request.title), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(item.request.reason, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
            for ((key, old, new) in item.request.diff) {
                Text("$key: $old → $new", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
            Spacer(Modifier.height(12.dp))
            when (item.decided) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onDecide(item, true) }) { Text(stringResource(R.string.ai_approve)) }
                    OutlinedButton(onClick = { onDecide(item, false) }) { Text(stringResource(R.string.ai_decline)) }
                }
                true -> Text(stringResource(R.string.ai_approved), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
                false -> Text(stringResource(R.string.ai_declined), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun ServerDialog(onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(AppPrefs.aiServerUrl(ctx)) }
    var code by remember { mutableStateOf(AppPrefs.aiAccessCode(ctx)) }
    val valid = url.isBlank() || url.startsWith("https://") || url.startsWith("http://")
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Dns, null) },
        title = { Text(stringResource(R.string.ai_server)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ai_server_sub), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, isError = !valid,
                    label = { Text(stringResource(R.string.ai_server_url)) }, placeholder = { Text("https://…") })
                OutlinedTextField(value = code, onValueChange = { code = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), label = { Text(stringResource(R.string.ai_access_code)) })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, code) }, enabled = valid) { Text(stringResource(R.string.ai_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private fun toolLabel(name: String) = when (name) {
    "list_systems" -> R.string.ai_tool_list
    "get_system_settings" -> R.string.ai_tool_settings
    "read_system_log" -> R.string.ai_tool_log
    "get_host_info" -> R.string.ai_tool_host
    "change_system_settings", "change_app_appearance" -> R.string.ai_tool_change
    else -> R.string.ai_tool_other
}

private fun failureText(kind: AiFailure) = when (kind) {
    AiFailure.NOT_CONFIGURED -> R.string.ai_err_setup
    AiFailure.ACCESS_CODE -> R.string.ai_err_code
    AiFailure.RATE_LIMIT -> R.string.ai_err_rate
    AiFailure.NETWORK -> R.string.ai_err_network
    AiFailure.REFUSED -> R.string.ai_err_refused
    AiFailure.TOO_LONG -> R.string.ai_err_long
    AiFailure.SERVER -> R.string.ai_err_server
}
