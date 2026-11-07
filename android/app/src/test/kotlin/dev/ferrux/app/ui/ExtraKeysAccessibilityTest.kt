// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
 * switches with a checked state, glyph keys have spoken names, every key has a
 * click action, and both a touch and an accessibility click fire a key exactly once.
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

    private fun stateOf(tag: String) =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    @Test
    fun modifiersAreSwitchesWithAnnouncedState() {
        show()
        val ctrl = compose.onNodeWithContentDescription("Control")
        ctrl.assertIsToggleable().assertIsOff().assertHasClickAction()
        assertEquals(Role.Switch, ctrl.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        assertEquals("off", stateOf("key-CTRL"))

        ctrl.performSemanticsAction(SemanticsActions.OnClick)
        ctrl.assertIsOn()
        assertEquals(ModState.Once, sticky.ctrl)
        assertEquals("on for next key", stateOf("key-CTRL"))

        // Consuming the one-shot modifier turns the switch off again.
        assertEquals(TerminalKeys.MOD_CTRL, sticky.consume())
        compose.waitForIdle()
        ctrl.assertIsOff()

        sticky.ctrl = ModState.Locked
        compose.waitForIdle()
        ctrl.assertIsOn()
        assertEquals("locked", stateOf("key-CTRL"))
    }

    @Test
    fun glyphKeysHaveSpokenNames() {
        show()
        for (name in listOf("Escape", "Slash", "Minus", "Pipe", "Up arrow", "Left arrow", "Page up", "Sessions")) {
            compose.onNodeWithContentDescription(name).assertHasClickAction()
        }
    }

    @Test
    fun accessibilityClicksFireKeysOnce() {
        show()
        compose.onNodeWithTag("key-ESC").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("key-/").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithTag("key-Up").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(TerminalKeys.ESCAPE, TerminalKeys.UP), keys)
        assertEquals(listOf("/"), texts)
    }

    @Test
    fun touchFiresKeysOnce() {
        show()
        compose.onNodeWithTag("key-TAB").performClick()
        compose.onNodeWithTag("key-|").performClick()
        compose.onNodeWithTag("key-CTRL").performClick()
        compose.waitForIdle()
        assertEquals(listOf(TerminalKeys.TAB), keys)
        assertEquals(listOf("|"), texts)
        assertEquals(ModState.Once, sticky.ctrl)
    }
}
