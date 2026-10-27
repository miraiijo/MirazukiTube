@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import dev.miraiijo.miryotube.data.ChatMessage
import dev.miraiijo.miryotube.data.YouTube

/** « Taille du chat »: small, normal, large (sp of the messages). */
val CHAT_SIZES = listOf("Petite" to 14, "Normale" to 16, "Grande" to 20)

/**
 * A live's chat, read only, in a column on the right: the last messages, the newest at the bottom.
 * Never focusable (the remote stays on the video). Nothing is ever sent from the box.
 */
@Composable
fun LiveChatPanel(chatKey: String, size: Int, modifier: Modifier = Modifier) {
    var messages by remember(chatKey) { mutableStateOf(emptyList<ChatMessage>()) }
    var ended by remember(chatKey) { mutableStateOf(false) }
    LaunchedEffect(chatKey) {
        runCatching {
            YouTube.liveChat(chatKey).collect { message ->
                messages = (messages.filterNot { it.id == message.id } + message).takeLast(CHAT_KEPT)
            }
        }
        ended = true
    }
    val fontSize = CHAT_SIZES.getOrNull(size)?.second ?: CHAT_SIZES[1].second
    val safe = LocalSafe.current
    Column(
        modifier
            .width(CHAT_WIDTH)
            .fillMaxHeight()
            .background(Brush.horizontalGradient(0f to Color.Transparent, 0.12f to Color.Black.copy(alpha = 0.55f), 1f to Color.Black.copy(alpha = 0.7f)))
            .padding(start = 28.dp, end = safe.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr), top = safe.calculateTopPadding(), bottom = safe.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Chat du direct", fontSize = Dimens.TextMin, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.6f))
        when {
            messages.isEmpty() && ended -> Text("Le chat n’est pas disponible.", fontSize = fontSize.sp, color = Color.White.copy(alpha = 0.7f))
            messages.isEmpty() -> Text("Chargement du chat…", fontSize = fontSize.sp, color = Color.White.copy(alpha = 0.7f))
        }
        // The newest at the bottom: as many as fit, the oldest go off the top. Never scrolled by hand.
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true,
            userScrollEnabled = false,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages.asReversed(), key = { it.id }) { message ->
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = CHAT_AUTHOR)) { append(message.author) }
                        append("  ")
                        append(message.text)
                    },
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.3f).sp,
                    color = Color.White,
                    maxLines = 4,
                )
            }
        }
    }
}

private val CHAT_WIDTH = 360.dp
private val CHAT_AUTHOR = Color(0xFFB9B4FF)
private const val CHAT_KEPT = 60
