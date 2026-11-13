package dev.learnai.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.toUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/** An EPUB the user imported on this phone. The book file never leaves the phone. */
@Serializable
data class LocalBook(val id: String, val identifier: String?, val title: String, val author: String?, val file: String)

/** Imported EPUBs: files in filesDir/books/, list in filesDir/books/index.json. */
class LocalBooks(private val context: Context) {
    private val dir = File(context.filesDir, "books").apply { mkdirs() }
    private val index = File(dir, "index.json")

    fun all(): List<LocalBook> = if (index.exists()) json.decodeFromString(index.readText()) else emptyList()
    fun forBook(bookId: String): LocalBook? = all().firstOrNull { it.id == bookId }
    fun file(book: LocalBook) = File(dir, book.file)

    /**
     * Copies the picked EPUB into app storage and reads its metadata with Readium. A book whose unique
     * identifier matches one in library.json gets that book's id, i.e. its summaries and quizzes.
     */
    suspend fun import(uri: Uri, library: Library): Result<LocalBook> = withContext(Dispatchers.IO) {
        runCatching {
            val tmp = File(context.cacheDir, "import.epub")
            checkNotNull(context.contentResolver.openInputStream(uri)) { "cannot read the picked file" }
                .use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val publication = Readium.open(context, tmp).getOrThrow()
            val meta = publication.metadata
            val identifier = meta.identifier
            val title = meta.title ?: "Untitled"
            val author = meta.authors.firstOrNull()?.name
            publication.close()
            val id = library.books.firstOrNull { it.identifier != null && it.identifier == identifier }?.id
                ?: ("local-" + sha1(identifier ?: title))
            val book = LocalBook(id, identifier, title, author, "$id.epub")
            tmp.copyTo(File(dir, book.file), overwrite = true)
            tmp.delete()
            tocFile(id).delete()  // a re-import may be a different edition
            coverFile(id).delete()
            File(dir, "$id.cover.none").delete()
            index.writeText(json.encodeToString(all().filter { it.id != id } + book))
            book
        }
    }

    private fun tocFile(id: String) = File(dir, "$id.toc.json")
    fun coverFile(id: String) = File(dir, "$id.cover.jpg")

    /** The EPUB's cover at list size (≤ 300×450), extracted once with Readium; null if the book has none. */
    suspend fun cover(book: LocalBook): File? = withContext(Dispatchers.IO) {
        val f = coverFile(book.id)
        val none = File(dir, "${book.id}.cover.none")
        if (f.exists()) return@withContext f
        if (none.exists()) return@withContext null
        runCatching {
            val publication = Readium.open(context, file(book)).getOrThrow()
            try {
                val bitmap = publication.coverFitting(android.util.Size(300, 450))
                if (bitmap == null) none.createNewFile()
                else f.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, it) }
            } finally { publication.close() }
        }
        f.takeIf { it.exists() }
    }

    /** The book's chapters from its table of contents; read with Readium once, then cached next to the EPUB. */
    suspend fun chapters(book: LocalBook): Result<List<ChapterInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            val cache = tocFile(book.id)
            if (cache.exists()) return@runCatching json.decodeFromString<List<ChapterInfo>>(cache.readText())
            val publication = Readium.open(context, file(book)).getOrThrow()
            try { tocChapters(publication).also { cache.writeText(json.encodeToString(it)) } } finally { publication.close() }
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(12)
}

/**
 * Chapters of an EPUB without summaries, from its table of contents: the shallowest level with ≥ 3 entries
 * (like split.py), numbered from 1. The reader numbers them the same way, so chapterKey()s match.
 * ponytail: href = the entry's file without its #fragment: sections sharing a file open at the file's start.
 */
fun tocChapters(publication: Publication): List<ChapterInfo> {
    var level = publication.tableOfContents
    while (level.size < 3 && level.any { it.children.isNotEmpty() }) level = level.flatMap { it.children.ifEmpty { listOf(it) } }
    return level.mapIndexed { i, link ->
        ChapterInfo(i + 1, link.title?.trim()?.ifEmpty { null } ?: "Chapter ${i + 1}", words = 0,
            href = link.url().removeFragment().path, cards = 0, questions = 0)
    }
}

/** Readium components, built once. pdfFactory = null: the app reads EPUB only. */
object Readium {
    private var components: Pair<AssetRetriever, PublicationOpener>? = null

    private fun components(context: Context) = components ?: run {
        val http = DefaultHttpClient()
        val retriever = AssetRetriever(context.contentResolver, http)
        val opener = PublicationOpener(DefaultPublicationParser(context, http, retriever, pdfFactory = null))
        (retriever to opener).also { components = it }
    }

    suspend fun open(context: Context, file: File): Result<Publication> = withContext(Dispatchers.IO) {
        val (retriever, opener) = components(context.applicationContext)
        val asset = retriever.retrieve(file.toUrl(isDirectory = false))
            .getOrElse { return@withContext Result.failure(Exception(it.message)) }
        opener.open(asset, allowUserInteraction = false)
            .getOrElse { asset.close(); return@withContext Result.failure(Exception(it.message)) }
            .let { Result.success(it) }
    }
}

