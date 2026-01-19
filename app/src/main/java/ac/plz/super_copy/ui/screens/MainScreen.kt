/*
 * Copyright 2026 SuperCopy
 *
 * Licensed under the MIT License.
 */

package ac.plz.super_copy.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ac.plz.super_copy.R
import ac.plz.super_copy.data.local.entity.ClipboardEntry
import ac.plz.super_copy.ui.MainUiState
import ac.plz.super_copy.ui.MainViewModel
import ac.plz.super_copy.ui.components.ClipboardEntryCard

/**
 * Main screen of the SuperCopy application.
 *
 * @param viewModel The ViewModel for managing state.
 * @param onFloatingButtonClick Callback when the floating button toggle is clicked.
 * @param onCopyToClipboard Callback to copy text to system clipboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onFloatingButtonClick: () -> Unit,
    onCopyToClipboard: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val clipboardHistory by viewModel.clipboardHistory.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val copySuccessMessage = stringResource(R.string.copy_success)

    // Handle success/error messages
    LaunchedEffect(uiState.showCopySuccess) {
        if (uiState.showCopySuccess) {
            uiState.lastRecognizedText?.let { text ->
                onCopyToClipboard(text)
            }
            snackbarHostState.showSnackbar(
                message = copySuccessMessage,
                duration = SnackbarDuration.Short
            )
            viewModel.clearCopySuccessMessage()
        }
    }

    LaunchedEffect(uiState.lastError) {
        uiState.lastError?.let { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Short
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.app_name))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (clipboardHistory.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Filled.DeleteSweep,
                                contentDescription = stringResource(R.string.clear_history)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        MainContent(
            uiState = uiState,
            clipboardHistory = clipboardHistory,
            onCopy = onCopyToClipboard,
            onDelete = viewModel::deleteEntry,
            onFloatingButtonToggle = onFloatingButtonClick,
            modifier = Modifier.padding(innerPadding)
        )
    }
}

@Composable
private fun MainContent(
    uiState: MainUiState,
    clipboardHistory: List<ClipboardEntry>,
    onCopy: (String) -> Unit,
    onDelete: (ClipboardEntry) -> Unit,
    onFloatingButtonToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Floating Button Toggle Card
        FloatingButtonToggleCard(
            isEnabled = uiState.isFloatingButtonEnabled,
            onToggle = onFloatingButtonToggle,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Clipboard History
        Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            if (clipboardHistory.isEmpty()) {
                EmptyHistoryPlaceholder(
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                ClipboardHistoryList(
                    entries = clipboardHistory,
                    onCopy = onCopy,
                    onDelete = onDelete
                )
            }
        }
    }
}

@Composable
private fun FloatingButtonToggleCard(
    isEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isEnabled) {
                        Icons.Filled.RadioButtonChecked
                    } else {
                        Icons.Filled.RadioButtonUnchecked
                    },
                    contentDescription = null,
                    tint = if (isEnabled) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.floating_button_title),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = stringResource(
                            if (isEnabled) R.string.floating_button_enabled
                            else R.string.floating_button_disabled
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggle() }
            )
        }
    }
}

@Composable
private fun EmptyHistoryPlaceholder(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.CameraAlt,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_history_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_history_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ClipboardHistoryList(
    entries: List<ClipboardEntry>,
    onCopy: (String) -> Unit,
    onDelete: (ClipboardEntry) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp,
            bottom = 88.dp // Extra padding for FAB
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = entries,
            key = { it.id }
        ) { entry ->
            ClipboardEntryCard(
                entry = entry,
                onCopy = onCopy,
                onDelete = onDelete
            )
        }
    }
}
