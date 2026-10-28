@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import dev.miraiijo.miryotube.data.Details
import dev.miraiijo.miryotube.player.MiryoPlayer
import kotlinx.coroutines.launch
import dev.miraiijo.miryotube.player.PlayerPrefs

/** The player's settings panel: a main list, and one list per setting. */
enum class PlayerMenu { MAIN, QUALITY, SUBTITLES, SPEED, AUDIO, FORMAT, INFO, CHAPTERS, TIMER }

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

/** Picture formats (PlayerPrefs.pictureFormat): whole picture, fill the screen, stretched. */
val PICTURE_FORMATS = listOf("Image entière", "Remplir l’écran", "Étirer")

/** The video's account actions and info, shown in the main list. */
class VideoActions(
    val details: Details?,
    val signedIn: Boolean,
    val watchLaterDone: Boolean,
    val onSubscribe: () -> Unit,
    val onWatchLater: () -> Unit,
    /** "Voir la chaîne": the video goes to the small window, the channel page opens. */
    val onChannel: () -> Unit = {},
    /** "Statistiques pour les curieux" shown. */
    val stats: Boolean = false,
    val onStats: () -> Unit = {},
    /** "Fréquence d'affichage automatique" on (a check mark: the label is too long for a value). */
    val autoFrameRate: Boolean = false,
    val onAutoFrameRate: () -> Unit = {},
    /** The video's chapters, the one playing (-1: none yet) and a jump to one. */
    val chapters: List<dev.miraiijo.miryotube.data.Chapter> = emptyList(),
    val currentChapter: Int = -1,
    val onChapter: (dev.miraiijo.miryotube.data.Chapter) -> Unit = {},
    /** Kids profile: no channel page, no account actions. */
    val kids: Boolean = false,
    /** « Son seul »: the picture off, the sound on. */
    val audioOnly: Boolean = false,
    val onAudioOnly: () -> Unit = {},
    /** « Minuterie »: what it's set to (« Désactivée », « 29 min »…) and a new choice (SleepTimer's minutes, AT_END, 0 = off). */
    val timer: String = "Désactivée",
    val timerMinutes: Int = 0,
    val onTimer: (Int) -> Unit = {},
    /** A live's chat: shown (null: the video has none), and its size (CHAT_SIZES). */
    val liveChat: Boolean? = null,
    val onLiveChat: () -> Unit = {},
    val chatSize: Int = 1,
    val onChatSize: () -> Unit = {},
)

private class Entry(
    val label: String,
    val value: String? = null,
    val selected: Boolean = false,
    val submenu: Boolean = false,
    /** A section title in the list (not focusable). */
    val header: Boolean = false,
    /** A picture on the left (a chapter's). */
    val image: String? = null,
    /** The value always whole (a chapter's time): the label is the one cut. */
    val keepValue: Boolean = false,
    val onClick: () -> Unit,
)

/** Subtitle languages listed first, under "Préférées" (names are in the app's language or English). */
private val PREFERRED_SUBTITLES = listOf("français", "french", "anglais", "english")

private fun isPreferredSubtitle(name: String) = name.lowercase().let { n -> PREFERRED_SUBTITLES.any { it in n } }

/**
 * YouTube's automatic translation of the subtitles (« Français* »): the engine marks it with a
 * star, as it does automatic captions, which also say « (générés automatiquement) ».
 */
internal fun isTranslatedSubtitle(name: String) = name.endsWith("*") && '(' !in name

internal fun isFrenchSubtitle(name: String) = name.trimEnd('*').trim().lowercase().let { it.startsWith("français") || it.startsWith("french") }

/** A subtitle track as shown: the engine's star gone, a translation said so. */
internal fun subtitleLabel(name: String): String =
    if (isTranslatedSubtitle(name)) name.trimEnd('*').trim() + " · traduit automatiquement" else name.trimEnd('*').trim()

/**
 * The French track to offer on a video in another language: its own French subtitles, else
 * YouTube's translation; -1 when the video is spoken in French (its automatic captions are) or
 * has neither.
 */
