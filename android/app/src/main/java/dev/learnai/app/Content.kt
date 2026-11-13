package dev.learnai.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors publish.py's output (FORMAT_VERSION 2: EPUB only, identifier, chapter href). */
const val FORMAT_VERSION = 2

@Serializable
data class Library(val version: Int, val generated: String, val books: List<Book>)

@Serializable
data class Book(
    val id: String,
    val title: String,
    val author: String? = null,
    val language: String? = null,
    /** The EPUB's unique identifier: matches an imported EPUB to these summaries. */
    val identifier: String? = null,
    val chapters: List<ChapterInfo>,
)

@Serializable
data class ChapterInfo(
    val n: Int,
    val title: String,
    val words: Int,
    /** Path of the chapter inside the EPUB ("OEBPS/Text/Section0005.htm"): where "Read" opens the book. */
    val href: String? = null,
    val cards: Int,
    val questions: Int,
)

@Serializable
data class Chapter(
    val n: Int,
    val title: String,
    val summary: String,
    @SerialName("key_ideas") val keyIdeas: List<String>,
    val flashcards: List<Flashcard>,
    val quiz: List<QuizItem>,
)

@Serializable
data class Flashcard(
    val id: String,
    val front: String,
    val back: String,
    @SerialName("source_quote") val sourceQuote: String,
)

@Serializable
data class QuizItem(
    val id: String,
    val question: String,
    val options: List<String>,
    val answer: Int,
    val explanation: String,
    @SerialName("source_quote") val sourceQuote: String,
)

internal val json = Json { ignoreUnknownKeys = true }

/**
 * publish.py's site/: the copy downloaded from Cloudflare (filesDir/site) if it's newer than the one bundled in the APK.
 * ponytail: "newer" = later `generated` string; an hour off around a DST change, harmless.
 */
class Content(private val context: Context) {
    private val cache = File(context.filesDir, "site")
    private fun bundled(path: String) = context.assets.open(path).bufferedReader().use { it.readText() }
    private val useCache by lazy {
        val downloaded = File(cache, "library.json").takeIf { it.exists() }?.let { json.decodeFromString<Library>(it.readText()) }
        downloaded != null && downloaded.version == FORMAT_VERSION &&
            downloaded.generated > json.decodeFromString<Library>(bundled("library.json")).generated
    }
    private fun read(path: String) = if (useCache) File(cache, path).readText() else bundled(path)

    fun library(): Library = json.decodeFromString<Library>(read("library.json")).also {
        check(it.version == FORMAT_VERSION) { "content format ${it.version}, app expects $FORMAT_VERSION: update the app" }
    }

    fun chapter(bookId: String, n: Int): Chapter = json.decodeFromString(read(chapterPath(bookId, n)))

    /**
     * Downloads library.json and every chapter into filesDir/site, all or nothing: a failed or partial download
     * leaves the previous content in place. Skipped when library.json hasn't changed.
     * ponytail: when it has, every chapter is re-downloaded (~10 KB each); fetch only changed ones if the library grows large.
     */
    suspend fun sync(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val text = cloud("library.json")
            // Same library.json as the last download (publish.py stamps `generated` on every export): nothing to fetch
            if (File(cache, "library.json").takeIf { it.exists() }?.readText() == text) return@runCatching
            val lib = json.decodeFromString<Library>(text)
            check(lib.version == FORMAT_VERSION) { "content format ${lib.version}, app expects $FORMAT_VERSION: update the app" }
            val tmp = File(context.filesDir, "site.tmp").apply { deleteRecursively(); mkdirs() }
            for (b in lib.books) for (c in b.chapters) {
                val path = chapterPath(b.id, c.n)
                val chapter = cloud(path).also { json.decodeFromString<Chapter>(it) }
                File(tmp, path).apply { parentFile!!.mkdirs(); writeText(chapter) }
            }
            File(tmp, "library.json").writeText(text)
            cache.deleteRecursively()
            check(tmp.renameTo(cache)) { "could not save the downloaded content" }
        }
    }

    private fun chapterPath(bookId: String, n: Int) = "books/$bookId/%02d.json".format(Locale.ROOT, n)
}

/**
 * Two-way sync of one JSON document with the private site, the same for every device: pull the cloud copy,
 * merge it into the local state ([mergeIn] saves and returns the merged local state), and push that back if the cloud
 * lacked anything. Phone and tablet converge; deletions travel inside the documents as timestamped tombstones.
 * Merges must give a canonical order (sorted lists, maps), or two devices would keep re-pushing their own order.
 * Blocking: call off the main thread.
 * ponytail: two devices pushing in the same second can overwrite each other; the loser's next sync sees the cloud
 * lacking its changes and pushes again.
 */
internal fun <T> syncDoc(path: String, serializer: KSerializer<T>, mergeIn: (remote: T) -> T) {
    val remote = json.decodeFromString(serializer, cloud(path))
    val merged = mergeIn(remote)
    if (merged != remote) cloud(path, "PUT", json.encodeToString(serializer, merged))
}

/** One request to the private site; functions/_middleware.js refuses it without this build's key. Blocking: call off the main thread. */
internal fun cloud(path: String, method: String = "GET", body: String? = null): String =
    cloudBytes(path, method, body?.toByteArray(), "application/json; charset=utf-8").toString(Charsets.UTF_8)

/** [cloud] for binary bodies (pictures). */
internal fun cloudBytes(path: String, method: String = "GET", body: ByteArray? = null, type: String = "application/octet-stream"): ByteArray {
    check(BuildConfig.CONTENT_KEY.isNotEmpty()) { "this build has no content key (make_key.py, then rebuild)" }
    val c = URL("${BuildConfig.CONTENT_URL}/$path").openConnection() as HttpURLConnection
    c.instanceFollowRedirects = false
    c.requestMethod = method
    c.setRequestProperty("X-Learnai-Key", BuildConfig.CONTENT_KEY)
    c.connectTimeout = 15_000
    c.readTimeout = 30_000
    try {
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", type)
            c.outputStream.use { it.write(body) }
        }
        check(c.responseCode != 401) { "the site refused this app's key (make_key.py, then rebuild)" }
        check(c.responseCode == 200) { "HTTP ${c.responseCode} for $path" }
        return c.inputStream.use { it.readBytes() }
    } finally {
        c.disconnect()
    }
}
