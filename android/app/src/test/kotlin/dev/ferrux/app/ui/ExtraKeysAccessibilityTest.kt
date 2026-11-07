// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import dev.ferrux.terminal.TerminalKeys
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The extra keys as accessibility services see them (issue #13): CTRL/ALT are
 * switches with a checked state, every key has a click action, and an
 * accessibility click (TalkBack double tap, Switch Access) fires a key exactly
 * once, like a touch does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExtraKeysAccessibilityTest {
    @get:Rule
    val compose = createComposeRule()

    private val sticky = StickyModifierState()
    private val keys = mutableListOf<Int>()
    private val texts = mutableListOf<String>()

    private fun show() = compose.setContent {
        ExtraKeysRow(
            layout = ExtraKeysLayout.Default,
            sticky = sticky,
            onKey = { keys += it },
            onText = { texts += it },
            onAction = {},
        )
    }

    @Test
    fun modifiersAreSwitchesWithState() {
        show()
        val ctrl = compose.onNodeWithContentDescription("CTRL")
        ctrl.assertIsToggleable().assertIsOff().assertHasClickAction()
        ctrl.performSemanticsAction(SemanticsActions.OnClick)
        ctrl.assertIsOn()
        assertEquals(ModState.Once, sticky.ctrl)
        val state = ctrl.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)
        assertEquals("on for next key", state)
        // Consuming the one-shot modifier turns the switch off again.
        assertEquals(TerminalKeys.MOD_CTRL, sticky.consume())
        compose.waitForIdle()
        ctrl.assertIsOff()
    }

    @Test
    fun accessibilityClicksFireKeysOnce() {
        show()
        compose.onNodeWithContentDescription("ESC").assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithContentDescription("/").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(TerminalKeys.ESCAPE), keys)
        assertEquals(listOf("/"), texts)
    }

    @Test
    fun touchFiresKeysOnce() {
        show()
        compose.onNodeWithContentDescription("TAB").performClick()
        compose.onNodeWithContentDescription("Up").performClick()
        compose.waitForIdle()
        assertEquals(listOf(TerminalKeys.TAB, TerminalKeys.UP), keys)
    }
}
