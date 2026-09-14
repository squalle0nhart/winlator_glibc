package com.winlator.cmod.ui.inputcontrols

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.winlator.cmod.R
import com.winlator.cmod.ui.theme.WinZTheme

data class ExternalControllerBindingItem(
    val keyCode: Int,
    val title: String,
    val type: Int,
    val binding: String
)

interface ExternalControllerBindingsCallbacks {
    fun onBack()
    fun onRemove(keyCode: Int)
    fun onBindingSelected(keyCode: Int, type: Int, position: Int)
}

class ExternalControllerBindingsState {
    var items by mutableStateOf<List<ExternalControllerBindingItem>>(emptyList())
        private set
    var activeKeyCode by mutableStateOf<Int?>(null)
        private set
    var activation by mutableStateOf(0L)
        private set

    fun update(items: List<ExternalControllerBindingItem>) {
        this.items = items.toList()
    }

    fun activate(keyCode: Int) {
        activeKeyCode = keyCode
        activation++
    }
}

object ExternalControllerBindingsComposeHost {
    @JvmStatic
    fun create(
        context: Context,
        title: String,
        state: ExternalControllerBindingsState,
        bindingLabels: List<List<String>>,
        callbacks: ExternalControllerBindingsCallbacks
    ): ComposeView = ComposeView(context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            WinZTheme {
                ExternalControllerBindingsScreen(title, state, bindingLabels, callbacks)
            }
        }
    }
}

@Composable
private fun ExternalControllerBindingsScreen(
    title: String,
    state: ExternalControllerBindingsState,
    bindingLabels: List<List<String>>,
    callbacks: ExternalControllerBindingsCallbacks
) {
    val listState = rememberLazyListState()
    val types = stringArrayResource(R.array.binding_type_entries)
    var editingKey by rememberSaveable { mutableStateOf<Int?>(null) }
    val editingItem = state.items.firstOrNull { it.keyCode == editingKey }

    LaunchedEffect(state.activation) {
        val index = state.items.indexOfFirst { it.keyCode == state.activeKeyCode }
        if (index >= 0) listState.scrollToItem(index)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = callbacks::onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            stringResource(androidx.appcompat.R.string.abc_action_bar_up_description)
                        )
                    }
                }
            )
        }
    ) { padding ->
        if (state.items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), Alignment.Center) {
                Text(
                    stringResource(R.string.press_any_button_on_your_controller),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(state.items, key = { it.keyCode }) { item ->
                    val alpha = remember { Animatable(0f) }
                    var choosingType by remember { mutableStateOf(false) }
                    LaunchedEffect(state.activation) {
                        if (state.activeKeyCode == item.keyCode) {
                            alpha.snapTo(0.4f)
                            alpha.animateTo(0f, tween(200))
                        } else {
                            alpha.snapTo(0f)
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha.value))
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleMedium)
                            Box {
                                OutlinedButton(onClick = { choosingType = true }) {
                                    Text(types[item.type])
                                }
                                DropdownMenu(choosingType, onDismissRequest = { choosingType = false }) {
                                    types.forEachIndexed { index, label ->
                                        DropdownMenuItem(
                                            text = { Text(label) },
                                            onClick = {
                                                choosingType = false
                                                if (index != item.type) {
                                                    callbacks.onBindingSelected(item.keyCode, index, 0)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                            OutlinedButton(onClick = { editingKey = item.keyCode }) {
                                Text(item.binding)
                            }
                        }
                        IconButton(onClick = { callbacks.onRemove(item.keyCode) }) {
                            Icon(Icons.Outlined.Delete, stringResource(R.string.remove) + ": " + item.title)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (editingItem != null) {
        AlertDialog(
            onDismissRequest = { editingKey = null },
            title = { Text(stringResource(R.string.binding) + ": " + editingItem.title) },
            text = {
                val labels = bindingLabels[editingItem.type]
                val selectionState = rememberLazyListState(
                    initialFirstVisibleItemIndex = labels.indexOf(editingItem.binding).coerceAtLeast(0)
                )
                LazyColumn(state = selectionState) {
                    items(labels.size) { index ->
                        TextButton(
                            onClick = {
                                callbacks.onBindingSelected(editingItem.keyCode, editingItem.type, index)
                                editingKey = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(labels[index], Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { editingKey = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }
}
