package dev.learnai.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.InputChip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
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

/** One part of a review: 0 = not rated yet, else 1..5, plus what you wrote about it. */
@Serializable
data class Aspect(val score: Int = 0, val text: String = "")

/** A movie or a series. One review per title, wherever it's listed. */
@Serializable
data class Title(
    val id: String,
    val name: String,
    val series: Boolean = false,
    val watched: Long? = null,  // watched at (epoch ms)
    val score: Int = 0,         // 0 = not rated yet, else 1..10: the title's colour (scoreColor)
    val aspects: Map<String, Aspect> = emptyMap(),  // keys: ASPECTS
    val opinion: String = "",
    val image: String? = null,  // file in filesDir/movies/, at most IMAGE_MAX_PX
    val created: Long,
    val episodes: Int = 0,      // series: episodes watched
    val totalEpisodes: Int = 0, // series: 0 = unknown
    val tags: List<String> = emptyList(),  // lowercase, e.g. "sci-fi", "with friends"
    val updated: Long = 0,      // last change on any device (Movies.change stamps it): the newer copy wins a sync
    val views: List<Long> = emptyList(),  // diary: each time you watched it (rewatches add one)
    val season: Int = 1,        // series: current season (episodes counts within it)
    val episodesSeen: Int = 0,  // series: every episode ever ticked, all seasons (time watched)
    val runtime: Int = 0,       // minutes: the movie, or one episode
    val favorite: Boolean = false,
)

/** Minutes watched: a movie's runtime per viewing, a series' episode runtime per episode seen. */
fun Title.minutesWatched() = if (series) runtime * episodesSeen else runtime * views.size

/** Ticking "watched" records a viewing in the diary; unticking takes back the one it added. */
fun Title.setWatched(w: Boolean, now: Long) =
    if (w) copy(watched = watched ?: now, views = if (views.isEmpty()) listOf(now) else views)
    else copy(watched = null, views = if (views.lastOrNull() == watched) views.dropLast(1) else views)

fun Title.watchAgain(now: Long) = copy(watched = watched ?: now, views = views + now)

@Serializable
data class WatchList(
    val id: String, val name: String, val titles: List<String> = emptyList(),
    val created: Long = 0, val updated: Long = 0,
)

/** A book's review: same score colours and 1-5 aspects as a movie's. Keyed by library book id. */
@Serializable
data class BookReview(
    val score: Int = 0, val aspects: Map<String, Aspect> = emptyMap(), val opinion: String = "", val updated: Long = 0,
    val status: String = "",      // shelf: SHELVES key, "" = none
    val started: Long? = null,    // set when moved to "reading"
    val finished: Long? = null,   // set when moved to "finished"
)

/** Goodreads / StoryGraph shelves. */
val SHELVES = listOf("to-read" to "To read", "reading" to "Reading", "finished" to "Finished", "abandoned" to "Abandoned")

fun BookReview.withStatus(s: String, now: Long) = copy(
    status = s,
    started = if (s == "reading" || s == "finished") started ?: now else started,
    finished = if (s == "finished") finished ?: now else null,
)

/**
 * Everything reviewed: movies & series (titles, lists) and books. One store and one cloud copy for all reviews,
 * so book reviews get the same debounced backup as movie reviews.
 */
@Serializable
data class MovieData(
    val titles: List<Title> = emptyList(),
    val lists: List<WatchList> = emptyList(),
    val books: Map<String, BookReview> = emptyMap(),
    /** Deletions, so they reach the other devices: "t:<title id>" / "l:<list id>" / "b:<book id>" → deleted at. */
    val removed: Map<String, Long> = emptyMap(),
)

val ASPECTS = listOf("characters" to "Characters", "plot" to "Plot", "setting" to "Setting", "meaning" to "Meaning")
val BOOK_ASPECTS = listOf("writing" to "Writing", "ideas" to "Ideas", "characters" to "Characters", "plot" to "Plot")

/** Longest side of a saved picture, in pixels: plenty for a phone screen, ~50 KB as JPEG. */
const val IMAGE_MAX_PX = 600

/** 1-3 red (bad), 4 orange, 5 yellow, 6 light green, 7 green, 8 vivid dark green, 9 pink, 10 violet. */
fun scoreColor(score: Int): Color = when (score) {
    1 -> Color(0xFF8E1B1B); 2 -> Color(0xFFB71C1C); 3 -> Color(0xFFE53935)
    4 -> Color(0xFFFB8C00); 5 -> Color(0xFFFDD835); 6 -> Color(0xFF9CCC65)
    7 -> Color(0xFF43A047); 8 -> Color(0xFF00701A); 9 -> Color(0xFFEC407A); 10 -> Color(0xFF8E24AA)
    else -> Color.Gray
}

