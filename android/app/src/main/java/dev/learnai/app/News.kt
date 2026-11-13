package dev.learnai.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** ai_news_pipeline's site (core/delivery.py deploys it to Cloudflare Pages every morning). Public. */
const val NEWS_URL = "https://<your-morning-brief>.pages.dev/"

/**
 * News v1: the morning-brief page itself, in a WebView. JavaScript + DOM storage on, because the page's
 * like/dislike votes keep their key in localStorage. Pages of the digest stay in the app; links to the
 * source articles open in the browser. A ✓ button injected next to each article's votes marks it read (+NEWS_XP XP);
 * read state lives in the app (Learning), so the counters work even though the page belongs to ai_news_pipeline.
 * ponytail: a native news list needs a digest.json from ai_news_pipeline (PLAN.md phase 4b, "News v2").
 */
@SuppressLint("SetJavaScriptEnabled")
fun newsWebView(context: Context): WebView = WebView(context).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    addJavascriptInterface(NewsBridge(context.applicationContext), "LearnAI")  // only this page runs here: other hosts open in the browser
    // The page asks for the vote key with prompt() and reports errors with alert(): without a WebChromeClient a WebView
    // drops both, so prompt() returned null and every 👍/👎 was silently ignored.
    webChromeClient = WebChromeClient()
    webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String) = view.evaluateJavascript(READ_BUTTONS, null)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            if (request.url.host == android.net.Uri.parse(NEWS_URL).host) return false
            context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
    }
    loadUrl(NEWS_URL)
}

/** The page's side of the ✓ / 🔖 buttons and Essentials. Called on a WebView thread: state changes go to the main thread. */
private class NewsBridge(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private fun ok(url: String) = url.length <= 2000 && url.startsWith("http")
    @JavascriptInterface fun readUrls(): String = json.encodeToString(Learning.progress.news.keys.toList())
    @JavascriptInterface fun savedUrls(): String = json.encodeToString(Learning.progress.saved.keys.toList())
    /** topic = "section|lean" of the article on the digest page (stats: what you read). */
    @JavascriptInterface fun setRead(url: String, read: Boolean, topic: String) {
        if (ok(url)) main.post { Learning.setNews(url, read, topic.take(100)) }
    }
    @JavascriptInterface fun setSaved(url: String, title: String, saved: Boolean) {
        if (ok(url)) main.post { Learning.setSaved(url, title, saved) }
    }
    /** 👍 / 👎 also recorded here (with the article's section), so Essentials and Stats can use them. */
    @JavascriptInterface fun setVote(url: String, v: Int, topic: String) {
        if (ok(url) && (v == 1 || v == -1)) NewsVotes.set(context, url, v, topic.take(100))
    }
    /** items = [{url, section, sources}] in page order → the Essentials' URLs, best first. */
    @JavascriptInterface fun essentials(items: String): String = runCatching {
        val all = json.decodeFromString<List<DigestItem>>(items)
        val votes = NewsVotes.all(context)
        val p = Learning.progress
        val since = System.currentTimeMillis() - INTEREST_DAYS * 86_400_000L
        val reads = p.news.filterValues { it >= since }.keys.mapNotNull { p.newsTopic[it]?.substringBefore('|') }
        val disliked = votes.filterValues { it.v < 0 }.keys
        json.encodeToString(essentials(all, sectionWeights(reads, votes.values.map { it.v to it.section }), disliked).map { it.url })
    }.getOrDefault("[]")
}

/** How far back your reads count as interests. */
const val INTEREST_DAYS = 60
const val ESSENTIALS = 10
/** A 👍 / 👎 counts like this many reads of its section. */
const val VOTE_WEIGHT = 3.0

@Serializable
data class DigestItem(val url: String, val section: String, val sources: Int = 1)

/**
 * Section → interest weight from what you read and voted: 1 (no signal) up to 5 (all your reads), a disliked section
 * down to 0.25.
 */
fun sectionWeights(readSections: List<String>, votes: List<Pair<Int, String>>): Map<String, Double> {
    val points = mutableMapOf<String, Double>()
    readSections.forEach { points.merge(it, 1.0, Double::plus) }
    votes.forEach { (v, section) -> points.merge(section, VOTE_WEIGHT * v, Double::plus) }
    val total = maxOf(points.values.filter { it > 0 }.sum(), 1.0)
    return points.mapValues { (_, pts) -> (1 + 4 * pts / total).coerceAtLeast(0.25) }
}

