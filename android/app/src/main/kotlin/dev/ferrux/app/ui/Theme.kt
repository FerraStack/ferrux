// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val FerruxColors = darkColorScheme(
    primary = Color(0xFFE8762C),
    onPrimary = Color(0xFF1A0C00),
    background = Color(0xFF101214),
    surface = Color(0xFF181B1E),
    surfaceContainer = Color(0xFF1E2226),
    secondaryContainer = Color(0xFF3A2A1E),
)

@Composable
fun FerruxTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FerruxColors, content = content)
}