private fun onScore(score: Int) = if (score in 5..6) Color.Black else Color.White

/** How much of it is watched: ticked = all; a series with a known length = its episodes; else nothing. */
fun Title.progress(): Float = when {
    watched != null -> 1f
    series && totalEpisodes > 0 -> (episodes.toFloat() / totalEpisodes).coerceIn(0f, 1f)
    else -> 0f
}

/** One more episode; reaching the last one ticks the series as watched. */
fun Title.plusEpisode(now: Long) = copy(episodes = episodes + 1, episodesSeen = episodesSeen + 1).let {
    if (it.totalEpisodes > 0 && it.episodes >= it.totalEpisodes && it.watched == null) it.setWatched(true, now) else it
}

fun tagOf(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")

fun MovieData.allTags() = titles.flatMap { it.tags }.distinct().sorted()

fun BookReview.reviewed() = score > 0 || opinion.isNotBlank() || aspects.values.any { it.score > 0 || it.text.isNotBlank() }

fun Title.reviewed() = score > 0 || opinion.isNotBlank() || aspects.values.any { it.score > 0 || it.text.isNotBlank() }

fun MovieData.title(id: String) = titles.firstOrNull { it.id == id }

fun MovieData.put(t: Title) =
    copy(titles = if (titles.any { it.id == t.id }) titles.map { if (it.id == t.id) t else it } else titles + t)

/** Drops a list, and the titles that were only on it and have no review (nothing would show them any more). */
fun MovieData.withoutList(listId: String): MovieData {
    val rest = lists.filter { it.id != listId }
    val kept = titles.filter { t -> t.reviewed() || rest.any { t.id in it.titles } }
    return copy(titles = kept, lists = rest)
}

fun MovieData.withoutTitle(id: String) =
    copy(titles = titles.filter { it.id != id }, lists = lists.map { l -> l.copy(titles = l.titles - id) })

/** [after] with what changed since [before] stamped `updated` = [now], and tombstones for what disappeared. */
fun stamped(before: MovieData, after: MovieData, now: Long): MovieData {
    val gone = (before.titles.map { "t:${it.id}" } - after.titles.map { "t:${it.id}" }.toSet()) +
        (before.lists.map { "l:${it.id}" } - after.lists.map { "l:${it.id}" }.toSet()) +
        (before.books.keys - after.books.keys).map { "b:$it" }
    return after.copy(
        titles = after.titles.map { t -> if (before.title(t.id) == t) t else t.copy(updated = now) },
        lists = after.lists.map { l -> if (before.lists.firstOrNull { it.id == l.id } == l) l else l.copy(updated = now) },
        books = after.books.mapValues { (k, r) -> if (before.books[k] == r) r else r.copy(updated = now) },
        removed = after.removed + gone.associateWith { now },
    )
}

/**
 * Two devices' copies → one. Per title / list / book review the latest change wins, unless it was deleted later.
 * A list keeps the titles added on either device. Canonical order (by creation), so equal data compares equal.
 */
fun merge(local: MovieData, remote: MovieData): MovieData {
    val removed = (local.removed.keys + remote.removed.keys)
        .associateWith { maxOf(local.removed[it] ?: 0, remote.removed[it] ?: 0) }
    fun alive(key: String, updated: Long) = (removed[key] ?: -1) < updated
    val titles = (local.titles + remote.titles).groupBy { it.id }.map { (_, v) -> v.maxBy { it.updated } }
        .filter { alive("t:${it.id}", it.updated) }.sortedWith(compareBy({ it.created }, { it.id }))
    val ids = titles.map { it.id }.toSet()
    val lists = (local.lists + remote.lists).groupBy { it.id }.map { (_, v) ->
        val newest = v.maxBy { it.updated }
        newest.copy(titles = (newest.titles + v.flatMap { it.titles }).distinct().filter { it in ids })
    }.filter { alive("l:${it.id}", it.updated) }.sortedWith(compareBy({ it.created }, { it.id }))
    val books = (local.books.keys + remote.books.keys)
        .associateWith { k -> listOfNotNull(local.books[k], remote.books[k]).maxBy { it.updated } }
        .filter { (k, r) -> alive("b:$k", r.updated) }
    return MovieData(titles, lists, books, removed)
}

/**
 * Lists, titles and reviews: filesDir/movies/movies.json (+ the pictures next to it), one observable state,
 * synced with the private site by syncDoc: JSON at /movies (functions/movies/index.js), merged per item ([merge]),
 * so phone and tablet share them; each picture at /movies/<name>. Pictures: <name>.pending = not uploaded yet,
 * <name>.deleted = no longer used, delete it in the cloud; a picture missing on this device is downloaded.
 * Syncs UPLOAD_DELAY_MS after the last change (reviews save on every keystroke; KV allows ~1000 writes a day) and on visits.
 */
object Movies {
    var data by mutableStateOf(MovieData())
        private set
    /** Bumped when pictures were downloaded: posters re-read their file. */
    var picturesVersion by mutableStateOf(0)
        private set
    private var dir: File? = null
    private const val UPLOAD_DELAY_MS = 10_000L
    private val io = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + io)
    private var scheduled: Job? = null
    private fun marker(name: String) = File(checkNotNull(dir), name)
    private val file get() = File(checkNotNull(dir) { "Movies.init() not called" }, "movies.json")

    fun init(context: Context) = synchronized(this) {
        if (dir != null) return
        dir = File(context.applicationContext.filesDir, "movies").apply { mkdirs() }
        val f = file
        data = if (!f.exists()) MovieData() else runCatching { json.decodeFromString<MovieData>(f.readText()) }
            .getOrElse { f.renameTo(File(f.path + ".bad")); MovieData() }  // keep a broken file, don't overwrite it
    }

    /**
     * local = a change made here: what changed gets `updated` = now, what disappeared a tombstone, and a sync is
     * scheduled. false = merging the cloud copy in (already stamped).
     */
    fun change(local: Boolean = true, f: (MovieData) -> MovieData) = synchronized(this) {
        val before = data
        var after = f(before)
        if (after == before) return
        if (local) after = stamped(before, after, System.currentTimeMillis())
        data = after
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(after))
        tmp.renameTo(file)
        // Pictures no title points to any more (deleted title, replaced picture)
        val used = after.titles.mapNotNull { it.image }.toSet()
        before.titles.mapNotNull { it.image }.filter { it !in used }.forEach {
            imageFile(it).delete()
            marker("$it.pending").delete()
            marker("$it.deleted").createNewFile()
        }
        if (local) {
            scheduled?.cancel()
            scheduled = scope.launch { delay(UPLOAD_DELAY_MS); runCatching { syncNow() } }
        }
    }

    /** Merges the cloud copy in, pushes what it lacks, moves pictures both ways. Call on tab visits; offline is normal. */
    suspend fun sync(): Result<Unit> = withContext(io) { runCatching { syncNow() } }

    private fun syncNow() {
        // Pictures up first: a device reading the new JSON can then fetch them
        checkNotNull(dir).listFiles()!!.forEach { m ->
            when {
                m.name.endsWith(".jpg.pending") -> {
                    val img = imageFile(m.name.removeSuffix(".pending"))
                    if (img.exists()) cloudBytes("movies/${img.name}", "PUT", img.readBytes(), "image/jpeg")
                    m.delete()
                }
                m.name.endsWith(".jpg.deleted") -> { cloudBytes("movies/${m.name.removeSuffix(".deleted")}", "DELETE"); m.delete() }
            }
        }
        syncDoc("movies", MovieData.serializer()) { remote -> change(local = false) { merge(it, remote) }; data }
        var downloaded = false
        for (name in data.titles.mapNotNull { it.image }) {
            val f = imageFile(name)
            if (f.exists() || marker("$name.pending").exists()) continue
            runCatching { cloudBytes("movies/$name") }.onSuccess { bytes ->
                File(f.path + ".tmp").apply { writeBytes(bytes); renameTo(f) }
                downloaded = true
            }
        }
        if (downloaded) picturesVersion++
    }

    fun imageFile(name: String) = File(checkNotNull(dir), name)

    fun newTitle(name: String, series: Boolean, listId: String?): Title {
        val t = Title(UUID.randomUUID().toString(), name.trim(), series, created = System.currentTimeMillis())
        change { d -> d.put(t).copy(lists = d.lists.map { if (it.id == listId) it.copy(titles = it.titles + t.id) else it }) }
        return t
    }

    /** Copies a gallery picture in at most IMAGE_MAX_PX (ImageDecoder also applies the photo's rotation). */
    suspend fun setImage(context: Context, titleId: String, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val scale = minOf(1f, IMAGE_MAX_PX.toFloat() / maxOf(info.size.width, info.size.height))
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE  // compress() can't read a hardware bitmap
            }
            val name = "$titleId-${System.currentTimeMillis()}.jpg"  // a new name each time: the screen reloads it
            imageFile(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 75, it) }
            marker("$name.pending").createNewFile()
            withContext(Dispatchers.Main) {
                change { d -> d.title(titleId)?.let { d.put(it.copy(image = name)) } ?: d }
            }
            if (Movies.data.title(titleId)?.image != name) imageFile(name).delete()  // title deleted meanwhile
        }
    }
}

