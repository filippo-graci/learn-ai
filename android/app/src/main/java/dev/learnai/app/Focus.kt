package dev.learnai.app

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/**
 * The laptop focus guard (~/Workspace/focus) logs one row per stretch in one mode to sessions.jsonl and uploads
 * the whole list to /focus (functions/focus.js) after every session. This tab turns it into XP, levels, a streak,
 * a time breakdown and a 14-day chart, for this month or all time. Seconds in the data, minutes on screen.
 */
@Serializable
data class FocusSession(
    val start: String,  // ISO with offset, laptop local time
    val end: String,
    val mode: String,   // soft | focus | hard
    val focused: Int,
    val distracted: Int,
    val away: Int,
    val nags: Int = 0,
)

/** Focused minutes a day that keep the streak alive. */
const val DAILY_GOAL_MIN = 60

/** XP is paid per this many focused minutes. */
const val XP_MINUTES = 5

/** XP per XP_MINUTES focused minutes: the stricter modes pay more. */
fun xpRate(mode: String) = when (mode) { "hard" -> 3; "focus" -> 2; else -> 1 }

fun sessionXp(s: FocusSession) = s.focused * xpRate(s.mode) / (XP_MINUTES * 60)

/** Level L spans 50·L·(L-1) .. 50·L·(L+1) XP: 100 for level 1, then 200, 300, ... more per level. */
data class Level(val level: Int, val start: Int, val end: Int)

fun levelOf(xp: Int): Level {
    var l = 1
    while (xp >= 50 * l * (l + 1)) l++
    return Level(l, 50 * l * (l - 1), 50 * l * (l + 1))
}

/** Week and month levels: one level every step XP, from level 1 again each Monday / each 1st (same as the laptop app). */
const val WEEK_LEVEL_XP = 20
const val MONTH_LEVEL_XP = 60

fun periodLevel(xp: Int, step: Int) = Level(xp / step + 1, xp / step * step, (xp / step + 1) * step)

/** Session grade by on-screen share: focused of focused + distracted (away is a pause, not a fault). */
enum class Grade(val argb: Long) { Good(0xFF43A047), Okay(0xFFFBC02D), Poor(0xFFE53935) }

fun onScreenPct(s: FocusSession) = (s.focused + s.distracted).let { if (it > 0) s.focused * 100 / it else 100 }

fun gradeOf(s: FocusSession) = onScreenPct(s).let { when { it >= 80 -> Grade.Good; it >= 60 -> Grade.Okay; else -> Grade.Poor } }

fun weekStart(today: LocalDate): LocalDate = today.minusDays(today.dayOfWeek.value - 1L)

/** Day-to-day numbers: they don't depend on the chosen period. */
data class FocusStats(
    val streak: Int,      // consecutive goal days up to today (today still open: counts from yesterday)
    val bestStreak: Int,
    val todayMin: Int,
    val days: List<Pair<LocalDate, Int>>,  // last 14 days, oldest first: focused minutes
)

fun sessionDate(s: FocusSession): LocalDate = OffsetDateTime.parse(s.start).toLocalDate()

fun focusStats(sessions: List<FocusSession>, today: LocalDate): FocusStats {
    val perDay = sessions.groupBy(::sessionDate).mapValues { (_, l) -> l.sumOf { it.focused } / 60 }
    val goalDays = perDay.filterValues { it >= DAILY_GOAL_MIN }.keys
    var day = if (today in goalDays) today else today.minusDays(1)
    var streak = 0
    while (day in goalDays) { streak++; day = day.minusDays(1) }
    var best = 0
    var run = 0
    var prev: LocalDate? = null
    for (d in goalDays.sorted()) {
        run = if (prev != null && d == prev.plusDays(1)) run + 1 else 1
        best = maxOf(best, run)
        prev = d
    }
    return FocusStats(
        streak = streak, bestStreak = best, todayMin = perDay[today] ?: 0,
        days = (13 downTo 0).map { today.minusDays(it.toLong()).let { d -> d to (perDay[d] ?: 0) } },
    )
}

