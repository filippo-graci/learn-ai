package dev.learnai.app

import org.junit.Assert.assertEquals
import org.junit.Test

class MoviesTest {
    private fun t(id: String, score: Int = 0) = Title(id, id, score = score, created = 0)

    @Test fun deletingAListKeepsReviewsAndSharedTitles() {
        val d = MovieData(
            titles = listOf(t("only"), t("reviewed", score = 7), t("shared")),
            lists = listOf(WatchList("a", "A", listOf("only", "reviewed", "shared")), WatchList("b", "B", listOf("shared"))),
        )
        val after = d.withoutList("a")
        assertEquals(listOf("reviewed", "shared"), after.titles.map { it.id })
        assertEquals(listOf("b"), after.lists.map { it.id })
        assertEquals(listOf(WatchList("b", "B", emptyList())), d.withoutTitle("shared").lists.filter { it.id == "b" })
    }

    @Test fun scoreColours() {
        assertEquals(10, (1..10).map(::scoreColor).toSet().size)  // every score its own colour
    }

    @Test fun episodesAndTags() {
        val s = Title("s", "S", series = true, totalEpisodes = 4, episodes = 2, created = 0)
        assertEquals(0.5f, s.progress())
        assertEquals(null, s.plusEpisode(9).watched)
        assertEquals(9L, s.plusEpisode(9).plusEpisode(9).watched)  // last episode ticks it
        assertEquals(0f, s.copy(totalEpisodes = 0).progress())     // unknown length: only the tick counts
        assertEquals("sci-fi with friends", tagOf("  Sci-Fi   with Friends "))
        assertEquals(listOf("a", "b"), MovieData(listOf(t("x").copy(tags = listOf("b", "a")), t("y").copy(tags = listOf("a")))).allTags())
    }

    @Test fun mergeKeepsThisPhonesVersion() {
        val phone = MovieData(listOf(t("a", score = 9)), listOf(WatchList("l", "Mine", listOf("a"))))
        val cloud = MovieData(listOf(t("a", score = 3), t("b")), listOf(WatchList("l", "Old", emptyList()), WatchList("m", "M")))
        val m = merge(phone, cloud)
        assertEquals(listOf(9, 0), m.titles.map { it.score })
        assertEquals(listOf("Mine", "M"), m.lists.map { it.name })
    }

    @Test fun bookReviewsMergeAndSurviveListChanges() {
        val phone = MovieData(books = mapOf("han" to BookReview(score = 8)))
        val cloud = MovieData(books = mapOf("han" to BookReview(score = 3), "other" to BookReview(opinion = "x")))
        assertEquals(mapOf("han" to BookReview(score = 8), "other" to BookReview(opinion = "x")), merge(phone, cloud).books)
        val d = MovieData(lists = listOf(WatchList("a", "A")), books = phone.books)
        assertEquals(phone.books, d.withoutList("a").books)
    }

    @Test fun twoDevicesConverge() {
        val base = MovieData(titles = listOf(t("x"), t("y")), lists = listOf(WatchList("l", "L", listOf("x"))))
        // phone: rates x and deletes y; tablet: adds z to the list and reviews a book
        val phone = stamped(base, base.put(t("x", score = 8)).withoutTitle("y"), 100)
        val z = t("z").copy(created = 1)
        val tablet = stamped(base, base.put(z).copy(lists = listOf(WatchList("l", "L", listOf("x", "z"))),
            books = mapOf("han" to BookReview(score = 9))), 200)
        val a = merge(phone, tablet)
        assertEquals(a, merge(tablet, phone))                  // same result on both devices
        assertEquals(listOf("x", "z"), a.titles.map { it.id })   // y stays deleted
        assertEquals(8, a.title("x")!!.score)
        assertEquals(listOf("x", "z"), a.lists.single().titles)
        assertEquals(9, a.books["han"]!!.score)
        assertEquals(a, merge(a, a))
    }

    @Test fun highlightsMergePerHighlight() {
        fun h(id: String, note: String = "", updated: Long = 0, deleted: Boolean = false) =
            Highlight(id, "a.htm", "{}", "t", 0.0, note, created = 1, updated = updated, deleted = deleted)
        val phone = listOf(h("1", note = "mine", updated = 5), h("2"))
        val tablet = listOf(h("1", updated = 3), h("2", deleted = true, updated = 4), h("3"))
        assertEquals(listOf(h("1", note = "mine", updated = 5), h("2", deleted = true, updated = 4), h("3")),
            mergeHighlights(phone, tablet))
    }
}
