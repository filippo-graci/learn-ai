package dev.learnai.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * Learning progress: chapters finished, quiz questions answered right, news marked read.
 * XP is derived from these, never stored: unticking a chapter or an article takes its XP back.
 */
@Serializable
data class Progress(
    val chapters: Map<String, Long> = emptyMap(),  // chapterKey → finished at (epoch ms)
    val correct: Map<String, Long> = emptyMap(),   // quiz item id → first answered right at
    val quizBest: Map<String, Int> = emptyMap(),   // chapterKey → best quiz score
    val news: Map<String, Long> = emptyMap(),      // article URL → marked read at
    /** Unticks / unsaves, so they reach the other devices: "c:<chapterKey>" / "n:<url>" / "s:<url>" → at. */
    val removed: Map<String, Long> = emptyMap(),
    /** Time in the reader: "yyyy-MM-dd|<device id>" → ms. Per device, so phone + tablet add up instead of overwriting. */
    val reading: Map<String, Long> = emptyMap(),
    /** News saved for later: article URL → title and when. */
    val saved: Map<String, SavedArticle> = emptyMap(),
    /** Read articles' digest section and political lean: URL → "Italy|L" (lean may be empty). */
    val newsTopic: Map<String, String> = emptyMap(),
)

@Serializable
data class SavedArticle(val title: String, val at: Long)

/** This install's short random id: keys per-device counters (reading time, reviews done) so devices add up. */
fun deviceId(context: Context): String = context.getSharedPreferences("device", Context.MODE_PRIVATE).let { p ->
    p.getString("id", null) ?: UUID.randomUUID().toString().take(8).also { p.edit().putString("id", it).apply() }
}

fun dayKey(day: LocalDate, device: String) = "$day|$device"

/** "yyyy-MM-dd|device" counters → total per day over all devices. */
fun perDay(m: Map<String, Long>): Map<LocalDate, Long> =
    m.entries.groupBy { LocalDate.parse(it.key.substringBefore('|')) }.mapValues { (_, l) -> l.sumOf { it.value } }

/** 1 XP per this many minutes in the reader. */
const val READING_XP_MINUTES = 5

const val CHAPTER_XP = 50
const val QUESTION_XP = 10  // first right answer only: retaking a quiz can't farm XP
const val NEWS_XP = 2

fun chapterKey(bookId: String, n: Int) = "$bookId/$n"

data class LearningStats(
    val xp: Int, val chapters: Int, val correct: Int, val newsToday: Int, val newsWeek: Int, val newsTotal: Int,
    val readingMin: Int = 0,
)

fun learningStats(p: Progress, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): LearningStats {
    val newsDays = p.news.values.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    val readingMin = (p.reading.values.sum() / 60_000).toInt()
    return LearningStats(
        readingMin = readingMin,
        xp = p.chapters.size * CHAPTER_XP + p.correct.size * QUESTION_XP + p.news.size * NEWS_XP + readingMin / READING_XP_MINUTES,
        chapters = p.chapters.size, correct = p.correct.size,
        newsToday = newsDays.count { it == today }, newsWeek = newsDays.count { it > today.minusDays(7) },
        newsTotal = p.news.size,
    )
}

/** Only what happened from [fromMs] on (best quiz scores have no date: kept). */
fun Progress.since(fromMs: Long, zone: ZoneId = ZoneId.systemDefault()) = copy(
    chapters = chapters.filterValues { it >= fromMs }, correct = correct.filterValues { it >= fromMs },
    news = news.filterValues { it >= fromMs },
    reading = reading.filterKeys {
        LocalDate.parse(it.substringBefore('|')).atStartOfDay(zone).toInstant().toEpochMilli() >= fromMs
    },
)

/**
 * Two devices' copies → one. A chapter / article is ticked unless it was unticked later than its latest tick;
 * a question counts from its first right answer; best quiz score wins.
 */
fun merge(a: Progress, b: Progress): Progress {
    fun <V> union(x: Map<String, V>, y: Map<String, V>, pick: (V, V) -> V) =
        (x.keys + y.keys).associateWith { k -> listOfNotNull(x[k], y[k]).reduce(pick) }
    val removed = union(a.removed, b.removed, ::maxOf)
    fun alive(prefix: String, m: Map<String, Long>) = m.filter { (k, t) -> (removed[prefix + k] ?: -1) < t }
    return Progress(
        chapters = alive("c:", union(a.chapters, b.chapters, ::maxOf)), correct = union(a.correct, b.correct, ::minOf),
        quizBest = union(a.quizBest, b.quizBest, ::maxOf), news = alive("n:", union(a.news, b.news, ::maxOf)),
        removed = removed,
        reading = union(a.reading, b.reading, ::maxOf),  // each device only grows its own keys
        saved = union(a.saved, b.saved) { x, y -> if (x.at >= y.at) x else y }
            .filter { (url, s) -> (removed["s:$url"] ?: -1) < s.at },
        newsTopic = b.newsTopic + a.newsTopic,
    )
}

