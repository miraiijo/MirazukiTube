package dev.miraiijo.miryotube.ui

import java.text.Normalizer

/**
 * Réglages › Rechercher: the settings themselves (« Volume stable · Lecture »), with the words people
 * use for them; OK opens their page. [page] is a page of Réglages by name (« PLAYBACK »). Keep it in
 * step with Réglages when a setting is added or moved. Pure (unit-tested).
 */
data class SettingEntry(val label: String, val page: String, val words: List<String> = emptyList())

object SettingsIndex {
    /** Réglages' pages: name → label, as in its menu. */
    val PAGES = linkedMapOf(
        "ACCOUNT" to "Compte", "KIDS" to "Contrôle parental", "DISPLAY" to "Affichage", "SOUNDS" to "Sons",
        "PLAYBACK" to "Lecture", "PHONE" to "Téléphone", "UPDATES" to "Mises à jour", "ABOUT" to "À propos",
    )

    private fun e(label: String, page: String, vararg words: String) = SettingEntry(label, page, words.toList())

    val ENTRIES: List<SettingEntry> = listOf(
        e("Lecture automatique", "PLAYBACK", "autoplay", "suivante", "enchaîner", "compte à rebours"),
        e("Passer les sponsors (SponsorBlock)", "PLAYBACK", "sponsor", "pub", "publicité", "autopromo", "sauter"),
        e("Volume stable", "PLAYBACK", "volume", "son", "fort", "niveau sonore"),
        e("Apparence (Jour, Nuit, Auto, Comme miryo)", "DISPLAY", "thème", "clair", "sombre", "mode sombre", "nuit", "jour", "miryo"),
        e("Animations : Auto, Économes ou Complètes", "DISPLAY", "animation", "fluide", "performance", "lent", "saccade", "effets", "économe", "rapide"),
        e("Sons de navigation", "SOUNDS", "son", "sons", "clic", "bip", "bruit", "touches", "télécommande", "muet", "couper"),
        e("Taille de l'interface", "DISPLAY", "taille", "zoom", "grand", "petit", "texte", "lisible"),
        e("DeArrow (titres sans racolage)", "DISPLAY", "titres", "miniatures", "clickbait", "racolage"),
        e("Regarder sur la TV (depuis le téléphone)", "PHONE", "cast", "caster", "envoyer", "télécommande", "youtube du téléphone", "chromecast"),
        e("Lier un téléphone", "PHONE", "code tv", "associer", "appairer", "téléphone"),
        e("Recevoir même miryo tube fermé", "PHONE", "arrière-plan", "fermé", "autoriser", "autorisation"),
        e("Compte YouTube (se connecter, se déconnecter)", "ACCOUNT", "connexion", "connecter", "déconnecter", "abonnements", "historique", "playlists", "google"),
        e("Chaînes autorisées pour Enfant", "KIDS", "contrôle", "enfant", "kids", "autorisées", "chaînes"),
        e("Code parental", "KIDS", "code", "pin", "parental", "parent", "verrou"),
        e("Supprimer le profil enfant", "KIDS", "enfant", "supprimer", "profil"),
        e("Vérifier les mises à jour", "UPDATES", "mise à jour", "version", "installer", "nouvelle version"),
        e("Recevoir les versions bêta", "UPDATES", "bêta", "beta", "test"),
        e("Basé sur SmartTube, licences", "ABOUT", "smarttube", "licence", "open source", "version"),
    )

    /** Lower case, without accents: « Bêta » matches « beta ». */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** Settings whose name, then whose words or page, contain [query] (2 letters at least), among [pages]. */
    fun search(query: String, pages: Set<String> = PAGES.keys): List<SettingEntry> {
        val needle = fold(query.trim())
        if (needle.length < 2) return emptyList()
        val shown = ENTRIES.filter { it.page in pages }
        val byLabel = shown.filter { needle in fold(it.label) }
        val byWord = shown.filter { entry ->
            entry !in byLabel && (entry.words.any { needle in fold(it) } || needle in fold(PAGES[entry.page].orEmpty()))
        }
        return (byLabel + byWord).take(HITS_MAX)
    }

    private const val HITS_MAX = 8
}
