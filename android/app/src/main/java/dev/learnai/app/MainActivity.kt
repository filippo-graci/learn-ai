package dev.learnai.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import android.content.Intent
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File

/** Where the user is. ponytail: plain state + BackHandler instead of a navigation library for 5 screens. */
sealed interface Screen {
    data object Library : Screen
    data class Book(val bookId: String) : Screen
    data class Chapter(val bookId: String, val n: Int) : Screen  // hub: Read full chapter / Summary / Quiz
    data class Summary(val bookId: String, val n: Int) : Screen
    data class Quiz(val bookId: String, val n: Int) : Screen
    data class Notes(val bookId: String) : Screen  // highlights & notes of a book
    data class Review(val bookId: String) : Screen  // your review of the book
    data object Search : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Learning.init(this)
        Movies.init(this)
        Reviews.init(this)
        scheduleDueReminder(this)
        // The "cards due" notification opens the Review tab
        val start = intent.getStringExtra(EXTRA_TAB)?.let { runCatching { Tab.valueOf(it) }.getOrNull() } ?: Tab.Library
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                App(start)
            }
        }
    }

    /** The news site's profile login is a cookie: write it to disk now, or killing the app soon after logging in loses it. */
    override fun onStop() {
        super.onStop()
        android.webkit.CookieManager.getInstance().flush()
    }
}

/** Focus = the Stats tab (focus, reading, reviews, books, movies, news; month / year / all). */
enum class Tab(val label: String, val icon: String) {
    Library("Library", "📚"), Review("Review", "🧠"), Focus("Stats", "📈"), News("News", "📰"), Movies("Movies", "🎬"),
}

@Composable
fun App(start: Tab = Tab.Library) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(start) }
    val news = remember { newsWebView(context) }  // kept across tab switches: no reload, scroll position kept
    // A highlight shown outside the Library (Review tab's daily highlights): open the reader at it
    val openHighlight = { bookId: String, locator: String ->
        val book = LocalBooks(context).forBook(bookId)
        if (book == null) Toast.makeText(context, "Import this book's EPUB on this device to open it", Toast.LENGTH_LONG).show()
        else scope.launch {
            ReaderSession.open(context, book).onSuccess { context.startActivity(ReaderActivity.intent(context, bookId, null, locator)) }
        }
        Unit
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                Tab.Library -> LibraryTab()
                Tab.Review -> ReviewTab(openHighlight)
                Tab.Focus -> FocusTab()
                Tab.News -> NewsScreen(news)
                Tab.Movies -> MoviesTab()
            }
        }
        NavigationBar {
            Tab.entries.forEach { t ->
                NavigationBarItem(
                    selected = tab == t,
                    onClick = {
                        if (t == Tab.News && tab == Tab.News) news.loadUrl(NEWS_URL)  // tap again: today's digest
                        tab = t
                    },
                    icon = { Text(t.icon) },
                    label = { Text(t.label) },
                )
            }
        }
    }
}