/**
 * Today's most important articles for you: outlets covering the story (importance) × your interest in its section;
 * ties keep the digest's order. Disliked articles are left out; at most [perSection] per section while others are left.
 * ponytail: a hand-made score; ai_news_pipeline's vote model (core/interest.py) could rank them once it has enough votes.
 */
fun essentials(items: List<DigestItem>, weights: Map<String, Double>, disliked: Set<String>, n: Int = ESSENTIALS, perSection: Int = 4):
    List<DigestItem> {
    val ranked = items.filter { it.url !in disliked }.withIndex()
        .sortedWith(compareByDescending<IndexedValue<DigestItem>> { (weights[it.value.section] ?: 1.0) * it.value.sources }.thenBy { it.index })
        .map { it.value }
    val count = mutableMapOf<String, Int>()
    val picked = ranked.filter { count.merge(it.section, 1, Int::plus)!! <= perSection }.take(n)
    return (picked + (ranked - picked.toSet())).take(n)
}

@Serializable
data class NewsVote(val v: Int, val section: String, val at: Long)

/** 👍 / 👎 cast in the app, this device only. ponytail: not synced; the site keeps the real votes (functions/api/vote.js). */
object NewsVotes {
    private fun prefs(context: Context) = context.getSharedPreferences("news-votes", Context.MODE_PRIVATE)
    fun set(context: Context, url: String, v: Int, topic: String) =
        prefs(context).edit().putString(url, json.encodeToString(NewsVote(v, topic.substringBefore('|'), System.currentTimeMillis()))).apply()
    fun all(context: Context): Map<String, NewsVote> = prefs(context).all.mapNotNull { (url, s) ->
        runCatching { url to json.decodeFromString<NewsVote>(s as String) }.getOrNull()
    }.toMap()
}

/**
 * Adds ✓ (read) and 🔖 (save for later) to each article's vote row (li[data-urls] > .vote, keyed on its first source
 * URL like the votes). stopPropagation: the page's own click handler on document treats any `.vote button` as a vote.
 * Records 👍 / 👎 in the app too, then moves the top ESSENTIALS articles (ranked by the app) into a ⭐ Essentials
 * section at the top. Moved, not copied: the page's vote handler and these buttons keep working on them.
 */
