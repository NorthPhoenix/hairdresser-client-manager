package com.northphoenix.hairdresserclientmanager.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.ScrollState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessStarted
import com.northphoenix.hairdresserclientmanager.R
import com.northphoenix.hairdresserclientmanager.ui.HcmSnackbarHost
import com.northphoenix.hairdresserclientmanager.ui.screens.BusyBar

/**
 * Scaffold for a pushed screen: back button, title, optional actions and a pinned bottom bar.
 * While the screen holds [unsaved] input, the back button and the system Back ask before leaving.
 * [saving] shows the thin progress line and disables nothing: pass `viewModel.saving`.
 * [onBack] is called without asking by whatever else closes the screen, such as a delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    saving: Boolean = false,
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
            BusyBar(saving, Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()))
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

/** Scrolling form body for a [SubScreen]. A screen that has to scroll it itself passes its own [scroll]. */
@Composable
fun ScreenColumn(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxSize()
            // Only the top inset is fixed; the bottom one is scrollable padding, so content
            // runs under the gesture bar instead of being cut off above it.
            .padding(top = padding.calculateTopPadding())
            .imePadding()
            .verticalScroll(scroll)
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

/**
 * Bottom sheet with the app's surface colour, a serif title and scrollable content. A notice
 * raised while it is open lies over the top of the sheet: it stays in view above the keyboard
 * wherever the sheet is scrolled to, and the content does not move for it.
 */
@Composable
fun HcmBottomSheet(title: String, onDismiss: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    SheetFrame(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(SheetPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetTitle(title, subtitle)
            content()
        }
    }
}

/**
 * [HcmBottomSheet] for a list that may be long. [content] is above the list and scrolls with it.
 * Of the [rows] only those in view are composed, so the sheet opens as fast with hundreds of rows as with ten.
 */
@Composable
fun HcmListBottomSheet(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit, rows: LazyListScope.() -> Unit) {
    SheetFrame(onDismiss) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = SheetPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SheetTitle(title, subtitle = null)
                    content()
                }
            }
            rows()
        }
    }
}

/**
 * [HcmBottomSheet] for picking from a list that may be long, with [bottomBar] (the action that
 * takes what was picked) fixed under the content. With [fillHeight] it is nearly as tall as the
 * screen, so it keeps its size while the list grows and shrinks and still leaves a strip of scrim
 * above it; without, it is as tall as its content. Scrolling the content never moves the sheet: scrolled back past its top it stops
 * there, and the sheet closes only by the scrim, Back, or a drag of its handle.
 */
@Composable
fun HcmTallBottomSheet(
    title: String,
    onDismiss: () -> Unit,
    bottomBar: @Composable ColumnScope.() -> Unit,
    fillHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    SheetFrame(onDismiss) {
        // Not quite the whole height: the strip of the screen above the sheet is the scrim that closes it.
        Column(if (fillHeight) Modifier.fillMaxWidth().fillMaxHeight(TALL_SHEET_HEIGHT) else Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .weight(1f, fill = fillHeight)
                    .fillMaxWidth()
                    .nestedScroll(KeepScrollInside)
                    .verticalScroll(rememberScrollState())
                    .padding(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SheetTitle(title, subtitle = null)
                content()
            }
            Hairline()
            Column(
                Modifier.fillMaxWidth().padding(start = ScreenPadding, end = ScreenPadding, top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = bottomBar,
            )
        }
    }
}

/**
 * Keeps what a scroll or a fling has left over after the content inside it has scrolled to its
 * end. A modal sheet takes that rest to drag itself down, so without this a list scrolled back
 * past its top closes the sheet it is in.
 */
private object KeepScrollInside : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
}

/** The share of the screen's height a tall sheet takes. */
private const val TALL_SHEET_HEIGHT = 0.9f

private val SheetPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, top = 16.dp, bottom = 28.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(onDismiss: () -> Unit, scrollingContent: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge.copy(
            bottomStart = androidx.compose.foundation.shape.CornerSize(0.dp),
            bottomEnd = androidx.compose.foundation.shape.CornerSize(0.dp),
        ),
    ) {
        Box {
            scrollingContent()
            // The sheet is its own window, so notices raised while it is open are shown inside it too.
            // Not a child of the scrolling content: there a notice was added above what is in view.
            HcmSnackbarHost(Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
private fun SheetTitle(title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
