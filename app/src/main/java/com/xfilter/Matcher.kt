package com.xfilter

import java.text.Normalizer
import java.util.Locale

data class Config(
    val words: List<String>,
    val accounts: List<String>,
    val allow: List<String>,
    val interests: List<String>,
    val interestsMode: Boolean
)

enum class Kind { WORD, ACCOUNT, OFF_INTEREST }

data class Hit(val kind: Kind, val reason: String)

object Matcher {
    private val marks = Regex("\\p{Mn}+")
    private val handleRe = Regex("@([a-z0-9_]{1,15})")
    private val cache = HashMap<String, Regex?>()

    fun norm(s: String): String =
        marks.replace(Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD), "")

    // termo normal = palavra inteira (letras ao redor bloqueiam, números não: "gta6" casa com "gta")
    // termo "re:..." = regex livre
    private fun regexFor(term: String): Regex? = cache.getOrPut(term) {
        try {
            if (term.startsWith("re:")) Regex(term.removePrefix("re:").trim(), RegexOption.IGNORE_CASE)
            else Regex("(?<![a-z])" + Regex.escape(norm(term)) + "(?![a-z])")
        } catch (e: Exception) {
            null
        }
    }

    private fun found(n: String, term: String) = regexFor(term)?.containsMatchIn(n) == true

    fun check(text: String, cfg: Config): Hit? {
        val n = norm(text)
        if (cfg.allow.any { found(n, it) }) return null

        // autor = primeiro @handle da linha do post
        val author = handleRe.find(n)?.groupValues?.get(1)
        if (author != null) {
            for (a in cfg.accounts) {
                if (norm(a).removePrefix("@") == author) return Hit(Kind.ACCOUNT, "@$author")
            }
        }
        for (w in cfg.words) if (found(n, w)) return Hit(Kind.WORD, w)

        if (cfg.interestsMode && cfg.interests.isNotEmpty() && cfg.interests.none { found(n, it) }) {
            return Hit(Kind.OFF_INTEREST, "fora dos interesses")
        }
        return null
    }
}
