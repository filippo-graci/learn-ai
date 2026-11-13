package dev.learnai.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.random.Random
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
 * Review tab: spaced repetition of the cards generate.py made (flashcards + quiz questions), scheduled by FSRS-6
 * (java-fsrs, MIT). A chapter's cards join the queue once the chapter is finished (Library tick or the reader),
 * at most NEW_PER_DAY new cards a day. Card states sync across devices (/doc/reviews, newest change per card wins).
 */

/** New cards introduced per day (Anki's default is 20). */
const val NEW_PER_DAY = 20
const val REVIEW_XP = 2

/** One card's FSRS memory state (java-fsrs Card fields), plus bookkeeping. Times in epoch ms. */
@Serializable
data class CardState(
    val state: Int = 1,              // java-fsrs State: 1 learning, 2 review, 3 relearning
    val step: Int? = 0,
    val stability: Double? = null,
    val difficulty: Double? = null,
    val due: Long = 0,
    val last: Long? = null,
    val first: Long = 0,             // first review: new cards per day count on it
    val reps: Int = 0,
    val lapses: Int = 0,
    val suspended: Boolean = false,  // flagged wrong / unclear: out of the queue (misreadings the checks can't catch)
    val updated: Long = 0,           // last review or flag: the newer copy wins a sync
)

@Serializable
data class ReviewData(
    val cards: Map<String, CardState> = emptyMap(),
    val done: Map<String, Long> = emptyMap(),  // "yyyy-MM-dd|device" → answers: per device, so devices add up
)

fun merge(a: ReviewData, b: ReviewData) = ReviewData(
    cards = (a.cards.keys + b.cards.keys).associateWith { k -> listOfNotNull(a.cards[k], b.cards[k]).maxBy { it.updated } },
    done = (a.done.keys + b.done.keys).associateWith { maxOf(a.done[it] ?: 0, b.done[it] ?: 0) },
)

private val scheduler: Scheduler by lazy { Scheduler.builder().build() }  // FSRS-6 defaults, retention 0.9

private fun CardState?.toCard(): Card {
    val b = Card.builder().cardId(1)
    if (this == null) return b.build()
    return b.state(State.entries.first { it.value == state }).step(step).stability(stability).difficulty(difficulty)
        .due(Instant.ofEpochMilli(due)).lastReview(last?.let(Instant::ofEpochMilli)).build()
}

/** The card's state after answering [rating] at [now]. */
fun review(old: CardState?, rating: Rating, now: Long): CardState {
    val c = scheduler.reviewCard(old.toCard(), rating, Instant.ofEpochMilli(now)).card()
    return CardState(
        state = c.state.value, step = c.step, stability = c.stability, difficulty = c.difficulty,
        due = c.due.toEpochMilli(), last = c.lastReview?.toEpochMilli(),
        first = old?.first?.takeIf { it > 0 } ?: now, reps = (old?.reps ?: 0) + 1,
        lapses = (old?.lapses ?: 0) + if (rating == Rating.AGAIN && old?.state == 2) 1 else 0,
        updated = now,
    )
}

/** "10m", "3h", "4d", "2mo": when the card comes back if answered [rating] now. */
fun nextIn(old: CardState?, rating: Rating, now: Long): String {
    val min = Duration.ofMillis(review(old, rating, now).due - now).toMinutes().coerceAtLeast(1)
    return when {
        min < 60 -> "${min}m"
        min < 60 * 24 -> "${min / 60}h"
        min < 60 * 24 * 30 -> "${min / (60 * 24)}d"
        else -> "${min / (60 * 24 * 30)}mo"
    }
}

/** A card to review: a flashcard or a quiz question of a finished chapter. */
data class ReviewItem(
    val id: String, val bookId: String, val bookTitle: String, val chapter: Int, val chapterTitle: String,
    val flashcard: Flashcard? = null, val quiz: QuizItem? = null,
)

/** Every card of every finished chapter (library books with summaries). */
fun reviewItems(context: Context): List<ReviewItem> {
    val content = Content(context)
    val finished = Learning.progress.chapters.keys
    return runCatching { content.library() }.getOrNull()?.books.orEmpty().flatMap { b ->
        b.chapters.filter { chapterKey(b.id, it.n) in finished }.flatMap { c ->
            runCatching { content.chapter(b.id, c.n) }.getOrNull()?.let { ch ->
                ch.flashcards.map { ReviewItem(it.id, b.id, b.title, c.n, c.title, flashcard = it) } +
                    ch.quiz.map { ReviewItem(it.id, b.id, b.title, c.n, c.title, quiz = it) }
            }.orEmpty()
        }
    }
}