private const val READ_BUTTONS = """
(function () {
  if (!window.LearnAI || document.getElementById('learnai-read')) return;
  var css = document.createElement('style');
  css.id = 'learnai-read';
  css.textContent = '.vote button.read-btn,.vote button.save-btn{color:var(--text)}' +
    '.vote button.done{opacity:1;border-color:var(--accent);background:var(--accent);color:var(--bg)}' +
    'li.is-read > details > summary{opacity:.5}' +
    '.ess-from{font-size:.7rem;color:var(--text-dim);margin-bottom:.15rem}';
  document.head.appendChild(css);
  var read = new Set(JSON.parse(LearnAI.readUrls()));
  var saved = new Set(JSON.parse(LearnAI.savedUrls()));
  var items = [], byUrl = {};
  document.querySelectorAll('li[data-urls]').forEach(function (li) {
    var url = JSON.parse(li.dataset.urls)[0];
    if (!url) return;
    var section = li.closest('section.category');
    var h2 = section && section.querySelector('h2');
    // An Essentials bullet built by the pipeline carries its real section in data-section.
    var secName = li.dataset.section || (h2 ? h2.textContent.trim() : '');
    var lean = li.querySelector('summary .lean');
    var topic = secName + '|' + (lean ? lean.textContent.trim() : '');
    var summary = li.querySelector('summary');
    var title = summary ? summary.textContent.trim().slice(0, 300) : url;
    var sources = 0;
    li.querySelectorAll('.source-link').forEach(function (a) { sources += a.textContent.replace('↗', '').split(',').length; });
    if (!byUrl[url]) { byUrl[url] = li; items.push({url: url, section: secName, sources: Math.max(1, sources)}); }
    li.querySelectorAll('.vote button[data-v]').forEach(function (b) {
      b.addEventListener('click', function () { LearnAI.setVote(url, Number(b.dataset.v), topic); });
    });
    function button(cls, label, aria, set, onChange) {
      var b = document.createElement('button');
      b.className = cls;
      b.textContent = label;
      b.setAttribute('aria-label', aria);
      function paint() {
        var on = set.has(url);
        b.classList.toggle('done', on);
        b.setAttribute('aria-pressed', on);
        if (cls === 'read-btn') li.classList.toggle('is-read', on);
      }
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        var on = !set.has(url);
        if (on) set.add(url); else set.delete(url);
        onChange(on);
        paint();
      });
      paint();
      return b;
    }
    var r = button('read-btn', '✓', 'Letto', read, function (on) { LearnAI.setRead(url, on, topic); });
    var s = button('save-btn', '🔖', 'Salva per dopo', saved, function (on) { LearnAI.setSaved(url, title, on); });
    var row = li.querySelector('.vote');
    if (row) { row.insertBefore(s, row.firstChild); row.insertBefore(r, row.firstChild); } else { li.appendChild(r); li.appendChild(s); }
  });
  var main = document.querySelector('main');
  // Pages from 13 Nov 2026 have Essentials built by ai_news_pipeline (core/delivery.py); older archive pages get the app's.
  if (document.getElementById('essentials')) return;
  var top = JSON.parse(LearnAI.essentials(JSON.stringify(items)));
  if (!main || !top.length) return;
  var ess = document.createElement('section');
  ess.className = 'category';
  ess.id = 'learnai-essentials';
  var h = document.createElement('h2');
  h.textContent = '⭐ Essentials';
  var ul = document.createElement('ul');
  ess.appendChild(h); ess.appendChild(ul);
  top.forEach(function (url) {
    var li = byUrl[url], it = items.filter(function (x) { return x.url === url; })[0];
    if (!li) return;
    var from = document.createElement('div');
    from.className = 'ess-from';
    from.textContent = it.section + (it.sources > 1 ? ' · ' + it.sources + ' fonti' : '');
    li.insertBefore(from, li.firstChild);
    ul.appendChild(li);
  });
  main.insertBefore(ess, main.firstChild);
  document.querySelectorAll('section.category').forEach(function (sec) {
    if (sec !== ess && !sec.querySelector('li[data-urls]')) sec.style.display = 'none';
  });
})();
"""

/** Counters, Saved (read later) list, and the digest page. The page reloads after the Saved list so its 🔖 / ✓ are current. */
@Composable
fun NewsScreen(webView: WebView) {
    val context = LocalContext.current
    var showSaved by remember { mutableStateOf(false) }
    BackHandler(enabled = showSaved || webView.canGoBack()) { if (showSaved) { showSaved = false; webView.reload() } else webView.goBack() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        val today = LocalDate.now()
        val s = learningStats(Learning.progress, today)
        val monthStart = today.withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val month = Learning.progress.news.values.count { it >= monthStart }
        val saved = Learning.progress.saved
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
            Text("📰 ${s.newsToday} today · ${s.newsWeek} week · $month month · +$NEWS_XP XP",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (showSaved) webView.reload(); showSaved = !showSaved }) {
                Text(if (showSaved) "Back to news" else "🔖 Saved (${saved.size})")
            }
        }
        if (!showSaved) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        val items = saved.entries.sortedByDescending { it.value.at }
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            if (items.isEmpty()) item {
                Text("Tap 🔖 on an article to read it later.", modifier = Modifier.padding(16.dp))
            }
            items(items, key = { it.key }) { (url, a) ->
                val read = url in Learning.progress.news
                ListItem(
                    headlineContent = { Text(a.title, maxLines = 4, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(android.net.Uri.parse(url).host.orEmpty()) },
                    trailingContent = {
                        Row {
                            TextButton(onClick = { Learning.setNews(url, !read) }) { Text(if (read) "✓ read" else "✓") }
                            TextButton(onClick = { Learning.setSaved(url, a.title, false) }) { Text("✕") }
                        }
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                )
                HorizontalDivider()
            }
        }
    }
}