@Composable
fun LibraryTab() {
    val context = LocalContext.current
    var library by remember { mutableStateOf(Content(context).library()) }
    val content = remember(library) { Content(context) }  // re-picks downloaded vs bundled after a sync
    val localBooks = remember { LocalBooks(context) }
    var imported by remember { mutableStateOf(localBooks.all()) }
    var screen by remember { mutableStateOf<Screen>(Screen.Library) }
    val scope = rememberCoroutineScope()

    var readerVisits by remember { mutableStateOf(0) }  // bumped on return from the reader or a notes sync: re-read highlights

    // On every visit to the tab (quietly: offline is normal) and on Refresh: new books/chapters from Cloudflare,
    // highlights & notes both ways (NotesSync), and learning progress (Learning.sync).
    fun sync(manual: Boolean) = scope.launch {
        val notes = NotesSync.sync(context, localBooks.all().map { it.id }).onSuccess { readerVisits++ }
        val progress = Learning.sync()
        val reviews = Movies.sync()  // book reviews show here too
        val site = Content(context).sync().onSuccess { library = Content(context).library() }
        val error = (site.exceptionOrNull() ?: notes.exceptionOrNull() ?: progress.exceptionOrNull() ?: reviews.exceptionOrNull())?.message
        if (manual) Toast.makeText(context, if (error == null) "Up to date" else "Refresh failed: $error",
            if (error == null) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
    }
    LaunchedEffect(Unit) { sync(manual = false) }
    val reader = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { readerVisits++ }
    // Imported EPUBs without summaries get a chapter list from their table of contents (LocalBooks.chapters, cached).
    var tocBooks by remember { mutableStateOf(mapOf<String, Book>()) }
    LaunchedEffect(imported, library) {
        tocBooks = imported.filter { b -> library.books.none { it.id == b.id } }.mapNotNull { b ->
            localBooks.chapters(b).getOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { b.id to Book(b.id, b.title, b.author, identifier = b.identifier, chapters = it) }
        }.toMap()
    }
    fun bookOf(id: String) = library.books.firstOrNull { it.id == id } ?: tocBooks[id]
    // EPUB covers (extracted once per book, cached next to the EPUB)
    var covers by remember { mutableStateOf(mapOf<String, File>()) }
    LaunchedEffect(imported) { covers = imported.mapNotNull { b -> localBooks.cover(b)?.let { b.id to it } }.toMap() }
    // How far into each e-book (Readium's whole-book progression of the saved position): the bar of a book without chapters.
    val reading = remember(readerVisits, imported) {
        val positions = Positions(context)
        imported.mapNotNull { b -> positions.get(b.id)?.locations?.totalProgression?.let { b.id to it } }.toMap()
    }

    fun read(bookId: String, chapterHref: String?, locator: String? = null) {
        val book = imported.firstOrNull { it.id == bookId } ?: return
        // Opening a book you haven't shelved, or meant to read: it's now "reading"
        Movies.change { d ->
            val r = d.books[bookId] ?: BookReview()
            if (r.status == "" || r.status == "to-read") d.copy(books = d.books + (bookId to r.withStatus("reading", System.currentTimeMillis()))) else d
        }
        scope.launch {
            ReaderSession.open(context, book)
                .onSuccess { reader.launch(ReaderActivity.intent(context, bookId, chapterHref, locator)) }
                .onFailure { Toast.makeText(context, "Cannot open the book: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            localBooks.import(uri, library)
                .onSuccess {
                    NotesSync.sync(context, listOf(it.id)).onSuccess { readerVisits++ }  // e.g. after a reinstall
                    imported = localBooks.all()
                    val linked = library.books.any { b -> b.id == it.id }
                    Toast.makeText(context, "${it.title}: " + if (linked) "linked to its summaries" else "imported (no summaries yet)",
                        Toast.LENGTH_LONG).show()
                }
                .onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val importEpub = { picker.launch(arrayOf("application/epub+zip")) }

    BackHandler(enabled = screen != Screen.Library) {
        screen = when (val s = screen) {
            is Screen.Quiz, is Screen.Summary -> Screen.Chapter(s.bookIdOf(), s.nOf())
            is Screen.Chapter -> Screen.Book(s.bookId)
            is Screen.Notes -> Screen.Book(s.bookId)
            is Screen.Review -> Screen.Book(s.bookId)
            else -> Screen.Library  // Book, Search
        }
    }
    when (val s = screen) {
        Screen.Search -> SearchScreen(library, library.books + tocBooks.values) { h ->
            when (h) {
                is Hit.Book -> screen = Screen.Book(h.bookId)
                is Hit.Chapter -> screen = if (h.bookId in tocBooks) Screen.Book(h.bookId) else Screen.Chapter(h.bookId, h.n)
                is Hit.Quote -> read(h.bookId, null, h.locator)
                is Hit.BookReview -> screen = Screen.Review(h.bookId)
                is Hit.Movie -> Toast.makeText(context, "It's in the Movies tab", Toast.LENGTH_SHORT).show()
            }
        }
        Screen.Library -> LibraryScreen(library, imported, tocBooks, reading, covers, importEpub, refresh = { sync(manual = true) },
            search = { screen = Screen.Search }) { id ->
            // An e-book with no chapter list (no summaries, no table of contents): open it straight in the reader.
            if (bookOf(id) != null) screen = Screen.Book(id) else read(id, null)
        }
        is Screen.Book -> {
            val book = bookOf(s.bookId) ?: return
            BookScreen(
                book, hasEpub = imported.any { it.id == s.bookId },
                notes = remember(s, readerVisits) { Highlights(context, s.bookId).all().size },
                continueReading = { read(s.bookId, null) }, importEpub = importEpub,
                showNotes = { screen = Screen.Notes(s.bookId) },
                review = { screen = Screen.Review(s.bookId) },
            ) { n ->
                // No summaries: a chapter is only its text, so open it in the reader instead of the chapter hub.
                if (s.bookId in tocBooks) read(s.bookId, book.chapters.first { it.n == n }.href)
                else screen = Screen.Chapter(s.bookId, n)
            }
        }
        is Screen.Review -> {
            val b = bookOf(s.bookId) ?: imported.firstOrNull { it.id == s.bookId }?.let { Book(it.id, it.title, it.author, chapters = emptyList()) }
            if (b != null) BookReviewScreen(b.id, b.title, b.author)
        }
        is Screen.Notes -> NotesScreen(
            bookOf(s.bookId) ?: return, Movies.data.books[s.bookId],
            remember(s, readerVisits) { Highlights(context, s.bookId).all() },
        ) { read(s.bookId, null, it.locator) }
        is Screen.Chapter -> {
            val book = library.books.first { it.id == s.bookId }
            val info = book.chapters.first { it.n == s.n }
            ChapterHub(
                s.bookId, info, canRead = imported.any { it.id == s.bookId } && info.href != null,
                read = { read(s.bookId, info.href) },
                summary = { screen = Screen.Summary(s.bookId, s.n) },
                quiz = { screen = Screen.Quiz(s.bookId, s.n) },
            )
        }
        is Screen.Summary -> SummaryScreen(remember(s) { content.chapter(s.bookId, s.n) }) {
            screen = Screen.Quiz(s.bookId, s.n)
        }
        is Screen.Quiz -> QuizScreen(s.bookId, remember(s) { content.chapter(s.bookId, s.n) }) {
            screen = Screen.Chapter(s.bookId, s.n)
        }
    }
}

private fun Screen.bookIdOf() = when (this) { is Screen.Summary -> bookId; is Screen.Quiz -> bookId; else -> error("no book") }
private fun Screen.nOf() = when (this) { is Screen.Summary -> n; is Screen.Quiz -> n; else -> error("no chapter") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String, actions: @Composable RowScope.() -> Unit = {}, bottomBar: @Composable () -> Unit = {},
    body: @Composable (PaddingValues) -> Unit,
) {
    // One line only: the bar is a fixed 64 dp, a 2-line chapter title spilled over the content below it.
    // No bottom insets here: the NavigationBar below the page already keeps clear of the system bar.
    Scaffold(
        topBar = { TopAppBar(title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, actions = actions) },
        bottomBar = bottomBar,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { body(it) }
}

/** All books: those with summaries (from the laptop) and EPUBs imported on the phone without summaries. */
@Composable
fun LibraryScreen(
    library: Library, imported: List<LocalBook>, tocBooks: Map<String, Book>, reading: Map<String, Double>,
    covers: Map<String, File>, importEpub: () -> Unit, refresh: () -> Unit, search: () -> Unit,
    open: (String) -> Unit,
) = Page("Library", actions = {
        TextButton(onClick = search) { Text("Search") }
        TextButton(onClick = refresh) { Text("Refresh") }
        TextButton(onClick = importEpub) { Text("Import") }
    }) { pad ->
        var shelf by rememberSaveable { mutableStateOf("") }  // "" = all
        val onShelf = { id: String -> shelf == "" || (Movies.data.books[id]?.status ?: "") == shelf }
        val readOnly = imported.filter { b -> library.books.none { it.id == b.id } && onShelf(b.id) }
        LazyColumn(contentPadding = pad) {
            item {
                // Goodreads-style shelves
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = shelf == "", onClick = { shelf = "" }, label = { Text("All") })
                    SHELVES.forEach { (key, label) ->
                        FilterChip(selected = shelf == key, onClick = { shelf = if (shelf == key) "" else key }, label = { Text(label) })
                    }
                }
            }
            items(library.books.filter { onShelf(it.id) }) { b ->
                val hasEpub = imported.any { it.id == b.id }
                val done = b.chapters.count { chapterKey(b.id, it.n) in Learning.progress.chapters }
                ListItem(
                    leadingContent = { Cover(covers[b.id]) },
                    headlineContent = { Text(b.title) },
                    trailingContent = { Movies.data.books[b.id]?.score?.takeIf { it > 0 }?.let { ScoreBadge(it, size = 32) } },
                    supportingContent = {
                        ProgressText(listOfNotNull(b.author, shelfLabel(b.id), "$done/${b.chapters.size} chapters", if (hasEpub) "📖" else "summaries only")
                            .joinToString(" · "), done.toFloat() / b.chapters.size.coerceAtLeast(1))
                    },
                    modifier = Modifier.clickable { open(b.id) },
                )
                HorizontalDivider()
            }
            items(readOnly) { b ->
                ListItem(
                    leadingContent = { Cover(covers[b.id]) },
                    headlineContent = { Text(listOfNotNull(b.title, shelfLabel(b.id)).joinToString(" · ")) },
                    trailingContent = { Movies.data.books[b.id]?.score?.takeIf { it > 0 }?.let { ScoreBadge(it, size = 32) } },
                    supportingContent = {
                        val chapters = tocBooks[b.id]?.chapters.orEmpty()
                        if (chapters.isNotEmpty()) {
                            val done = chapters.count { chapterKey(b.id, it.n) in Learning.progress.chapters }
                            ProgressText(listOfNotNull(b.author, "📖 e-book · $done/${chapters.size} chapters").joinToString(" · "),
                                done.toFloat() / chapters.size)
                        } else {  // no table of contents: how far into the book
                            val read = reading[b.id] ?: 0.0
                            ProgressText(listOfNotNull(b.author, "📖 e-book, ${(read * 100).toInt()}% read").joinToString(" · "), read.toFloat())
                        }
                    },
                    modifier = Modifier.clickable { open(b.id) },
                )
                HorizontalDivider()
            }
        }
    }

fun shelfLabel(bookId: String) = Movies.data.books[bookId]?.status?.let { s -> SHELVES.firstOrNull { it.first == s }?.second }

/** A book's cover (or a plain 📕 tile), list size. */
@Composable
fun Cover(file: File?) {
    val bitmap = remember(file) { file?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() } }
    if (bitmap != null) Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop,
        modifier = Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(4.dp)))
    else Box(Modifier.size(width = 40.dp, height = 60.dp).clip(RoundedCornerShape(4.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Text("📕") }
}

@Composable
fun BookScreen(
    book: Book, hasEpub: Boolean, notes: Int, continueReading: () -> Unit, importEpub: () -> Unit,
    showNotes: () -> Unit, review: () -> Unit, open: (Int) -> Unit,
) = Page(book.title) { pad ->
    val progress = Learning.progress
    val done = book.chapters.count { chapterKey(book.id, it.n) in progress.chapters }
    LazyColumn(contentPadding = pad) {
        item {
            ProgressText("$done / ${book.chapters.size} chapters finished · +$CHAPTER_XP XP each",
                done.toFloat() / book.chapters.size.coerceAtLeast(1), Modifier.padding(16.dp, 16.dp, 16.dp, 0.dp))
            if (hasEpub) {
                Button(onClick = continueReading, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text("Continue reading") }
                if (notes > 0) {
                    OutlinedButton(onClick = showNotes, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Text("Highlights & notes ($notes)")
                    }
                }
            } else {
                OutlinedButton(onClick = importEpub, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Import this book's EPUB to read the full text")
                }
            }
            val score = Movies.data.books[book.id]?.score ?: 0
            OutlinedButton(onClick = review, modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 8.dp)) {
                Text(if (Movies.data.books[book.id]?.reviewed() == true) "✍️  Your review" else "✍️  Write a review", Modifier.weight(1f))
                if (score > 0) ScoreBadge(score, size = 32)
            }
            Box(Modifier.padding(horizontal = 16.dp)) { ShelfPicker(book.id) }
        }
        items(book.chapters) { c ->
            val key = chapterKey(book.id, c.n)
            ListItem(
                leadingContent = {
                    Checkbox(checked = key in progress.chapters, onCheckedChange = { Learning.setChapter(book.id, c.n, it) })
                },
                headlineContent = { Text("${c.n}. ${c.title}") },
                // Chapters of an EPUB without summaries have no counts: nothing to show under the title.
                supportingContent = listOfNotNull(
                    c.words.takeIf { it > 0 }?.let { "$it words" }, c.cards.takeIf { it > 0 }?.let { "$it cards" },
                    c.questions.takeIf { it > 0 }?.let { "$it questions" },
                    progress.quizBest[key]?.let { "quiz best $it/${c.questions}" },
                ).joinToString(" · ").ifEmpty { null }?.let { info -> { Text(info) } },
                modifier = Modifier.clickable { open(c.n) },
            )
            HorizontalDivider()
        }
    }
}

/** Highlights in book order (chapter, then place in the chapter); tap one to open the reader there. */
@Composable
fun NotesScreen(book: Book, review: BookReview?, highlights: List<Highlight>, open: (Highlight) -> Unit) {
    val context = LocalContext.current
    val chapterOf = { h: Highlight -> book.chapters.lastOrNull { it.href?.substringBefore('#') == h.href } }
    val sorted = highlights.sortedWith(compareBy({ chapterOf(it)?.n ?: Int.MAX_VALUE }, { it.progression }, { it.created }))
    // Share: highlights, notes and your review as Markdown (to Obsidian, mail, a chat...)
    val share = {
        val text = bookMarkdown(book, review, sorted) { chapterOf(it)?.let { c -> "${c.n}. ${c.title}" } }
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, book.title).putExtra(Intent.EXTRA_TEXT, text), "Share highlights"))
    }
    Page("Highlights & notes", actions = { TextButton(onClick = share) { Text("Share") } }) { pad -> NotesList(sorted, chapterOf, pad, open) }
}

