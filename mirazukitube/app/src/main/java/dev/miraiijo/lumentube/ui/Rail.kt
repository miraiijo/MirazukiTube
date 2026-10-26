@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.ui.unit.em
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import dev.miraiijo.lumentube.UpdateUi
import dev.miraiijo.lumentube.update.Updater
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.tv.material3.LocalContentColor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.miraiijo.lumentube.Settings
import dev.miraiijo.lumentube.data.Account
import dev.miraiijo.lumentube.UiState
import dev.miraiijo.lumentube.data.Section


@Composable
private fun Avatar(url: String?, name: String?) = AvatarPicture(url, name, 32.dp, 15.sp)

/** Round account picture (sharp: asked in 256 px), else the name's initial. */
@Composable
internal fun AvatarPicture(url: String?, name: String?, size: androidx.compose.ui.unit.Dp, initialSize: androidx.compose.ui.unit.TextUnit) {
    // No picture: the name's initial, white and bold on a colored gradient (the redesign's). The
    // initial is also drawn under a picture: if it fails to load (404, offline), the initial shows.
    val back = if (name != null) Modifier.background(initialGradient(name)) else Modifier.background(Palette.ControlIdle)
    Box(Modifier.size(size).clip(CircleShape).then(back), contentAlignment = Alignment.Center) {
        if (name != null) {
            Text(name.take(1).uppercase(), fontSize = initialSize, fontWeight = FontWeight.Bold, color = Color.White)
        } else if (url == null) {
            Icon(MirazukiIcon.PLAY, size = size / 2, color = Palette.Danger)
        }
        if (url != null) {
            GlideImage(model = avatarUrl(url), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** The gradients of initials (the redesign's), one per name, always the same for a name. */
private val INITIAL_GRADIENTS = listOf(
    Color(0xFFF58A6B) to Color(0xFFE0466F),
    Color(0xFFF2B45A) to Color(0xFFD9772E),
    Color(0xFF7C7CF5) to Color(0xFF3F64F0),
    Color(0xFF6CCB8E) to Color(0xFF2F9E73),
    Color(0xFFB08CF0) to Color(0xFF7A4FD6),
)

internal fun initialGradient(name: String): androidx.compose.ui.graphics.Brush {
    val (from, to) = INITIAL_GRADIENTS[Math.floorMod(name.hashCode(), INITIAL_GRADIENTS.size)]
    return androidx.compose.ui.graphics.Brush.linearGradient(listOf(from, to))
}

/**
 * Sign-in: scan the QR code with a phone (the code is already filled in), or open
 * youtube.com/activate and type it. Closes by itself once the account is linked.
 */
@Composable
fun SignInDialog(code: String?, error: String?, onRetry: () -> Unit, onDismiss: () -> Unit) {
    MirazukiDialog(onDismissRequest = onDismiss) {
        val button = remember { FocusRequester() }
        // 760 dp at most, narrower when the safe zone is (120 %): the text column gives way, not the QR code.
        SafeDialog(SIGN_IN_WIDTH, Modifier.background(Palette.Panel, DialogShape).padding(32.dp)) { Row(
            horizontalArrangement = Arrangement.spacedBy(32.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Connecter un compte YouTube", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Scanne le QR code avec ton téléphone, ou ouvre youtube.com/activate et saisis ce code :",
                    color = Palette.TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                )
                // A fixed slot: "Code en cours…", the code or the error, the dialog keeps its height.
                Box(Modifier.fillMaxWidth().height(CODE_SLOT), contentAlignment = Alignment.CenterStart) {
                    when {
                        error != null -> Text(error, color = Palette.Danger, fontSize = 15.sp, lineHeight = 20.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        code == null -> Text("Code en cours…", color = Palette.TextSecondary, fontSize = 28.sp, lineHeight = 34.sp)
                        else -> CodeText(code)
                    }
                }
                Text("miryo tube reste connecté ensuite. Tu peux lier plusieurs comptes et choisir lequel à l’ouverture.", color = Palette.TextSecondary, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                    if (error != null) MirazukiButton("Réessayer", icon = MirazukiIcon.REFRESH, onClick = onRetry)
                    MirazukiButton("Annuler", modifier = Modifier.focusRequester(button), onClick = onDismiss)
                }
            }
            Box(Modifier.size(220.dp).clip(ControlShape).background(Color.White), contentAlignment = Alignment.Center) {
                // YouTube's activation link with the code in it: the phone lands on the confirmation.
                if (code != null && error == null) QrCode(QR_SIGN_IN_URL + code.trim().replace(' ', '-'), Modifier.fillMaxSize().padding(14.dp))
            }
        } }
        // At opening, and after "Réessayer" (it goes away with the error, focus and all): "Annuler".
        LaunchedEffect(error == null) {
            withFrameNanos { }
            button.tryRequestFocus()
        }
    }
}

/** The code's line in the sign-in and pairing dialogs (the 40 sp code on a 48 sp line). */
internal val CODE_SLOT = 52.dp

/** The sign-in and pairing dialogs' widest (SafeDialog caps it to the safe zone). */
internal val SIGN_IN_WIDTH = 760.dp

/**
 * A sign-in / pairing code, always on one line: 40 sp, a few sp smaller at a time (down to 24)
 * while it doesn't fit its column (a narrow dialog at 120 %, a long code).
 */
@Composable
internal fun CodeText(code: String) {
    var size by remember(code) { mutableStateOf(CODE_SIZE) }
    Text(
        code,
        fontSize = size,
        lineHeight = size * 1.2f,
        fontWeight = FontWeight.Bold,
        letterSpacing = size * 0.1f,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        onTextLayout = { if (it.hasVisualOverflow && size.value > CODE_MIN_SIZE.value) size = maxOf(size.value - 4f, CODE_MIN_SIZE.value).sp },
    )
}

private val CODE_SIZE = 40.sp
private val CODE_MIN_SIZE = 24.sp

/** Same address as SmartTube's QR sign-in. */
private const val QR_SIGN_IN_URL = "https://youtube.com/qr/activate/"

/** A QR code drawn on the device (ZXing): the sign-in code never goes through a QR web service. */
@Composable
internal fun QrCode(content: String, modifier: Modifier) {
    val matrix = remember(content) {
        runCatching { QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }.getOrNull()
    } ?: return
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        val left = (size.width - cell * matrix.width) / 2
        val top = (size.height - cell * matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(Color.Black, topLeft = Offset(left + x * cell, top + y * cell), size = Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}

/**
 * "Qui regarde ?" (the redesign: full screen, like miryo's): the linked accounts as large round
 * avatars, and "Ajouter". Opened by the avatar of the header, and at launch when several accounts
 * are linked. OK switches account.
 */
@Composable
fun ProfileDialog(
    accounts: List<Account>,
    onChoose: (Account) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
    /** "Invité" (signed out): offered once an account is linked; null hides it. */
    onGuest: (() -> Unit)? = null,
    /** Accounts linked but none selected: "Invité" is the current one. */
    guest: Boolean = false,
    /** "Enfant": the kids profile, or its creation (parental code first); null hides it. */
    onKids: (() -> Unit)? = null,
    kidsExists: Boolean = false,
    /** The sign-in dialog is open over this one: when it closes, the focus goes back to "Ajouter". */
    signingIn: Boolean = false,
) {
    MirazukiDialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        val add = remember { FocusRequester() }
        var wasSigningIn by remember { mutableStateOf(false) }
        LaunchedEffect(signingIn) {
            if (signingIn) {
                wasSigningIn = true
            } else if (wasSigningIn) {
                wasSigningIn = false
                withFrameNanos { }
                add.tryRequestFocus()
            }
        }
        Column(
            Modifier.fillMaxSize().mirazukiBackground().safePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Qui regarde ?", fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em, color = Palette.TextPrimary)
            Spacer(Modifier.height(40.dp))
            Row(
                // The safe zone (the column's padding) keeps the tiles, ring and zoom off the edges.
                Modifier.widthIn(max = 880.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                // The current account first takes the focus ("Invité" when it's the current one).
                val focusIndex = if (guest) -1 else accounts.indexOfFirst { it.selected }.coerceAtLeast(0)
                accounts.forEachIndexed { index, account ->
                    ProfileTile(
                        name = account.name,
                        caption = if (account.selected) "Compte actuel" else null,
                        modifier = if (index == focusIndex) Modifier.focusRequester(first) else Modifier,
                        onClick = { onChoose(account) },
                    ) { AvatarPicture(account.avatar, account.name, PROFILE_AVATAR, 46.sp) }
                }
                if (onGuest != null && accounts.isNotEmpty()) {
                    ProfileTile(
                        name = "Invité",
                        caption = if (guest) "Compte actuel" else "Sans compte",
                        modifier = if (guest) Modifier.focusRequester(first) else Modifier,
                        onClick = onGuest,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(MirazukiIcon.USER, size = 40.dp) }
                    }
                }
                if (onKids != null) {
                    ProfileTile(
                        name = "Enfant",
                        caption = if (kidsExists) "Chaînes choisies" else "À créer",
                        modifier = Modifier,
                        onClick = onKids,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(MirazukiIcon.SMILE, size = 40.dp) }
                    }
                }
                ProfileTile(
                    name = "Ajouter",
                    caption = null,
                    modifier = (if (accounts.isEmpty()) Modifier.focusRequester(first) else Modifier).focusRequester(add),
                    onClick = onAdd,
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(MirazukiIcon.PLUS, size = 34.dp) }
                }
            }
            Spacer(Modifier.height(36.dp))
            Text("Le compte change ton Accueil, tes abonnements, ton historique et le flux de miryo.", fontSize = Dimens.TextMin, color = Palette.TextSecondary)
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
}

@Composable
private fun ProfileTile(name: String, caption: String?, modifier: Modifier, onClick: () -> Unit, picture: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(PROFILE_TILE), horizontalAlignment = Alignment.CenterHorizontally) {
        // The redesign's ring (a gap, then the focus color around the avatar), drawn by this box
        // rather than Surface's border: tv-material drew its outset border inside the round picture.
        Box(
            Modifier
                .size(PROFILE_RING)
                .graphicsLayer {
                    val s = if (focused) FocusScale.Avatar else 1f
                    scaleX = s
                    scaleY = s
                }
                .then(if (focused) Modifier.border(3.dp, Palette.FocusRing, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = onClick,
                modifier = modifier.size(PROFILE_AVATAR).onFocusChanged { focused = it.isFocused },
                shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (Palette.colors.isLight) Palette.Panel else Palette.ControlIdle,
                    contentColor = Palette.TextPrimary,
                    // The ring shows the focus: the tile itself doesn't change ("Ajouter" included).
                    focusedContainerColor = if (Palette.colors.isLight) Palette.Panel else Palette.ControlIdle,
                    focusedContentColor = Palette.TextPrimary,
                ),
            ) { picture() }
        }
        Text(
            name,
            fontSize = 16.sp,
            fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium,
            color = if (focused) Palette.TextPrimary else Palette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 7.dp),
        )
        Text(caption.orEmpty(), fontSize = Dimens.TextMin, color = Palette.TextSecondary, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp).height(18.dp))
    }
}

/** Réglages' categories (left column). */
private enum class SettingsPage(val label: String) { PLAYBACK("Lecture"), DISPLAY("Affichage"), PHONE("Téléphone"), ACCOUNT("Compte"), KIDS("Enfant"), UPDATES("Mises à jour"), ABOUT("À propos") }

/**
 * Réglages (the redesign: full screen, like miryo's): the categories on the left (the one focused
 * shows its settings), and on the right one card per setting with its choices as pills (the
 * current one checked). OK on a pill applies it.
 */
@Composable
fun SettingsDialog(
    settings: Settings,
    accountName: String?,
    onSettings: (Settings) -> Unit,
    onPair: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    /** The pairing dialog is open over Réglages: when it closes, the focus goes back to "Lier un téléphone". */
    pairingOpen: Boolean = false,
    update: UpdateUi = UpdateUi(),
    onCheckUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onWantsBeta: (Boolean) -> Unit = {},
    /** Réglages › Enfant: the channels the kids profile may watch (null: no kids profile, no page). */
    kidChannels: List<dev.miraiijo.lumentube.kids.KidChannel>? = null,
    onRemoveKidChannel: (String) -> Unit = {},
    onChangeKidsPin: () -> Unit = {},
    onDeleteKids: () -> Unit = {},
) {
    MirazukiDialog(onDismissRequest = onDismiss) {
        val first = remember { FocusRequester() }
        val pair = remember { FocusRequester() }
        var wasPairing by remember { mutableStateOf(false) }
        LaunchedEffect(pairingOpen) {
            if (pairingOpen) {
                wasPairing = true
            } else if (wasPairing) {
                wasPairing = false
                withFrameNanos { }
                pair.tryRequestFocus()
            }
        }
        var page by remember { mutableStateOf(SettingsPage.PLAYBACK) }
        val pages = SettingsPage.entries.filter { (it != SettingsPage.ACCOUNT || accountName != null) && (it != SettingsPage.KIDS || kidChannels != null) }
        Column(Modifier.fillMaxSize().mirazukiBackground().safePadding()) {
            Text("Réglages", fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.03).em, color = Palette.TextPrimary)
            Row(Modifier.weight(1f).padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Column(
                    Modifier
                        .width(230.dp)
                        .fillMaxHeight()
                        .liquidGlass(RoundedCornerShape(18.dp))
                        .padding(11.dp)
                        // Left from a pill: back to the current category (the 2D search picked the
                        // one level with the pill, which then showed its page).
                        .focusRestorer()
                        .focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    pages.forEachIndexed { index, item ->
                        SettingsNavItem(
                            item.label,
                            current = item == page,
                            modifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                            onFocus = { page = item },
                        )
                    }
                }
                // One scroll per page: a page scrolled down doesn't make the next one open scrolled.
                androidx.compose.runtime.key(page) { Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    when (page) {
                        SettingsPage.PLAYBACK -> {
                            SettingCard("Lecture automatique", "À la fin d’une vidéo, la suivante démarre après un court compte à rebours.") {
                                Choice("Activée", settings.autoplay) { onSettings(settings.copy(autoplay = true)) }
                                Choice("Désactivée", !settings.autoplay) { onSettings(settings.copy(autoplay = false)) }
                            }
                            SettingCard("Passer les sponsors (SponsorBlock)", "Sponsors, autopromo et rappels d’abonnement sautés automatiquement.") {
                                Choice("Activé", settings.sponsorBlock) { onSettings(settings.copy(sponsorBlock = true)) }
                                Choice("Désactivé", !settings.sponsorBlock) { onSettings(settings.copy(sponsorBlock = false)) }
                            }
                            SettingCard("Volume stable", "Même niveau sonore d’une vidéo à l’autre.") {
                                Choice("Activé", settings.stableVolume) { onSettings(settings.copy(stableVolume = true)) }
                                Choice("Désactivé", !settings.stableVolume) { onSettings(settings.copy(stableVolume = false)) }
                            }
                        }
                        SettingsPage.DISPLAY -> {
                            SettingCard("Apparence", "Auto : clair de 7 h 30 à 19 h 30, sombre le reste du temps.") {
                                Choice("Jour", settings.appearance == 1) { onSettings(settings.copy(appearance = 1)) }
                                Choice("Nuit", settings.appearance == 2) { onSettings(settings.copy(appearance = 2)) }
                                Choice("Auto", settings.appearance == 0) { onSettings(settings.copy(appearance = 0)) }
                            }
                            // The size in the title: the pills never move under the focus as it changes.
                            val size = settings.uiScale
                            SettingCard("Taille de l’interface · $size %", "De $UI_SCALE_MIN à $UI_SCALE_MAX % par pas de $UI_SCALE_STEP %, appliqué tout de suite. Par défaut : $UI_SCALE_DEFAULT %.") {
                                MirazukiButton("Plus petit", contentColor = Palette.ChipText) { onSettings(settings.copy(uiScale = (size - UI_SCALE_STEP).coerceAtLeast(UI_SCALE_MIN))) }
                                MirazukiButton("Plus grand", contentColor = Palette.ChipText) { onSettings(settings.copy(uiScale = (size + UI_SCALE_STEP).coerceAtMost(UI_SCALE_MAX))) }
                                Choice("Par défaut", size == UI_SCALE_DEFAULT) { onSettings(settings.copy(uiScale = UI_SCALE_DEFAULT)) }
                            }
                            SettingCard("DeArrow", "Titres et miniatures de la communauté, sans racolage, quand il y en a.") {
                                Choice("Activé", settings.deArrow) { onSettings(settings.copy(deArrow = true)) }
                                Choice("Désactivé", !settings.deArrow) { onSettings(settings.copy(deArrow = false)) }
                            }
                        }
                        SettingsPage.PHONE -> {
                            SettingCard("Regarder sur la TV", "L’app YouTube du téléphone, liée avec un code TV, envoie ses vidéos ici et sert de télécommande, quand miryo tube est ouvert.") {
                                Choice("Activé", settings.remote) { onSettings(settings.copy(remote = true)) }
                                Choice("Désactivé", !settings.remote) { onSettings(settings.copy(remote = false)) }
                                MirazukiButton("Lier un téléphone", modifier = Modifier.focusRequester(pair), icon = MirazukiIcon.PLUS, onClick = onPair)
                            }
                            // Opening miryo tube from the background needs the overlay permission (adb, once).
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val canOpen = remember(settings.backgroundCast) { android.provider.Settings.canDrawOverlays(context) }
                            SettingCard(
                                "Recevoir même miryo tube fermé",
                                if (settings.backgroundCast && !canOpen) {
                                    "Autorisation à donner une fois depuis l’ordinateur : adb shell appops set dev.miraiijo.lumentube SYSTEM_ALERT_WINDOW allow"
                                } else {
                                    "miryo tube reste prêt en arrière-plan et s’ouvre quand le téléphone envoie une vidéo. Garde environ 50 à 80 Mo de mémoire."
                                },
                            ) {
                                Choice("Activé", settings.backgroundCast) { onSettings(settings.copy(backgroundCast = true, remote = true)) }
                                Choice("Désactivé", !settings.backgroundCast) { onSettings(settings.copy(backgroundCast = false)) }
                            }
                        }
                        SettingsPage.ACCOUNT -> {
                            SettingCard("Compte YouTube", "Les autres comptes liés restent sur la box (« Qui regarde ? »).") {
                                MirazukiButton("Se déconnecter de ${accountName.orEmpty()}", icon = MirazukiIcon.CLOSE, onClick = onSignOut)
                            }
                        }
                        SettingsPage.KIDS -> {
                            val channels = kidChannels.orEmpty()
                            SettingCard(
                                "Chaînes autorisées · ${channels.size}",
                                if (channels.isEmpty()) "Aucune pour l’instant : « Pour Enfant » sur la page d’une chaîne l’ajoute." else "OK sur une chaîne la retire. « Pour Enfant » sur la page d’une chaîne en ajoute une.",
                            ) {
                                // A long list scrolls sideways under the focus.
                                EdgeScroll(24.dp) {
                                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        channels.forEach { channel ->
                                            MirazukiButton(channel.name, icon = MirazukiIcon.CLOSE, contentColor = Palette.ChipText) { onRemoveKidChannel(channel.id) }
                                        }
                                    }
                                }
                            }
                            SettingCard("Code parent", "Demandé pour quitter le profil enfant. Le profil enfant de miryo se quitte dans miryo.") {
                                MirazukiButton("Changer le code", contentColor = Palette.ChipText, onClick = onChangeKidsPin)
                                MirazukiButton("Supprimer le profil enfant", icon = MirazukiIcon.CLOSE, onClick = onDeleteKids)
                            }
                        }
                        SettingsPage.UPDATES -> {
                            val installedBeta = Updater.isBeta(update.installed)
                            BrandLogo(Modifier.height(40.dp))
                            SettingCard(
                                "Version installée · ${update.installed}${if (installedBeta) " (bêta)" else ""}",
                                update.message ?: "miryo tube vérifie une fois par jour, au démarrage.",
                            ) {
                                MirazukiButton(
                                    if (update.busy && update.progress == null) "Vérification…" else "Vérifier",
                                    icon = MirazukiIcon.REFRESH,
                                    contentColor = Palette.ChipText,
                                    onClick = onCheckUpdate,
                                )
                            }
                            SettingCard(
                                "Recevoir les versions bêta",
                                "Les bêtas arrivent plus tôt mais peuvent planter ou contenir des bugs. Sans elles, seules les versions stables sont proposées." +
                                    if (!update.wantsBeta && installedBeta) " Tu restes sur cette bêta jusqu’à la prochaine version stable." else "",
                            ) {
                                Choice("Activé", update.wantsBeta) { onWantsBeta(true) }
                                Choice("Désactivé", !update.wantsBeta) { onWantsBeta(false) }
                            }
                            update.found?.let { found ->
                                SettingCard(
                                    "${if (found.beta) "BÊTA" else "STABLE"} · ${found.versionName}",
                                    found.notes.ifBlank { if (found.beta) "Version bêta : elle peut planter ou contenir des bugs." else "Version stable." },
                                ) {
                                    MirazukiButton(
                                        update.progress?.let { "Téléchargement… ${(it * 100).toInt()} %" } ?: "Installer ${found.versionName}",
                                        contentColor = Palette.ChipText,
                                        onClick = onInstallUpdate,
                                    )
                                }
                            }
                        }
                        SettingsPage.ABOUT -> {
                            // SmartTube's MIT licence asks for its notice in every copy: shown here, and
                            // every licence text is in the APK (assets/licenses).
                            val context = androidx.compose.ui.platform.LocalContext.current
                            val mit = remember { readLicence(context, "SmartTube-MIT.txt") }
                            SettingCard(
                                "Basé sur SmartTube",
                                "miryo tube est un fork de SmartTube (github.com/yuliskov/SmartTube) et utilise son moteur : MediaServiceCore, SharedModules et son ExoPlayer modifié. Appli non officielle, sans lien avec SmartTube, YouTube ou Google.",
                            ) {}
                            SettingCard("Licence de SmartTube", mit) {}
                            SettingCard(
                                "Autres composants",
                                "ExoPlayer, AndroidX, Cronet, OkHttp, RxJava et ZXing : licence Apache 2.0. Glide : licences BSD, MIT et Apache 2.0. Police Inter : SIL Open Font License 1.1. Les textes complets sont dans l’APK (assets/licenses).",
                            ) {}
                        }
                    }
                } }
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            first.tryRequestFocus()
        }
    }
}


@Composable
private fun SettingsNavItem(label: String, current: Boolean, modifier: Modifier, onFocus: () -> Unit) {
    Surface(
        onClick = onFocus,
        modifier = modifier.fillMaxWidth().height(40.dp).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (current) Palette.Selected else Color.Transparent,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = Palette.Focus,
            pressedContentColor = Palette.OnFocus,
        ),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SettingCard(title: String, help: String, choices: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .liquidGlass(RoundedCornerShape(16.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { choices() }
        Text(help, fontSize = Dimens.TextMin, color = if (Palette.colors.isLight) Palette.TextSecondary else SETTINGS_HELP_NIGHT, modifier = Modifier.padding(top = 8.dp))
    }
}

/** A licence text from assets/licenses, its paragraphs joined back into lines (the file wraps at 70 columns). */
private fun readLicence(context: android.content.Context, name: String): String =
    runCatching { context.assets.open("licenses/$name").bufferedReader().use { it.readText() } }.getOrDefault("")
        .trim().split(Regex("\\n\\s*\\n")).joinToString("\n\n") { it.replace(Regex("\\s*\\n\\s*"), " ") }

/** The settings' help text at night (the redesign's, a touch lighter than TextSecondary). */
private val SETTINGS_HELP_NIGHT = Color(0xFFA3A5AD)

/** A choice of a setting: a pill, checked when it's the current one. */
@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) =
    MirazukiButton(label, icon = if (selected) MirazukiIcon.CHECK else null, containerColor = if (selected) Palette.Selected else null, contentColor = Palette.ChipText, onClick = onClick)

@Composable
internal fun SectionLabel(text: String) {
    Text(text, fontSize = Dimens.TextMin, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 2.dp))
}

private val PROFILE_AVATAR = 110.dp
/** The avatar, a 6 dp gap and the 3 dp focus ring. */
private val PROFILE_RING = PROFILE_AVATAR + 18.dp
private val PROFILE_TILE = 140.dp

@Composable
internal fun DialogRow(
    label: String,
    value: String? = null,
    modifier: Modifier = Modifier,
    icon: MirazukiIcon? = null,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(52.dp),
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
        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // An icon in its own 20 dp (the text lines up with the menu's thumbnail); a leading picture keeps 36.
            Box(Modifier.size(if (leading != null) 36.dp else 20.dp), contentAlignment = Alignment.Center) {
                if (leading != null) leading() else if (icon != null) Icon(icon, size = 20.dp)
            }
            Spacer(Modifier.width(12.dp))
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (value != null) Text(value, fontSize = 14.sp, color = LocalContentColor.current.copy(alpha = 0.65f), modifier = Modifier.padding(start = 12.dp))
        }
    }
}