/**
 * The publication the reader shows. The navigator Fragment needs it synchronously in
 * ReaderActivity.onCreate, so it's opened here (suspend) before the activity starts.
 */
object ReaderSession {
    var bookId: String? = null
        private set
    var publication: Publication? = null
        private set

    suspend fun open(context: Context, book: LocalBook): Result<Publication> {
        publication?.takeIf { bookId == book.id }?.let { return Result.success(it) }
        publication?.close()
        publication = null
        return Readium.open(context, LocalBooks(context).file(book)).onSuccess {
            bookId = book.id
            publication = it
        }
    }
}

/** Last reading position per book (a Readium Locator as JSON). */
class Positions(context: Context) {
    private val prefs = context.getSharedPreferences("positions", Context.MODE_PRIVATE)
    fun get(bookId: String): Locator? = prefs.getString(bookId, null)?.let { Locator.fromJSON(JSONObject(it)) }
    fun save(bookId: String, locator: Locator) = prefs.edit().putString(bookId, locator.toJSON().toString()).apply()
}

/**
 * A highlight, with an optional note. href = the chapter file inside the EPUB (no fragment): matches the
 * chapter list's href. locator = Readium Locator JSON, the exact place to draw it and to jump back to.
 */
@Serializable
data class Highlight(
    val id: String, val href: String, val locator: String, val text: String,
    val progression: Double, val note: String = "", val created: Long,
    val updated: Long = 0,           // last edit (0 = never edited: created counts)
    val deleted: Boolean = false,    // tombstone: the deletion reaches the other devices (text and note emptied)
    val color: String = "",          // HIGHLIGHT_COLORS key; "" = yellow, or blue when it has a note (the old look)
    val favorite: Boolean = false,   // a favourite quote: shown with the book's review
)

/** Highlight colours (Kindle's four), as ARGB. */
val HIGHLIGHT_COLORS = linkedMapOf(
    "yellow" to 0xFFFFD54F.toInt(), "green" to 0xFFAED581.toInt(), "blue" to 0xFF64B5F6.toInt(), "pink" to 0xFFF48FB1.toInt(),
)

fun Highlight.argb(): Int = HIGHLIGHT_COLORS[color] ?: HIGHLIGHT_COLORS[if (note.isBlank()) "yellow" else "blue"]!!

private fun Highlight.changedAt() = maxOf(updated, created)

/** Two devices' copies of a book's highlights → one: per highlight the latest edit wins, a deletion wins a tie. */
fun mergeHighlights(a: List<Highlight>, b: List<Highlight>): List<Highlight> =
    (a + b).groupBy { it.id }.map { (_, v) -> v.maxWith(compareBy({ it.changedAt() }, { it.deleted })) }
        .sortedWith(compareBy({ it.created }, { it.id }))

/**
 * Highlights of one book: filesDir/highlights/<bookId>.json, copied to Cloudflare after every change (NotesSync).
 * ponytail: a JSON list, Room if it ever needs queries.
 */
class Highlights(context: Context, private val bookId: String) {
    private val app = context.applicationContext
    private val file = NotesSync.file(app, bookId)

    fun all(): List<Highlight> = NotesSync.read(file).filter { !it.deleted }
    fun save(h: Highlight) = change { list -> list.filter { it.id != h.id } + h.copy(updated = System.currentTimeMillis()) }
    fun delete(id: String) = change { list ->
        list.map { if (it.id == id) it.copy(deleted = true, text = "", note = "", updated = System.currentTimeMillis()) else it }
    }

    private fun change(f: (List<Highlight>) -> List<Highlight>) {
        synchronized(NotesSync) { NotesSync.write(file, f(NotesSync.read(file))) }
        NotesSync.push(app, bookId)
    }
}

/**
 * Every book's highlights & notes, synced with the private site (functions/notes/[book].js, KV) by syncDoc after each
 * change and on Library visits: per-highlight merge ([mergeHighlights]), deletions as tombstones, so phone and
 * tablet share them and a reinstall gets them back.
 */
object NotesSync {
    private val io = Dispatchers.IO.limitedParallelism(1)  // one book at a time: each upload reads the latest list
    private val scope = CoroutineScope(SupervisorJob() + io)

    fun file(context: Context, bookId: String) = File(context.filesDir, "highlights/$bookId.json")
    fun read(file: File): List<Highlight> = synchronized(this) {
        if (file.exists()) json.decodeFromString(file.readText()) else emptyList()
    }
    fun write(file: File, list: List<Highlight>) = synchronized(this) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(list))
    }

    /** After a local change: sync the book in the background (offline: the next Library visit does it). */
    fun push(context: Context, bookId: String) {
        scope.launch { runCatching { syncBook(context, bookId) } }
    }

    /** Uploads pending changes and restores the cloud copy of the given books (the imported ones). */
    suspend fun sync(context: Context, bookIds: List<String>): Result<Unit> = withContext(io) {
        runCatching { bookIds.forEach { syncBook(context.applicationContext, it) } }
    }

    private fun syncBook(context: Context, bookId: String) {
        val list = file(context, bookId)
        syncDoc("notes/$bookId", ListSerializer(Highlight.serializer())) { remote ->
            synchronized(this) { mergeHighlights(read(list), remote).also { if (it != read(list)) write(list, it) } }
        }
    }
}
