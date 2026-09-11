package com.kaavalan.note.data.export

/**
 * A real RFC 4180 style CSV reader and writer.
 *
 * The pre-2.6.0 importer split the file on newlines and treated any blank line as a
 * section break. That is wrong for the data this app actually exports: an instruction's
 * `updates_json` journal and a station's notes both legitimately contain commas, double
 * quotes, and blank lines inside a quoted field. Splitting on newlines first tore those
 * records in half, and the halves were then silently discarded as unparseable - so an
 * export could report success while dropping exactly the notes the officer most wanted
 * back.
 *
 * This reader is field-aware: it walks the text once, tracks whether it is inside a
 * quoted field, and only treats a newline as a record separator when it is not. CRLF,
 * bare LF, doubled quotes as an escaped quote, Unicode and empty trailing fields are all
 * handled.
 */
object CsvCodec {

    /** Excel needs the byte-order mark to open Tamil names without mojibake. */
    const val BOM = "\uFEFF"

    /** Quote a single field only when it needs it, matching the historic export byte-for-byte. */
    fun field(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n') || value.contains('\r')) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    fun row(values: List<String>): String = values.joinToString(",") { field(it) }

    /**
     * Split CSV text into records of fields.
     *
     * A blank line **outside** a quoted field is preserved as an empty record, because the
     * export uses one as the separator between the persons, instructions and tags blocks.
     * A blank line **inside** a quoted field stays part of that field's value.
     */
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var sawAnyChar = false
        var index = 0

        fun endField() {
            fields.add(current.toString())
            current.setLength(0)
        }

        fun endRecord() {
            endField()
            records.add(fields)
            fields = mutableListOf()
            sawAnyChar = false
        }

        while (index < text.length) {
            val c = text[index]
            when {
                inQuotes -> when {
                    // A doubled quote inside a quoted field is one literal quote.
                    c == '"' && index + 1 < text.length && text[index + 1] == '"' -> {
                        current.append('"')
                        index++
                    }
                    c == '"' -> inQuotes = false
                    else -> current.append(c)
                }
                c == '"' -> { inQuotes = true; sawAnyChar = true }
                c == ',' -> { endField(); sawAnyChar = true }
                c == '\r' -> {
                    // Swallow the LF of a CRLF pair so it is one record break, not two.
                    if (index + 1 < text.length && text[index + 1] == '\n') index++
                    endRecord()
                }
                c == '\n' -> endRecord()
                else -> { current.append(c); sawAnyChar = true }
            }
            index++
        }
        // A file that does not end with a newline still has a final record. A file that
        // does must not gain a phantom empty one.
        if (inQuotes) {
            throw IllegalArgumentException(
                "This CSV file ends inside a quoted value, so it is incomplete. Nothing has been changed.",
            )
        }
        if (sawAnyChar || current.isNotEmpty() || fields.isNotEmpty()) endRecord()
        return records
    }

    /**
     * Group parsed records into blocks, each starting with a header record.
     *
     * A block ends at the next empty record. This replaces the old "split the raw text on
     * blank lines" approach, which could not tell a separator blank line from a blank line
     * inside somebody's notes.
     */
    fun blocks(text: String): List<CsvBlock> {
        val records = parse(text.removePrefix(BOM))
        val blocks = mutableListOf<CsvBlock>()
        var header: List<String>? = null
        var rows = mutableListOf<List<String>>()
        fun flush() {
            header?.let { blocks.add(CsvBlock(it, rows)) }
            header = null
            rows = mutableListOf()
        }
        records.forEach { record ->
            val blank = record.all { it.isEmpty() }
            when {
                blank -> flush()
                header == null -> header = record
                else -> rows.add(record)
            }
        }
        flush()
        return blocks
    }

    data class CsvBlock(val header: List<String>, val rows: List<List<String>>) {
        val headerLine: String get() = header.joinToString(",")
    }
}