private sealed interface MovieScreen {
    data object Home : MovieScreen
    data class List(val id: String) : MovieScreen
    data class Edit(val id: String, val back: MovieScreen) : MovieScreen
    data object Diary : MovieScreen
}

@Composable
fun MoviesTab() {
    var screen by remember { mutableStateOf<MovieScreen>(MovieScreen.Home) }
    LaunchedEffect(Unit) { Movies.sync() }  // quietly: offline is normal, pending changes go up next time
    BackHandler(enabled = screen != MovieScreen.Home) {
        screen = (screen as? MovieScreen.Edit)?.back ?: MovieScreen.Home
    }
    val data = Movies.data
    when (val s = screen) {
        MovieScreen.Home -> MoviesHome(data, openList = { screen = MovieScreen.List(it) },
            openTitle = { screen = MovieScreen.Edit(it, MovieScreen.Home) }, openDiary = { screen = MovieScreen.Diary })
        MovieScreen.Diary -> DiaryScreen(data) { screen = MovieScreen.Edit(it, MovieScreen.Diary) }
        is MovieScreen.List -> {
            val list = data.lists.firstOrNull { it.id == s.id }
            if (list == null) { LaunchedEffect(s) { screen = MovieScreen.Home }; return }  // deleted
            ListScreen(data, list, openTitle = { screen = MovieScreen.Edit(it, s) }, deleted = { screen = MovieScreen.Home })
        }
        is MovieScreen.Edit -> {
            val t = data.title(s.id)
            if (t == null) { LaunchedEffect(s) { screen = s.back }; return }  // deleted
            ReviewScreen(t)
        }
    }
}

