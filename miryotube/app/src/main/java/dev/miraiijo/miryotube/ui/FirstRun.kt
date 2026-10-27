package dev.miraiijo.miryotube.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Text

/** Whether the first-launch screen is still to show: a new install only, until it is answered. */
object FirstRun {
    private const val PREFS = "setup"
    private const val KEY_DONE = "done"

    fun pending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DONE)) {
            // An install that was already updated (in use before this version) never sees it.
            val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
            val fresh = info != null && info.firstInstallTime == info.lastUpdateTime
            prefs.edit { putBoolean(KEY_DONE, !fresh) }
        }
        return !prefs.getBoolean(KEY_DONE, true)
    }

    fun finish(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_DONE, true) }
}

/**
 * miryo tube's short first launch: without miryo, the box's fluidity first (measured, then
 * Économes or Complètes recommended; with miryo, its choice is followed). Then one question, the YouTube account (the usual sign-in by code
 * and QR), or « Sans compte ». It closes by itself once an account is linked; Retour = sans compte.
 */
@Composable
fun FirstRunTube(withMiryo: Boolean, animations: String, onAnimations: (String) -> Unit, onSignIn: () -> Unit, onDone: () -> Unit) {
    // With miryo, its animations are followed (« Comme miryo »): no question.
    var fluidity by remember { mutableStateOf(!withMiryo) }
    if (fluidity) return FluidityPage(animations, onAnimations) { fluidity = false }
    MiryoDialog(onDismissRequest = onDone) {
        val connect = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().miryoBackground(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(horizontal = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandLogo(Modifier.height(40.dp))
                Spacer(Modifier.height(22.dp))
                Text("Bienvenue dans miryo tube.", color = Palette.TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(
                    "YouTube sans publicité. Connecte ton compte pour retrouver tes abonnements et ton historique : tu scannes un code avec ton téléphone, rien à taper sur la TV. Ou regarde sans compte.",
                    color = Palette.TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp).widthIn(max = 620.dp),
                )
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                    MiryoButton("Connecter mon compte", modifier = Modifier.focusRequester(connect), onClick = onSignIn)
                    MiryoButton("Sans compte", onClick = onDone)
                }
                Text(
                    if (withMiryo) "Chaque profil de miryo peut avoir son compte : Réglages › Profils dans miryo." else "Sans compte : tendances, recherche et lecture, sans abonnements ni historique.",
                    color = Palette.TextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { connect.requestFocus() }
        }
    }
}

/** First launch without miryo: the fluidity test (once), then the recommended animations. */
@Composable
private fun FluidityPage(animations: String, onAnimations: (String) -> Unit, onNext: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var testing by remember { mutableStateOf(dev.miraiijo.miryotube.system.LowEnd.measured(context) == null) }
    if (testing) {
        FluidityTest { late ->
            if (late != null) dev.miraiijo.miryotube.system.LowEnd.saveMeasure(context, late)
            testing = false
        }
        return
    }
    MiryoDialog(onDismissRequest = onNext) {
        val first = remember { FocusRequester() }
        Box(Modifier.fillMaxSize().miryoBackground(), contentAlignment = Alignment.Center) {
            Column(
                Modifier.padding(horizontal = 96.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BrandLogo(Modifier.height(40.dp))
                Spacer(Modifier.height(22.dp))
                Text("Fluidité", color = Palette.TextPrimary, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Text(
                    "miryo tube a mesuré ta box pour choisir ses animations. Garde la recommandation pour que tout reste fluide ; ça se change ensuite dans Réglages › Affichage.",
                    color = Palette.TextSecondary,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp, bottom = 22.dp).widthIn(max = 620.dp),
                )
                AnimationsChoice(animations, onAnimations, first)
                MiryoButton("Continuer", modifier = Modifier.padding(top = 22.dp), onClick = onNext)
            }
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { first.requestFocus() }
        }
    }
}
