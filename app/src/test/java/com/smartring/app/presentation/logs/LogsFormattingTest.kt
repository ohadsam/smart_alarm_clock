package com.smartring.app.presentation.logs

import com.smartring.app.domain.model.AppLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exported/copied log text deliberately reads in the opposite order to the screen:
 * the list is newest-first so the latest event is visible without scrolling, while a
 * log file has to read oldest-first or a diagnostic sequence runs backwards for whoever
 * receives it. That inversion is easy to lose in a refactor and impossible to notice
 * without opening an exported file, so it is pinned here.
 */
class LogsFormattingTest {

    private fun entry(id: Long, tag: String, message: String) =
        AppLogEntry(id = id, timestamp = 1_700_000_000_000L + id * 1000, tag = tag, message = message)

    @Test
    fun `export reverses the newest-first screen order into oldest-first`() {
        val newestFirst = listOf(entry(3, "C", "third"), entry(2, "B", "second"), entry(1, "A", "first"))
        val lines = logsAsText(newestFirst).lines()
        assertEquals(3, lines.size)
        assertTrue("oldest entry must come first, got: ${lines[0]}", lines[0].contains("A: first"))
        assertTrue(lines[1].contains("B: second"))
        assertTrue("newest entry must come last, got: ${lines[2]}", lines[2].contains("C: third"))
    }

    @Test
    fun `each line carries a timestamp, the tag and the message`() {
        val line = logsAsText(listOf(entry(1, "Scheduler", "armed #7")))
        assertTrue("missing bracketed timestamp: $line", line.startsWith("["))
        assertTrue("missing tag/message: $line", line.contains("Scheduler: armed #7"))
    }

    @Test
    fun `an empty log exports as an empty string rather than a stray newline`() {
        assertEquals("", logsAsText(emptyList()))
    }

    @Test
    fun `a message containing a newline does not silently split into two entries`() {
        // Nothing logs a multi-line message today, but if something ever does the export
        // gains a line with no timestamp in front of it — worth knowing rather than
        // discovering from a confusing exported file.
        val text = logsAsText(listOf(entry(1, "T", "line one\nline two")))
        assertEquals(2, text.lines().size)
    }
}
