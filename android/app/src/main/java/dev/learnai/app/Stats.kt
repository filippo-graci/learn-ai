package dev.learnai.app

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** What the Stats tab covers: this month (default; the level starts again each month), this year, or everything. */
enum class Period { Month, Year, All }

/** First day of [period] around [today]; null = all time. */
fun Period.start(today: LocalDate): LocalDate? = when (this) {
    Period.Month -> today.withDayOfMonth(1)
    Period.Year -> today.withDayOfYear(1)
    Period.All -> null
}

fun startMs(day: LocalDate?, zone: ZoneId = ZoneId.systemDefault()) = day?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: 0L

fun dateOf(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

// A day meets the daily goal with any one of these (Duolingo's daily goal, made of this app's activities).
const val GOAL_FOCUS_MIN = 60
const val GOAL_READING_MIN = 30
const val GOAL_REVIEWS = 20
/** Days in a row that earn a streak freeze; at most MAX_FREEZES are banked. */
const val FREEZE_EVERY = 7
const val MAX_FREEZES = 2

/** Days that met the goal. */
fun goalDays(
    focusMinPerDay: Map<LocalDate, Int>, readingMsPerDay: Map<LocalDate, Long>, reviewsPerDay: Map<LocalDate, Long>,
    chapterDays: Collection<LocalDate>,
): Set<LocalDate> =
    focusMinPerDay.filterValues { it >= GOAL_FOCUS_MIN }.keys + readingMsPerDay.filterValues { it >= GOAL_READING_MIN * 60_000L }.keys +
        reviewsPerDay.filterValues { it >= GOAL_REVIEWS }.keys + chapterDays

data class GoalStreak(val current: Int, val best: Int, val freezes: Int, val todayMet: Boolean)

/**
 * Days in a row that met the goal, up to today (today still open: an unmet today doesn't break it). A missed day
 * spends a banked freeze instead of breaking the streak (the day itself doesn't count); every FREEZE_EVERY met days
 * in a row earn one, up to MAX_FREEZES.
 */
fun goalStreak(met: Set<LocalDate>, today: LocalDate): GoalStreak {
    val first = met.minOrNull() ?: return GoalStreak(0, 0, 0, false)
    var run = 0; var best = 0; var freezes = 0; var sinceEarn = 0
    var d = first
    while (d <= today) {
        when {
            d in met -> {
                run++; sinceEarn++
                if (sinceEarn == FREEZE_EVERY) { freezes = minOf(MAX_FREEZES, freezes + 1); sinceEarn = 0 }
            }
            d == today -> {}  // still open
            freezes > 0 && run > 0 -> freezes--
            else -> { run = 0; sinceEarn = 0 }
        }
        best = maxOf(best, run)
        d = d.plusDays(1)
    }
    return GoalStreak(run, best, freezes, today in met)
}

@Composable
fun GoalCard(streak: GoalStreak, focusToday: Int, readingToday: Long, reviewsToday: Long, chapterToday: Boolean) =
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("🔥 Daily goal", style = MaterialTheme.typography.labelLarge)
            Text((if (streak.current == 1) "1 day" else "${streak.current} days") + if (streak.freezes > 0) "  ·  ❄️ ${streak.freezes}" else "",
                style = MaterialTheme.typography.headlineSmall)
            Text("best ${streak.best} · today " + if (streak.todayMet) "done ✅" else "not yet", style = MaterialTheme.typography.bodyMedium)
            Text("Any one a day: ${focusToday}/$GOAL_FOCUS_MIN focus min · ${readingToday / 60_000}/$GOAL_READING_MIN reading min · " +
                "$reviewsToday/$GOAL_REVIEWS reviews · " + (if (chapterToday) "a chapter ✅" else "or finish a chapter"),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Text("❄️ $FREEZE_EVERY days in a row earn a freeze (max $MAX_FREEZES): it saves the streak on a missed day.",
                style = MaterialTheme.typography.bodySmall)
        }
    }

