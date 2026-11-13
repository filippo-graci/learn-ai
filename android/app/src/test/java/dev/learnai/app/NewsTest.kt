package dev.learnai.app

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class NewsTest {
    @Test fun essentialsRankByInterestTimesSources() {
        val items = listOf(
            DigestItem("a", "Italy", 1), DigestItem("b", "Tech", 1), DigestItem("c", "Tech", 3),
            DigestItem("d", "Sport", 6), DigestItem("e", "Italy", 2), DigestItem("x", "Tech", 9),
        )
        val w = sectionWeights(List(4) { "Tech" } + "Italy", listOf(-1 to "Sport"))
        assertEquals(1 + 4 * 4.0 / 5, w["Tech"]!!, 1e-9)
        assertEquals(0.25, w["Sport"]!!, 1e-9)  // disliked section
        // x disliked; c 3×4.2, e 2×1.8, b 1×4.2 > a 1×1.8, d 6×0.25 last
        assertEquals(listOf("c", "b", "e", "a", "d"), essentials(items, w, disliked = setOf("x")).map { it.url })
    }

    @Test fun essentialsCapPerSectionThenFill() {
        val items = List(6) { DigestItem("t$it", "Tech", 2) } + DigestItem("i", "Italy", 1)
        val top = essentials(items, emptyMap(), emptySet(), n = 5, perSection = 3).map { it.url }
        assertEquals(listOf("t0", "t1", "t2", "i", "t3"), top)  // Italy gets in before Tech's 4th
    }

    @Test fun newsPerDayLast14() {
        val utc = ZoneOffset.UTC
        fun at(day: String) = LocalDate.parse(day).atTime(10, 0).toInstant(utc).toEpochMilli()
        val p = Progress(news = mapOf("u1" to at("2026-11-10"), "u2" to at("2026-11-10"), "u3" to at("2026-10-28"), "u4" to at("2026-10-27")))
        val days = newsPerDay(p, LocalDate.parse("2026-11-10"), zone = utc)
        assertEquals(14, days.size)
        assertEquals(LocalDate.parse("2026-10-28") to 1, days.first())  // 27 Oct is 15 days back: out
        assertEquals(3, days.sumOf { it.second })
    }
}
