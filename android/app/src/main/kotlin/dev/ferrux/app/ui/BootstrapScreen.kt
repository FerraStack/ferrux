// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.ferrux.app.BootstrapState
import dev.ferrux.app.R
import java.util.Locale

/**
 * First-run setup: explains what will be downloaded, asks before downloading
 * (RESEARCH.md §2.2: no code download without a user action), shows progress,
 * and offers retry or Android's own shell on failure.
 */
@Composable
fun BootstrapScreen(
    state: BootstrapState,
    onInstall: () -> Unit,
    onUseSystemShell: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(TerminalColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.bootstrap_title),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            when (state) {
                BootstrapState.Idle -> {
                    BodyText(stringResource(R.string.bootstrap_intro))
                    BodyText(stringResource(R.string.bootstrap_source))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bootstrap_install))
                    }
                    TextButton(onClick = onUseSystemShell, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bootstrap_use_system_shell))
                    }
                }
                is BootstrapState.Failed -> {
                    BodyText(stringResource(R.string.bootstrap_failed))
                    Text(
                        state.message,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bootstrap_retry))
                    }
                    TextButton(onClick = onUseSystemShell, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.bootstrap_use_system_shell))
                    }
                }
                else -> Progress(state)
            }
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
}

@Composable
private fun Progress(state: BootstrapState) {
    val (label, fraction, detail) = when (state) {
        is BootstrapState.Downloading -> Triple(
            stringResource(R.string.bootstrap_downloading),
            if (state.total > 0) state.done.toFloat() / state.total else null,
            if (state.total > 0) "${mb(state.done)} / ${mb(state.total)}" else mb(state.done),
        )
        is BootstrapState.Verifying -> Triple(
            stringResource(R.string.bootstrap_verifying),
            if (state.total > 0) state.done.toFloat() / state.total else null,
            "",
        )
        is BootstrapState.Extracting -> Triple(
            stringResource(R.string.bootstrap_extracting),
            if (state.total > 0) state.done.toFloat() / state.total else null,
            stringResource(R.string.bootstrap_files, state.done, state.total),
        )
        else -> Triple(stringResource(R.string.bootstrap_finishing), null, "")
    }
    BodyText(label)
    if (fraction != null) {
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (detail.isNotEmpty()) {
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun mb(bytes: Long): String = String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