internal fun frenchSubtitle(tracks: List<String>): Int {
    if (tracks.any { isFrenchSubtitle(it) && it.endsWith("*") && !isTranslatedSubtitle(it) }) return -1
    val own = tracks.indexOfFirst { isFrenchSubtitle(it) && !it.endsWith("*") }
    return if (own >= 0) own else tracks.indexOfFirst { isFrenchSubtitle(it) && isTranslatedSubtitle(it) }
}

/**
 * The track in miryo's subtitle language ([iso], « fr », « en »…): the video's own subtitles in it,
 * else its automatic captions in it, else YouTube's translation into it; -1 when none.
 */
internal fun languageSubtitle(tracks: List<String>, iso: String): Int {
    val locale = java.util.Locale(iso)
    val names = listOf(locale.getDisplayLanguage(java.util.Locale.FRENCH), locale.getDisplayLanguage(java.util.Locale.ENGLISH))
        .map { it.lowercase() }.filter { it.isNotEmpty() && it != iso.lowercase() }
    if (names.isEmpty()) return -1
    fun inLanguage(name: String) = name.trimEnd('*').trim().lowercase().let { n -> names.any { n.startsWith(it) } }
    val own = tracks.indexOfFirst { inLanguage(it) && !it.endsWith("*") }
    if (own >= 0) return own
    val automatic = tracks.indexOfFirst { inLanguage(it) && it.endsWith("*") && !isTranslatedSubtitle(it) }
    if (automatic >= 0) return automatic
    return tracks.indexOfFirst { inLanguage(it) && isTranslatedSubtitle(it) }
}

/** « Minuterie »'s choices: minutes, and [SleepTimer.AT_END] (at the end of the video). */
private val TIMER_CHOICES = listOf(0 to "Désactivée", 15 to "15 minutes", 30 to "30 minutes", 60 to "1 heure", 120 to "2 heures", dev.miraiijo.miryotube.player.SleepTimer.AT_END to "À la fin de la vidéo")

/**
 * Nuvio-like side panel on the right of the player. OK opens a setting or picks a value (back to
 * the main list), Back goes up one level (handled by the player screen). [tracksTick] changes when
 * the video's tracks do, so the lists follow them.
 */
