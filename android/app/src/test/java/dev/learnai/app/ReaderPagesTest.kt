package dev.learnai.app

import org.junit.Assert.assertEquals
import org.junit.Test
import org.readium.r2.navigator.epub.EpubPreferences

class ReaderPagesTest {
    /** Readium rejects some values (Spread.AUTO crashed the reader on every book): every option must build. */
    @Test fun everyReaderOptionIsAcceptedByReadium() {
        Pages.entries.forEach { p ->
            listOf(FONT_MIN, 1.0, FONT_MAX).forEach { EpubPreferences(columnCount = p.columns, spread = p.spread, fontSize = it) }
        }
    }

    @Test fun fontSizeSteps() {
        assertEquals(1.1, stepFontSize(1.0, FONT_STEP), 1e-9)
        assertEquals(0.9, stepFontSize(1.0, -FONT_STEP), 1e-9)
        assertEquals(FONT_MIN, stepFontSize(FONT_MIN, -FONT_STEP), 1e-9)  // stops at the ends
        assertEquals(FONT_MAX, stepFontSize(FONT_MAX, FONT_STEP), 1e-9)
        assertEquals(1.3, stepFontSize(1.2000000001, FONT_STEP), 1e-9)   // no float drift after many taps
    }
}
