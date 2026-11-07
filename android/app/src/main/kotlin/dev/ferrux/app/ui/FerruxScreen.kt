// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ferrux.app.FerruxService
import dev.ferrux.app.R
import dev.ferrux.app.SessionInfo
import dev.ferrux.app.SessionManager
import dev.ferrux.terminal.NativeSessionBackend
import dev.ferrux.terminal.TerminalView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Top-level UI, Termux-style: the terminal fills the screen edge to edge, the
 * extra-keys row sits above the soft keyboard, and the session drawer opens by
 * swiping from the left edge or with the ☰ extra key.
 */
@Composable
fun FerruxScreen(
    service: FerruxService?,
    onNewSession: () -> Unit,
    onExit: () -> Unit,
) {
    if (service == null) {
        // Binding is asynchronous; show the terminal background meanwhile.
        Box(Modifier.fillMaxSize().background(TerminalColors.Background))
        return
    }
    LaunchedEffect(service) { service.exitRequests.collect { onExit() } }
    SessionsScreen(service.sessions, onNewSession, onExit)
}

@Composable
private fun SessionsScreen(manager: SessionManager, onNewSession: () -> Unit, onExit: () -> Unit) {
    val sessions by manager.sessions.collectAsStateWithLifecycle()
    val currentId by manager.current.collectAsStateWithLifecycle()
    val current = sessions.firstOrNull { it.id == currentId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sticky = remember { StickyModifierState() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

    // Closing the last session closes the app, like a terminal window.
    LaunchedEffect(manager) {
        var hadSessions = false
        manager.sessions.collect { list ->
            if (list.isNotEmpty()) hadSessions = true else if (hadSessions) onExit()
        }
    }

    // Redraw the terminal whenever the native side reports a change.
    DisposableEffect(manager, terminalView) {
        val view = terminalView
        val listener = Runnable { view?.onScreenUpdated() }
        manager.addOutputListener(listener)
        onDispose { manager.removeOutputListener(listener) }
    }
    LaunchedEffect(manager, terminalView) {
        manager.bells.collect { id ->
            if (id == manager.current.value) {
                terminalView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        }
    }
    // OSC 52: ask before a program replaces the clipboard.
    val snackbars = remember { SnackbarHostState() }
    val resources = LocalContext.current.resources
    LaunchedEffect(manager) {
        manager.clipboardRequest.collectLatest { request ->
            if (request == null) return@collectLatest
            val result = snackbars.showSnackbar(
                message = resources.getQuantityString(
                    R.plurals.clipboard_request, request.length, request.sessionTitle, request.length,
                ),
                actionLabel = resources.getString(R.string.clipboard_allow),
                withDismissAction = true,
                duration = SnackbarDuration.Long,
            )
            manager.answerClipboard(request, allow = result == SnackbarResult.ActionPerformed)
        }
    }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Dragging is only enabled to close; opening uses the edge strip below
        // so horizontal drags never fight with the terminal.
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            SessionDrawer(
                sessions = sessions,
                currentId = currentId,
                onSelect = {
                    manager.select(it)
                    scope.launch { drawerState.close() }
                },
                onKill = manager::kill,
                onNew = {
                    onNewSession()
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(TerminalColors.Background)
                // Status bar, navigation bar, display cutout and the IME: the
                // terminal shrinks above the keyboard and the grid is resized.
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (current != null) {
                    AndroidView(
                        factory = { context ->
                            TerminalView(context).also {
                                it.stickyModifiers = sticky
                                it.setPadding(4.dp.px(context), 2.dp.px(context), 4.dp.px(context), 0)
                                terminalView = it
                                ActiveTerminal.view = java.lang.ref.WeakReference(it)
                            }
                        },
                        update = { view ->
                            if ((view.backend as? NativeSessionBackend)?.sessionId != current.id) {
                                view.backend = NativeSessionBackend(current.id)
                                view.requestFocus()
                            }
                        },
                        onRelease = { if (terminalView === it) terminalView = null },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                EdgeSwipeStrip(
                    onOpen = { scope.launch { drawerState.open() } },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                SnackbarHost(
                    snackbars,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 10.dp),
                ) { data -> FerruxSnackbar(data) }
            }
            ExtraKeysRow(
                layout = ExtraKeysLayout.Default,
                sticky = sticky,
                onKey = { code -> terminalView?.sendKey(code) },
                onText = { text -> terminalView?.sendText(text) },
                onAction = { action ->
                    when (action) {
                        ExtraKeyAction.Drawer -> scope.launch { drawerState.open() }
                    }
                },
            )
        }
    }
}

/** A snackbar in the terminal's palette rather than Material's inverse colours. */
@Composable
private fun FerruxSnackbar(data: SnackbarData) {
    Snackbar(
        data,
        shape = RoundedCornerShape(12.dp),
        containerColor = TerminalColors.ExtraKeysBackground,
        contentColor = TerminalColors.Foreground,
        actionColor = TerminalColors.Accent,
        dismissActionContentColor = TerminalColors.KeyLabel,
        modifier = Modifier.border(1.dp, TerminalColors.KeyPressed, RoundedCornerShape(12.dp)),
    )
}

/** A thin strip on the left edge: swiping right from it opens the session drawer. */
@Composable
private fun EdgeSwipeStrip(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    Box(
        modifier
            .fillMaxHeight()
            .width(14.dp)
            .pointerInput(Unit) {
                var dragged = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onHorizontalDrag = { change, amount ->
                        dragged += amount
                        change.consume()
                        if (dragged > threshold) {
                            dragged = Float.NEGATIVE_INFINITY
                            onOpen()
                        }
                    },
                )
            },
    )
}

private fun androidx.compose.ui.unit.Dp.px(context: android.content.Context): Int =
    (value * context.resources.displayMetrics.density).toInt()

@Composable
private fun titleOf(session: SessionInfo): String {
    val code = session.exitCode ?: return session.title
    return "${session.title} (${stringResource(R.string.exited, code)})"
}

@Composable
private fun SessionDrawer(
    sessions: List<SessionInfo>,
    currentId: Long,
    onSelect: (Long) -> Unit,
    onKill: (Long) -> Unit,
    onNew: () -> Unit,
) {
    ModalDrawerSheet(Modifier.semantics { contentDescription = "Session drawer" }) {
        Text(
            stringResource(R.string.sessions),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        )
        HorizontalDivider()
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(sessions, key = { it.id }) { session ->
                NavigationDrawerItem(
                    label = { Text(titleOf(session), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = session.id == currentId,
                    onClick = { onSelect(session.id) },
                    badge = {
                        IconButton(onClick = { onKill(session.id) }) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.kill_session))
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.new_session)) },
            icon = { Icon(painterResource(R.drawable.ic_add), null) },
            selected = false,
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/**
 * The terminal view currently on screen (weakly held), for debug tooling such as the
 * CI `DebugReceiver`, which reads its selection state.
 */
internal object ActiveTerminal {
    @Volatile
    var view: java.lang.ref.WeakReference<TerminalView>? = null
}

/** Shown when libferrux.so is missing (APK built with -Pferrux.skipCargo=true). */
@Composable
fun NativeMissingScreen(error: Throwable) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TerminalColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Text(
            stringResource(R.string.native_missing, error.message.orEmpty()),
            modifier = Modifier.padding(24.dp),
        )
    }
}