@Composable
fun PlayerMenuPanel(
    menu: PlayerMenu,
    player: MiryoPlayer,
    prefs: PlayerPrefs,
    live: Boolean,
    tracksTick: Int,
    sponsorBlock: Boolean,
    onSponsorBlock: (Boolean) -> Unit,
    pictureFormat: Int,
    onPictureFormat: (Int) -> Unit,
    actions: VideoActions,
    onMenu: (PlayerMenu?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bumped after each choice: the values shown are read from the player again.
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(menu, tracksTick, version, sponsorBlock, pictureFormat, actions.details, actions.watchLaterDone, actions.stats, actions.autoFrameRate, actions.currentChapter, actions.audioOnly, actions.timer, actions.liveChat, actions.chatSize) {
        buildEntries(menu, player, prefs, live, sponsorBlock, onSponsorBlock, pictureFormat, onPictureFormat, actions, onMenu) { version++ }
    }
    val title = when (menu) {
        PlayerMenu.MAIN -> "Réglages"
        PlayerMenu.QUALITY -> "Qualité"
        PlayerMenu.SUBTITLES -> "Sous-titres"
        PlayerMenu.SPEED -> "Vitesse"
        PlayerMenu.AUDIO -> "Piste audio"
        PlayerMenu.FORMAT -> "Format de l’image"
        PlayerMenu.INFO -> "À propos"
        PlayerMenu.CHAPTERS -> "Chapitres"
        PlayerMenu.TIMER -> "Minuterie"
    }
    // The level shown just before (read before it's updated below): back from a sub-list, the main
    // list opens on the line that led to it (Vitesse, Format…), not on its first line.
    val lastMenu = remember { arrayOfNulls<PlayerMenu>(1) }
    val cameFrom = remember(menu) { lastMenu[0] }
    SideEffect { lastMenu[0] = menu }
    // A list of choices opens on the current one; the main list on its first line (not a header).
    val focusIndex = if (menu == PlayerMenu.MAIN) {
        val origin = SUBMENU_LABELS[cameFrom]
        entries.indexOfFirst { !it.header && origin != null && it.label == origin }.takeIf { it >= 0 }
            ?: entries.indexOfFirst { !it.header }.coerceAtLeast(0)
    } else {
        entries.indexOfFirst { it.selected }.coerceAtLeast(0)
    }
    val first = remember(menu) { FocusRequester() }

    // To the screen's edges, its content in the safe zone (the last line above the bottom inset).
    SafeSidePanel(
        400.dp,
        modifier
            // The focus stays in the panel until Back closes it.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup(),
    ) { bottom ->
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 12.dp, bottom = 12.dp))
        if (menu == PlayerMenu.INFO) {
            InfoText(actions.details, Modifier.weight(1f).focusRequester(first), bottom)
        } else Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entries.forEachIndexed { index, entry ->
                if (entry.header) {
                    Text(entry.label, fontSize = Dimens.TextMin, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 2.dp))
                } else {
                    MenuRow(entry, if (index == focusIndex) Modifier.focusRequester(first) else Modifier)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    LaunchedEffect(menu) {
        withFrameNanos { }
        first.tryRequestFocus()
    }
}

private fun buildEntries(
    menu: PlayerMenu,
    player: MiryoPlayer,
    prefs: PlayerPrefs,
    live: Boolean,
    sponsorBlock: Boolean,
    onSponsorBlock: (Boolean) -> Unit,
    pictureFormat: Int,
    onPictureFormat: (Int) -> Unit,
    actions: VideoActions,
    onMenu: (PlayerMenu?) -> Unit,
    changed: () -> Unit,
): List<Entry> = when (menu) {
    // Grouped by use, most used first: how it plays, then the video itself, then extras. Chapters,
    // À propos and comments have their own buttons in the controls: not repeated here.
    PlayerMenu.MAIN -> buildList {
        val details = actions.details
        add(Entry("LECTURE", header = true) {})
        val qualities = player.qualities()
        if (qualities.isNotEmpty()) {
            val value = if (prefs.quality == 0) "Auto" + (player.playingLabel?.let { " · $it" } ?: "") else player.qualityLabel(prefs.quality)
            add(Entry("Qualité", value, submenu = true) { onMenu(PlayerMenu.QUALITY) })
        }
        val subtitles = player.subtitleTracks()
        if (subtitles.isNotEmpty()) {
            add(Entry("Sous-titres", subtitles.getOrNull(player.selectedSubtitle)?.let(::subtitleLabel) ?: "Désactivés", submenu = true) { onMenu(PlayerMenu.SUBTITLES) })
        }
        val audio = player.audioTracks()
        if (audio.size > 1) add(Entry("Piste audio", audio.getOrNull(player.playingAudio) ?: "Auto", submenu = true) { onMenu(PlayerMenu.AUDIO) })
        if (!live) add(Entry("Vitesse", speedName(player.speed), submenu = true) { onMenu(PlayerMenu.SPEED) })
        add(Entry("Format de l’image", PICTURE_FORMATS[pictureFormat.coerceIn(0, PICTURE_FORMATS.lastIndex)], submenu = true) { onMenu(PlayerMenu.FORMAT) })

        val channelId = details?.channelId
        if (!actions.kids && (channelId != null || actions.signedIn)) {
            add(Entry("VIDÉO ET CHAÎNE", header = true) {})
            if (actions.signedIn) {
                add(Entry("À regarder plus tard", if (actions.watchLaterDone) "Ajoutée" else null, selected = actions.watchLaterDone, onClick = actions.onWatchLater))
            }
            if (details != null && channelId != null) {
                val channel = details.channel?.takeIf { it.isNotBlank() }
                add(Entry("Voir la chaîne", channel, onClick = actions.onChannel))
                if (actions.signedIn) {
                    add(Entry(if (details.subscribed) "Abonné" else "S’abonner", if (details.subscribed) "Se désabonner" else channel, onClick = actions.onSubscribe))
                }
            }
        }

        add(Entry("OPTIONS", header = true) {})
        if (!live) add(Entry("SponsorBlock", if (sponsorBlock) "Activé" else "Désactivé") { onSponsorBlock(!sponsorBlock) })
        add(Entry("Statistiques pour les curieux", if (actions.stats) "Affichées" else "Masquées", onClick = actions.onStats))
        add(Entry("Fréquence d’affichage automatique", selected = actions.autoFrameRate, onClick = actions.onAutoFrameRate))
        // Music: the picture goes out, the sound goes on (less light, less data).
        add(Entry("Son seul", if (actions.audioOnly) "Activé" else "Éteint l’image", selected = actions.audioOnly, onClick = actions.onAudioOnly))
        add(Entry("Minuterie", actions.timer, submenu = true) { onMenu(PlayerMenu.TIMER) })
        actions.liveChat?.let { shown ->
            add(Entry("Chat du direct", if (shown) "Affiché" else "Masqué", selected = shown, onClick = actions.onLiveChat))
            if (shown) add(Entry("Taille du chat", CHAT_SIZES.getOrNull(actions.chatSize)?.first ?: "Normale", onClick = actions.onChatSize))
        }
    }
    PlayerMenu.TIMER -> TIMER_CHOICES.map { (minutes, name) ->
        Entry(name, selected = minutes == actions.timerMinutes) {
            actions.onTimer(minutes)
            onMenu(PlayerMenu.MAIN)
        }
    }
    PlayerMenu.FORMAT -> PICTURE_FORMATS.mapIndexed { index, name ->
        Entry(name, selected = index == pictureFormat) {
            onPictureFormat(index)
            onMenu(PlayerMenu.MAIN)
        }
    }
    PlayerMenu.INFO -> emptyList()
    // Each chapter with its start time; OK jumps there (the one playing is checked).
    PlayerMenu.CHAPTERS -> actions.chapters.mapIndexed { index, chapter ->
        Entry(chapter.title.ifBlank { "Chapitre ${index + 1}" }, formatDuration(chapter.startMs).ifEmpty { "0:00" }, selected = index == actions.currentChapter, image = chapter.image, keepValue = true) {
            actions.onChapter(chapter)
            onMenu(PlayerMenu.MAIN)
        }
    }
    PlayerMenu.QUALITY -> buildList {
        fun pick(quality: Int) {
            prefs.quality = quality
            player.setQuality(quality)
            changed()
            onMenu(PlayerMenu.MAIN)
        }
        add(Entry("Auto", if (player.screenHdr) "connexion, box · HDR si dispo" else "selon la connexion et la box", selected = prefs.quality == 0) { pick(0) })
        // Each height once per kind: "2160p60 · 4K HDR", then "2160p60 · 4K", "1080p60"…
        player.qualities().forEach { quality -> add(Entry(player.qualityLabel(quality), selected = prefs.quality == quality) { pick(quality) }) }
    }
    PlayerMenu.SUBTITLES -> buildList {
        val tracks = player.subtitleTracks()
        fun pick(index: Int) {
            player.selectSubtitle(index)
            prefs.subtitles = tracks.getOrNull(index)
            changed()
            onMenu(PlayerMenu.MAIN)
        }
        add(Entry("Désactivés", selected = player.selectedSubtitle < 0) { pick(-1) })
        // A video in another language: YouTube's French translation, first.
        val french = frenchSubtitle(tracks)
        if (french >= 0 && isTranslatedSubtitle(tracks[french])) {
            add(Entry("Traduire en français", "traduit automatiquement", selected = player.selectedSubtitle == french) { pick(french) })
        }
        // Français and English first (the video's own, then the automatic translations), then the rest.
        val (preferred, others) = tracks.indices.partition { isPreferredSubtitle(tracks[it]) }
        if (preferred.isNotEmpty()) {
            add(Entry("PRÉFÉRÉES", header = true) {})
            preferred.forEach { index -> add(Entry(subtitleLabel(tracks[index]), selected = player.selectedSubtitle == index) { pick(index) }) }
        }
        if (others.isNotEmpty()) {
            if (preferred.isNotEmpty()) add(Entry("AUTRES LANGUES", header = true) {})
            others.forEach { index -> add(Entry(subtitleLabel(tracks[index]), selected = player.selectedSubtitle == index) { pick(index) }) }
        }
    }
    PlayerMenu.SPEED -> SPEEDS.map { speed ->
        Entry(speedName(speed), selected = player.speed == speed) {
            player.speed = speed
            changed()
            onMenu(PlayerMenu.MAIN)
        }
    }
    PlayerMenu.AUDIO -> {
        val playing = player.playingAudio
        player.audioTracks().mapIndexed { index, name ->
            Entry(name, selected = index == playing) {
                player.selectAudio(index)
                changed()
                onMenu(PlayerMenu.MAIN)
            }
        }
    }
}

/** Description and figures; Up / Down scroll it (it takes the focus, the panel has nothing else). */
@Composable
private fun InfoText(details: Details?, modifier: Modifier, bottom: androidx.compose.foundation.layout.PaddingValues) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val step = with(LocalDensity.current) { 160.dp.toPx() }
    Column(
        modifier
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { scope.launch { scroll.animateScrollBy(step) }; true }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> { scope.launch { scroll.animateScrollBy(-step) }; true }
                    else -> false
                }
            }
            .focusable()
            .verticalScroll(scroll)
            .padding(bottom)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val figures = listOfNotNull(details?.views, details?.published, details?.likes?.let { "$it J’aime" }).joinToString(" · ")
        if (figures.isNotEmpty()) Text(figures, fontSize = 13.sp, color = Palette.TextSecondary)
        Text(details?.description?.takeIf { it.isNotBlank() } ?: "Pas de description.", fontSize = 13.sp, lineHeight = 19.sp, color = Palette.TextPrimary.copy(alpha = 0.9f))
        Spacer(Modifier.height(24.dp))
    }
}

