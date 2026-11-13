package dev.learnai.app

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.Normalizer

/** Where a search hit leads. */
sealed interface Hit {
    val kind: String; val title: String; val text: String
    data class Book(val bookId: String, override val title: String, override val text: String) : Hit { override val kind = "📚 Book" }
    data class Chapter(val bookId: String, val n: Int, override val title: String, override val text: String) : Hit { override val kind = "📄 Chapter" }
    data class Quote(val bookId: String, val locator: String, override val title: String, override val text: String) : Hit { override val kind = "🖍 Highlight" }
    data class BookReview(val bookId: String, override val title: String, override val text: String) : Hit { override val kind = "✍️ Book review" }
    data class Movie(val titleId: String, override val title: String, override val text: String) : Hit { override val kind = "🎬 Movies & TV" }
}

/** Lowercase, accents removed: "società" matches "societa". */
fun fold(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

/** The part of [text] around the first match, ~[width] chars. */
fun snippet(text: String, query: String, width: Int = 140): String {
    val i = fold(text).indexOf(fold(query)).coerceAtLeast(0)
    val start = (i - width / 3).coerceAtLeast(0)
    return (if (start > 0) "…" else "") + text.substring(start, minOf(text.length, start + width)).replace('\n', ' ') +
        if (start + width < text.length) "…" else ""
}

/**
 * Everything searchable, as (hit, text to match). Plain substring search: the library is small enough to scan.
 * ponytail: the bge-m3 embeddings PLAN.md mentions would add meaning-based search; substring first.
 */
fun searchIndex(context: Context, library: Library, books: List<Book>): List<Pair<Hit, String>> {
    val content = Content(context)
    val out = mutableListOf<Pair<Hit, String>>()
    for (b in books) {
        out += Hit.Book(b.id, b.title, b.author.orEmpty()) to "${b.title} ${b.author.orEmpty()}"
        val summarized = library.books.any { it.id == b.id }
        for (c in b.chapters) {
            val ch = if (summarized) runCatching { content.chapter(b.id, c.n) }.getOrNull() else null
            val body = listOfNotNull(c.title, ch?.summary, ch?.keyIdeas?.joinToString(" "),
                ch?.flashcards?.joinToString(" ") { "${it.front} ${it.back}" }).joinToString("\n")
            out += Hit.Chapter(b.id, c.n, "${b.title} · ${c.n}. ${c.title}", body) to body
        }
        for (h in Highlights(context, b.id).all()) {
            val t = listOf(h.text, h.note).filter { it.isNotBlank() }.joinToString("\n📝 ")
            out += Hit.Quote(b.id, h.locator, b.title, t) to t
        }
        Movies.data.books[b.id]?.takeIf { it.reviewed() }?.let { r ->
            val t = (listOf(r.opinion) + r.aspects.values.map { it.text }).filter { it.isNotBlank() }.joinToString("\n")
            out += Hit.BookReview(b.id, b.title, t) to t
        }
    }
    for (t in Movies.data.titles) {
        val body = (listOf(t.name, t.opinion) + t.tags + t.aspects.values.map { it.text }).filter { it.isNotBlank() }.joinToString("\n")
        out += Hit.Movie(t.id, t.name, body) to body
    }
    return out
}

fun search(index: List<Pair<Hit, String>>, query: String): List<Hit> {
    val q = fold(query.trim())
    if (q.length < 2) return emptyList()
    return index.filter { (hit, text) -> fold(hit.title).contains(q) || fold(text).contains(q) }.map { it.first }
}

/** Library → Search: books, chapters (title, summary, key ideas, cards), highlights and notes, reviews. */
@Composable
fun SearchScreen(library: Library, books: List<Book>, open: (Hit) -> Unit) = Page("Search") { pad ->
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    val index = remember(library, books, Movies.data) { searchIndex(context, library, books) }
    val hits = remember(query, index) { search(index, query) }
    LazyColumn(contentPadding = pad) {
        item {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search books, notes, reviews…") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp))
        }
        if (query.trim().length >= 2 && hits.isEmpty()) item {
            Text("Nothing found.", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        items(hits) { h ->
            ListItem(
                overlineContent = { Text(h.kind) },
                headlineContent = { Text(h.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                supportingContent = if (h.text.isBlank()) null else ({ Text(snippet(h.text, query), maxLines = 3) }),
                modifier = Modifier.clickable { open(h) },
            )
            HorizontalDivider()
        }
    }
}