@Composable
private fun MoviesHome(data: MovieData, openList: (String) -> Unit, openTitle: (String) -> Unit, openDiary: () -> Unit) {
    var newList by remember { mutableStateOf(false) }
    var newReview by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    val reviews = data.titles.filter { it.reviewed() && (tag == null || tag in it.tags) }.sortedWith(compareByDescending<Title> { it.score }.thenBy { it.name.lowercase() })
    val favorites = data.titles.filter { it.favorite }.sortedByDescending { it.score }.take(4)
    Page("Movies & TV", actions = { TextButton(onClick = openDiary) { Text("Diary") } }) { pad ->
        LazyColumn(contentPadding = pad) {
            if (favorites.isNotEmpty()) item {
                Header("Favourites")
                // Letterboxd's top four
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    favorites.forEach { t ->
                        Column(Modifier.weight(1f).clickable { openTitle(t.id) }) {
                            Poster(t, Modifier.fillMaxWidth().aspectRatio(2f / 3f), thumbnail = true)
                            Text(t.name, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                        }
                    }
                    repeat(4 - favorites.size) { Box(Modifier.weight(1f)) }
                }
            }
            item { Header("Lists") }
            if (data.lists.isEmpty()) item { Hint("Make a list of movies and series to watch, then tick them off.") }
            items(data.lists, key = { it.id }) { l ->
                val ts = l.titles.mapNotNull { data.title(it) }
                val watched = ts.count { it.watched != null }
                ListItem(
                    headlineContent = { Text(l.name) },
                    supportingContent = {
                        ProgressText("$watched / ${ts.size} watched", ts.sumOf { it.progress().toDouble() }.toFloat() / ts.size.coerceAtLeast(1))
                    },
                    modifier = Modifier.clickable { openList(l.id) },
                )
                HorizontalDivider()
            }
            item {
                OutlinedButton(onClick = { newList = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("+ New list") }
            }
            item { Header("Reviews") }
            item { TagFilter(data.allTags(), tag) { tag = it } }
            if (reviews.isEmpty() && tag == null) item { Hint("Rate a title you watched: its score from 1 to 10 gives it its colour.") }
            items(reviews, key = { it.id }) { t -> TitleRow(t, check = false) { openTitle(t.id) } }
            item {
                OutlinedButton(onClick = { newReview = true }, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("+ New review") }
            }
        }
    }
    if (newList) NameDialog("New list", askKind = false, dismiss = { newList = false }) { name, _ ->
        val l = WatchList(UUID.randomUUID().toString(), name.trim(), created = System.currentTimeMillis())
        Movies.change { it.copy(lists = it.lists + l) }
        openList(l.id)
    }
    if (newReview) NameDialog("New review", askKind = true, dismiss = { newReview = false }) { name, series ->
        openTitle(Movies.newTitle(name, series, listId = null).id)
    }
}

@Composable
private fun ListScreen(data: MovieData, list: WatchList, openTitle: (String) -> Unit, deleted: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tag by remember { mutableStateOf<String?>(null) }
    val all = list.titles.mapNotNull { data.title(it) }
    val titles = all.filter { tag == null || tag in it.tags }
    val watched = titles.count { it.watched != null }
    Page(list.name, actions = {
        TextButton(onClick = { adding = true }) { Text("Add") }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete list") }
    }) { pad ->
        LazyColumn(contentPadding = pad) {
            item {
                // The bar counts series episodes too: half a series watched = half a title
                ProgressText("$watched / ${titles.size} watched", titles.sumOf { it.progress().toDouble() }.toFloat() / titles.size.coerceAtLeast(1),
                    Modifier.padding(16.dp))
            }
            item { TagFilter(all.flatMap { it.tags }.distinct().sorted(), tag) { tag = it } }
            if (titles.isEmpty()) item { Hint("Empty list: tap Add.") }
            // Still to watch first, then the watched ones
            items(titles.sortedBy { it.watched != null }, key = { it.id }) { t -> TitleRow(t, check = true) { openTitle(t.id) } }
        }
    }
    if (adding) NameDialog("Add to ${list.name}", askKind = true, dismiss = { adding = false }) { name, series ->
        Movies.newTitle(name, series, list.id)
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${list.name}”?") },
        text = { Text("Titles with a review stay in Reviews; the others are deleted with the list.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; deleted(); Movies.change { it.withoutList(list.id) } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** A title in a list (with its watched checkbox) or in Reviews. Its colour is its score. */
@Composable
private fun TitleRow(t: Title, check: Boolean, open: () -> Unit) {
    ListItem(
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (check) Checkbox(checked = t.watched != null, onCheckedChange = { w ->
                    Movies.change { it.put(t.setWatched(w, System.currentTimeMillis())) }
                })
                Poster(t, Modifier.size(44.dp), thumbnail = true)
            }
        },
        headlineContent = { Text(t.name) },
        supportingContent = {
            val rated = ASPECTS.mapNotNull { (k, label) -> t.aspects[k]?.score?.takeIf { it > 0 }?.let { "$label $it★" } }
            Text((listOfNotNull(if (t.series) "Series" else "Movie", episodesText(t)) + t.tags.map { "#$it" } + rated)
                .joinToString(" · "))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (check && t.series && t.watched == null) {
                    TextButton(onClick = { Movies.change { it.put(t.plusEpisode(System.currentTimeMillis())) } }) { Text("+1 ep") }
                }
                if (t.score > 0) ScoreBadge(t.score)
            }
        },
        modifier = Modifier.clickable(onClick = open),
    )
    HorizontalDivider()
}

private fun episodesText(t: Title) = when {
    !t.series || (t.episodes == 0 && t.totalEpisodes == 0 && t.season == 1) -> null
    t.totalEpisodes > 0 -> "S${t.season} · ep ${t.episodes}/${t.totalEpisodes}"
    else -> "S${t.season} · ep ${t.episodes}"
}

/** Every viewing, newest first, grouped by month (Letterboxd's diary). */
@Composable
private fun DiaryScreen(data: MovieData, open: (String) -> Unit) = Page("Diary") { pad ->
    val zone = java.time.ZoneId.systemDefault()
    val views = data.titles.flatMap { t -> t.views.map { it to t } }.sortedByDescending { it.first }
    val fmtMonth = java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")
    val fmtDay = java.time.format.DateTimeFormatter.ofPattern("EEE d")
    LazyColumn(contentPadding = pad) {
        if (views.isEmpty()) item { Hint("Tick a title as watched (or “Watched again”) and it shows up here, by date.") }
        views.groupBy { java.time.Instant.ofEpochMilli(it.first).atZone(zone).toLocalDate().withDayOfMonth(1) }.forEach { (month, list) ->
            item { Header("${month.format(fmtMonth).replaceFirstChar { it.uppercase() }} · ${list.size}") }
            items(list, key = { "${it.second.id}-${it.first}" }) { (at, t) ->
                ListItem(
                    overlineContent = { Text(java.time.Instant.ofEpochMilli(at).atZone(zone).format(fmtDay)) },
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text(listOfNotNull(if (t.series) "Series" else "Movie",
                        if (t.views.size > 1) "watched ${t.views.size}×" else null).joinToString(" · ")) },
                    trailingContent = { if (t.score > 0) ScoreBadge(t.score, size = 32) },
                    modifier = Modifier.clickable { open(t.id) },
                )
                HorizontalDivider()
            }
        }
    }
}

/** "All" + one chip per tag; shown only when there are tags. */
@Composable
private fun TagFilter(tags: List<String>, chosen: String?, choose: (String?) -> Unit) {
    if (tags.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = chosen == null, onClick = { choose(null) }, label = { Text("All") })
        tags.forEach { t -> FilterChip(selected = chosen == t, onClick = { choose(if (chosen == t) null else t) }, label = { Text("#$t") }) }
    }
}

