package com.sporez.actualist.core.importer

import com.sporez.actualist.core.rules.PlatformRegex
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Upstream's payee-name handling for imported transactions (`sync.ts` `normalizePayeeName`). The
 * `transactions-import` default is title case, so a new payee created by an import reads
 * "Starbucks Coffee", not "STARBUCKS COFFEE". Bank Sync keeps the provider's name as sent.
 */
enum class ImportPayeeNameNormalization {
    ORIGINAL,
    TITLE_CASE,
    ;

    fun normalize(name: String): String = when (this) {
        ORIGINAL -> name
        TITLE_CASE -> UpstreamTitleCase.title(name)
    }
}

/** Port of `server/accounts/title/index.ts`. */
internal object UpstreamTitleCase {
    /** `lower-case.ts`: conjunctions, articles and prepositions stay lower case unless they open a sentence. */
    private val lowerCaseWords: Set<String> = setOf(
        "for", "and", "nor", "but", "or", "yet", "so", "a", "an", "the", "aboard", "about", "above", "across", "after",
        "against", "along", "amid", "among", "anti", "around", "as", "at", "before", "behind", "below", "beneath",
        "beside", "besides", "between", "beyond", "by", "concerning", "considering", "despite", "down", "during",
        "except", "excepting", "excluding", "following", "from", "in", "inside", "into", "like", "minus", "near", "of",
        "off", "on", "onto", "opposite", "over", "past", "per", "plus", "regarding", "round", "save", "since", "than",
        "through", "to", "toward", "towards", "under", "underneath", "unlike", "until", "up", "upon", "versus", "via",
        "with", "within", "without",
    )

    /** `specials.ts`: words restored to their canonical spelling. */
    private val specials: List<String> = listOf(
        "CLI", "API", "HTTP", "HTTPS", "JSX", "DNS", "URL", "CI", "CDN", "GitHub", "CSS", "JS",
        "JavaScript", "TypeScript", "HTML", "WordPress", "JavaScript", "Next.js", "Node.js",
    )

    private val wordPattern: Pattern = Pattern.compile(
        "(?:(?:(\\s?(?:^|[.\\(\\)!?;:\"-])\\s*)($TITLE_CASE_CHARACTER))|($TITLE_CASE_CHARACTER))" +
            "($TITLE_CASE_CHARACTER*[’']*$TITLE_CASE_CHARACTER*)",
        PlatformRegex.unicodeFlag,
    )

    private val specialPatterns: List<Pair<Pattern, String>> = specials.map { special ->
        Pattern.compile("\\b$special\\b", Pattern.CASE_INSENSITIVE or PlatformRegex.unicodeFlag) to special
    }

    fun title(input: String): String {
        val lowered = input.lowercase()
        val builder = StringBuilder()
        var cursor = 0
        val matcher = wordPattern.matcher(lowered)
        while (matcher.find()) {
            builder.append(lowered, cursor, matcher.start())
            cursor = matcher.end()
            val whole = matcher.group()
            val lead = matcher.group(1) ?: ""
            val forced: String? = matcher.group(2)
            val lower: String? = matcher.group(3)
            val rest = matcher.group(4) ?: ""
            builder.append(replacement(whole, lead, forced, lower, rest))
        }
        builder.append(lowered, cursor, lowered.length)
        var output = builder.toString()
        for ((pattern, special) in specialPatterns) {
            output = pattern.matcher(output).replaceAll(Matcher.quoteReplacement(special))
        }
        return output
    }

    private fun replacement(whole: String, lead: String, forced: String?, lower: String?, rest: String): String {
        // `parseMatch`: a match that opens with whitespace drops it; one that opens with a parenthesis is left alone.
        val first = whole.firstOrNull()
        val parsed: String? = when {
            first != null && first.isWhitespace() -> whole.drop(1)
            first == '(' || first == ')' -> null
            else -> whole
        }
        if (parsed == null) return whole
        if (forced == null && (((lower ?: "") + rest) in lowerCaseWords)) return parsed
        return lead + (lower ?: forced ?: "").uppercase() + rest
    }
}
