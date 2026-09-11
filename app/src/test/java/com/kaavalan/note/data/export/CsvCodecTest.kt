package com.kaavalan.note.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CSV reader, tested against the shapes the pre-2.6.0 parser silently destroyed.
 *
 * The old implementation split the whole file on newlines and treated any blank line as a
 * section break, so a station note with a blank line in it, or an instruction journal
 * containing a comma or a quote, was torn in half and the halves were then discarded as
 * unparseable rows. An export that reports success while dropping the officer's notes is
 * the worst possible failure for this feature, hence the emphasis here.
 */
class CsvCodecTest {

    @Test
    fun `a quoted field keeps its commas, quotes and newlines`() {
        val notes = "Coastal beat, two outposts.\n\nSecond paragraph with \"quoted\" text."
        val row = CsvCodec.row(listOf("st-1", "Kalakad", notes))
        val parsed = CsvCodec.parse(row).single()
        assertEquals(listOf("st-1", "Kalakad", notes), parsed)
    }

    @Test
    fun `a blank line inside a quoted field is not a record break`() {
        val text = CsvCodec.row(listOf("a", "line one\n\nline two")) + "\n" + CsvCodec.row(listOf("b", "plain"))
        val records = CsvCodec.parse(text)
        assertEquals("two records, not four", 2, records.size)
        assertEquals("line one\n\nline two", records[0][1])
        assertEquals("plain", records[1][1])
    }

    @Test
    fun `CRLF is one record separator, not two`() {
        val records = CsvCodec.parse("a,b\r\nc,d\r\n")
        assertEquals(2, records.size)
        assertEquals(listOf("a", "b"), records[0])
        assertEquals(listOf("c", "d"), records[1])
    }

    @Test
    fun `a CRLF inside a quoted field is preserved as typed`() {
        val value = "first\r\nsecond"
        val records = CsvCodec.parse(CsvCodec.row(listOf("id", value)))
        assertEquals(value, records.single()[1])
    }

    @Test
    fun `a doubled quote decodes to one literal quote`() {
        assertEquals(listOf("say \"hi\""), CsvCodec.parse("\"say \"\"hi\"\"\"").single())
    }

    @Test
    fun `Unicode text survives the round trip`() {
        val tamil = "நாங்குநேரி, காவல் நிலையம்"
        assertEquals(tamil, CsvCodec.parse(CsvCodec.row(listOf(tamil))).single().single())
    }

    @Test
    fun `empty and trailing empty fields are preserved`() {
        assertEquals(listOf("a", "", "c", ""), CsvCodec.parse("a,,c,").single())
    }

    @Test
    fun `a file with no trailing newline still yields its last record`() {
        val records = CsvCodec.parse("a,b\nc,d")
        assertEquals(2, records.size)
        assertEquals(listOf("c", "d"), records[1])
    }

    @Test
    fun `a file that ends inside a quoted value is refused rather than half-read`() {
        val failure = runCatching { CsvCodec.parse("id,\"unterminated") }.exceptionOrNull()
        assertNotNull("a truncated file must not parse as if it were complete", failure)
        assertTrue(
            "the message must say nothing was changed; got ${failure?.message}",
            failure?.message?.contains("Nothing has been changed") == true,
        )
    }

    @Test
    fun `blocks split on separator lines and keep multi-paragraph values intact`() {
        val text = CsvCodec.BOM +
            "id,name\n" +
            CsvCodec.row(listOf("p1", "Ramesh")) + "\n" +
            "\n" +
            "id,notes\n" +
            CsvCodec.row(listOf("st-1", "Coastal beat.\n\nSecond paragraph.")) + "\n" +
            "\n" +
            "subdivision_json\n" +
            CsvCodec.field("""{"version":1,"stations":[]}""") + "\n"
        val blocks = CsvCodec.blocks(text)
        assertEquals(3, blocks.size)
        assertEquals("id,name", blocks[0].headerLine)
        assertEquals(listOf("p1", "Ramesh"), blocks[0].rows.single())
        assertEquals("id,notes", blocks[1].headerLine)
        assertEquals(
            "a blank line inside notes must not end the block",
            "Coastal beat.\n\nSecond paragraph.",
            blocks[1].rows.single()[1],
        )
        assertEquals("subdivision_json", blocks[2].headerLine)
        assertEquals("""{"version":1,"stations":[]}""", blocks[2].rows.single().single())
    }

    @Test
    fun `the byte order mark is stripped from the first header only`() {
        val blocks = CsvCodec.blocks(CsvCodec.BOM + "id,name\n" + "p1,Ramesh\n")
        assertEquals("id,name", blocks.single().headerLine)
    }

    @Test
    fun `only fields that need quoting are quoted`() {
        assertEquals("plain", CsvCodec.field("plain"))
        assertEquals("\"has,comma\"", CsvCodec.field("has,comma"))
        assertEquals("\"has\"\"quote\"", CsvCodec.field("has\"quote"))
        assertEquals("\"has\nnewline\"", CsvCodec.field("has\nnewline"))
    }
}
