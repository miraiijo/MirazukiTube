@file:OptIn(ExperimentalTvMaterial3Api::class)

package dev.miraiijo.miryotube.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import dev.miraiijo.miryotube.Pairing
import dev.miraiijo.miryotube.data.Video

/**
 * Long press on a card: what YouTube's own "⋮" menu offers. Account actions only once signed in;
 * "Pas intéressé" / "Ne pas recommander" only on the cards YouTube allows it for (recommendations).
 */
@Composable
fun CardMenuDialog(
    video: Video,
    signedIn: Boolean,
    canGiveFeedback: Boolean,
    onPlay: () -> Unit,
    onWatchLater: () -> Unit,
    onChannel: () -> Unit,
    onNotInterested: (channel: Boolean) -> Unit,
    /** « À la suite »: videos in it, add this one, empty it. */
    queued: Int = 0,
    onQueue: () -> Unit = {},
    onClearQueue: () -> Unit = {},
    /** « Ne plus montrer cette chaîne » (on this box, for this profile). */
    onHideChannel: () -> Unit = {},
    /** « Ranger dans un dossier » (null: no channel known, no line). */
    onFolder: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    MiryoDialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        SafeDialog(
            520.dp,
            Modifier.background(Palette.Panel, DialogShape).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Palette.Tile)) {
                    Thumbnail(video.videoId, video.thumbnail, Modifier.matchParentSize(), custom = video.customThumbnail, small = true, width = 112.dp)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(video.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                    if (video.channel.isNotBlank()) Text(video.channel, fontSize = 13.sp, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            DialogRow("Lire", icon = MiryoIcon.PLAY, modifier = Modifier.focusRequester(first), onClick = onPlay)
            if (video.isVideo && video.videoId != null) DialogRow("Lire ensuite", value = if (queued > 0) "À la suite · $queued" else null, icon = MiryoIcon.PLAYLIST, onClick = onQueue)
            if (queued > 0) DialogRow("Vider « À la suite »", icon = MiryoIcon.CLOSE, onClick = onClearQueue)
            if (signedIn && video.videoId != null) DialogRow("À regarder plus tard", icon = MiryoIcon.HISTORY, onClick = onWatchLater)
            if (video.raw.channelId != null) DialogRow("Ouvrir la chaîne", icon = MiryoIcon.USER, onClick = onChannel)
            if (signedIn && onFolder != null) DialogRow("Ranger la chaîne dans un dossier", icon = MiryoIcon.PLAYLIST, onClick = onFolder)
            if (video.channelId != null && !video.isChannel) DialogRow("Ne plus montrer cette chaîne", icon = MiryoIcon.EYE_OFF, onClick = onHideChannel)
            if (signedIn && canGiveFeedback) {
                DialogRow("Pas intéressé", icon = MiryoIcon.EYE_OFF) { onNotInterested(false) }
                if (video.raw.feedbackToken2 != null || video.raw.feedbackEndpoint != null) {
                    DialogRow("Ne pas recommander cette chaîne", icon = MiryoIcon.CLOSE) { onNotInterested(true) }
                }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
}

/**
 * « Ranger dans un dossier »: the profile's folders (the channel's own checked), « Aucun dossier »,
 * and « Nouveau dossier… » with the TV keyboard (or the remote's dictation). On the box only.
 */
@Composable
fun FolderDialog(
    channel: dev.miraiijo.miryotube.data.ChannelRef,
    folders: List<dev.miraiijo.miryotube.data.Folder>,
    onFile: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    MiryoDialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        val field = remember { FocusRequester() }
        val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
        var naming by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
        var name by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
        val current = folders.firstOrNull { channel.id in it.channels }?.name
        SafeDialog(
            520.dp,
            Modifier.background(Palette.Panel, DialogShape).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Ranger dans un dossier", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp))
            Text(
                "${channel.name}. Les dossiers deviennent des filtres en haut d’Abonnements. Ils restent sur la box, ton compte YouTube ne change pas.",
                fontSize = 14.sp,
                lineHeight = 19.sp,
                color = Palette.TextSecondary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
            if (naming) {
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(ControlShape).background(Palette.ControlIdle).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = name,
                        onValueChange = { name = it.take(dev.miraiijo.miryotube.data.Curation.NAME_MAX) },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.TextPrimary, fontSize = 18.sp, fontFamily = InterFamily),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(Palette.Highlight),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = {
                            keyboard?.hide()
                            if (name.isNotBlank()) onFile(name) else naming = false
                        }),
                        decorationBox = { inner ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (name.isEmpty()) Text("Nom du dossier (Cuisine, Jeux…)", fontSize = 18.sp, color = Palette.TextSecondary)
                                inner()
                            }
                        },
                        modifier = Modifier.weight(1f).focusRequester(field),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap), modifier = Modifier.padding(top = 8.dp)) {
                    MiryoButton("Créer", icon = MiryoIcon.CHECK) { if (name.isNotBlank()) onFile(name) }
                    MiryoButton("Annuler") { naming = false }
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    field.tryRequestFocus()
                    // As in Rechercher: the box ignored a keyboard asked for at once.
                    kotlinx.coroutines.delay(150)
                    keyboard?.show()
                }
            } else {
                folders.forEachIndexed { index, folder ->
                    DialogRow(
                        folder.name,
                        value = if (folder.name == current) "Ici" else "${folder.channels.size} chaîne${if (folder.channels.size > 1) "s" else ""}",
                        icon = if (folder.name == current) MiryoIcon.CHECK else MiryoIcon.PLAYLIST,
                        modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                    ) { onFile(folder.name) }
                }
                DialogRow("Nouveau dossier…", icon = MiryoIcon.PLUS, modifier = if (folders.isEmpty()) Modifier.focusRequester(first) else Modifier) {
                    name = ""
                    naming = true
                }
                if (current != null) DialogRow("Sortir du dossier « $current »", icon = MiryoIcon.CLOSE) { onFile(null) }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    first.tryRequestFocus()
                }
            }
        }
    }
}

