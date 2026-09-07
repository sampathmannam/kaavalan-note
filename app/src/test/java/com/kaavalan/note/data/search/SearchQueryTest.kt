package com.kaavalan.note.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tier 1.3 (v2.0): the FTS4 search query builder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SearchQueryTest {

    @Test
    fun `empty string returns empty match expression`() {
        assertEquals("", SearchQuery.build(""))
    }

    @Test
    fun `whitespace-only string returns empty match expression`() {
        assertEquals("", SearchQuery.build("   "))
    }

    @Test
    fun `single token is suffixed with star for prefix match`() {
        assertEquals("ramesh*", SearchQuery.build("ramesh"))
    }

    @Test
    fun `multiple tokens are joined with spaces, each suffixed with star`() {
        assertEquals("ramesh* kumar*", SearchQuery.build("ramesh kumar"))
    }

    @Test
    fun `FTS4 reserved characters are stripped from each token`() {
        // The double-quote, asterisk, minus, plus, paren, colon,
        // and caret are all reserved by FTS4. The builder
        // strips them so the MATCH parser doesn't crash.
        val expr = SearchQuery.build("r\"amesh")
        assertEquals("ramesh*", expr)
    }

    @Test
    fun `tokens made entirely of reserved characters are dropped`() {
        // The builder strips FTS4 reserved characters per
        // token, then appends `*` to the surviving prefix.
        // "**" is all reserved (gets dropped), "!!" is
        // non-reserved (survives as "!!*").
        val expr = SearchQuery.build("ramesh ** !! kumar")
        assertEquals("ramesh* !!* kumar*", expr)
    }

    @Test
    fun `case is preserved (FTS4 MATCH is case-insensitive on ASCII)`() {
        assertEquals("Temple*", SearchQuery.build("Temple"))
    }

    @Test
    fun `internal hyphen splits into separate tokens like the FTS4 tokenizer does`() {
        // Regression for a data-loss bug: the FTS4 "porter"
        // tokenizer splits indexed text on hyphens (same as
        // whitespace), so a hyphenated query like "R4-5948-longtext"
        // must split into "R4", "5948", "longtext" too. The old
        // implementation only stripped the hyphen out of the
        // whitespace token, merging it into the single search term
        // "R45948longtext*" which can never match the index.
        val expr = SearchQuery.build("R4-5948-longtext")
        assertEquals("R4* 5948* longtext*", expr)
    }

    @Test
    fun `hyphenated word with surrounding whitespace tokens still splits correctly`() {
        val expr = SearchQuery.build("CASE-4521 needs follow-up")
        assertEquals("CASE* 4521* needs* follow* up*", expr)
    }

    @Test
    fun `standalone or repeated hyphens between words collapse like whitespace`() {
        val expr = SearchQuery.build("ramesh--kumar")
        assertEquals("ramesh* kumar*", expr)
    }

    @Test
    fun `multibyte unicode passes through (FTS4 handles it after the lowercase fold)`() {
        // Indian / Tamil / Hindi tokens are valid for FTS4
        // MATCH after the porter-stemmer lowercases them.
        val expr = SearchQuery.build("காவலன்")
        assertTrue("multibyte input must produce a non-empty expression", expr.isNotEmpty())
    }
}