/** Books (StoryGraph-style): finished, reading, average score; this year's goal (per device). */
@Composable
fun BooksCard(period: Period, today: LocalDate) {
    val context = LocalContext.current
    val from = startMs(period.start(today))
    val books = Movies.data.books
    val finished = books.values.filter { (it.finished ?: 0) >= from && it.finished != null }
    val scores = finished.map { it.score }.filter { it > 0 }
    val reading = books.values.count { it.status == "reading" }
    val prefs = context.getSharedPreferences("goals", Context.MODE_PRIVATE)
    var goal by remember { mutableStateOf(prefs.getInt("books-${today.year}", 0)) }
    val thisYear = books.values.count { it.finished != null && dateOf(it.finished).year == today.year }
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("${finished.size} finished · $reading reading", style = MaterialTheme.typography.headlineSmall)
            if (scores.isNotEmpty()) Text("average score %.1f".format(scores.average()), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("${today.year} goal: $thisYear of", Modifier.padding(end = 8.dp))
                OutlinedTextField(if (goal > 0) "$goal" else "", { v ->
                    goal = v.filter(Char::isDigit).take(3).toIntOrNull() ?: 0
                    prefs.edit().putInt("books-${today.year}", goal).apply()
                }, singleLine = true, placeholder = { Text("12") }, modifier = Modifier.width(90.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (goal > 0) LinearProgressIndicator(progress = { (thisYear.toFloat() / goal).coerceAtMost(1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** Movies & TV (Letterboxd / TV Time): viewings, time watched, average score. */
@Composable
fun MoviesCard(period: Period, today: LocalDate) {
    val from = startMs(period.start(today))
    val titles = Movies.data.titles
    val views = titles.flatMap { t -> t.views.filter { it >= from }.map { t } }
    val minutes = views.filter { !it.series }.sumOf { it.runtime } +
        if (period == Period.All) titles.filter { it.series }.sumOf { it.runtime * it.episodesSeen } else 0
    val scores = views.distinct().map { it.score }.filter { it > 0 }
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("${views.size} watched · ${minutes / 60}h ${minutes % 60}m", style = MaterialTheme.typography.headlineSmall)
            if (scores.isNotEmpty()) Text("average score %.1f".format(scores.average()), style = MaterialTheme.typography.bodyMedium)
            Text(if (period == Period.All) "Time: runtimes × viewings, and minutes per episode × episodes seen."
                else "Time: movie runtimes × viewings (series episodes have no dates: see All time).",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** What you read in the news: articles per digest section, and per political lean (Ground News' blindspot idea). */
@Composable
fun NewsTopicsCard(period: Period, today: LocalDate) {
    val from = startMs(period.start(today))
    val p = Learning.progress
    val read = p.news.filterValues { it >= from }.keys
    val topics = read.mapNotNull { p.newsTopic[it] }
    if (topics.isEmpty()) return
    val sections = topics.groupingBy { it.substringBefore('|').ifBlank { "Other" } }.eachCount().entries.sortedByDescending { it.value }
    val leans = topics.map { it.substringAfter('|', "") }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
    val max = sections.maxOf { it.value }
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("📰 What you read", style = MaterialTheme.typography.labelLarge)
            sections.forEach { (name, n) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Text(name, Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                    Box(Modifier.weight(1f).height(12.dp)) {
                        Box(Modifier.fillMaxWidth(n.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.primary))
                    }
                    Text("$n", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (leans.isNotEmpty()) Text("Lean: " + leans.entries.sortedBy { it.key }.joinToString(" · ") { "${it.key} ${it.value}" },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Reading time and reviews over the period. */
@Composable
fun ReadingReviewsRow(period: Period, today: LocalDate, card: @Composable (String, String, String, Modifier) -> Unit) {
    val from = period.start(today)
    val reading = perDay(Learning.progress.reading).filterKeys { from == null || it >= from }.values.sum() / 60_000
    val reviews = perDay(Reviews.data.done).filterKeys { from == null || it >= from }.values.sum()
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        card("📖 Reading", if (reading >= 60) "${reading / 60}h ${reading % 60}m" else "${reading}m", "in the reader", Modifier.weight(1f))
        card("🧠 Reviews", "$reviews", "cards answered", Modifier.weight(1f))
    }
}

/** The Stats tab's detail pages ("More stats" under each section); null = the overview. */
enum class StatsPage(val title: String) {
    Focus("⏱️ Focus"), Learning("📖 Reading & learning"), News("📰 News"), Books("📚 Books"), Movies("🎬 Movies & TV"),
}

/** The [n] days up to today, oldest first. */
fun lastDays(today: LocalDate, n: Int = 14): List<LocalDate> = (n - 1 downTo 0).map { today.minusDays(it.toLong()) }

/** Articles read per day over the last [n] days, oldest first. */
fun newsPerDay(p: Progress, today: LocalDate, n: Int = 14, zone: ZoneId = ZoneId.systemDefault()): List<Pair<LocalDate, Int>> {
    val counts = p.news.values.groupingBy { dateOf(it, zone) }.eachCount()
    return lastDays(today, n).map { it to (counts[it] ?: 0) }
}

private fun avg(total: Number, days: Int) = if (days > 0) total.toDouble() / days else 0.0
private fun Double.one() = "%.1f".format(this)
private fun day(d: LocalDate) = "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())}"

/** A section title on the Stats overview with its "More stats" button. */
@Composable
fun SectionHeader(page: StatsPage, open: (StatsPage) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(page.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { open(page) }) { Text("More stats ›") }
    }

/** A card of label → value lines. */
@Composable
fun FactsCard(title: String, facts: List<Pair<String, String>>, note: String? = null) {
    if (facts.isEmpty()) return
    OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
            facts.forEach { (k, v) ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(v, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Bars with a label under each; goal > 0 draws it as a dashed line and bars that reach it solid. */
@Composable
fun Bars(title: String, bars: List<Pair<String, Int>>, goal: Int = 0, format: (Int) -> String = { "$it" }) =
    Column(Modifier.padding(16.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        val met = MaterialTheme.colorScheme.primary
        val under = met.copy(alpha = 0.35f)
        val line = MaterialTheme.colorScheme.outline
        val top = maxOf(goal * 1.25f, bars.maxOfOrNull { it.second }?.toFloat() ?: 0f, 1f)
        val described = bars.joinToString { (l, v) -> "$l: ${format(v)}" }
        Canvas(Modifier.fillMaxWidth().height(140.dp).padding(top = 12.dp).semantics { contentDescription = "$title. $described" }) {
            val w = size.width / bars.size
            bars.forEachIndexed { i, (_, v) ->
                val h = size.height * v / top
                drawRect(if (goal <= 0 || v >= goal) met else under, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h))
            }
            if (goal > 0) {
                val y = size.height * (1 - goal / top)
                drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)))
            }
        }
        Row(Modifier.fillMaxWidth()) {
            bars.forEach { (l, _) -> Text(l, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) }
        }
    }

/** Overview: news read in the last 14 days and per day (not tied to the period buttons, like the focus chart), and in the period. */
@Composable
fun NewsRecentRow(today: LocalDate, periodLabel: String, periodTotal: Int, card: @Composable (String, String, String, Modifier) -> Unit) {
    val total = newsPerDay(Learning.progress, today).sumOf { it.second }
    Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        card("Last 14 days", "$total", "articles read", Modifier.weight(1f))
        card("Per day", avg(total, 14).one(), "average, 14 days", Modifier.weight(1f))
        card(periodLabel, "$periodTotal", "articles read", Modifier.weight(1f))
    }
}

/** Reading time, reviews and the review deck, chapters and quizzes by book, daily-goal days. */
@Composable
fun LearningDetails(period: Period, today: LocalDate, goalDays: Set<LocalDate>) {
    val context = LocalContext.current
    val from = period.start(today)
    val fromMs = startMs(from)
    fun inPeriod(d: LocalDate) = from == null || d >= from
    val p = Learning.progress
    val reading = perDay(p.reading)
    val reviews = perDay(Reviews.data.done)
    val titles = remember { bookTitles(context) }
    Bars("Reading · last 14 days", lastDays(today).map { "${it.dayOfMonth}" to ((reading[it] ?: 0L) / 60_000).toInt() }, GOAL_READING_MIN, ::hm)
    Bars("Reviews · last 14 days", lastDays(today).map { "${it.dayOfMonth}" to (reviews[it] ?: 0L).toInt() }, GOAL_REVIEWS)
    val readDays = reading.filterKeys(::inPeriod).filterValues { it > 0 }
    val readMin = (readDays.values.sum() / 60_000).toInt()
    FactsCard("📖 Reading time", listOfNotNull(
        "Total" to hm(readMin),
        "Days with reading" to "${readDays.size}",
        "Per reading day" to hm(avg(readMin, readDays.size).toInt()),
        readDays.maxByOrNull { it.value }?.let { "Best day" to "${day(it.key)} · ${hm((it.value / 60_000).toInt())}" },
    ), "Time in the reader: gaps between page turns under 5 minutes, all devices.")
    val revDays = reviews.filterKeys(::inPeriod).filterValues { it > 0 }
    val cards = Reviews.data.cards.values
    FactsCard("🧠 Reviews", listOf(
        "Answers" to "${revDays.values.sum()}",
        "Per review day" to avg(revDays.values.sum(), revDays.size).one(),
        "Cards started" to "${cards.size}",
        "Learning / review" to "${cards.count { it.state != 2 && !it.suspended }} / ${cards.count { it.state == 2 && !it.suspended }}",
        "Mature (stability ≥ 21 d)" to "${cards.count { (it.stability ?: 0.0) >= 21 }}",
        "Lapses (forgotten)" to "${cards.sumOf { it.lapses }}",
        "Flagged" to "${cards.count { it.suspended }}",
    ), "Deck numbers are all time.")
    val chapters = p.chapters.filterValues { it >= fromMs }
    FactsCard("📚 Chapters finished", chapters.keys.groupingBy { it.substringBeforeLast('/') }.eachCount().entries
        .sortedByDescending { it.value }.map { (id, n) -> (titles[id] ?: id) to "$n" })
    FactsCard("❓ Quiz", listOf(
        "First right answers" to "${p.correct.count { it.value >= fromMs }}",
        "Chapters with a quiz done" to "${p.quizBest.size} (all time)",
    ))
    val elapsed = if (from == null) goalDays.minOrNull()?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today).toInt() + 1 } ?: 0
        else java.time.temporal.ChronoUnit.DAYS.between(from, today).toInt() + 1
    FactsCard("🔥 Daily goal", listOf("Days met" to "${goalDays.count(::inPeriod)} of $elapsed"))
}

/** Book id → title: the laptop's summaries and the EPUBs imported on this device. */
fun bookTitles(context: Context): Map<String, String> =
    LocalBooks(context).all().associate { it.id to it.title } +
        runCatching { Content(context).library().books.associate { it.id to it.title } }.getOrDefault(emptyMap())

/** Shelves, finished books with score and days to read, highlights. */
@Composable
fun BooksDetails(period: Period, today: LocalDate) {
    val context = LocalContext.current
    val fromMs = startMs(period.start(today))
    val titles = remember { bookTitles(context) }
    val books = Movies.data.books
    FactsCard("Shelves (now)", SHELVES.map { (k, label) -> label to "${books.values.count { it.status == k }}" })
    val finished = books.entries.filter { (it.value.finished ?: -1) >= fromMs }.sortedByDescending { it.value.finished }
    val days = finished.mapNotNull { (_, b) -> b.started?.let { s -> ((b.finished!! - s) / 86_400_000).toInt() + 1 } }
    FactsCard("Finished", finished.map { (id, b) ->
        (titles[id] ?: id) to listOfNotNull(
            if (b.score > 0) "${b.score.scoreText()}/10" else null,
            b.started?.let { "${((b.finished!! - it) / 86_400_000) + 1} d" },
        ).joinToString(" · ").ifEmpty { "–" }
    }, if (days.isNotEmpty()) "Average ${avg(days.sum(), days.size).one()} days from Reading to Finished." else null)
    val highlights = remember { LocalBooks(context).all().associate { it.id to Highlights(context, it.id).all() } }
        .mapValues { (_, l) -> l.filter { it.created >= fromMs } }.filterValues { it.isNotEmpty() }
    FactsCard("✏️ Highlights", highlights.entries.sortedByDescending { it.value.size }.map { (id, l) ->
        (titles[id] ?: id) to "${l.size}" + (l.count { it.note.isNotBlank() }.takeIf { it > 0 }?.let { " · $it notes" } ?: "") +
            (l.count { it.favorite }.takeIf { it > 0 }?.let { " · $it ⭐" } ?: "")
    }, "EPUBs on this device.")
}

/** Viewings per month, movies vs series, rewatches, tags, top rated. */
@Composable
fun MoviesDetails(period: Period, today: LocalDate) {
    val fromMs = startMs(period.start(today))
    val titles = Movies.data.titles
    val months = (11 downTo 0).map { YearMonth.from(today).minusMonths(it.toLong()) }
    val perMonth = titles.flatMap { it.views }.groupingBy { YearMonth.from(dateOf(it)) }.eachCount()
    Bars("Viewings · last 12 months", months.map { it.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()) to (perMonth[it] ?: 0) })
    val seen = titles.filter { t -> t.views.any { it >= fromMs } }
    val views = titles.sumOf { t -> t.views.count { it >= fromMs } }
    FactsCard("In this period", listOf(
        "Viewings" to "$views",
        "Movies / series" to "${seen.count { !it.series }} / ${seen.count { it.series }}",
        "Rewatches" to "${seen.sumOf { t -> t.views.withIndex().count { (i, v) -> i > 0 && v >= fromMs } }}",
        "Favourites (all time)" to "${titles.count { it.favorite }}",
        "Series episodes seen (all time)" to "${titles.filter { it.series }.sumOf { it.episodesSeen }}",
    ))
    FactsCard("Top rated", seen.filter { it.score > 0 }.sortedByDescending { it.score }.take(5).map { it.name to "${it.score.scoreText()}/10" })
    FactsCard("Tags", seen.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        .map { it.key to "${it.value}" })
}

/** Articles per day, reading habits, sections and lean, votes, saved list. */
@Composable
fun NewsDetails(period: Period, today: LocalDate) {
    val context = LocalContext.current
    val from = period.start(today)
    val fromMs = startMs(from)
    val p = Learning.progress
    val recent = newsPerDay(p, today)
    val total14 = recent.sumOf { it.second }
    Bars("Read · last 14 days (${avg(total14, 14).one()} a day)", recent.map { "${it.first.dayOfMonth}" to it.second })
    val perDay = p.news.values.filter { it >= fromMs }.groupingBy { dateOf(it) }.eachCount()
    val total = perDay.values.sum()
    FactsCard("In this period", listOfNotNull(
        "Articles read" to "$total",
        "Days with news" to "${perDay.size}",
        "Per news day" to avg(total, perDay.size).one(),
        perDay.maxByOrNull { it.value }?.let { "Best day" to "${day(it.key)} · ${it.value}" },
        "XP from news" to "${total * NEWS_XP}",
    ))
    NewsTopicsCard(period, today)
    val votes = remember { NewsVotes.all(context) }.values.filter { it.at >= fromMs }
    FactsCard("👍 / 👎 by section", votes.groupBy { it.section.ifBlank { "Other" } }.entries.sortedByDescending { it.value.size }.map { (s, l) ->
        s to "👍 ${l.count { it.v > 0 }} · 👎 ${l.count { it.v < 0 }}"
    }, "Votes cast on this device. Essentials puts sections you read and like first.")
    val saved = p.saved
    FactsCard("🔖 Saved for later", listOf("Saved" to "${saved.size}", "Of which read" to "${saved.keys.count { it in p.news }}"))
}
