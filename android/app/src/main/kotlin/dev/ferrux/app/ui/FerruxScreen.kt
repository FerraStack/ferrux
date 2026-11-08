// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ferrux.app.BellMode
import dev.ferrux.app.ExitedSessionBackend
import dev.ferrux.app.FerruxService
import dev.ferrux.app.FerruxSettings
import dev.ferrux.app.R
import dev.ferrux.app.SessionInfo
import dev.ferrux.app.SessionManager
import dev.ferrux.terminal.NativeSessionBackend
import dev.ferrux.terminal.TerminalView
import kotlin.math.abs
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
    val appContext = LocalContext.current.applicationContext
    val settings = remember(appContext) { FerruxSettings.get(appContext) }
    var terminalView by remember { mutableStateOf<TerminalView?>(null) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val themeId by settings.theme.collectAsStateWithLifecycle()
    val cursorStyle by settings.cursorStyle.collectAsStateWithLifecycle()
    val cursorBlink by settings.cursorBlink.collectAsStateWithLifecycle()
    val fontSizeSp by settings.fontSizeSp.collectAsStateWithLifecycle()
    val extraKeys by settings.extraKeys.collectAsStateWithLifecycle()
    val bell by settings.bell.collectAsStateWithLifecycle()

    // Theme and cursor live in the native sessions (FerruxService also applies them, for
    // sessions created without this screen); redraw with the new colours.
    LaunchedEffect(manager, terminalView, themeId, cursorStyle) {
        manager.setAppearance(TerminalTheme.byId(themeId).id, cursorStyle.code)
        terminalView?.invalidate()
    }
    LaunchedEffect(terminalView, cursorBlink) { terminalView?.cursorBlink = cursorBlink }
    // The Settings slider (pinch zoom saves to the same setting).
    LaunchedEffect(terminalView, fontSizeSp) {
        val size = fontSizeSp ?: return@LaunchedEffect
        terminalView?.let { if (it.textSizeSp != size) it.textSizeSp = size }
    }

    // "Keep screen on" (off by default): holds the window's KEEP_SCREEN_ON flag while
    // the terminal is shown.
    val keepScreenOn by settings.keepScreenOn.collectAsStateWithLifecycle()
    val hostView = LocalView.current
    DisposableEffect(hostView, keepScreenOn) {
        hostView.keepScreenOn = keepScreenOn
        onDispose { hostView.keepScreenOn = false }
    }

    // The view survives switching apps, but the soft keyboard goes away with the
    // window. Bring it back on return if it was up when the user left.
    var imeWasVisible by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        imeWasVisible = terminalView?.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val view = terminalView
        if (imeWasVisible && view != null) view.whenWindowFocused { view.showKeyboard() }
        imeWasVisible = false
    }

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
    val bellMode by rememberUpdatedState(bell)
    LaunchedEffect(manager, terminalView) {
        // Created on the first beep; the emulator in CI, for one, has no audio.
        var tone: ToneGenerator? = null
        try {
            manager.bells.collect { id ->
                if (id != manager.current.value) return@collect
                when (bellMode) {
                    BellMode.VIBRATE -> terminalView?.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    BellMode.BEEP -> {
                        if (tone == null) {
                            tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, BEEP_VOLUME) }.getOrNull()
                        }
                        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, BEEP_MS)
                    }
                    BellMode.SILENT -> Unit
                }
            }
        } finally {
            tone?.release()
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
    BackHandler(enabled = settingsOpen) { settingsOpen = false }
    // Typing goes back to the terminal when Settings closes.
    LaunchedEffect(settingsOpen) { if (!settingsOpen) terminalView?.requestFocus() }
    val openSettings = {
        terminalView?.let { view ->
            view.context.getSystemService(InputMethodManager::class.java)
                ?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        settingsOpen = true
    }

    Box(Modifier.fillMaxSize()) {
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
                    onSettings = {
                        openSettings()
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
                val closeExited by rememberUpdatedState(
                    current?.takeIf { it.exitCode != null }?.let { exited -> { manager.kill(exited.id) } },
                )
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .swipeToClose { closeExited?.invoke() },
                ) {
                    if (current != null) {
                        AndroidView(
                            factory = { context ->
                                TerminalView(context).also { view ->
                                    view.stickyModifiers = sticky
                                    view.setPadding(4.dp.px(context), 2.dp.px(context), 4.dp.px(context), 0)
                                    // The saved zoom, applied before the first layout so the pty
                                    // is sized once.
                                    settings.fontSizeSp.value?.let { view.textSizeSp = it }
                                    view.onTextSizeChanged = { settings.setFontSize(view.textSizeSp) }
                                    terminalView = view
                                    ActiveTerminal.view = java.lang.ref.WeakReference(view)
                                }
                            },
                            update = { view ->
                                // A running session talks to its process; an exited one turns
                                // Enter / Esc into restart / close (see ExitedSessionBackend).
                                val exited = current.exitCode != null
                                val shown = when (val b = view.backend) {
                                    is ExitedSessionBackend -> exited && b.sessionId == current.id
                                    is NativeSessionBackend -> !exited && b.sessionId == current.id
                                    else -> false
                                }
                                if (!shown) {
                                    val id = current.id
                                    view.backend = if (exited) {
                                        ExitedSessionBackend(
                                            id,
                                            onRestart = { manager.restart(id) },
                                            onClose = { manager.kill(id) },
                                        )
                                    } else {
                                        NativeSessionBackend(id)
                                    }
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
                }
                // Transient prompts (the OSC 52 question) get their own space below the
                // terminal, which shrinks while one is shown, so the prompt line the user
                // is typing on stays visible.
                SnackbarHost(snackbars, modifier = Modifier.fillMaxWidth()) { data ->
                    FerruxSnackbar(data, Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
                ExtraKeysRow(
                    layout = ExtraKeysLayout.of(extraKeys),
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
        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn() + slideInHorizontally { it / 6 },
            exit = fadeOut() + slideOutHorizontally { it / 6 },
        ) {
            SettingsScreen(settings, onBack = { settingsOpen = false })
        }
    }
}

private const val BEEP_VOLUME = 60
private const val BEEP_MS = 120

/** A snackbar in the terminal's palette rather than Material's inverse colours. */
@Composable
private fun FerruxSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    Snackbar(
        data,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        containerColor = TerminalColors.ExtraKeysBackground,
        contentColor = TerminalColors.Foreground,
        actionColor = TerminalColors.Accent,
        dismissActionContentColor = TerminalColors.KeyLabel,
    )
}

/**
 * Calls [onSwipe] when a single finger sweeps sideways across the terminal (not from
 * the left edge, which opens the drawer). Only observes: the terminal still gets
 * every event, so scrolling and taps keep working. [onSwipe] decides whether a swipe
 * means anything (it closes an exited session).
 */
private fun Modifier.swipeToClose(onSwipe: () -> Unit): Modifier = pointerInput(Unit) {
    val distance = SWIPE_CLOSE_DISTANCE.toPx()
    val edge = SWIPE_EDGE_WIDTH.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (down.position.x < edge) return@awaitEachGesture
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.size > 1) return@awaitEachGesture // pinch zoom
            val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if (!change.pressed) return@awaitEachGesture
            val d = change.position - down.position
            if (abs(d.x) > distance && abs(d.x) > 2 * abs(d.y)) {
                onSwipe()
                return@awaitEachGesture
            }
        }
    }
}

private val SWIPE_CLOSE_DISTANCE = 120.dp
private val SWIPE_EDGE_WIDTH = 24.dp

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

/** Runs [action] once this view's window has input focus (now, if it already has). */
private fun android.view.View.whenWindowFocused(action: () -> Unit) {
    if (hasWindowFocus()) {
        action()
        return
    }
    viewTreeObserver.addOnWindowFocusChangeListener(object : android.view.ViewTreeObserver.OnWindowFocusChangeListener {
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus) return
            viewTreeObserver.removeOnWindowFocusChangeListener(this)
            action()
        }
    })
}

private fun androidx.compose.ui.unit.Dp.px(context: android.content.Context): Int =
    (value * context.resources.displayMetrics.density).toInt()

/** "exit 3" / "signal 9" pill for a session whose process ended. */
@Composable
private fun ExitBadge(code: Int) {
    val signal = code > 128
    val n = if (signal) code - 128 else code
    val label = stringResource(if (signal) R.string.exit_badge_signal else R.string.exit_badge_code, n)
    val description = stringResource(
        if (signal) R.string.exited_description_signal else R.string.exited_description_code, n,
    )
    val color = if (code == 0) TerminalColors.Muted else TerminalColors.Error
    Text(
        label,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = FontFamily.Monospace,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
            // TalkBack reads the item as "Session 2, Exited with code 3".
            .clearAndSetSemantics { text = AnnotatedString(description) },
    )
}

@Composable
private fun SessionDrawer(
    sessions: List<SessionInfo>,
    currentId: Long,
    onSelect: (Long) -> Unit,
    onKill: (Long) -> Unit,
    onNew: () -> Unit,
    onSettings: () -> Unit,
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
                val exitCode = session.exitCode
                NavigationDrawerItem(
                    label = {
                        Text(
                            session.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (exitCode != null) TerminalColors.Muted else Color.Unspecified,
                        )
                    },
                    selected = session.id == currentId,
                    onClick = { onSelect(session.id) },
                    badge = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (exitCode != null) ExitBadge(exitCode)
                            IconButton(onClick = { onKill(session.id) }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.kill_session))
                            }
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
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.settings)) },
            icon = { Icon(painterResource(R.drawable.ic_settings), null) },
            selected = false,
            onClick = onSettings,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * The terminal view currently on screen (weakly held), for debug tooling such as the
 * CI `DebugReceiver`, which reads its selection state, identity and font size.
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