/**
 * Totals over a set of sessions, in seconds. duration = wall-clock start..end; untracked = the part of it
 * that is none of focused / distracted / away (e.g. stretches the guard didn't classify).
 */
data class FocusTotals(
    val xp: Int, val sessions: Int, val duration: Int,
    val focused: Int, val distracted: Int, val away: Int, val untracked: Int, val nags: Int,
)

fun durationOf(s: FocusSession) =
    (OffsetDateTime.parse(s.end).toEpochSecond() - OffsetDateTime.parse(s.start).toEpochSecond()).toInt().coerceAtLeast(0)

fun focusTotals(sessions: List<FocusSession>): FocusTotals {
    val duration = sessions.sumOf(::durationOf)
    val f = sessions.sumOf { it.focused }
    val d = sessions.sumOf { it.distracted }
    val a = sessions.sumOf { it.away }
    return FocusTotals(
        xp = sessions.sumOf { it.focused * xpRate(it.mode) } / (XP_MINUTES * 60), sessions = sessions.size,
        duration = maxOf(duration, f + d + a), focused = f, distracted = d, away = a,
        untracked = (duration - f - d - a).coerceAtLeast(0), nags = sessions.sumOf { it.nags },
    )
}


/** Cloud copy cached on disk: the tab works offline with the last download. */
object FocusData {
    private fun file(context: Context) = File(context.filesDir, "focus.json")

    fun cached(context: Context): List<FocusSession> = file(context).takeIf { it.exists() }
        ?.let { runCatching { json.decodeFromString<List<FocusSession>>(it.readText()) }.getOrNull() } ?: emptyList()

    suspend fun sync(context: Context): Result<List<FocusSession>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = cloud("focus")
            json.decodeFromString<List<FocusSession>>(text).also { file(context).writeText(text) }
        }
    }
}

fun hm(min: Int) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
private fun pct(part: Int, whole: Int) = if (whole > 0) part * 100 / whole else 0
private fun modeIcon(mode: String) = when (mode) { "hard" -> "⚡"; "focus" -> "⏱️"; else -> "🌿" }

