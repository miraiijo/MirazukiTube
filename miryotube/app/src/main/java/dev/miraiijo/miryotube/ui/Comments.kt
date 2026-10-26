@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import dev.miraiijo.miryotube.data.Comment
import dev.miraiijo.miryotube.data.YouTube
import kotlinx.coroutines.launch

/** A comment's replies: loaded so far, key of the next page, loading. */
private data class Replies(val items: List<Comment> = emptyList(), val next: String? = null, val loading: Boolean = true)

/** One line of the panel's list. */
private sealed interface Line {
    val key: String

    data class Top(val comment: Comment, val expanded: Boolean) : Line {
        override val key get() = "c:${comment.id}"
    }

    data class Reply(val comment: Comment) : Line {
        override val key get() = "r:${comment.id}"
    }

    data class MoreReplies(val parentId: String) : Line {
        override val key get() = "more:$parentId"
    }

    data class Loading(override val key: String) : Line

    data class Message(val text: String) : Line {
        override val key get() = "msg"
    }
}

/**
 * Nuvio-like side panel (dark, 40 % of the width) with the video's comments: avatar, author,
 * relative date, text, likes, replies. OK on a comment shows / hides its replies; the next page
 * loads when the end of the list comes near. Back (handled by the player) closes it.
 */
@Composable
fun CommentsPanel(commentsKey: String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var comments by remember(commentsKey) { mutableStateOf(emptyList<Comment>()) }
    var next by remember(commentsKey) { mutableStateOf<String?>(commentsKey) }
    var loading by remember(commentsKey) { mutableStateOf(false) }
    var failed by remember(commentsKey) { mutableStateOf(false) }
    var replies by remember(commentsKey) { mutableStateOf(emptyMap<String, Replies>()) }
    var expanded by remember(commentsKey) { mutableStateOf(emptySet<String>()) }
    val first = remember { FocusRequester() }

    fun loadMore() {
        val key = next ?: return
        if (loading) return
        loading = true
        scope.launch {
            val page = YouTube.comments(key)
            if (page == null) {
                failed = comments.isEmpty()
                next = null
            } else {
                comments = (comments + page.comments).distinctBy { it.id }
                next = page.next
            }
            loading = false
        }
    }

    fun loadReplies(parent: Comment, key: String) {
        val current = replies[parent.id] ?: Replies()
        replies = replies + (parent.id to current.copy(loading = true))
        scope.launch {
            val page = YouTube.comments(key)
            val now = replies[parent.id] ?: Replies()
            replies = replies + (parent.id to Replies(
                items = (now.items + page?.comments.orEmpty()).distinctBy { it.id },
                next = page?.next,
                loading = false,
            ))
        }
    }

    fun toggle(comment: Comment) {
        val key = comment.repliesKey ?: return
        if (comment.id in expanded) {
            expanded = expanded - comment.id
        } else {
            expanded = expanded + comment.id
            if (comment.id !in replies) loadReplies(comment, key)
        }
    }

    LaunchedEffect(commentsKey) { loadMore() }

    val lines = buildList {
        comments.forEach { comment ->
            val open = comment.id in expanded
            add(Line.Top(comment, open))
            if (open) {
                val r = replies[comment.id]
                r?.items?.forEach { add(Line.Reply(it)) }
                when {
                    r == null || r.loading -> add(Line.Loading("loading:${comment.id}"))
                    r.next != null -> add(Line.MoreReplies(comment.id))
                }
            }
        }
        when {
            loading -> add(Line.Loading("loading"))
            failed -> add(Line.Message("Impossible de charger les commentaires."))
            comments.isEmpty() -> add(Line.Message("Aucun commentaire."))
        }
    }

    // To the screen's edges, its content in the safe zone (the last comment above the bottom inset).
    SafeSidePanel(
        PANEL_WIDTH,
        modifier
            // The focus stays in the panel until Back closes it.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) { bottom ->
        Text("Commentaires", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = bottom,
        ) {
            itemsIndexed(lines, key = { _, line -> line.key }) { index, line ->
                // The end of the list is near: the next page.
                val nearEnd = { if (index >= lines.size - 4) loadMore() }
                when (line) {
                    is Line.Top -> CommentRow(
                        line.comment,
                        reply = false,
                        expanded = line.expanded,
                        modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).onFocusChanged { if (it.isFocused) nearEnd() },
                        onClick = { toggle(line.comment) },
                    )
                    is Line.Reply -> CommentRow(
                        line.comment,
                        reply = true,
                        expanded = false,
                        modifier = Modifier.onFocusChanged { if (it.isFocused) nearEnd() },
                        onClick = {},
                    )
                    is Line.MoreReplies -> PanelButton(
                        "Plus de réponses",
                        Modifier.padding(start = REPLY_INDENT).onFocusChanged { if (it.isFocused) nearEnd() },
                    ) {
                        val parent = comments.firstOrNull { it.id == line.parentId }
                        val key = replies[line.parentId]?.next
                        if (parent != null && key != null) loadReplies(parent, key)
                    }
                    // Focusable as the first line: the panel holds the focus while the first page loads
                    // (the button that opened it is gone).
                    is Line.Loading -> Text(
                        "Chargement…",
                        fontSize = 14.sp,
                        color = Palette.TextSecondary,
                        modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).focusable(index == 0).padding(12.dp),
                    )
                    // Focusable, so the panel holds the focus (and Back) even without comments.
                    is Line.Message -> Text(
                        line.text,
                        fontSize = 15.sp,
                        color = Palette.TextSecondary,
                        modifier = (if (index == 0) Modifier.focusRequester(first) else Modifier).focusable().padding(12.dp),
                    )
                }
            }
        }
    }

    // The first line takes the focus as soon as there is one (first comment, or the message).
    val firstKey = lines.firstOrNull()?.takeIf { it is Line.Top || it is Line.Message || it is Line.Loading }?.key
    LaunchedEffect(firstKey) {
        if (firstKey == null) return@LaunchedEffect
        withFrameNanos { }
        first.tryRequestFocus()
    }
}

