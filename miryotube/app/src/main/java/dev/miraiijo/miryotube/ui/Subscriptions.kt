@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.miraiijo.miryotube.Pastille
import dev.miraiijo.miryotube.data.Folder
import dev.miraiijo.miryotube.data.SubsFilter

/**
 * The top of Abonnements: the channels' pictures, with a pastille counting their new videos since
 * last looked at (OK: that channel only), then the filters: « Que du nouveau », the duration, the
 * folders. All of it on the box, nothing sent to YouTube.
 */
@Composable
fun SubscriptionsHeader(
    pastilles: List<Pastille>,
    filter: SubsFilter,
    folders: List<Folder>,
    onPastille: (Pastille) -> Unit,
    onFilter: (SubsFilter) -> Unit,
    first: FocusRequester,
) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pastilles.isNotEmpty()) {
            RowScroll {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Dimens.Gutter, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(PASTILLE_GAP),
                ) {
                    itemsIndexed(pastilles, key = { _, p -> p.channelId }) { index, p ->
                        PastilleItem(p, selected = filter.channel == p.channelId, modifier = if (index == 0) Modifier.focusRequester(first) else Modifier) { onPastille(p) }
                    }
                }
            }
        }
        EdgeScroll(Dimens.Gutter) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter),
                horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val firstChip = if (pastilles.isEmpty()) Modifier.focusRequester(first) else Modifier
                FilterChip("Que du nouveau", filter.newOnly, firstChip) { onFilter(filter.copy(newOnly = !filter.newOnly)) }
                // OK goes through the durations, back to « Toutes ».
                val index = SubsFilter.DURATIONS.indexOfFirst { it.first == filter.duration }.coerceAtLeast(0)
                val (_, name) = SubsFilter.DURATIONS[index]
                FilterChip(if (filter.duration == SubsFilter.DURATION_ALL) "Durée" else "Durée : $name", filter.duration != SubsFilter.DURATION_ALL, showCheck = false) {
                    onFilter(filter.copy(duration = SubsFilter.DURATIONS[(index + 1) % SubsFilter.DURATIONS.size].first))
                }
                if (folders.isNotEmpty()) {
                    FilterChip("Tous les dossiers", filter.folder == null) { onFilter(filter.copy(folder = null)) }
                    folders.forEach { folder ->
                        FilterChip(folder.name, filter.folder == folder.name) { onFilter(filter.copy(folder = if (filter.folder == folder.name) null else folder.name)) }
                    }
                }
                // One channel picked from its pastille: a way back to all of them.
                filter.channelName?.let { channel ->
                    MiryoButton(channel, icon = MiryoIcon.CLOSE, iconSize = 13.dp, containerColor = Palette.Selected, contentColor = Palette.ChipText) {
                        onFilter(filter.copy(channel = null, channelName = null))
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, modifier: Modifier = Modifier, showCheck: Boolean = true, onClick: () -> Unit) =
    MiryoButton(
        label,
        modifier = modifier,
        icon = if (selected && showCheck) MiryoIcon.CHECK else null,
        iconSize = 13.dp,
        containerColor = if (selected) Palette.Selected else null,
        contentColor = Palette.ChipText,
        onClick = onClick,
    )

/** A channel's round picture, its name under it, and its new videos' count on the top right. */
@Composable
private fun PastilleItem(pastille: Pastille, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(PASTILLE_SLOT), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(PASTILLE_RING)) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(PASTILLE_RING)
                    .then(
                        when {
                            focused -> Modifier.border(2.5.dp, Palette.FocusRing, CircleShape)
                            selected -> Modifier.border(2.dp, Palette.TextSecondary, CircleShape)
                            else -> Modifier
                        },
                    ),
            )
            Surface(
                onClick = onClick,
                modifier = modifier.align(Alignment.Center).size(PASTILLE_AVATAR).onFocusChanged { focused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Tile, focusedContainerColor = Palette.Tile),
            ) {
                AvatarPicture(pastille.avatar, pastille.name, PASTILLE_AVATAR, 26.sp)
            }
            if (pastille.newCount > 0) {
                Text(
                    if (pastille.newCount > 99) "99+" else pastille.newCount.toString(),
                    fontSize = Dimens.TextMin,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .widthIn(min = 22.dp)
                        .background(Palette.Live, CircleShape)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        Text(
            pastille.name,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
            color = if (focused) Palette.TextPrimary else Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

private val PASTILLE_AVATAR = 60.dp
/** The avatar, a 4 dp gap and the ring. */
private val PASTILLE_RING = PASTILLE_AVATAR + 10.dp
private val PASTILLE_SLOT = 84.dp
private val PASTILLE_GAP = 8.dp
