package com.northphoenix.hairdresserclientmanager.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.northphoenix.hairdresserclientmanager.ui.HcmSnackbarHost
import com.northphoenix.hairdresserclientmanager.ui.screens.BusyBar

/** Scaffold for a pushed screen: back button, title, optional actions and a pinned bottom bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    busy: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { HcmSnackbarHost() },
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
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