@Composable
fun ScoreBadge(score: Int, size: Int = 40) = Box(
    Modifier.size(size.dp).clip(RoundedCornerShape(8.dp)).background(scoreColor(score)).semantics { contentDescription = "Score $score of 10" },
    contentAlignment = Alignment.Center,
) { Text("$score", color = onScore(score), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) }

/** The title's picture, or a plain tile in its score colour. */
@Composable
private fun Poster(t: Title, modifier: Modifier, thumbnail: Boolean = false) {
    // A list row needs ~150 px: decoding at 1/4 keeps a long list light (inSampleSize 4 on a 600 px picture)
    val bitmap = remember(t.image, Movies.picturesVersion, thumbnail) {
        t.image?.let {
            val opts = BitmapFactory.Options().apply { inSampleSize = if (thumbnail) 4 else 1 }
            BitmapFactory.decodeFile(Movies.imageFile(it).path, opts)?.asImageBitmap()
        }
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(6.dp)))
    } else {
        Box(modifier.clip(RoundedCornerShape(6.dp)).background(if (t.score > 0) scoreColor(t.score) else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center) { Text(if (t.series) "📺" else "🎬") }
    }
}

/** The review: picture, watched, score 1-10, characters / plot / setting / meaning (1-5 + text), opinions. Saved as you type. */
@Composable
private fun ReviewScreen(t: Title) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    fun update(f: (Title) -> Title) = Movies.change { d -> d.title(t.id)?.let { d.put(f(it)) } ?: d }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            Movies.setImage(context, t.id, uri).onFailure {
                Toast.makeText(context, "Cannot use this picture: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    val pick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    Page(t.name.ifBlank { "Review" }, actions = { TextButton(onClick = { confirmDelete = true }) { Text("Delete") } }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // The score colour frames the whole title
            val frame = if (t.score > 0) scoreColor(t.score) else MaterialTheme.colorScheme.outlineVariant
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(4.dp, frame, RoundedCornerShape(12.dp)).padding(4.dp)) {
                if (t.image != null) {
                    Poster(t, Modifier.fillMaxWidth().heightIn(max = 320.dp).aspectRatio(2f / 3f, matchHeightConstraintsFirst = true)
                        .align(Alignment.Center).clickable(onClick = pick))
                } else {
                    TextButton(onClick = pick, modifier = Modifier.fillMaxWidth().padding(24.dp)) { Text("🖼  Add a picture from the gallery") }
                }
            }
            if (t.image != null) TextButton(onClick = pick) { Text("Change picture") }

            OutlinedTextField(t.name, { v -> update { it.copy(name = v) } }, label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                listOf(false to "🎬 Movie", true to "📺 Series").forEachIndexed { i, (series, label) ->
                    SegmentedButton(selected = t.series == series, onClick = { update { it.copy(series = series) } },
                        shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Checkbox(checked = t.watched != null, onCheckedChange = { w -> update { it.setWatched(w, System.currentTimeMillis()) } })
                Text("Watched", Modifier.weight(1f))
                TextButton(onClick = { update { it.watchAgain(System.currentTimeMillis()) } }) { Text("Watched again") }
                FilterChip(selected = t.favorite, onClick = { update { it.copy(favorite = !it.favorite) } }, label = { Text("⭐ Favourite") })
            }
            if (t.views.isNotEmpty()) {
                val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
                Text("Watched: " + t.views.joinToString(", ") {
                    java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(fmt)
                }, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                if (t.runtime > 0) "${t.runtime}" else "",
                { v -> update { it.copy(runtime = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) } },
                label = { Text(if (t.series) "Minutes per episode" else "Runtime (minutes)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            if (t.series) {
                Header("Season", Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { update { it.copy(season = (it.season - 1).coerceAtLeast(1)) } }) { Text("−") }
                    Text("${t.season}", style = MaterialTheme.typography.headlineSmall)
                    // A new season starts its episode count again (episodesSeen keeps the total)
                    OutlinedButton(onClick = { update { it.copy(season = it.season + 1, episodes = 0, watched = null) } }) { Text("Next season") }
                }
                Header("Episodes watched", Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { update { it.copy(episodes = (it.episodes - 1).coerceAtLeast(0)) } }) { Text("−") }
                    Text("${t.episodes}", style = MaterialTheme.typography.headlineSmall)
                    OutlinedButton(onClick = { update { it.plusEpisode(System.currentTimeMillis()) } }) { Text("+1") }
                    OutlinedTextField(
                        if (t.totalEpisodes > 0) "${t.totalEpisodes}" else "",
                        { v -> update { it.copy(totalEpisodes = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) } },
                        label = { Text("of (total)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                    )
                }
            }

            Header("Tags", Modifier.padding(top = 16.dp))
            TagEditor(t.tags, Movies.data.allTags()) { tags -> update { it.copy(tags = tags) } }

            ReviewFields(
                t.score, t.aspects, ASPECTS, t.opinion,
                setScore = { s -> update { it.copy(score = s) } },
                setAspect = { k, a -> update { it.copy(aspects = it.aspects + (k to a)) } },
                setOpinion = { v -> update { it.copy(opinion = v) } },
            )
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${t.name}”?") },
        text = { Text("Its review and picture are deleted, and it leaves every list.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; Movies.change { it.withoutTitle(t.id) } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** The title's tags (tap to remove), the other tags you've used (tap to add), and a box for a new one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditor(tags: List<String>, known: List<String>, set: (List<String>) -> Unit) {
    var new by remember { mutableStateOf("") }
    val add = { tag: String -> if (tag.isNotEmpty() && tag !in tags) set(tags + tag); new = "" }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            InputChip(selected = true, onClick = { set(tags - tag) }, label = { Text("#$tag") }, trailingIcon = { Text("✕") })
        }
        known.filter { it !in tags }.forEach { tag ->
            AssistChip(onClick = { add(tag) }, label = { Text("+ #$tag") })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(new, { new = it }, placeholder = { Text("New tag (e.g. sci-fi)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add(tagOf(new)) }), modifier = Modifier.weight(1f))
        TextButton(onClick = { add(tagOf(new)) }, enabled = new.isNotBlank()) { Text("Add") }
    }
}

/** Score 1-10 (its colour), one 1-5 ★ + text per aspect, opinions. Shared by movie and book reviews. */
@Composable
fun ReviewFields(
    score: Int, aspects: Map<String, Aspect>, aspectList: List<Pair<String, String>>, opinion: String,
    setScore: (Int) -> Unit, setAspect: (String, Aspect) -> Unit, setOpinion: (String) -> Unit,
) {
    Header("Score", Modifier.padding(top = 16.dp))
    ScorePicker(score) { s -> setScore(if (score == s) 0 else s) }
    aspectList.forEach { (key, label) ->
        val a = aspects[key] ?: Aspect()
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 20.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Stars(a.score) { s -> setAspect(key, a.copy(score = if (a.score == s) 0 else s)) }
        }
        OutlinedTextField(a.text, { v -> setAspect(key, a.copy(text = v)) },
            placeholder = { Text("About the ${label.lowercase()}…") }, minLines = 2, modifier = Modifier.fillMaxWidth())
    }
    Header("Opinions", Modifier.padding(top = 20.dp))
    OutlinedTextField(opinion, setOpinion, placeholder = { Text("What you thought of it…") },
        minLines = 4, modifier = Modifier.fillMaxWidth())
}

/** A book's review page (Library → book → Review). Saved as you type, backed up with the movie reviews. */
@Composable
fun BookReviewScreen(bookId: String, title: String, author: String?) {
    val r = Movies.data.books[bookId] ?: BookReview()
    fun update(f: (BookReview) -> BookReview) =
        Movies.change { d -> d.copy(books = d.books + (bookId to f(d.books[bookId] ?: BookReview()))) }
    var confirmDelete by remember { mutableStateOf(false) }
    val actions: @Composable RowScope.() -> Unit = {
        if (r.reviewed()) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete your review of “$title”?") },
        // The shelf (to read / reading / ...) stays: only the review is deleted
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            Movies.change { d ->
                val keep = d.books[bookId]?.let { BookReview(status = it.status, started = it.started, finished = it.finished) }
                d.copy(books = if (keep != null && keep.status.isNotEmpty()) d.books + (bookId to keep) else d.books - bookId)
            }
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
    Page("Review · $title", actions = actions) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (author != null) Text(author, style = MaterialTheme.typography.bodyLarge)
            ShelfPicker(bookId)
            FavouriteQuotes(bookId)
            ReviewFields(
                r.score, r.aspects, BOOK_ASPECTS, r.opinion,
                setScore = { s -> update { it.copy(score = s) } },
                setAspect = { k, a -> update { it.copy(aspects = it.aspects + (k to a)) } },
                setOpinion = { v -> update { it.copy(opinion = v) } },
            )
        }
    }
}

/** To read / Reading / Finished / Abandoned (tap again to clear), with the dates they set. */
@Composable
fun ShelfPicker(bookId: String) {
    val r = Movies.data.books[bookId] ?: BookReview()
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")
    fun date(ms: Long?) = ms?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).format(fmt) }
    Column(Modifier.padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            SHELVES.forEach { (key, label) ->
                FilterChip(selected = r.status == key, label = { Text(label) }, onClick = {
                    Movies.change { d ->
                        val old = d.books[bookId] ?: BookReview()
                        d.copy(books = d.books + (bookId to if (old.status == key) old.copy(status = "") else old.withStatus(key, System.currentTimeMillis())))
                    }
                })
            }
        }
        listOfNotNull(date(r.started)?.let { "Started $it" }, date(r.finished)?.let { "finished $it" }).joinToString(" · ")
            .takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** The book's highlights; ⭐ marks favourite quotes (shown first, exported with the review). */
@Composable
private fun FavouriteQuotes(bookId: String) {
    val context = LocalContext.current
    var version by remember { mutableStateOf(0) }
    val all = remember(bookId, version) { Highlights(context, bookId).all().sortedByDescending { it.favorite } }
    if (all.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Header("Favourite quotes", Modifier.padding(top = 16.dp))
    Text("Star your highlights to keep them with the review.", style = MaterialTheme.typography.bodySmall)
    (if (expanded) all else all.take(3)).forEach { h ->
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(top = 6.dp)) {
            Text("“${h.text}”", fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, modifier = Modifier.weight(1f), maxLines = 4)
            TextButton(onClick = { Highlights(context, bookId).save(h.copy(favorite = !h.favorite)); version++ }) {
                Text(if (h.favorite) "⭐" else "☆")
            }
        }
    }
    if (all.size > 3) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show fewer" else "Show all ${all.size}") }
}

/** 1..10, each in its colour; tap the chosen one again to clear it. */
@Composable
private fun ScorePicker(score: Int, choose: (Int) -> Unit) =
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..10).forEach { s ->
            val on = s == score
            Box(
                Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(6.dp))  // 48 dp: comfortable to tap
                    .background(scoreColor(s).copy(alpha = if (on || score == 0) 1f else 0.3f))
                    .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(6.dp)) else Modifier)
                    .clickable { choose(s) }
                    .semantics { contentDescription = "Score $s" + if (on) ", chosen" else "" },
                contentAlignment = Alignment.Center,
            ) { Text("$s", color = onScore(s), fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }
        }
    }

/** 1..5 stars; tap the chosen one again to clear it. */
@Composable
private fun Stars(score: Int, choose: (Int) -> Unit) = Row {
    (1..5).forEach { s ->
        Text(if (s <= score) "★" else "☆", style = MaterialTheme.typography.headlineSmall,
            color = if (s <= score) Color(0xFFFFB300) else MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center,
            modifier = Modifier.size(width = 40.dp, height = 48.dp).wrapContentHeight().clickable { choose(s) }  // ≥ 48 dp tall to tap
                .semantics { contentDescription = "$s of 5" })
    }
}

/** Name (+ movie or series) for a new list or title. */
@Composable
private fun NameDialog(title: String, askKind: Boolean, dismiss: () -> Unit, done: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var series by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                if (askKind) SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    listOf(false to "Movie", true to "Series").forEachIndexed { i, (s, label) ->
                        SegmentedButton(selected = series == s, onClick = { series = s }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { dismiss(); done(name, series) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Header(text: String, modifier: Modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)) =
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier)

@Composable
private fun Hint(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
