package com.kaavalan.note.data.search

/**
 * Tier 1.3 (v2.0): build a safe FTS4 MATCH expression from
 * a free-form user query.
 *
 * The rules:
 *  - tokenize on whitespace AND on hyphens — the FTS4
 *    "porter" tokenizer that indexes this data splits on
 *    hyphens exactly like whitespace, so the query must
 *    split there too or a hyphenated phrase like "R4-5948"
 *    never lines up with the indexed tokens "r4" and "5948"
 *  - drop remaining reserved chars that FTS4 would otherwise
 *    choke on inside a token (`"`, `*`, `+`, `(`, `)`, `:`,
 *    `^`) — these would crash the MATCH parser
 *  - append `*` to each surviving token so a prefix search
 *    works ("ramesh*" matches "Ramesh", "Rameshwaram" etc.)
 *  - return an empty string if the input is empty / blank /
 *    has no surviving tokens (caller short-circuits to
 *    "no results")
 *
 * The expression is **safe to pass to a `MATCH` clause** —
 * no SQL-injection path, no reserved-char surprises. We do
 * NOT concat with the table name (Room does that from the
 * DAO method's parameter).
 */
object SearchQuery {

    fun build(input: String): String {
        if (input.isBlank()) return ""
        val tokens = input
            // The FTS4 "porter" tokenizer (built on the "simple"
            // base tokenizer) treats a hyphen as a token
            // separator, exactly like whitespace, when it indexes
            // text — so a query token must be split on hyphens
            // too, not just whitespace. Stripping the hyphen
            // instead (leaving whitespace as the only separator)
            // merges two indexed words into one search token that
            // never matches the index.
            .split(Regex("[\\s-]+"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { stripFtsReserved(it) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return ""
        return tokens.joinToString(" ") { "$it*" }
    }

    private fun stripFtsReserved(token: String): String {
        // Remove FTS4 reserved characters from the token; if
        // the result is empty, the token is dropped.
        val cleaned = token
            .replace(Regex("[\\\"\\*\\-\\+\\(\\)\\:\\^]"), "")
        return cleaned
    }
}
