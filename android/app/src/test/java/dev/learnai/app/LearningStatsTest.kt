package dev.learnai.app

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class LearningStatsTest {
    @Test fun xpAndNewsCounters() {
        val utc = ZoneOffset.UTC
        fun at(day: String) = LocalDate.parse(day).atTime(10, 0).toInstant(utc).toEpochMilli()
        val p = Progress(
            chapters = mapOf("b/1" to at("2026-11-01"), "b/2" to at("2026-11-09")),
            correct = mapOf("q1" to 0L, "q2" to 0L, "q3" to 0L),
            news = mapOf("u1" to at("2026-11-09"), "u2" to at("2026-11-09"), "u3" to at("2026-11-03"), "u4" to at("2026-11-02")),
        )
        val s = learningStats(p, LocalDate.parse("2026-11-09"), utc)
        assertEquals(2 * CHAPTER_XP + 3 * QUESTION_XP + 4 * NEWS_XP, s.xp)
        assertEquals(2, s.newsToday)
        assertEquals(3, s.newsWeek)  // 3..9 Nov
        assertEquals(4, s.newsTotal)
    }

    @Test fun mergeKeepsBoth() {
        val phone = Progress(chapters = mapOf("b/1" to 5L), quizBest = mapOf("b/1" to 2), news = mapOf("u1" to 9L))
        val cloud = Progress(chapters = mapOf("b/1" to 3L, "b/2" to 4L), quizBest = mapOf("b/1" to 4), correct = mapOf("q" to 1L))
        assertEquals(
            Progress(chapters = mapOf("b/1" to 5L, "b/2" to 4L), correct = mapOf("q" to 1L), quizBest = mapOf("b/1" to 4),
                news = mapOf("u1" to 9L)),
            merge(phone, cloud),
        )
    }

    @Test fun sinceKeepsOnlyTheMonth() {
        val p = Progress(chapters = mapOf("a" to 5L, "b" to 20L), correct = mapOf("q" to 9L), quizBest = mapOf("a" to 3),
            news = mapOf("u" to 10L))
        assertEquals(Progress(chapters = mapOf("b" to 20L), quizBest = mapOf("a" to 3), news = mapOf("u" to 10L)), p.since(10L))
    }

    @Test fun untickTravelsUnlessTickedAgainLater() {
        val phone = Progress(chapters = mapOf("b/1" to 10L), news = mapOf("u" to 10L))
        val tablet = Progress(removed = mapOf("c:b/1" to 20L, "n:u" to 5L))  // chapter unticked after, news before
        val m = merge(phone, tablet)
        assertEquals(emptyMap<String, Long>(), m.chapters)
        assertEquals(mapOf("u" to 10L), m.news)
        assertEquals(mapOf("b/1" to 30L), merge(m.copy(chapters = mapOf("b/1" to 30L)), tablet).chapters)  // re-ticked later
        assertEquals(m, merge(m, m))
    }
}