private fun speedName(speed: Float) = if (speed == 1f) "Normale" else "${speed.toString().removeSuffix(".0").replace('.', ',')}×"

@Composable
private fun MenuRow(entry: Entry, modifier: Modifier) {
    Surface(
        onClick = entry.onClick,
        modifier = modifier.fillMaxWidth().height(if (entry.image != null) 64.dp else 48.dp),
        shape = ClickableSurfaceDefaults.shape(shape = ControlShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (entry.selected) Palette.Selected else Color.Transparent,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = Palette.Focus,
            pressedContentColor = Palette.OnFocus,
        ),
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // A chapter's picture (YouTube's), to find « the part with the dough » at a glance.
            if (entry.image != null) {
                Box(Modifier.padding(end = 12.dp).width(CHAPTER_IMAGE).height(CHAPTER_IMAGE * 9 / 16).clip(ControlShape).background(Palette.Tile)) {
                    Thumbnail(null, entry.image, Modifier.fillMaxSize(), small = true, width = CHAPTER_IMAGE)
                }
            }
            if (entry.value != null && entry.keepValue) {
                // A long chapter title ends in « … », its time stays whole on the right.
                Text(entry.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(entry.value, fontSize = 14.sp, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 12.dp))
            } else if (entry.value == null) {
                Text(entry.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                // The label first, whole (up to a cap); the value takes the rest, right-aligned, and
                // is the one cut when both are long (an auto-generated subtitle track…).
                Text(entry.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                Text(
                    entry.value,
                    fontSize = 14.sp,
                    color = LocalContentColor.current.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(start = 12.dp),
                )
            }
            // The mark's place is always kept: the values line up in one column.
            Box(Modifier.padding(start = 10.dp).width(18.dp), contentAlignment = Alignment.Center) {
                when {
                    entry.selected -> Icon(MiryoIcon.CHECK, size = 18.dp)
                    entry.submenu -> Text("›", fontSize = 20.sp, color = LocalContentColor.current.copy(alpha = 0.65f))
                }
            }
        }
    }
}

/** The main list's line that opens each sub-list (focused again when coming back from it). */
private val SUBMENU_LABELS = mapOf(
    PlayerMenu.QUALITY to "Qualité",
    PlayerMenu.SUBTITLES to "Sous-titres",
    PlayerMenu.AUDIO to "Piste audio",
    PlayerMenu.SPEED to "Vitesse",
    PlayerMenu.FORMAT to "Format de l’image",
    PlayerMenu.TIMER to "Minuterie",
)

private val CHAPTER_IMAGE = 80.dp
