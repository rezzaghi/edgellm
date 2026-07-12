package io.github.rezzaghi.edgellm.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rezzaghi.edgellm.ChatMessage
import io.github.rezzaghi.edgellm.Fit
import io.github.rezzaghi.edgellm.ModelSpec
import io.github.rezzaghi.edgellm.sample.ChatViewModel
import io.github.rezzaghi.edgellm.sample.UiState

@Composable
fun ChatScreen(vm: ChatViewModel) {
    val state by vm.state.collectAsState()
    val messages by vm.messages.collectAsState()
    val generating by vm.generating.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(16.dp),
    ) {
        ModelPicker(vm.models, vm.model, enabled = !generating, onSelect = vm::selectModel)
        Spacer(Modifier.height(12.dp))

        when (val s = state) {
            is UiState.NeedsDownload -> PrepareCard(vm.model, s, vm::prepare)
            is UiState.Downloading -> {
                Text("Downloading… ${(s.fraction * 100).toInt()}%")
                LinearProgressIndicator(
                    progress = { s.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
            is UiState.LoadingModel -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.padding(end = 12.dp))
                Text("Loading model…")
            }
            is UiState.Error -> Column {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = vm::prepare) { Text("Retry") }
            }
            is UiState.Ready -> ChatArea(
                messages = messages,
                generating = generating,
                backend = s.backend.name,
                onSend = vm::send,
                onStop = vm::stop,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelPicker(
    models: List<ModelSpec>,
    selected: ModelSpec,
    enabled: Boolean,
    onSelect: (ModelSpec) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected.id,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            models.forEach { spec ->
                DropdownMenuItem(
                    text = { Text(spec.id) },
                    onClick = {
                        expanded = false
                        onSelect(spec)
                    },
                )
            }
        }
    }
}

@Composable
private fun PrepareCard(model: ModelSpec, state: UiState.NeedsDownload, onPrepare: () -> Unit) {
    Column {
        val sizeMb = model.sizeBytes / (1024 * 1024)
        when (val fit = state.fit) {
            is Fit.Ok -> Text("Fits in memory")
            is Fit.TightRam ->
                Text(
                    "Low memory: ${fit.availableMb}MB free, ~${fit.requiredMb}MB needed",
                    color = MaterialTheme.colorScheme.error,
                )
            is Fit.WontFit ->
                Text(
                    "Won't fit: ${fit.availableMb}MB free, ~${fit.requiredMb}MB needed",
                    color = MaterialTheme.colorScheme.error,
                )
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onPrepare, enabled = state.fit !is Fit.WontFit) {
            Text(if (state.downloaded) "Load model" else "Download ($sizeMb MB) & load")
        }
    }
}

@Composable
private fun ChatArea(
    messages: List<ChatMessage>,
    generating: Boolean,
    backend: String,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, (messages.lastOrNull()?.content?.length ?: 0)) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Text("Running on $backend", style = MaterialTheme.typography.labelMedium)
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages) { message -> MessageBubble(message) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask something…") },
                enabled = !generating,
            )
            Spacer(Modifier.padding(4.dp))
            if (generating) {
                Button(onClick = onStop) { Text("Stop") }
            } else {
                Button(
                    onClick = {
                        onSend(input)
                        input = ""
                    },
                    enabled = input.isNotBlank(),
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val fromUser = message.role == "user"
    Box(Modifier.fillMaxWidth()) {
        Surface(
            color = if (fromUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .align(if (fromUser) Alignment.CenterEnd else Alignment.CenterStart)
                .widthIn(max = 300.dp),
        ) {
            Text(message.content, Modifier.padding(12.dp))
        }
    }
}