/**
 * The one observable copy of [Progress] that every screen reads (Library, Reader, Quiz, News, Focus),
 * saved to filesDir/progress.json on every change and synced with the private site (/progress, functions/progress.js)
 * by syncDoc: a few seconds after each change and on Library visits. Every device merges ([merge]), so phone and
 * tablet share one progress; a reinstall gets it back on its first sync.
 * ponytail: a JSON file, not Room.
 */
object Learning {
    var progress by mutableStateOf(Progress())
        private set
    private var file: File? = null
    private val io = Dispatchers.IO.limitedParallelism(1)  // one upload at a time: each sends the latest state
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var scheduled: Job? = null
    /** Ticking ten news in a row is one upload, not ten: KV's free tier allows ~1000 writes a day for everything. */
    private const val UPLOAD_DELAY_MS = 5_000L

    fun init(context: Context) = synchronized(this) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "progress.json")
        progress = if (!f.exists()) Progress() else runCatching { json.decodeFromString<Progress>(f.readText()) }
            .getOrElse { f.renameTo(File(f.path + ".bad")); Progress() }  // keep a broken file for a look, don't overwrite it
        file = f
    }

    /**
     * Returns true if this call changed the state. local = a change made here: unticks get a tombstone and a sync is
     * scheduled; false = merging the cloud copy in (its tombstones are already in it).
     */
    private fun change(local: Boolean = true, f: (Progress) -> Progress): Boolean = synchronized(this) {
        val target = checkNotNull(file) { "Learning.init() not called" }
        val before = progress
        var p = f(before)
        if (p == before) return false
        if (local) {
            val gone = (before.chapters.keys - p.chapters.keys).map { "c:$it" } + (before.news.keys - p.news.keys).map { "n:$it" } +
                (before.saved.keys - p.saved.keys).map { "s:$it" }
            if (gone.isNotEmpty()) p = p.copy(removed = p.removed + gone.associateWith { now() })
        }
        progress = p
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.encodeToString(p))
        tmp.renameTo(target)
        if (local) {
            scheduled?.cancel()
            scheduled = scope.launch { delay(UPLOAD_DELAY_MS); runCatching { syncNow() } }
        }
        true
    }

    /** Merges the cloud copy in and pushes what it lacks. Call on tab visits; offline is normal. */
    suspend fun sync(): Result<Unit> = withContext(io) { runCatching { syncNow() } }

    private fun syncNow() = syncDoc("progress", Progress.serializer()) { remote ->
        change(local = false) { merge(it, remote) }
        progress
    }

    private fun now() = System.currentTimeMillis()

    /** True if the chapter was newly marked finished. */
    fun setChapter(bookId: String, n: Int, done: Boolean): Boolean = chapterKey(bookId, n).let { k ->
        change { it.copy(chapters = if (done) it.chapters + (k to (it.chapters[k] ?: now())) else it.chapters - k) } && done
    }

    /** XP this answer earned: only the first right answer to a question pays. */
    fun answered(questionId: String, right: Boolean): Int =
        if (right && change { it.copy(correct = it.correct + (questionId to (it.correct[questionId] ?: now()))) }) QUESTION_XP else 0

    fun quizDone(bookId: String, n: Int, score: Int) = chapterKey(bookId, n).let { k ->
        change { it.copy(quizBest = it.quizBest + (k to maxOf(score, it.quizBest[k] ?: 0))) }
    }

    /** topic = "category|lean" from the digest page, kept for the monthly "what you read" stats. */
    fun setNews(url: String, read: Boolean, topic: String? = null) = change {
        it.copy(
            news = if (read) it.news + (url to (it.news[url] ?: now())) else it.news - url,
            newsTopic = if (read && topic != null) it.newsTopic + (url to topic) else it.newsTopic,
        )
    }

    fun setSaved(url: String, title: String, saved: Boolean) = change {
        it.copy(saved = if (saved) it.saved + (url to (it.saved[url] ?: SavedArticle(title.take(300), now()))) else it.saved - url)
    }

    /** Time spent in the reader, added to today's counter of this device. */
    fun addReading(context: Context, ms: Long) {
        if (ms <= 0) return
        val k = dayKey(LocalDate.now(), deviceId(context))
        change { it.copy(reading = it.reading + (k to (it.reading[k] ?: 0L) + ms)) }
    }
}
