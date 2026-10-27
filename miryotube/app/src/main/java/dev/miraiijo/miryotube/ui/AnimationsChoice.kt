package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.miraiijo.miryotube.system.LowEnd

/**
 * Animations, in the first launch and in Réglages › Affichage, as in miryo: what was measured on
 * this box, Économes or Complètes with the recommended one marked (choosing it means « Auto »),
 * « Comme miryo » when miryo is there, « Refaire le test ». Complètes on a modest box asks first,
 * then stays flagged. [first] goes to the recommended choice.
 */
@Composable
fun AnimationsChoice(animations: String, onAnimations: (String) -> Unit, first: FocusRequester? = null) {
    val context = LocalContext.current
    // Read for its state: a new test recomposes this block.
    LowEnd.detectedLowEnd(context)
    val report = remember(LowEnd.measured(context)) { LowEnd.report(context) }
    val miryo = dev.miraiijo.miryotube.link.MiryoLook.current != null
    var testing by remember { mutableStateOf(false) }
    var confirmFull by remember { mutableStateOf(false) }
    val recommended = if (report.low) LowEnd.ECO else LowEnd.FULL
    val chosen = when (animations) {
        LowEnd.AUTO -> recommended
        // Without miryo, « Comme miryo » means Auto.
        LowEnd.MIRYO -> if (miryo) LowEnd.MIRYO else recommended
        else -> animations
    }
    val reduced = LowEnd.reduced(context)
    val pick = { choice: String -> onAnimations(if (choice == recommended) LowEnd.AUTO else choice) }
    val warning = if (Palette.colors.isLight) Color(0xFFA65200) else Palette.Warning
    Column(Modifier.widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Ta box : " + (if (report.low) "modeste" else "puissante") + ". Recommandé : " + (if (report.low) "Économes." else "Complètes."),
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary,
        )
        Text(LowEnd.describe(report).replaceFirstChar { it.uppercase() } + ".", fontSize = 13.sp, color = Palette.TextSecondary)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (miryo) Pick("Comme miryo", chosen == LowEnd.MIRYO) { onAnimations(LowEnd.MIRYO) }
            Pick(
                if (recommended == LowEnd.ECO) "Économes (recommandé)" else "Économes",
                chosen == LowEnd.ECO,
                if (first != null && recommended == LowEnd.ECO) Modifier.focusRequester(first) else Modifier,
            ) { pick(LowEnd.ECO) }
            Pick(
                if (recommended == LowEnd.FULL) "Complètes (recommandé)" else "Complètes",
                chosen == LowEnd.FULL,
                if (first != null && recommended == LowEnd.FULL) Modifier.focusRequester(first) else Modifier,
            ) { if (report.low && chosen != LowEnd.FULL) confirmFull = true else pick(LowEnd.FULL) }
            Pick(if (report.latePercent == null) "Faire le test" else "Refaire le test", false) { testing = true }
        }
        Text(
            when (chosen) {
                LowEnd.MIRYO -> "Comme miryo suit son réglage : " + (if (reduced) "Économes" else "Complètes") + " en ce moment."
                LowEnd.ECO -> "Économes : pages sans fondu, pas de reflet sur les vignettes, plus fluide sur une petite box."
                else -> "Complètes : tous les effets."
            },
            fontSize = 13.sp, color = Palette.TextSecondary,
        )
        if (report.low && !reduced) {
            Text(
                "Attention : ta box est modeste. Avec toutes les animations, le défilement peut saccader et les pages s'ouvrir plus lentement. Reviens à Économes si c'est le cas.",
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = warning,
            )
        }
    }
    if (confirmFull) {
        MiryoDialog(onDismissRequest = { confirmFull = false }) {
            val cancel = remember { FocusRequester() }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 560.dp).liquidGlass(androidx.compose.foundation.shape.RoundedCornerShape(20.dp), flat = true).padding(26.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Toutes les animations ?", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary)
                    Text(
                        "Ta box est modeste (" + LowEnd.describe(report) + "). Avec toutes les animations, le défilement peut saccader et les pages s'ouvrir plus lentement. Tu pourras revenir à Économes à tout moment dans Réglages › Affichage.",
                        fontSize = 15.sp, lineHeight = 21.sp, color = Palette.TextSecondary,
                    )
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                        MiryoButton("Annuler", modifier = Modifier.focusRequester(cancel)) { confirmFull = false }
                        MiryoButton("Activer quand même") {
                            confirmFull = false
                            pick(LowEnd.FULL)
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { cancel.requestFocus() }
            }
        }
    }
    if (testing) {
        FluidityTest { late ->
            testing = false
            if (late != null) LowEnd.saveMeasure(context, late)
        }
    }
}

/** A choice of the setting: a pill, checked when it's the current one (like Réglages' choices). */
@Composable
private fun Pick(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) =
    MiryoButton(label, modifier = modifier, icon = if (selected) MiryoIcon.CHECK else null, containerColor = if (selected) Palette.Selected else null, contentColor = Palette.ChipText, onClick = onClick)
