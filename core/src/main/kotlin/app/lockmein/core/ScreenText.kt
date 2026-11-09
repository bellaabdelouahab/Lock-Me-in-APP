package app.lockmein.core

/**
 * Sexual words and phrases in the text on screen (titles, captions, manga genre tags, search boxes), read through the
 * accessibility tree, so no OCR and almost no battery. Whole words only, any case, any script: "Essex" or "naked eye"
 * style false hits are the price of catching "Ecchi" and "Harem" tags before the pictures load.
 */
object ScreenText {
    val TERMS: List<String> = listOf(
        // English, and the tags manga and anime sites use
        "hentai", "ecchi", "nsfw", "porn", "porno", "pornhub", "xvideos", "xnxx", "xxx", "sex", "sexy", "sexual", "nude", "nudes",
        "naked", "nudity", "lewd", "smut", "erotic", "erotica", "seductive", "onlyfans", "fansly", "fanservice", "fan service",
        "lingerie", "bikini", "panties", "panty", "pantsu", "oppai", "boobs", "busty", "cleavage", "thong", "milf", "harem",
        "r18", "r-18", "18+", "+18", "adults only", "adult content", "mature content",
        // French
        "sexe", "nue", "nues", "seins", "coquine",
        // Arabic
        "سكس", "اباحي", "إباحي", "إباحية", "اباحية", "عارية", "عاري", "جنسي", "جنسية", "بورن",
    )

    private val pattern = Regex(
        TERMS.sortedByDescending { it.length }.joinToString("|", "(?<![\\p{L}\\p{N}])(", ")(?![\\p{L}\\p{N}])") { Regex.escape(it) },
        RegexOption.IGNORE_CASE,
    )

    /** The first sexual term in [text], as written on screen, or null. */
    fun match(text: String): String? = pattern.find(text)?.value
}