/** A book's highlights (by chapter), notes, favourite quotes and review, as Markdown. */
fun bookMarkdown(book: Book, review: BookReview?, highlights: List<Highlight>, chapter: (Highlight) -> String?): String = buildString {
    append("# ${book.title}\n")
    book.author?.let { append("*$it*\n") }
    if (review != null && review.reviewed()) {
        append("\n## My review")
        if (review.score > 0) append(" — ${review.score}/10")
        append("\n")
        BOOK_ASPECTS.forEach { (k, label) ->
            review.aspects[k]?.takeIf { it.score > 0 || it.text.isNotBlank() }?.let { a ->
                append("\n**$label**" + (if (a.score > 0) " ${"★".repeat(a.score)}" else "") + "\n${a.text}\n")
            }
        }
        if (review.opinion.isNotBlank()) append("\n${review.opinion}\n")
    }
    highlights.filter { it.favorite }.takeIf { it.isNotEmpty() }?.let { favs ->
        append("\n## Favourite quotes\n")
        favs.forEach { append("\n> ${it.text.replace("\n", "\n> ")}\n") }
    }
    var current: String? = null
    highlights.forEach { h ->
        val c = chapter(h) ?: "Other"
        if (c != current) { append("\n## $c\n"); current = c }
        append("\n> ${h.text.replace("\n", "\n> ")}\n")
        if (h.note.isNotBlank()) append("\n**Note:** ${h.note}\n")
    }
}