@Composable
private fun CommentRow(comment: Comment, reply: Boolean, expanded: Boolean, modifier: Modifier, onClick: () -> Unit) {
    // A long comment shows its first lines; OK shows it whole (then OK does the usual: replies).
    var whole by remember(comment.id) { mutableStateOf(false) }
    var cut by remember(comment.id) { mutableStateOf(false) }
    Surface(
        onClick = { if (cut && !whole) whole = true else onClick() },
        modifier = modifier.fillMaxWidth().padding(start = if (reply) REPLY_INDENT else 0.dp),
        shape = ClickableSurfaceDefaults.shape(shape = ControlShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = Palette.Focus,
            pressedContentColor = Palette.OnFocus,
        ),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val avatarSize = if (reply) 28.dp else 36.dp
            val (from, to) = AVATAR_GRADIENTS[Math.floorMod(comment.author.hashCode(), AVATAR_GRADIENTS.size)]
            Box(
                Modifier.size(avatarSize).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(from, to))),
                contentAlignment = Alignment.Center,
            ) {
                if (comment.avatar != null) {
                    GlideImage(model = avatarUrl(comment.avatar), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(comment.author.removePrefix("@").take(1).uppercase(), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val secondary = LocalContentColor.current.copy(alpha = 0.65f)
                // A long author name is cut, never the date after it.
                KeepEndLine(comment.author, comment.published, fontWeight = FontWeight.Medium, color = secondary, separator = "  ·  ")
                Text(
                    comment.text,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    // Whole: still capped, so the focused line always fits on screen (even at 120 %).
                    maxLines = if (whole) COMMENT_WHOLE_LINES else COMMENT_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!whole) cut = it.hasVisualOverflow },
                )
                if (comment.likes != null || comment.replies != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        if (comment.likes != null) {
                            Icon(MiryoIcon.THUMB_UP_OUTLINE, size = 14.dp, color = secondary)
                            Spacer(Modifier.width(6.dp))
                            Text(comment.likes, fontSize = 13.sp, color = secondary)
                            Spacer(Modifier.width(16.dp))
                        }
                        if (comment.replies != null) {
                            Text(comment.replies + if (expanded) "  ▴" else "  ▾", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = secondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PanelButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(shape = ControlShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.TextSecondary,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Palette.OnFocus,
        ),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
    }
}

/** A bit narrower than the settings panel (the redesign): more of the video stays visible. */
private val PANEL_WIDTH = 384.dp
/** No photo: the initial in white on one of these gradients (picked from the author). */
private val AVATAR_GRADIENTS = listOf(
    Color(0xFFF58A6B) to Color(0xFFE0466F),
    Color(0xFFF2B45A) to Color(0xFFD9772E),
    Color(0xFF7C7CF5) to Color(0xFF3F64F0),
    Color(0xFF6CCB8E) to Color(0xFF2F9E73),
    Color(0xFFB08CF0) to Color(0xFF7A4FD6),
)
private val REPLY_INDENT = 40.dp
private const val COMMENT_LINES = 6
private const val COMMENT_WHOLE_LINES = 14
