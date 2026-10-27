@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.miraiijo.miryotube.ui

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text

/** Warm-up before timing (the first frames build the layers), then the measured time. */
private const val TEST_WARMUP_NS = 600_000_000L
private const val TEST_DURATION_NS = 3_000_000_000L
private val TEST_CARD_SHAPE = RoundedCornerShape(14.dp)

/**
 * The fluidity test, full screen: three seconds of what the screens ask from the GPU (a picture
 * that zooms in a masked layer, cards with shadows sliding, a lit edge), the frames timed meanwhile.
 * [onResult] gets the share of missed frames (%), or null when Retour stopped it. The same test in
 * miryo, miryo iptv and miryo tube.
 */
@Composable
fun FluidityTest(onResult: (Int?) -> Unit) {
    val result by rememberUpdatedState(onResult)
    Dialog(onDismissRequest = { result(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { result(null) }
        val context = LocalContext.current
        val transition = rememberInfiniteTransition(label = "fluidity-test")
        val zoom by transition.animateFloat(1f, 1.15f, infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = "zoom")
        val slide by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Reverse), label = "slide")
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B10))) {
            // The « artwork »: full screen, zooming, melted by two masks (like the heroes).
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        scaleX = zoom
                        scaleY = zoom
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.5f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.3f), 0.5f to Color.Black), blendMode = BlendMode.DstIn)
                    }
                    .background(Brush.linearGradient(listOf(Color(0xFF2B1E4A), Color(0xFF8A4F7D), Color(0xFFE0A05A)))),
            ) {
                Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent))))
            }
            // A row of cards sliding, with shadows and a lit edge.
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 48.dp, bottom = 56.dp)
                    .graphicsLayer { translationX = -slide * 360.dp.toPx() },
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                repeat(8) { i ->
                    val hue = Color.hsv((i * 40f) % 360f, 0.45f, 0.75f)
                    Box(
                        Modifier
                            .size(220.dp, 124.dp)
                            .shadow(10.dp, TEST_CARD_SHAPE)
                            .background(Brush.verticalGradient(listOf(hue, hue.copy(alpha = 0.55f))), TEST_CARD_SHAPE)
                            .border(1.dp, Color.White.copy(alpha = 0.3f), TEST_CARD_SHAPE),
                    )
                }
            }
            Column(Modifier.padding(start = 64.dp, top = 56.dp)) {
                Text("Test de fluidité", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Trois secondes d'animations, comme sur l'Accueil, pour mesurer ce que ta box peut afficher. Ne touche à rien.",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 15.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        LaunchedEffect(Unit) { result(measureMissedFrames(context)) }
    }
}

/** Times the frames for [TEST_DURATION_NS]: the share (%) of screen refreshes that got no new frame. */
private suspend fun measureMissedFrames(context: Context): Int {
    @Suppress("DEPRECATION")
    val rate = context.getSystemService(WindowManager::class.java)?.defaultDisplay?.refreshRate?.takeIf { it in 20f..240f } ?: 60f
    val period = 1_000_000_000.0 / rate
    val start = withFrameNanos { it }
    var last = start
    while (last - start < TEST_WARMUP_NS) last = withFrameNanos { it }
    val from = last
    var missed = 0L
    while (last - from < TEST_DURATION_NS) {
        val now = withFrameNanos { it }
        // 2 refreshes between two frames: one missed, and so on.
        missed += (Math.round((now - last) / period) - 1).coerceAtLeast(0)
        last = now
    }
    val refreshes = ((last - from) / period).coerceAtLeast(1.0)
    return (missed * 100 / refreshes).toInt().coerceIn(0, 100)
}
