package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessStarted
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.ui.HcmSnackbarHost
import com.northphoenix.hairdresserclientmanager.ui.screens.BusyBar

/**
 * Scaffold for a pushed screen: back button, title, optional actions and a pinned bottom bar.
 * While the screen holds [unsaved] input, the back button and the system Back ask before leaving.
 * [onBack] is called without asking by whatever else closes the screen, such as a delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    busy: Boolean = false,
    unsaved: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    // A screen that is already closing stays tappable until its transition ends. It is no longer
    // started by then, and a tap on its back button does nothing: neither leaves again nor asks.
    val leave = dropUnlessStarted {
        if (unsaved) {
            confirmDiscard = true
        } else {
            onBack()
        }
    }

    // Declared inside the screen, so it is asked before the navigation's own handling of Back.
    BackHandler(enabled = unsaved, onBack = leave)
    // A save that was still running can finish while the question is open: then nothing is left to discard.
    LaunchedEffect(unsaved) {
        if (!unsaved) {
            confirmDiscard = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { HcmSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(leave) },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = floatingActionButton,
        bottomBar = bottomBar,
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            content(padding)
            BusyBar(busy, Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()))
        }
    }

    if (confirmDiscard && unsaved) {
        ConfirmDialog(
            title = stringResource(R.string.discard_changes_title),
            body = stringResource(R.string.discard_changes_body),
            confirmText = stringResource(R.string.discard_changes_confirm),
            dismissText = stringResource(R.string.discard_changes_keep),
            danger = true,
            onConfirm = {
                confirmDiscard = false
                onBack()
            },
            onDismiss = { confirmDiscard = false },
        )
    }
}

/** Scrolling form body for a [SubScreen]. */
@Composable
fun ScreenColumn(padding: PaddingValues, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            // Only the top inset is fixed; the bottom one is scrollable padding, so content
            // runs under the gesture bar instead of being cut off above it.
            .padding(top = padding.calculateTopPadding())
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = ScreenPadding, end = ScreenPadding, top = 8.dp, bottom = 32.dp + padding.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

/** Pinned primary action at the bottom of a form screen. */
@Composable
fun BottomActionBar(content: @Composable ColumnScope.() -> Unit) {
    // Opaque, so scrolling form content does not show through behind the button.
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
        Hairline()
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

/** Bottom sheet with the app's surface colour, a serif title and scrollable content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HcmBottomSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = ScreenPadding, end = ScreenPadding, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // The sheet is its own window, so notices raised while it is open are shown inside it too.
            HcmSnackbarHost()
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}
