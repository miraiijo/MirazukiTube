package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/**
 * Parental code (kids profile), as in miryo: 4 digits typed with the remote's number keys, or
 * chosen with Up / Down (0→9) and validated with Right / OK (Left goes back one). Back erases the
 * last digit, then cancels. [submit] gets the 4 digits: an error to show (the boxes empty again),
 * or null once accepted (the caller closes the dialog). [key] restarts the boxes empty (the same
 * dialog asking for the code again, to confirm it).
 */
@Composable
fun PinDialog(title: String, hint: String, key: Any, submit: suspend (String) -> String?, onDismiss: () -> Unit) {
    var pin by remember(key) { mutableStateOf("") }
    // The digit being chosen with Up / Down in the active box (shown until validated).
    var currentDigit by remember(key) { mutableStateOf<Int?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    // The check hashes off the main thread: keys are ignored until it answers.
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun type(digit: Int) {
        currentDigit = null
        error = null
        val next = pin + ('0' + digit)
        pin = next
        if (next.length < PIN_LENGTH) return
        checking = true
        scope.launch {
            val failed = try {
                submit(next)
            } finally {
                checking = false
            }
            if (failed != null) {
                error = failed
                pin = ""
            }
        }
    }
    // Back comes through the dismiss request: it erases first, closes only once empty.
    MiryoDialog(onDismissRequest = {
        if (!checking) {
            when {
                currentDigit != null -> currentDigit = null
                pin.isNotEmpty() -> pin = pin.dropLast(1)
                else -> onDismiss()
            }
        }
    }) {
        val field = remember { FocusRequester() }
        SafeDialog(PIN_WIDTH, Modifier.background(Palette.Panel, DialogShape).padding(28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary)
            Text(error ?: hint, color = if (error != null) Palette.Danger else Palette.TextSecondary, fontSize = 14.sp, lineHeight = 19.sp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .focusRequester(field)
                    .onPreviewKeyEvent { event ->
                        val native = event.nativeKeyEvent
                        if (native.action != android.view.KeyEvent.ACTION_DOWN || checking) return@onPreviewKeyEvent false
                        val code = native.keyCode
                        val full = pin.length >= PIN_LENGTH
                        when {
                            code in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                                if (!full) type(code - android.view.KeyEvent.KEYCODE_0)
                                true
                            }
                            code in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> {
                                if (!full) type(code - android.view.KeyEvent.KEYCODE_NUMPAD_0)
                                true
                            }
                            code == android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                if (!full) currentDigit = ((currentDigit ?: -1) + 1) % 10
                                error = null
                                true
                            }
                            code == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                if (!full) currentDigit = ((currentDigit ?: 1) + 9) % 10
                                error = null
                                true
                            }
                            code == android.view.KeyEvent.KEYCODE_DPAD_RIGHT || code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                code == android.view.KeyEvent.KEYCODE_ENTER || code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                if (!full && native.repeatCount == 0) type(currentDigit ?: 0)
                                true
                            }
                            code == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                if (pin.isNotEmpty()) {
                                    currentDigit = pin.last() - '0'
                                    pin = pin.dropLast(1)
                                } else {
                                    currentDigit = null
                                }
                                error = null
                                true
                            }
                            else -> false
                        }
                    }
                    .focusable(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            ) {
                repeat(PIN_LENGTH) { i ->
                    val active = i == pin.length
                    Box(
                        Modifier
                            .size(width = 46.dp, height = 56.dp)
                            .background(Palette.ControlIdle, BOX_SHAPE)
                            .then(if (active) Modifier.border(2.dp, Palette.FocusRing, BOX_SHAPE) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            i < pin.length -> Text("•", color = Palette.TextPrimary, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                            active && currentDigit != null -> Text(currentDigit.toString(), color = Palette.TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            Text(
                "Chiffres de la télécommande, ou ↑ ↓ puis OK · Retour efface",
                color = Palette.TextSecondary,
                fontSize = Dimens.TextMin,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            field.tryRequestFocus()
        }
    }
}

private const val PIN_LENGTH = 4
private val PIN_WIDTH = 420.dp
private val BOX_SHAPE = RoundedCornerShape(12.dp)