/** What to review now: due cards (oldest due first), then today's share of new ones. */
fun queue(items: List<ReviewItem>, data: ReviewData, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ReviewItem> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val due = items.filter { data.cards[it.id]?.let { s -> !s.suspended && s.due <= now } == true }
        .sortedBy { data.cards[it.id]!!.due }
    val newToday = data.cards.values.count { it.first > 0 && Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate() == today }
    val fresh = items.filter { it.id !in data.cards }.take((NEW_PER_DAY - newToday).coerceAtLeast(0))
    return due + fresh
}

/** [n] of your highlights for [day]: the same all day, different tomorrow (Readwise's daily review). */
fun <T> dailyPick(all: List<T>, day: LocalDate, n: Int = 3, key: (T) -> String): List<T> =
    all.sortedBy(key).shuffled(Random(day.toEpochDay())).take(n)

/** Card states, filesDir/reviews.json, synced like the other stores (debounced, newest change per card wins). */
object Reviews {
    var data by mutableStateOf(ReviewData())
        private set
    private var file: File? = null
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var scheduled: Job? = null
    private const val UPLOAD_DELAY_MS = 10_000L  // a review session is a burst of answers: one upload at the end

    fun init(context: Context) = synchronized(this) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, "reviews.json")
        data = if (!f.exists()) ReviewData() else runCatching { json.decodeFromString<ReviewData>(f.readText()) }
            .getOrElse { f.renameTo(File(f.path + ".bad")); ReviewData() }
        file = f
    }

    private fun change(local: Boolean = true, f: (ReviewData) -> ReviewData): Unit = synchronized(this) {
        val target = checkNotNull(file) { "Reviews.init() not called" }
        val d = f(data)
        if (d == data) return
        data = d
        val tmp = File(target.path + ".tmp")
        tmp.writeText(json.encodeToString(d))
        tmp.renameTo(target)
        if (local) {
            scheduled?.cancel()
            scheduled = scope.launch { delay(UPLOAD_DELAY_MS); runCatching { syncNow() } }
        }
    }

    suspend fun sync(): Result<Unit> = withContext(io) { runCatching { syncNow() } }

    private fun syncNow(): Unit = syncDoc("doc/reviews", ReviewData.serializer()) { remote ->
        change(local = false) { merge(it, remote) }
        data
    }

    fun answer(context: Context, id: String, rating: Rating) {
        val now = System.currentTimeMillis()
        val k = dayKey(LocalDate.now(), deviceId(context))
        change { d -> d.copy(cards = d.cards + (id to review(d.cards[id], rating, now)), done = d.done + (k to (d.done[k] ?: 0) + 1)) }
    }

    fun flag(id: String) {
        val now = System.currentTimeMillis()
        change { d -> d.copy(cards = d.cards + (id to (d.cards[id] ?: CardState(due = now, first = now)).copy(suspended = true, updated = now))) }
    }
}

