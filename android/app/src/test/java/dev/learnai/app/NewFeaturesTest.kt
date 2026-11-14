package dev.learnai.app

import io.github.openspacedrepetition.Rating
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewFeaturesTest {
    private val utc = ZoneOffset.UTC
    private fun d(s: String) = LocalDate.parse(s)
    private fun ms(day: String, hour: Int = 10) = d(day).atTime(hour, 0).toInstant(utc).toEpochMilli()

    // ---- daily goal streak with freezes
    @Test fun streakEarnsAndSpendsFreezes() {
        val met = (1..7).map { d("2026-11-01").plusDays(it - 1L) }.toSet() +  // 1-7: earns a freeze
            setOf(d("2026-11-09"), d("2026-11-10"))                            // 8 missed: the freeze covers it
        val s = goalStreak(met, d("2026-11-10"))
        assertEquals(9, s.current)   // the frozen day keeps the streak but doesn't count
        assertEquals(0, s.freezes)
        assertTrue(s.todayMet)
        val broken = goalStreak(setOf(d("2026-11-01"), d("2026-11-03")), d("2026-11-03"))  // no freeze banked: the gap breaks it
        assertEquals(1, broken.current)
        assertEquals(1, broken.best)
        assertEquals(2, goalStreak(setOf(d("2026-11-08"), d("2026-11-09")), d("2026-11-10")).current)  // today still open
        assertEquals(GoalStreak(0, 0, 0, false), goalStreak(emptySet(), d("2026-11-10")))
    }

    @Test fun goalDaysAnyOneActivity() {
        val days = goalDays(
            focusMinPerDay = mapOf(d("2026-11-01") to 60, d("2026-11-02") to 59),
            readingMsPerDay = mapOf(d("2026-11-03") to 30 * 60_000L),
            reviewsPerDay = mapOf(d("2026-11-04") to 20L, d("2026-11-05") to 19L),
            chapterDays = listOf(d("2026-11-06")),
        )
        assertEquals(setOf(d("2026-11-01"), d("2026-11-03"), d("2026-11-04"), d("2026-11-06")), days)
    }

    // ---- FSRS reviews
    @Test fun fsrsSchedulesFurtherForBetterAnswers() {
        val now = ms("2026-11-09")
        val again = review(null, Rating.AGAIN, now)
        val easy = review(null, Rating.EASY, now)
        assertTrue(again.due < easy.due)
        assertEquals(now, easy.first)
        assertEquals(1, easy.reps)
        val later = review(easy, Rating.GOOD, easy.due)  // answered when due: comes back later still
        assertTrue(later.due - easy.due > easy.due - now)
        assertEquals(easy.first, later.first)
    }

    @Test fun queueDueFirstThenTodaysNewCards() {
        val items = (1..30).map { ReviewItem("c$it", "b", "B", 1, "Ch") }
        val now = ms("2026-11-09", 12)
        val data = ReviewData(cards = mapOf(
            "c1" to CardState(due = now - 1000, first = ms("2026-11-01")),
            "c2" to CardState(due = now + 60_000, first = ms("2026-11-01")),                 // not due yet
            "c3" to CardState(due = now - 5000, first = ms("2026-11-02")),
            "c4" to CardState(due = now - 1, first = ms("2026-11-09", 9), suspended = true),  // flagged; introduced today
        ))
        val q = queue(items, data, now, utc)
        assertEquals(listOf("c3", "c1"), q.take(2).map { it.id })  // oldest due first
        assertEquals(NEW_PER_DAY - 1, q.size - 2)                  // c4 used one of today's new cards
        assertFalse(q.any { it.id in setOf("c2", "c4") })
    }

    @Test fun reviewStatesMergePerCard() {
        val a = ReviewData(cards = mapOf("x" to CardState(reps = 1, updated = 5)), done = mapOf("2026-11-09|phone" to 3))
        val b = ReviewData(cards = mapOf("x" to CardState(reps = 2, updated = 9), "y" to CardState(updated = 1)),
            done = mapOf("2026-11-09|phone" to 2, "2026-11-09|tab" to 4))
        val m = merge(a, b)
        assertEquals(2, m.cards["x"]!!.reps)
        assertEquals(setOf("x", "y"), m.cards.keys)
        assertEquals(7L, perDay(m.done)[d("2026-11-09")])  // phone 3 (max of its copies) + tablet 4
        assertEquals(m, merge(b, a))
    }

    @Test fun dailyPickIsStableForADay() {
        val all = (1..20).map { "h$it" }
        assertEquals(dailyPick(all, d("2026-11-09")) { it }, dailyPick(all.reversed(), d("2026-11-09")) { it })
        assertNotEquals(dailyPick(all, d("2026-11-09")) { it }, dailyPick(all, d("2026-11-10")) { it })
        assertEquals(3, dailyPick(all, d("2026-11-09")) { it }.size)
    }

    // ---- progress: reading time, saved news
    @Test fun readingAddsUpAcrossDevicesAndSavedTravels() {
        val phone = Progress(reading = mapOf("2026-11-09|p" to 10L), saved = mapOf("u" to SavedArticle("T", 5)))
        val tablet = Progress(reading = mapOf("2026-11-09|p" to 4L, "2026-11-09|t" to 20L), removed = mapOf("s:u" to 9))
        val m = merge(phone, tablet)
        assertEquals(30L, perDay(m.reading)[d("2026-11-09")])
        assertEquals(emptyMap<String, SavedArticle>(), m.saved)  // unsaved later on the tablet
        assertEquals(mapOf("u" to SavedArticle("T", 20)), merge(m.copy(saved = mapOf("u" to SavedArticle("T", 20))), tablet).saved)
        val month = Progress(reading = mapOf("2026-10-31|p" to 1L, "2026-11-01|p" to 2L)).since(ms("2026-11-01", 0), utc)
        assertEquals(mapOf("2026-11-01|p" to 2L), month.reading)
    }

    // ---- movies diary, books shelves
    @Test fun diaryAndTimeWatched() {
        val t = Title("m", "M", runtime = 100, created = 0)
        val w = t.setWatched(true, 5)
        assertEquals(listOf(5L), w.views)
        val again = w.watchAgain(9)
        assertEquals(listOf(5L, 9L), again.views)
        assertEquals(200, again.minutesWatched())
        assertEquals(emptyList<Long>(), w.setWatched(false, 10).views)  // untick takes back the viewing it added
        val s = Title("s", "S", series = true, runtime = 50, totalEpisodes = 2, created = 0).plusEpisode(1).plusEpisode(2)
        assertEquals(2, s.episodesSeen)
        assertEquals(100, s.minutesWatched())
        assertEquals(listOf(2L), s.views)  // finishing the season logs a viewing
    }

    @Test fun shelvesSetTheirDates() {
        val r = BookReview().withStatus("reading", 5)
        assertEquals(5L, r.started)
        val f = r.withStatus("finished", 9)
        assertEquals(5L, f.started)
        assertEquals(9L, f.finished)
        assertEquals(null, f.withStatus("reading", 12).finished)
    }

    // ---- reader, search, share
    @Test fun timeLeftInChapter() {
        assertEquals(10, minutesLeft(4600, 0.5))
        assertEquals(0, minutesLeft(4600, 1.0))
    }

    @Test fun searchIgnoresCaseAndAccents() {
        val index = listOf<Pair<Hit, String>>(
            Hit.Book("han", "La società della stanchezza", "Han") to "La società della stanchezza Han",
            Hit.Movie("m", "Dark", "time travel") to "Dark time travel",
        )
        assertEquals(listOf("han"), search(index, "SOCIETA").map { (it as Hit.Book).bookId })
        assertEquals(1, search(index, "travel").size)
        assertEquals(emptyList<Hit>(), search(index, "x"))  // too short
        val snip = snippet("una lunga frase con molte parole prima della stanchezza e altro dopo", "stanchezza", 40)
        assertTrue(snip.startsWith("…") && "stanchezza" in snip && snip.length <= 42)
    }

    @Test fun shareAsMarkdown() {
        val book = Book("b", "Libro", "Autore", chapters = listOf(ChapterInfo(1, "Uno", 10, "a.htm", 0, 0)))
        val h = Highlight("1", "a.htm", "{}", "frase", 0.1, note = "nota", created = 0, favorite = true)
        val md = bookMarkdown(book, BookReview(score = 8.0, opinion = "Bello"), listOf(h)) { "1. Uno" }
        assertTrue(md.startsWith("# Libro\n*Autore*\n"))
        assertTrue("## My review — 8/10" in md && "Bello" in md)
        assertTrue("## Favourite quotes\n\n> frase" in md)
        assertTrue("## 1. Uno\n\n> frase\n\n**Note:** nota" in md)
    }
}
