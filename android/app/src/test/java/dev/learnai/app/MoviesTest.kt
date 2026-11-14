package dev.learnai.app

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.encodeToString

class MoviesTest {
    private fun t(id: String, score: Double = 0.0) = Title(id, id, score = score, created = 0)

    @Test fun deletingAListKeepsReviewsAndSharedTitles() {
        val d = MovieData(
            titles = listOf(t("only"), t("reviewed", score = 7.0), t("shared")),
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

    @Test fun halfScores() {
        assertEquals(listOf(8.0, 8.5, 0.0), listOf(nextScore(0.0, 8), nextScore(8.0, 8), nextScore(8.5, 8)))
        assertEquals(10.0 to 0.0, nextScore(3.5, 10) to nextScore(10.0, 10))  // no 10.5
        assertEquals("8" to "8.5", 8.0.scoreText() to 8.5.scoreText())
        assertEquals(scoreColor(8), scoreColor(8.5))
        // stores saved before half scores (whole numbers) still load
        val old = json.decodeFromString<MovieData>("""{"titles":[{"id":"a","name":"A","score":8,"created":0}],"books":{"b":{"score":7}}}""")
        assertEquals(8.0 to 7.0, old.titles[0].score to old.books["b"]!!.score)
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
        val phone = MovieData(listOf(t("a", score = 9.0)), listOf(WatchList("l", "Mine", listOf("a"))))
        val cloud = MovieData(listOf(t("a", score = 3.0), t("b")), listOf(WatchList("l", "Old", emptyList()), WatchList("m", "M")))
        val m = merge(phone, cloud)
        assertEquals(listOf(9.0, 0.0), m.titles.map { it.score })
        assertEquals(listOf("Mine", "M"), m.lists.map { it.name })
    }

    @Test fun tmdbLinkIsOptionalAndSurvivesTheApp() {
        val old = json.decodeFromString<MovieData>("""{"titles":[{"id":"a","name":"A","created":0}]}""")
        assertEquals("", old.titles[0].tmdb)  // stores written before the field still load
        val linked = json.decodeFromString<MovieData>(json.encodeToString(MovieData(listOf(t("a").copy(tmdb = "tv/1396")))))
        assertEquals("tv/1396", linked.titles[0].copy(score = 8.0).tmdb)  // an edit in the app keeps it
    }

    @Test fun tmdbSearchKeepsMoviesAndSeries() {
        val r = tmdbResults("""{"page":1,"results":[
            {"media_type":"movie","id":603,"title":"Matrix","original_title":"The Matrix","release_date":"1999-03-30","overview":"Neo."},
            {"media_type":"person","id":6384,"name":"Keanu Reeves"},
            {"media_type":"tv","id":70523,"name":"Dark","original_name":"Dark","first_air_date":""}]}""")
        assertEquals(listOf(Suggestion("movie/603", "Matrix", "The Matrix", "1999", overview = "Neo."),
            Suggestion("tv/70523", "Dark", "Dark", "", series = true)), r)
    }

    @Test fun bookReviewsMergeAndSurviveListChanges() {
        val phone = MovieData(books = mapOf("han" to BookReview(score = 8.0)))
        val cloud = MovieData(books = mapOf("han" to BookReview(score = 3.0), "other" to BookReview(opinion = "x")))
        assertEquals(mapOf("han" to BookReview(score = 8.0), "other" to BookReview(opinion = "x")), merge(phone, cloud).books)
        val d = MovieData(lists = listOf(WatchList("a", "A")), books = phone.books)
        assertEquals(phone.books, d.withoutList("a").books)
    }

    @Test fun twoDevicesConverge() {
        val base = MovieData(titles = listOf(t("x"), t("y")), lists = listOf(WatchList("l", "L", listOf("x"))))
        // phone: rates x and deletes y; tablet: adds z to the list and reviews a book
        val phone = stamped(base, base.put(t("x", score = 8.0)).withoutTitle("y"), 100)
        val z = t("z").copy(created = 1)
        val tablet = stamped(base, base.put(z).copy(lists = listOf(WatchList("l", "L", listOf("x", "z"))),
            books = mapOf("han" to BookReview(score = 9.0))), 200)
        val a = merge(phone, tablet)
        assertEquals(a, merge(tablet, phone))                  // same result on both devices
        assertEquals(listOf("x", "z"), a.titles.map { it.id })   // y stays deleted
        assertEquals(8.0, a.title("x")!!.score, 0.0)
        assertEquals(listOf("x", "z"), a.lists.single().titles)
        assertEquals(9.0, a.books["han"]!!.score, 0.0)
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