@Composable
fun ReviewTab(openHighlight: (bookId: String, locator: String) -> Unit) {
    val context = LocalContext.current
    val finished = Learning.progress.chapters.keys
    val items = remember(finished) { reviewItems(context) }
    var current by remember { mutableStateOf<ReviewItem?>(null) }
    var session by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { Reviews.sync() }  // other devices' answers; offline is normal

    // Android 13+: ask once for the daily "cards due" notification
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("review", Context.MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("asked", false) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            prefs.edit().putBoolean("asked", true).apply()
            ask.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val now = System.currentTimeMillis()
    val q = queue(items, Reviews.data, now)
    // The card on screen stays put while answering (the queue changes as soon as an answer is saved)
    val item = if (session) current ?: q.firstOrNull() else null
    if (item != null) {
        BackHandler { session = false; current = null }
        if (current == null) LaunchedEffect(item.id) { current = item }
        ReviewCard(item, Reviews.data.cards[item.id], left = q.size) { rating ->
            if (rating == null) Reviews.flag(item.id) else Reviews.answer(context, item.id, rating)
            current = null
        }
        return
    }

    val dueCount = q.count { it.id in Reviews.data.cards }
    val nextDue = Reviews.data.cards.values.filter { !it.suspended && it.due > now }.minOfOrNull { it.due }
    val doneToday = perDay(Reviews.data.done)[LocalDate.now()] ?: 0
    val highlights = remember(Unit) {
        val books = LocalBooks(context).all()
        dailyPick(books.flatMap { b -> Highlights(context, b.id).all().map { Triple(b.id, b.title, it) } }, LocalDate.now()) { it.third.id }
    }
    Page("Review") { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                OutlinedCard(Modifier.fillMaxWidth().padding(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("${q.size} to review", style = MaterialTheme.typography.headlineMedium)
                        Text("$dueCount due · ${q.size - dueCount} new · $doneToday answered today · +$REVIEW_XP XP each",
                            style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { session = true }, enabled = q.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                            Text(if (q.isEmpty()) "All done for now" else "Start")
                        }
                        if (q.isEmpty() && nextDue != null) {
                            Text("Next card: " + LocalDateTime.ofInstant(Instant.ofEpochMilli(nextDue), ZoneId.systemDefault())
                                .let { "${it.toLocalDate()} ${"%02d:%02d".format(it.hour, it.minute)}" },
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                        if (items.isEmpty()) {
                            Text("A chapter's flashcards and quiz questions join here when you finish it (tick it in Library, or read to its end).",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            if (highlights.isNotEmpty()) item {
                Text("Today's highlights", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(16.dp, 8.dp, 16.dp, 4.dp))
            }
            items(highlights, key = { it.third.id }) { (bookId, title, h) ->
                OutlinedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { openHighlight(bookId, h.locator) }) {
                    Column(Modifier.padding(16.dp)) {
                        Text("“${h.text}”", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyLarge)
                        if (h.note.isNotBlank()) Text("📝 ${h.note}", modifier = Modifier.padding(top = 6.dp))
                        Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            val flagged = Reviews.data.cards.count { it.value.suspended }
            if (flagged > 0) item {
                Text("$flagged flagged cards are out of the queue.", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp))
            }
        }
    }
}

/** One card. answer(null) = flagged. Flashcard: front → Show answer → Again/Hard/Good/Easy. Quiz: right = Good, wrong = Again. */
@Composable
private fun ReviewCard(item: ReviewItem, state: CardState?, left: Int, answer: (Rating?) -> Unit) =
    Page("Review · $left left") { pad ->
        var revealed by remember(item.id) { mutableStateOf(false) }
        var chosen by remember(item.id) { mutableStateOf<Int?>(null) }
        val now = System.currentTimeMillis()
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("${item.bookTitle} · ${item.chapter}. ${item.chapterTitle}", style = MaterialTheme.typography.labelMedium)
            val card = item.flashcard
            val quiz = item.quiz
            if (card != null) {
                Text(card.front, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
                if (!revealed) {
                    Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) { Text("Show answer") }
                } else {
                    Text(card.back, style = MaterialTheme.typography.bodyLarge)
                    Text("«${card.sourceQuote}»", fontStyle = FontStyle.Italic, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(Rating.AGAIN to "Again", Rating.HARD to "Hard", Rating.GOOD to "Good", Rating.EASY to "Easy").forEach { (r, label) ->
                            OutlinedButton(onClick = { answer(r) }, modifier = Modifier.weight(1f)) {
                                Column { Text(label); Text(nextIn(state, r, now), style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                            }
                        }
                    }
                }
            } else if (quiz != null) {
                Text(quiz.question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
                val order = remember(item.id) { quiz.options.indices.shuffled() }  // generation puts answers at 1-2
                order.forEach { i ->
                    val colors = when {
                        chosen == null -> CardDefaults.outlinedCardColors()
                        i == quiz.answer -> CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                        i == chosen -> CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        else -> CardDefaults.outlinedCardColors()
                    }
                    OutlinedCard(onClick = { if (chosen == null) chosen = i }, colors = colors,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(quiz.options[i], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
                    }
                }
                chosen?.let { c ->
                    Text(if (c == quiz.answer) "Correct" else "Wrong", style = MaterialTheme.typography.titleMedium,
                        color = if (c == quiz.answer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 16.dp))
                    Text(quiz.explanation, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                    Text("«${quiz.sourceQuote}»", fontStyle = FontStyle.Italic, modifier = Modifier.padding(top = 8.dp))
                    Button(onClick = { answer(if (c == quiz.answer) Rating.GOOD else Rating.AGAIN) },
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Next") }
                }
            }
            TextButton(onClick = { answer(null) }, modifier = Modifier.padding(top = 24.dp)) { Text("⚑ Flag: wrong or unclear (removes it)") }
        }
    }

/** Daily at ~18:00: "N cards to review" if there are any. Tapping it opens the Review tab. */
class DueWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Learning.init(ctx)
        Reviews.init(ctx)
        Learning.sync()  // chapters finished on another device add cards
        Reviews.sync()
        val n = queue(reviewItems(ctx), Reviews.data, System.currentTimeMillis()).size
        if (n == 0 || (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        ) return Result.success()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reviews", "Reviews due", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).putExtra(EXTRA_TAB, Tab.Review.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(1, NotificationCompat.Builder(ctx, "reviews")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("$n cards to review")
            .setContentText("A few minutes keeps what you read.")
            .setContentIntent(open).setAutoCancel(true).build())
        return Result.success()
    }
}

const val EXTRA_TAB = "tab"

/** Schedules [DueWorker] once a day, first run at the next 18:00. KEEP: rescheduling on every launch is a no-op. */
fun scheduleDueReminder(context: Context) {
    val now = LocalDateTime.now()
    val six = now.toLocalDate().atTime(18, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        "due-cards", ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<DueWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(Duration.between(now, six).toMillis(), TimeUnit.MILLISECONDS).build(),
    )
}