@Composable
fun FocusTab() {
    val context = LocalContext.current
    var sessions by remember { mutableStateOf(FocusData.cached(context)) }
    var period by rememberSaveable { mutableStateOf(Period.Month) }
    var page by rememberSaveable { mutableStateOf<StatsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val scope = rememberCoroutineScope()
    fun sync(manual: Boolean) = scope.launch {
        val r = FocusData.sync(context).onSuccess { sessions = it }
        if (manual) Toast.makeText(context, r.exceptionOrNull()?.let { "Refresh failed: ${it.message}" } ?: "Up to date",
            Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) { sync(manual = false) }  // quietly: offline is normal

    val today = LocalDate.now()
    val month = YearMonth.from(today)
    val monthName = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()).replaceFirstChar { it.uppercase() }
    val stats = remember(sessions, today) { focusStats(sessions, today) }
    val from = period.start(today)
    val shown = remember(sessions, period, today) { sessions.filter { from == null || sessionDate(it) >= from } }
    val totals = remember(shown) { focusTotals(shown) }
    val progress = Learning.progress
    val learning = learningStats(progress.since(startMs(from)), today)
    // the week / month levels don't follow the period buttons: (focus XP, learning XP) since Monday / the 1st
    fun xpSince(day: LocalDate) = Pair(
        focusTotals(sessions.filter { sessionDate(it) >= day }).xp,
        learningStats(progress.since(startMs(day)), today).xp +
            REVIEW_XP * perDay(Reviews.data.done).filterKeys { it >= day }.values.sum().toInt(),
    )
    val weekXp = xpSince(weekStart(today))
    val monthXp = xpSince(today.withDayOfMonth(1))
    val label = when (period) { Period.Month -> "$monthName ${month.year}"; Period.Year -> "${today.year}"; Period.All -> "All time" }
    // Daily goal: any of focus / reading / reviews / a finished chapter
    val focusPerDay = remember(sessions) { sessions.groupBy(::sessionDate).mapValues { (_, l) -> l.sumOf { it.focused } / 60 } }
    val readingPerDay = perDay(progress.reading)
    val reviewsPerDay = perDay(Reviews.data.done)
    val chapterDays = progress.chapters.values.map { dateOf(it) }
    val streak = goalStreak(goalDays(focusPerDay, readingPerDay, reviewsPerDay, chapterDays), today)

    val open = { p: StatsPage -> page = p }
    Page(
        page?.title ?: "Stats",
        actions = {
            if (page != null) TextButton(onClick = { page = null }) { Text("Back") }
            else TextButton(onClick = { sync(manual = true) }) { Text("Refresh") }
        },
        bottomBar = { PeriodBar(period, monthName) { period = it } },
    ) { pad ->
        LazyColumn(contentPadding = pad) {
            when (page) {
                null -> {
                    if (sessions.isEmpty()) item {
                        Text("No focus sessions yet. Start one on the laptop (focus soft); it shows up here when it ends.",
                            style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
                    }
                    item { LevelCard(weekXp, monthXp) }
                    item {
                        GoalCard(streak, focusPerDay[today] ?: 0, readingPerDay[today] ?: 0, reviewsPerDay[today] ?: 0, today in chapterDays)
                    }
                    item { SectionHeader(StatsPage.Focus, open) }
                    item {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("Focused", hm(totals.focused / 60), "${totals.sessions} sessions", Modifier.weight(1f))
                            StatCard("Streak", "${stats.streak} d", "best ${stats.bestStreak} d", Modifier.weight(1f))
                            StatCard("Today", hm(stats.todayMin), "goal ${hm(DAILY_GOAL_MIN)}", Modifier.weight(1f))
                        }
                    }
                    item { SectionHeader(StatsPage.Learning, open) }
                    item {
                        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatCard("📖 Chapters", "${learning.chapters}", "finished", Modifier.weight(1f))
                            StatCard("❓ Quiz", "${learning.correct}", "answered right", Modifier.weight(1f))
                        }
                    }
                    item { ReadingReviewsRow(period, today) { t, v, n, m -> StatCard(t, v, n, m) } }
                    item { SectionHeader(StatsPage.News, open) }
                    item { NewsRecentRow(today, label, learning.newsTotal) { t, v, n, m -> StatCard(t, v, n, m) } }
                    item { SectionHeader(StatsPage.Books, open) }
                    item { BooksCard(period, today) }
                    item { SectionHeader(StatsPage.Movies, open) }
                    item { MoviesCard(period, today) }
                }
                StatsPage.Focus -> {
                    item { TimeBreakdown(totals) }
                    item { FocusHabits(shown) }
                    item { Bars("Last 14 days", stats.days.map { "${it.first.dayOfMonth}" to it.second }, DAILY_GOAL_MIN, ::hm) }
                    if (shown.isNotEmpty()) item {
                        Column(Modifier.padding(16.dp, 24.dp, 16.dp, 0.dp)) {
                            Text("Sessions · $label", style = MaterialTheme.typography.titleMedium)
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                listOf(Grade.Good to "80%+ on screen", Grade.Okay to "60–79%", Grade.Poor to "under 60%").forEach { (g, text) ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        GradeDot(g, Modifier.padding(top = 4.dp))
                                        Text(text, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    items(shown.asReversed().take(30)) { s -> SessionRow(s) }
                }
                StatsPage.Learning -> item {
                    Column { LearningDetails(period, today, goalDays(focusPerDay, readingPerDay, reviewsPerDay, chapterDays)) }
                }
                StatsPage.News -> item { Column { NewsDetails(period, today) } }
                StatsPage.Books -> item { Column { BooksDetails(period, today) } }
                StatsPage.Movies -> item { Column { MoviesDetails(period, today) } }
            }
        }
    }
}

@Composable
private fun SessionRow(s: FocusSession) {
    val total = durationOf(s)
    ListItem(
        overlineContent = {
            Text(OffsetDateTime.parse(s.start).format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm")) + " · ${s.mode}")
        },
        leadingContent = { GradeDot(gradeOf(s), size = 14) },
        headlineContent = { Text("${modeIcon(s.mode)}  ${hm(s.focused / 60)} focused of ${hm(total / 60)}") },
        supportingContent = {
            Column {
                TimeBar(timeParts(s.focused, s.distracted, s.away, maxOf(0, total - s.focused - s.distracted - s.away)),
                    8, Modifier.padding(top = 4.dp, bottom = 6.dp))
                Text(listOfNotNull(
                    "${onScreenPct(s)}% on screen",
                    if (s.distracted > 0) "${hm(s.distracted / 60)} distracted" else null,
                    if (s.away > 0) "${hm(s.away / 60)} away" else null,
                    if (s.nags > 0) "${s.nags} nags" else null,
                    "+${sessionXp(s)} XP",
                ).joinToString(" · "))
            }
        },
    )
    HorizontalDivider()
}

/** Per mode, average session, best day and when in the day you focus, over the period. */
@Composable
private fun FocusHabits(shown: List<FocusSession>) {
    if (shown.isEmpty()) return
    val days = shown.groupBy(::sessionDate).mapValues { (_, l) -> l.sumOf { it.focused } / 60 }
    val best = days.maxBy { it.value }
    val parts = shown.groupBy {
        when (OffsetDateTime.parse(it.start).hour) { in 5..11 -> "morning"; in 12..17 -> "afternoon"; in 18..22 -> "evening"; else -> "night" }
    }.mapValues { (_, l) -> l.sumOf { it.focused } }
    val focused = shown.sumOf { it.focused }
    FactsCard("Habits", listOf("soft", "focus", "hard").mapNotNull { m ->
        shown.filter { it.mode == m }.takeIf { it.isNotEmpty() }?.let { l ->
            "${modeIcon(m)} $m" to "${l.size} · ${hm(l.sumOf { it.focused } / 60)} · ${l.sumOf(::onScreenPct) / l.size}% on screen"
        }
    } + listOf(
        "Average session" to hm(shown.sumOf(::durationOf) / shown.size / 60),
        "Per focus day" to hm(days.values.sum() / days.size),
        "Best day" to "${best.key.dayOfMonth}/${best.key.monthValue} · ${hm(best.value)}",
        "When" to listOf("morning", "afternoon", "evening", "night").filter { (parts[it] ?: 0) > 0 }
            .joinToString(" · ") { "$it ${pct(parts[it]!!, focused)}%" },
    ), "Per mode: sessions · focused time · average time on screen.")
}

/** Fixed at the bottom of the tab: which period the totals cover (the levels are always this week / this month). */
@Composable
private fun PeriodBar(period: Period, monthName: String, choose: (Period) -> Unit) = Surface(tonalElevation = 3.dp) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Period.entries.forEachIndexed { i, p ->
            SegmentedButton(
                selected = period == p, onClick = { choose(p) },
                shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size),
            ) { Text(when (p) { Period.Month -> monthName; Period.Year -> "This year"; Period.All -> "All time" }) }
        }
    }
}

/** Two levels, focus XP + learning XP (chapters, quiz, news, reading, reviews): this week and this month. */
@Composable
private fun LevelCard(week: Pair<Int, Int>, month: Pair<Int, Int>) = OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
    Column(Modifier.padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LevelColumn("This week", week, WEEK_LEVEL_XP, Modifier.weight(1f))
            LevelColumn("This month", month, MONTH_LEVEL_XP, Modifier.weight(1f))
        }
        Text("A level every $WEEK_LEVEL_XP XP in the week, $MONTH_LEVEL_XP XP in the month, from level 1 again " +
            "every Monday and every 1st. XP: every $XP_MINUTES focused minutes 1 / 2 / 3 (soft / focus / hard) · " +
            "chapter $CHAPTER_XP · first right answer $QUESTION_XP · review $REVIEW_XP · " +
            "$READING_XP_MINUTES reading minutes 1 · news read $NEWS_XP",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun LevelColumn(label: String, xp: Pair<Int, Int>, step: Int, modifier: Modifier) = Column(modifier) {
    val total = xp.first + xp.second
    val l = periodLevel(total, step)
    Text(label, style = MaterialTheme.typography.labelLarge)
    Text("Level ${l.level}", style = MaterialTheme.typography.headlineMedium)
    Text("$total XP · ${l.end - total} to ${l.level + 1}", style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(
        progress = { (total - l.start).toFloat() / (l.end - l.start) },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
    Text("⏱️ ${xp.first} · 📚 ${xp.second}", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp))
}

/** Where the session time went: focused / distracted / away / untracked, as a bar and as numbers. */
@Composable
private fun TimeBreakdown(t: FocusTotals) = OutlinedCard(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
    val parts = timeParts(t.focused, t.distracted, t.away, t.untracked)
    Column(Modifier.padding(16.dp)) {
        Text("Session time", style = MaterialTheme.typography.labelLarge)
        Text("${hm(t.duration / 60)} in ${t.sessions} sessions", style = MaterialTheme.typography.headlineSmall)
        Text("${pct(t.focused, t.duration)}% focused · ${hm((t.distracted + t.away) / 60)} not focused · ${t.nags} nags",
            style = MaterialTheme.typography.bodyMedium)
        TimeBar(parts, 14, Modifier.padding(vertical = 12.dp))
        parts.forEach { (name, sec, color) -> LegendRow(name, sec, t.duration, color) }
    }
}

/** focused / distracted / away / untracked colours, shared by the breakdown card and each session row. */
@Composable
private fun timeParts(focused: Int, distracted: Int, away: Int, untracked: Int) = listOf(
    Triple("Focused", focused, MaterialTheme.colorScheme.primary),
    Triple("Distracted", distracted, MaterialTheme.colorScheme.error),
    Triple("Away", away, MaterialTheme.colorScheme.tertiary),
    Triple("Untracked", untracked, MaterialTheme.colorScheme.outlineVariant),
)

@Composable
private fun TimeBar(parts: List<Triple<String, Int, Color>>, height: Int, modifier: Modifier = Modifier) {
    if (parts.sumOf { it.second } <= 0) return
    Row(modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape((height / 2).dp))) {
        parts.filter { it.second > 0 }.forEach { (_, sec, color) ->
            Box(Modifier.weight(sec.toFloat()).fillMaxHeight().background(color))
        }
    }
}

@Composable
private fun LegendRow(name: String, sec: Int, whole: Int, color: Color) =
    Row(Modifier.padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.padding(top = 4.dp).size(12.dp).clip(RoundedCornerShape(3.dp)).background(color))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text("${hm(sec / 60)} · ${pct(sec, whole)}%", style = MaterialTheme.typography.bodyMedium)
    }

@Composable
private fun GradeDot(g: Grade, modifier: Modifier = Modifier, size: Int = 10) =
    Box(modifier.size(size.dp).clip(RoundedCornerShape(50)).background(Color(g.argb)))

@Composable
fun StatCard(title: String, value: String, note: String, modifier: Modifier) = OutlinedCard(modifier) {
    Column(Modifier.padding(12.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(note, style = MaterialTheme.typography.bodySmall)
    }
}
