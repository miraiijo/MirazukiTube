package dev.miraiijo.lumentube.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import dev.miraiijo.lumentube.R

/**
 * Start-up animation, right after the system splash (which shows the mark alone): « miryo » is
 * written from left to right, the logo makes room for « tube », which fades in, then the dot drops in, in
 * the app's colour. The logo stays centred while it grows. It is drawn over the app, which loads
 * underneath, and fades out once [ready] says there is something to show (at most [maxMs] after
 * it started). About 1.4 s: the Pop starts the app in that time anyway.
 */
@Composable
fun BrandIntro(ready: () -> Boolean, maxMs: Long, onDone: () -> Unit) {
    val word = remember { Animatable(0f) }
    val suffix = remember { Animatable(0f) }
    val dot = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    val isReady = rememberUpdatedState(ready)
    LaunchedEffect(Unit) {
        val startedAt = android.os.SystemClock.uptimeMillis()
        word.animateTo(1f, tween(WORD_MS, easing = FastOutSlowInEasing))
        suffix.animateTo(1f, tween(SUFFIX_MS, easing = FastOutSlowInEasing))
        dot.animateTo(1f, tween(DOT_MS, easing = BackEasing))
        delay(HOLD_MS)
        // Then held until the app has something to show (its own state, polled), never past maxMs.
        val left = maxMs - (android.os.SystemClock.uptimeMillis() - startedAt)
        if (left > 0) withTimeoutOrNull(left) { while (!isReady.value()) delay(READY_POLL_MS) }
        fade.animateTo(0f, tween(FADE_MS, easing = LinearEasing))
        onDone()
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(SplashNight),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(LOGO_HEIGHT * (Logo.WIDTH / Logo.HEIGHT), LOGO_HEIGHT).graphicsLayer {
                // Centred on what is visible so far: the mark, then the mark and the growing word.
                var right = Logo.MARK_END + (Logo.WORD_END - Logo.MARK_END) * word.value
                right += (Logo.SUFFIX_END - Logo.WORD_END) * suffix.value
                right += (Logo.DOT_END - Logo.SUFFIX_END) * dot.value.coerceIn(0f, 1f)
                translationX = (Logo.WIDTH / 2 - (Logo.MARK_START + right) / 2) * size.width / Logo.WIDTH
            },
        ) {
            Image(painterResource(R.drawable.intro_mark), null, Modifier.matchParentSize())
            Image(
                painterResource(R.drawable.intro_word), null,
                Modifier.matchParentSize().drawWithContent {
                    val k = size.width / Logo.WIDTH
                    val edge = Logo.WORD_START + (Logo.WORD_END + 2 - Logo.WORD_START) * word.value
                    clipRect(right = edge * k) { this@drawWithContent.drawContent() }
                },
            )
            Image(
                painterResource(R.drawable.intro_suffix), null,
                // Never seen while it comes out of « miryo »: the logo makes room for it, then it
                // fades in, whole, in its place.
                Modifier.matchParentSize().graphicsLayer { alpha = ((suffix.value - SUFFIX_FADE_FROM) / (1 - SUFFIX_FADE_FROM)).coerceIn(0f, 1f) },
            )
            Image(
                painterResource(R.drawable.intro_dot), null,
                Modifier.matchParentSize().graphicsLayer { alpha = (dot.value * 2).coerceIn(0f, 1f) }.drawWithContent {
                    val k = size.width / Logo.WIDTH
                    val p = dot.value
                    withTransform({
                        translate(top = (1 - p.coerceIn(0f, 1f)) * -DOT_DROP * k)
                        scale(p.coerceAtLeast(0f), p.coerceAtLeast(0f), Offset((Logo.DOT_START + Logo.DOT_END) / 2 * k, Logo.BASELINE * k))
                    }) { this@drawWithContent.drawContent() }
                },
            )
        }
    }
}

/** The intro_* drawables' viewport (generated with the logo, Outfit outlined): x positions of each part. */
private object Logo {
    const val WIDTH = 230f
    const val HEIGHT = 64f
    const val BASELINE = 42f
    const val MARK_START = 12.96f
    const val MARK_END = 51.04f
    const val WORD_START = 66f
    const val WORD_END = 146.63f
    const val SUFFIX_END = 216.48f
    const val DOT_START = 217.48f
    const val DOT_END = 226.47f
}

/** The splash's night (splash_background), so the system splash hands over without a flash. */
private val SplashNight = Color(0xFF10121A)
private val LOGO_HEIGHT = 112.dp
private const val WORD_MS = 420
private const val SUFFIX_MS = 460
private const val DOT_MS = 300
/** Part of the suffix's time spent making room before it fades in. */
private const val SUFFIX_FADE_FROM = 0.45f
private const val HOLD_MS = 200L
private const val READY_POLL_MS = 40L
private const val FADE_MS = 220
/** How far above its place the dot starts, in viewport units. */
private const val DOT_DROP = 7f
/** Ease-out with a little overshoot: the dot lands with a small bounce. */
private val BackEasing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