/**
 * "Lier un téléphone": the TV code for the phone's YouTube app (Paramètres › Regarder sur la TV ›
 * Saisir le code TV). Closes by itself once the phone is connected.
 */
@Composable
fun PairingDialog(pairing: Pairing, onRetry: () -> Unit, onDismiss: () -> Unit) {
    MiryoDialog(onDismissRequest = onDismiss) {
        val button = remember { FocusRequester() }
        // Like the sign-in: the text and the code on the left, the QR code on the right.
        SafeDialog(SIGN_IN_WIDTH, Modifier.background(Palette.Panel, DialogShape).padding(32.dp)) { Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Lier un téléphone", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Scanne le QR code avec le téléphone. Ou dans l’app YouTube : photo de profil › Paramètres › Regarder sur la TV › Saisir le code TV, puis tape ce code :",
                    color = Palette.TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                // A fixed slot: "Code en cours…", the code or the error, the dialog keeps its height.
                Box(Modifier.fillMaxWidth().height(CODE_SLOT), contentAlignment = Alignment.CenterStart) {
                    when {
                        pairing.error != null -> Text(pairing.error, color = Palette.Danger, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        pairing.code == null -> Text("Code en cours…", color = Palette.TextSecondary, fontSize = 28.sp, lineHeight = 34.sp)
                        else -> CodeText(pairing.code)
                    }
                }
                Text(
                    "Ensuite, le bouton Caster du téléphone envoie les vidéos ici (quand miryo tube est ouvert) et le téléphone sert de télécommande.",
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                    if (pairing.error != null) MiryoButton("Réessayer", icon = MiryoIcon.REFRESH, onClick = onRetry)
                    MiryoButton("Fermer", modifier = Modifier.focusRequester(button), onClick = onDismiss)
                }
            }
            Box(Modifier.size(220.dp).clip(ControlShape).background(Color.White), contentAlignment = Alignment.Center) {
                // YouTube's pairing link with the TV code (digits only): the phone opens YouTube and links.
                val digits = pairing.code?.filter(Char::isDigit).orEmpty()
                if (pairing.error == null && digits.isNotEmpty()) QrCode(QR_PAIR_URL + digits, Modifier.fillMaxSize().padding(14.dp))
            }
        } }
        // At opening, and after "Réessayer" (it goes away with the error, focus and all): "Fermer".
        LaunchedEffect(pairing.error == null) {
            withFrameNanos { }
            button.tryRequestFocus()
        }
    }
}

/** YouTube's "link with TV code" page, the code in it (what YouTube's own TV apps put in their QR). */
private const val QR_PAIR_URL = "https://www.youtube.com/pair?pairingCode="

/** A short message at the bottom center ("Ajoutée à À regarder plus tard."), never focusable. */
@Composable
fun Notice(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = text != null, enter = fadeIn(tween(150)), exit = fadeOut(tween(300)), modifier = modifier) {
        // Keeps the last text while fading out.
        val shown = remember { mutableListOf("") }.also { if (text != null) it[0] = text }[0]
        Text(
            shown,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.background(Color.Black.copy(alpha = 0.85f), ControlShape).padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}
