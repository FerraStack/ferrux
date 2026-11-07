// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import kotlinx.coroutines.launch

/** Top-level UI: session drawer, title bar, terminal and extra keys. */
@Composable
fun FerruxScreen(
    service: FerruxService?,
    onNewSession: () -> Unit,
    onExit: () -> Unit,
) {
    if (service == null) {
        // Binding is asynchronous; it normally completes before the first frame matters.
        Box(Modifier.fillMaxSize())
        return
    }
    LaunchedEffect(service) { service.exitRequests.collect { onExit() } }
    SessionsScreen(service.sessions, onNewSession)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionsScreen(manager: SessionManager, onNewSession: () -> Unit) {
    val sessions by manager.sessions.collectAsStateWithLifecycle()
    val currentId by manager.current.collectAsStateWithLifecycle()
    val current = sessions.firstOrNull { it.id == currentId }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val sticky = remember { StickyModifierState() }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }

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

    ModalNavigationDrawer(
        drawerState = drawerState,
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
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = current?.let { titleOf(it) } ?: stringResource(R.string.app_name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(painterResource(R.drawable.ic_menu), stringResource(R.string.open_drawer))
                        }
                    },
                    actions = {
                        IconButton(onClick = onNewSession) {
                            Icon(painterResource(R.drawable.ic_add), stringResource(R.string.new_session))
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding)
                    .imePadding(),
            ) {
                if (current == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_sessions))
                    }
                } else {
                    AndroidView(
                        factory = { context ->
                            TerminalView(context).also {
                                it.stickyModifiers = sticky
                                terminalView = it
                            }
                        },
                        update = { view ->
                            if ((view.backend as? NativeSessionBackend)?.sessionId != current.id) {
                                view.backend = NativeSessionBackend(current.id)
                                view.requestFocus()
                            }
                        },
                        onRelease = { if (terminalView === it) terminalView = null },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }
                ExtraKeysRow(
                    sticky = sticky,
                    onKey = { code -> terminalView?.sendKey(code) },
                    onText = { text -> terminalView?.sendText(text) },
                )
            }
        }
    }
}

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
    ModalDrawerSheet {
        Text(
            stringResource(R.string.sessions),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
        )
        HorizontalDivider()
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

/** Shown when libferrux.so is missing (APK built with -Pferrux.skipCargo=true). */
@Composable
fun NativeMissingScreen(error: Throwable) {
    Scaffold { padding ->
        Text(
            stringResource(R.string.native_missing, error.message.orEmpty()),
            modifier = Modifier.padding(padding).padding(24.dp),
        )
    }
}
