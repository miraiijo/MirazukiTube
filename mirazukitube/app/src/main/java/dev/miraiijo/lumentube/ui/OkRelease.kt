package dev.miraiijo.lumentube.ui

import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * A long OK never clicks twice. The long press fires on the key's first repeat (≈ 0.5 s) and
 * usually opens a menu that takes the focus; the release, still to come, then landed on the
 * menu's first button and clicked it. Here the press is closed right after the long press (a
 * release sent to the element that was pressed, which only resets it), and the rest of the
 * held key — further repeats and the real release — is dropped.
 * Menus in their own window (Dialog) only count presses started in them: see okPressStartsHere.
 */
object OkRelease {
    private var held = false

    fun dispatch(event: KeyEvent, deliver: (KeyEvent) -> Boolean): Boolean {
        if (event.keyCode !in OK) return deliver(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> when {
                event.repeatCount == 0 -> held = false
                held -> return true
                event.repeatCount == 1 -> {
                    held = true
                    val handled = deliver(event)
                    deliver(KeyEvent.changeAction(event, KeyEvent.ACTION_UP))
                    return handled
                }
            }
            KeyEvent.ACTION_UP -> if (held) {
                held = false
                return true
            }
        }
        return deliver(event)
    }

    private val OK = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
}

/**
 * In a window of its own (a Dialog opened by a long OK): only OK presses that started here count.
 * The release (and repeats) of the press that opened it would otherwise click its first button.
 */
fun Modifier.okPressStartsHere(): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    onPreviewKeyEvent { event ->
        val code = event.nativeKeyEvent.keyCode
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER && code != KeyEvent.KEYCODE_NUMPAD_ENTER && code != KeyEvent.KEYCODE_BUTTON_A) return@onPreviewKeyEvent false
        when (event.nativeKeyEvent.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.nativeKeyEvent.repeatCount == 0) pressed = true
                !pressed
            }
            KeyEvent.ACTION_UP -> {
                val swallow = !pressed
                pressed = false
                swallow
            }
            else -> false
        }
    }
}
