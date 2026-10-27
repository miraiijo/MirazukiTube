@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.miraiijo.miryotube.ui

import android.content.Context
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Warm-up before timing (the first frames build the layers and upload the pictures), then the measured time. */
private const val TEST_WARMUP_NS = 800_000_000L
private const val TEST_DURATION_NS = 5_000_000_000L
/** One step to the right every so often (a held ▶), a few steps per row, then the row below. */
private const val TEST_STEP_MS = 260L
private const val TEST_STEPS_PER_ROW = 5
private const val TEST_ROWS = 4
private const val TEST_CARDS = 24
private const val TEST_POSTERS = 14
private val TEST_CARD_SHAPE = RoundedCornerShape(12.dp)
private val TEST_PILL_SHAPE = RoundedCornerShape(24.dp)
private val TEST_CARD_WIDTH = 150.dp
private val TEST_SIDE = 64.dp

/**
 * The fluidity test, full screen: five seconds of what a real screen asks from the box, the frames
 * timed meanwhile. Rows of posters (real pictures, uploaded to the GPU) move one card to the right
 * at a time like a held ▶, then go down a row; the focused card grows with its ring and shadow, its
 * title sheet is rewritten at each step, a glass pill sits on top and the artwork behind zooms in a
 * masked layer (as on Complètes screens). So the processor (composing, laying out the rows) and the
 * GPU both work, not only a layer sliding. [onResult] gets the share of missed frames (%), or null
 * when Retour stopped it. The same test in miryo, miryo iptv and miryo tube.
 */
@Composable
fun FluidityTest(onResult: (Int?) -> Unit) {
    val result by rememberUpdatedState(onResult)
    Dialog(onDismissRequest = { result(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BackHandler { result(null) }
        val context = LocalContext.current
        val posters = remember { List(TEST_POSTERS) { testPoster(it) } }
        val column = rememberLazyListState()
        val rows = remember { List(TEST_ROWS) { LazyListState() } }
        var row by remember { mutableIntStateOf(0) }
        var card by remember { mutableIntStateOf(0) }
        val transition = rememberInfiniteTransition(label = "fluidity-test")
        val zoom by transition.animateFloat(1f, 1.12f, infiniteRepeatable(tween(2500), RepeatMode.Reverse), label = "zoom")
        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    // The page's background, as the apps draw it (baked once, copied each frame).
                    val w = size.width.toInt()
                    val h = size.height.toInt()
                    if (w <= 0 || h <= 0) return@drawWithCache onDrawBehind { }
                    val bitmap = ImageBitmap(w, h)
                    CanvasDrawScope().draw(this, layoutDirection, Canvas(bitmap), size) {
                        drawRect(Brush.linearGradient(listOf(Color(0xFF0B0B10), Color(0xFF1A1530), Color(0xFF0B0B10))))
                        drawRect(Brush.radialGradient(listOf(Color(0x335B4BB7), Color.Transparent), center = Offset(size.width, 0f), radius = 1400f))
                    }
                    onDrawBehind { drawImage(bitmap, blendMode = BlendMode.Src) }
                },
        ) {
            // The focused title's artwork: top right, zooming, melted by two masks (Complètes heroes).
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxWidth(0.62f)
                    .aspectRatio(16f / 9f)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        scaleX = zoom
                        scaleY = zoom
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0.45f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                        drawRect(Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black), blendMode = BlendMode.DstIn)
                    },
            ) {
                Image(posters[(row * 3 + card) % TEST_POSTERS], null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = TEST_SIDE, end = TEST_SIDE, top = 40.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text("Test de fluidité", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "Cinq secondes comme sur tes écrans (affiches, défilement, verre) pour mesurer ce que ta box peut afficher. Ne touche à rien.",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 6.dp).width(520.dp),
                        )
                    }
                    // A glass pill, like the status pill (score · météo · heure).
                    Text(
                        "Rangée ${row + 1} · ${card + 1}",
                        color = Color.White,
                        fontSize = 15.sp,
                        modifier = Modifier
                            .shadow(8.dp, TEST_PILL_SHAPE)
                            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f))), TEST_PILL_SHAPE)
                            .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0.1f))), TEST_PILL_SHAPE)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                    )
                }
                // The focused title's sheet, rewritten at each step.
                Column(Modifier.padding(start = TEST_SIDE, top = 14.dp).height(64.dp)) {
                    Text("Titre ${row * TEST_CARDS + card + 1}", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        "2024 · ${(card % 3) + 1} h ${(row * 7 + card * 11) % 60} min · Aventure, Drame · ★ 7,${(row + card) % 10}",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
                LazyColumn(
                    state = column,
                    userScrollEnabled = false,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(bottom = 160.dp),
                ) {
                    items(TEST_ROWS) { r ->
                        Column {
                            Text("Rangée ${r + 1}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = TEST_SIDE, top = 10.dp))
                            LazyRow(
                                state = rows[r],
                                userScrollEnabled = false,
                                contentPadding = PaddingValues(horizontal = TEST_SIDE, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                items(TEST_CARDS) { i ->
                                    TestCard(posters[(i + r * 5) % TEST_POSTERS], "Titre ${r * TEST_CARDS + i + 1}", focused = r == row && i == card)
                                }
                            }
                        }
                    }
                }
            }
        }
        // A held ▶ along a row, then ▼ to the next one: the rows scroll and recompose as on the real screens.
        LaunchedEffect(Unit) {
            while (true) {
                delay(TEST_STEP_MS)
                if (card < TEST_STEPS_PER_ROW) {
                    card++
                    val state = rows[row]
                    launch { state.animateScrollToItem((card - 1).coerceAtLeast(0)) }
                } else {
                    row = (row + 1) % TEST_ROWS
                    card = 0
                    val state = rows[row]
                    launch { state.scrollToItem(0) }
                    launch { column.animateScrollToItem(row) }
                }
            }
        }
        LaunchedEffect(Unit) { result(measureMissedFrames(context)) }
    }
}

/** A poster of the test's rows: picture, ring and shadow when focused, title and a line below. */
@Composable
private fun TestCard(poster: ImageBitmap, title: String, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "test-card")
    Column(Modifier.width(TEST_CARD_WIDTH).graphicsLayer { scaleX = scale; scaleY = scale }) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .then(if (focused) Modifier.shadow(18.dp, TEST_CARD_SHAPE) else Modifier)
                .clip(TEST_CARD_SHAPE)
                .border(if (focused) 3.dp else 1.dp, Color.White.copy(alpha = if (focused) 0.9f else 0.25f), TEST_CARD_SHAPE),
        ) {
            Image(poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Spacer(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))),
            )
        }
        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text("2024 · ★ 7,${title.length % 10}", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1)
    }
}

/** A poster-sized picture (240 × 360 px) of its own colours: a real bitmap for the GPU to upload and draw. */
private fun testPoster(seed: Int): ImageBitmap {
    val bitmap = ImageBitmap(240, 360)
    val hue = (seed * 37f) % 360f
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(240f, 360f)) {
        drawRect(Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.35f), Color.hsv((hue + 60f) % 360f, 0.6f, 0.85f))))
        repeat(6) { i ->
            drawCircle(Color.White.copy(alpha = 0.06f + 0.03f * i), radius = 26f + 16f * i, center = Offset(36f * i + 20f, 50f + 48f * i))
        }
    }
    return bitmap
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