@Composable
private fun NotesList(
    sorted: List<Highlight>, chapterOf: (Highlight) -> ChapterInfo?, pad: PaddingValues, open: (Highlight) -> Unit,
) {
    LazyColumn(contentPadding = pad) {
        items(sorted, key = { it.id }) { h ->
            ListItem(
                overlineContent = { Text(chapterOf(h)?.let { "${it.n}. ${it.title}" } ?: "") },
                headlineContent = { Text("“${h.text}”", fontStyle = FontStyle.Italic, maxLines = 6, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (h.note.isBlank()) null else ({ Text("📝 ${h.note}") }),
                leadingContent = { Box(Modifier.size(width = 6.dp, height = 40.dp).background(androidx.compose.ui.graphics.Color(h.argb()))) },
                trailingContent = if (h.favorite) ({ Text("⭐") }) else null,
                modifier = Modifier.clickable { open(h) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun SummaryScreen(chapter: Chapter, startQuiz: () -> Unit) = Page(chapter.title) { pad ->
    LazyColumn(contentPadding = pad, modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(16.dp)) {
                Text("${chapter.n}. ${chapter.title}", style = MaterialTheme.typography.headlineSmall)
                Text("Summary", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(chapter.summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Text("Key ideas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 24.dp))
            }
        }
        items(chapter.keyIdeas) { idea ->
            Text("•  $idea", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (chapter.quiz.isNotEmpty()) item {
            Button(onClick = startQuiz, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Quiz · ${chapter.quiz.size} questions")
            }
        }
    }
}

/**
 * The chapter's crossroads. Not read yet: Read full chapter → Summary → Quiz.
 * Already read: Summary to refresh the memory → Quiz.
 */
@Composable
fun ChapterHub(bookId: String, info: ChapterInfo, canRead: Boolean, read: () -> Unit, summary: () -> Unit, quiz: () -> Unit) =
    Page(info.title) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
            Text("${info.n}. ${info.title}", style = MaterialTheme.typography.headlineSmall)
            Text("${info.words} words · ${info.cards} cards · ${info.questions} questions",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
            Button(onClick = read, enabled = canRead, modifier = Modifier.fillMaxWidth()) { Text("📖  Read full chapter") }
            if (!canRead) {
                Text("Import the book's EPUB (Library → Import EPUB) to read the full text here.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
            OutlinedButton(onClick = summary, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("📝  Summary") }
            OutlinedButton(onClick = quiz, enabled = info.questions > 0, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("❓  Quiz · ${info.questions} questions")
            }
            val finished = chapterKey(bookId, info.n) in Learning.progress.chapters
            val toggle = { Learning.setChapter(bookId, info.n, !finished); Unit }
            if (finished) OutlinedButton(onClick = toggle, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("✅  Finished · tap to undo")
            } else Button(onClick = toggle, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
                Text("Mark chapter as finished · +$CHAPTER_XP XP")
            }
        }
    }

/** A line of text with a progress bar under it. */
@Composable
fun ProgressText(text: String, fraction: Float, modifier: Modifier = Modifier) = Column(modifier) {
    Text(text)
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
}

/**
 * One question at a time. Options are shuffled on every showing: generation tends to put the answer
 * at position 1-2. After answering: right/wrong colours, the explanation, and the quote it comes from.
 * XP: QUESTION_XP for the first right answer to each question (Learning.answered); best score kept per chapter.
 * ponytail: no spaced repetition yet; that comes with Room + FSRS reviews.
 */
@Composable
fun QuizScreen(bookId: String, chapter: Chapter, done: () -> Unit) = Page("Quiz · ${chapter.title}") { pad ->
    var index by rememberSaveable { mutableStateOf(0) }
    var chosen by rememberSaveable(index) { mutableStateOf<Int?>(null) }
    var score by rememberSaveable { mutableStateOf(0) }
    var earned by rememberSaveable(index) { mutableStateOf(0) }  // XP of this question
    var roundXp by rememberSaveable { mutableStateOf(0) }

    Column(Modifier.padding(pad).padding(16.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
        if (index == chapter.quiz.size) {
            Text("$score / ${chapter.quiz.size}", style = MaterialTheme.typography.displayMedium)
            Text("+$roundXp XP · best ${Learning.progress.quizBest[chapterKey(bookId, chapter.n)] ?: score}/${chapter.quiz.size}",
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                when {
                    score == chapter.quiz.size -> "All correct."
                    score * 2 >= chapter.quiz.size -> "Good: reread the ones you missed."
                    else -> "Worth rereading the chapter."
                },
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 16.dp),
            )
            Button(onClick = { index = 0; score = 0; roundXp = 0 }, modifier = Modifier.fillMaxWidth()) { Text("Retake quiz") }
            OutlinedButton(onClick = done, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Back to chapter") }
            return@Column
        }

        val q = chapter.quiz[index]
        val order = remember(q.id, index) { q.options.indices.shuffled() }
        Text("${index + 1} / ${chapter.quiz.size}", style = MaterialTheme.typography.labelLarge)
        Text(q.question, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))

        order.forEach { i ->
            val colors = when {
                chosen == null -> CardDefaults.outlinedCardColors()
                i == q.answer -> CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                i == chosen -> CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                else -> CardDefaults.outlinedCardColors()
            }
            OutlinedCard(
                onClick = {
                    if (chosen == null) {
                        chosen = i
                        if (i == q.answer) score++
                        earned = Learning.answered(q.id, i == q.answer)
                        roundXp += earned
                    }
                },
                colors = colors,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) { Text(q.options[i], style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp)) }
        }

        if (chosen != null) {
            Text(
                if (chosen != q.answer) "Wrong" else if (earned > 0) "Correct · +$earned XP" else "Correct",
                style = MaterialTheme.typography.titleMedium,
                color = if (chosen == q.answer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(q.explanation, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
            Text(
                "«${q.sourceQuote}»", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(onClick = {
                if (index + 1 == chapter.quiz.size) Learning.quizDone(bookId, chapter.n, score)
                index++
            }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text(if (index + 1 == chapter.quiz.size) "Result" else "Next")
            }
        }
    }
}
