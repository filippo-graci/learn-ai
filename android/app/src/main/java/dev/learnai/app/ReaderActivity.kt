package dev.learnai.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.app.SearchManager
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT as WRAP
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.commitNow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import android.content.res.Configuration
import android.graphics.Color
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.UUID
import org.json.JSONObject
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Spread
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl

/**
 * Full-text reader: Readium's EPUB navigator (Fragment) in a plain activity.
 * Opens at a highlight (EXTRA_LOCATOR), a chapter (EXTRA_HREF) or the last position; saves the position as you read.
 * Select text -> Highlight / Note. Tap a highlight -> its note (edit, delete).
 */
class ReaderActivity : AppCompatActivity(), EpubNavigatorFragment.Listener {

    private lateinit var navigator: EpubNavigatorFragment
    private lateinit var highlights: Highlights
    private lateinit var container: FragmentContainerView
    /** Words of each chapter, by its start file (library books only): "time left in this chapter". */
    private var wordsByHref: Map<String, Int> = emptyMap()

    // Reading time: the gaps between page turns, when shorter than IDLE_MS, are reading. Saved on pause.
    private var lastActivity = 0L
    private var unsavedMs = 0L

    private fun activity() {
        val now = System.currentTimeMillis()
        if (lastActivity > 0 && now - lastActivity < IDLE_MS) unsavedMs += now - lastActivity
        lastActivity = now
    }

    override fun onResume() {
        super.onResume()
        lastActivity = System.currentTimeMillis()
    }

    override fun onPause() {
        if (::navigator.isInitialized) {
            activity()
            Learning.addReading(this, unsavedMs)
            unsavedMs = 0
            lastActivity = 0
        }
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val bookId = intent.getStringExtra(EXTRA_BOOK)
        val publication = ReaderSession.publication?.takeIf { bookId != null && ReaderSession.bookId == bookId }
        if (publication == null) {
            // Process was killed while reading: the publication is gone. Readium's documented recovery.
            supportFragmentManager.fragmentFactory = EpubNavigatorFragment.createDummyFactory()
            super.onCreate(savedInstanceState)
            finish()
            return
        }
        Learning.init(this)
        val positions = Positions(this)
        highlights = Highlights(this, bookId!!)
        val saved = positions.get(bookId)
        val initial = intent.getStringExtra(EXTRA_LOCATOR)?.let { Locator.fromJSON(JSONObject(it)) }
            ?: intent.getStringExtra(EXTRA_HREF)?.let { chapterStart(publication, it, saved) }
            ?: saved

        // Dark theme in dark mode. Top/bottom padding: see the container below.
        val factory = EpubNavigatorFactory(publication)
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        // IMPORTANT (Readium): the fragment factory must be set before super.onCreate.
        this.night = night
        supportFragmentManager.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initial,
            initialPreferences = preferences(),
            listener = this,
            configuration = EpubNavigatorFragment.Configuration(selectionActionModeCallback = selectionActions),
        )
        super.onCreate(savedInstanceState)

        // Top/bottom breathing room around the page. Readium's own setting (readium_navigator_epub_vertical_padding)
        // didn't show on the phone: measured 32dp/27dp with it at 64dp. So the space is added here, around the
        // navigator, on top of the system bars, in the page's own background colour.
        val extra = (PAGE_VERTICAL_PADDING_DP * resources.displayMetrics.density).toInt()
        container = FragmentContainerView(this).apply {
            id = R.id.reader_container
            setBackgroundColor(pageBackground())
            // Set directly: on the Poco (Android 12) the window consumes the insets before they reach this view,
            // so the listener below never ran and the page had no padding at all (view bounds 91..2179).
            setPadding(0, extra, 0, extra)
        }
        // Edge-to-edge windows (Android 15+) do deliver insets: then add the system bars too.
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top + extra, bars.right, bars.bottom + extra)
            insets
        }
        setContentView(container)
        if (savedInstanceState == null) {
            supportFragmentManager.commitNow {
                add(R.id.reader_container, EpubNavigatorFragment::class.java, Bundle(), TAG)
            }
        }
        navigator = supportFragmentManager.findFragmentByTag(TAG) as EpubNavigatorFragment
        navigator.addInputListener(DirectionalNavigationAdapter(navigator))  // tap the edges to turn pages
        // Tap the middle of the page (the edges are taken by the adapter above): reader options
        navigator.addInputListener(object : InputListener {
            override fun onTap(event: TapEvent): Boolean { showOptions(); return true }
        })
        navigator.addDecorationListener(GROUP, object : DecorableNavigator.Listener {
            override fun onDecorationActivated(event: DecorableNavigator.OnActivatedEvent): Boolean {
                // Called on a WebView thread: the dialog must open on the main thread.
                runOnUiThread { highlights.all().firstOrNull { it.id == event.decoration.id }?.let { editNote(it, isNew = false) } }
                return true
            }
        })
        showHighlights()

        val finish = chapterFinisher(publication, bookId)
        wordsByHref = runCatching { Content(this).library().books.firstOrNull { it.id == bookId }?.chapters }.getOrNull().orEmpty()
            .mapNotNull { c -> c.href?.substringBefore('#')?.let { it to c.words } }.toMap()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                navigator.currentLocator.collect { positions.save(bookId, it); finish(it); activity() }
            }
        }
    }

    /** Added to the selection menu (next to Copy / Share). */
    private val selectionActions = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, ACTION_HIGHLIGHT, 0, "Highlight")
            menu.add(Menu.NONE, ACTION_NOTE, 1, "Note")
            menu.add(Menu.NONE, ACTION_COPY, 2, "Copy")  // Readium's menu replaces the system one
            menu.add(Menu.NONE, ACTION_DEFINE, 3, "Define")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (item.itemId !in listOf(ACTION_HIGHLIGHT, ACTION_NOTE, ACTION_COPY, ACTION_DEFINE)) return false
            lifecycleScope.launch {
                val locator = navigator.currentSelection()?.locator ?: return@launch
                navigator.clearSelection()
                mode.finish()
                if (item.itemId == ACTION_COPY) {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("text", locator.text.highlight.orEmpty().trim()))
                    return@launch
                }
                if (item.itemId == ACTION_DEFINE) { define(locator.text.highlight.orEmpty().trim()); return@launch }
                val h = Highlight(
                    id = UUID.randomUUID().toString(),
                    href = locator.href.removeFragment().path ?: locator.href.toString(),
                    locator = locator.toJSON().toString(),
                    text = locator.text.highlight.orEmpty().trim(),
                    progression = locator.locations.progression ?: 0.0,
                    created = System.currentTimeMillis(),
                )
                if (item.itemId == ACTION_NOTE) editNote(h, isNew = true) else { highlights.save(h); showHighlights() }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {}
    }

    /**
     * Define: the dictionary / translate apps that handle selected text (Android's "process text", e.g. Google
     * Translate); none installed → a web search for the word.
     */
    private fun define(text: String) {
        if (text.isBlank()) return
        val process = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val intent = if (packageManager.queryIntentActivities(process, 0).isNotEmpty()) Intent.createChooser(process, "Define “${text.take(40)}”")
            else Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, "define $text")
        runCatching { startActivity(intent) }.onFailure { Toast.makeText(this, "No app to look it up", Toast.LENGTH_SHORT).show() }
    }

    /** Note dialog: note + colour. A new note is saved (with its highlight) only on Save; an existing one can be deleted. */
    private fun editNote(h: Highlight, isNew: Boolean) {
        val input = EditText(this).apply { setText(h.note); hint = "Your note"; minLines = 3 }
        val pad = (20 * resources.displayMetrics.density).toInt()
        var color = h.color
        val colors = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            HIGHLIGHT_COLORS.entries.forEachIndexed { i, (name, argb) ->
                addView(RadioButton(context).apply {
                    id = 200 + i
                    text = name.replaceFirstChar { it.uppercase() }
                    buttonTintList = android.content.res.ColorStateList.valueOf(argb)
                    isChecked = name == h.color
                })
            }
            setOnCheckedChangeListener { _, id -> color = HIGHLIGHT_COLORS.keys.elementAt(id - 200) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
            addView(input)
            addView(HorizontalScrollView(context).apply { addView(colors) })
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (isNew) "Note" else "Highlight")
            .setMessage("“${h.text.take(300)}”")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> highlights.save(h.copy(note = input.text.toString().trim(), color = color)); showHighlights() }
            .setNegativeButton("Cancel", null)
            .apply { if (!isNew) setNeutralButton("Delete") { _, _ -> highlights.delete(h.id); showHighlights() } }
            .create()
        // adjustNothing (manifest) stops the keyboard opening by itself: ask for it, before show()
        if (isNew) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        if (isNew) input.requestFocus()
    }

    /** In its colour; without one: yellow, or blue when it has a note. */
    private fun showHighlights() {
        val decorations = highlights.all().map {
            Decoration(
                id = it.id,
                locator = Locator.fromJSON(JSONObject(it.locator))!!,
                style = Decoration.Style.Highlight(tint = it.argb()),
            )
        }
        lifecycleScope.launch { navigator.applyDecorations(decorations, GROUP) }
    }

    /**
     * Marks a chapter finished (+XP, toast) when a page turn leaves its last file for the next one in reading order.
     * A chapter's files run from its href up to the next chapter's href. Chapters can also be ticked by hand in the app.
     * ponytail: the last chapter is assumed to be one file; a jump (TOC) into exactly the next file also counts.
     */
    private fun chapterFinisher(publication: Publication, bookId: String): (Locator) -> Unit {
        val order = publication.readingOrder.map { it.url().removeFragment().path }
        val chapters = runCatching { Content(this).library().books.firstOrNull { it.id == bookId }?.chapters }.getOrNull()
            ?: tocChapters(publication)  // an EPUB without summaries: its table of contents, as in the app's chapter list
        val starts = chapters.mapNotNull { c -> c.href?.let { order.indexOf(it.substringBefore('#')) }?.takeIf { it >= 0 }?.let { it to c } }
            .sortedBy { it.first }
        var last: Int? = null
        return { locator ->
            val i = order.indexOf(locator.href.removeFragment().path)
            val prev = last
            last = i
            val k = if (prev != null && i == prev + 1) starts.indexOfLast { it.first <= prev } else -1
            if (k >= 0 && i == (starts.getOrNull(k + 1)?.first ?: (starts[k].first + 1))) {
                val c = starts[k].second
                if (Learning.setChapter(bookId, c.n, true)) {
                    Toast.makeText(this, "✅ ${c.n}. ${c.title} finished · +$CHAPTER_XP XP", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private var night = false

    /** Saved per device: two pages suit a tablet, one a phone. */
    private val readerPrefs by lazy { getSharedPreferences("reader", Context.MODE_PRIVATE) }
    private var pages: Pages
        get() = runCatching { Pages.valueOf(readerPrefs.getString("pages", null) ?: "") }.getOrDefault(Pages.Auto)
        set(value) = readerPrefs.edit().putString("pages", value.name).apply()

    private var theme: ReaderTheme
        get() = runCatching { ReaderTheme.valueOf(readerPrefs.getString("theme", null) ?: "") }.getOrDefault(ReaderTheme.Auto)
        set(value) = readerPrefs.edit().putString("theme", value.name).apply()

    private fun readiumTheme() = when (theme) {
        ReaderTheme.Auto -> if (night) Theme.DARK else Theme.LIGHT
        ReaderTheme.Light -> Theme.LIGHT
        ReaderTheme.Sepia -> Theme.SEPIA
        ReaderTheme.Dark -> Theme.DARK
    }

    /** Readium's page colours, for the padding around the page. */
    private fun pageBackground() = when (readiumTheme()) {
        Theme.DARK -> Color.BLACK
        Theme.SEPIA -> Color.rgb(0xFA, 0xF4, 0xE8)
        else -> Color.WHITE
    }

    /** Theme; one / two pages (columnCount = reflowable EPUBs, spread = fixed-layout ones); text size. */
    private fun preferences() = EpubPreferences(
        theme = readiumTheme(),
        columnCount = pages.columns,
        spread = pages.spread,
        fontSize = fontSize,
    )

    /** 1.0 = the book's own size. Saved per device, like [pages]. */
    private var fontSize: Double
        get() = readerPrefs.getFloat("fontSize", 1f).toDouble()
        set(value) = readerPrefs.edit().putFloat("fontSize", value.toFloat()).apply()

    /** Reader options: time left, text size (A− / A+), theme, pages. Each change applies at once; the dialog stays open. */
    private fun showOptions() {
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val here = navigator.currentLocator.value
        val left = wordsByHref[here.href.removeFragment().path]
            ?.let { minutesLeft(it, here.locations.progression ?: 0.0) }
        val percent = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            text = "${(fontSize * 100).roundToInt()}%"
        }
        fun step(delta: Double) {
            fontSize = stepFontSize(fontSize, delta)
            percent.text = "${(fontSize * 100).roundToInt()}%"
            navigator.submitPreferences(preferences())
        }
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "Text size"; textSize = 16f }, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(Button(context).apply { text = "A−"; contentDescription = "Smaller text"; setOnClickListener { step(-FONT_STEP) } })
            addView(percent, LinearLayout.LayoutParams((72 * dp).toInt(), WRAP))
            addView(Button(context).apply { text = "A+"; contentDescription = "Bigger text"; setOnClickListener { step(FONT_STEP) } })
        }
        val pageChoices = RadioGroup(this).apply {
            Pages.entries.forEachIndexed { i, p ->
                addView(RadioButton(context).apply { id = 100 + i; text = p.label; isChecked = p == pages; minHeight = (48 * dp).toInt() })
            }
            setOnCheckedChangeListener { _, id ->
                pages = Pages.entries[id - 100]
                navigator.submitPreferences(preferences())
            }
        }
        val themeChoices = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            ReaderTheme.entries.forEachIndexed { i, t ->
                addView(RadioButton(context).apply { id = 300 + i; text = t.label; isChecked = t == theme; minHeight = (48 * dp).toInt() })
            }
            setOnCheckedChangeListener { _, id ->
                theme = ReaderTheme.entries[id - 300]
                container.setBackgroundColor(pageBackground())
                navigator.submitPreferences(preferences())
            }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            if (left != null) addView(TextView(context).apply {
                text = if (left == 0) "Almost at the end of this chapter" else "About $left min left in this chapter"
                setPadding(0, 0, 0, pad / 2)
            })
            addView(sizeRow)
            addView(TextView(context).apply { text = "Theme"; textSize = 16f; setPadding(0, pad, 0, 0) })
            addView(HorizontalScrollView(context).apply { addView(themeChoices) })
            addView(TextView(context).apply { text = "Pages"; textSize = 16f; setPadding(0, pad, 0, 0) })
            addView(pageChoices)
        }
        AlertDialog.Builder(this)
            .setTitle("Reader")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onExternalLinkActivated(url: AbsoluteUrl) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())))
    }

    companion object {
        private const val TAG = "EpubNavigatorFragment"
        private const val PAGE_VERTICAL_PADDING_DP = 48  // top and bottom; tune here
        private const val EXTRA_BOOK = "book"
        private const val EXTRA_HREF = "href"
        private const val EXTRA_LOCATOR = "locator"
        private const val GROUP = "highlights"
        private const val ACTION_HIGHLIGHT = 1001
        private const val ACTION_NOTE = 1002
        private const val ACTION_COPY = 1003
        private const val ACTION_DEFINE = 1004
        private const val IDLE_MS = 5 * 60_000L  // no page turn for this long: not reading (left the phone on the table)

        /** Opens at [locator] (a highlight) if given, else at [chapterHref], else where the reader left off. */
        fun intent(context: Context, bookId: String, chapterHref: String?, locator: String? = null) =
            Intent(context, ReaderActivity::class.java).putExtra(EXTRA_BOOK, bookId)
                .putExtra(EXTRA_HREF, chapterHref).putExtra(EXTRA_LOCATOR, locator)
    }
}

enum class ReaderTheme(val label: String) { Auto("System"), Light("Light"), Sepia("Sepia"), Dark("Dark") }

/** Reading speed for "time left": an average adult's silent reading of non-fiction, words per minute. */
const val READING_WPM = 230

fun minutesLeft(chapterWords: Int, progression: Double) = (chapterWords * (1 - progression.coerceIn(0.0, 1.0)) / READING_WPM).roundToInt()

/** Text size: steps of 10 %, from 60 % to 250 % of the book's own size (Readium only needs ≥ 0). */
const val FONT_STEP = 0.1
const val FONT_MIN = 0.6
const val FONT_MAX = 2.5

fun stepFontSize(current: Double, delta: Double) = (((current + delta) * 10).roundToInt() / 10.0).coerceIn(FONT_MIN, FONT_MAX)

/**
 * How many pages the reader shows side by side. Automatic = Readium's choice: two when the screen is wide.
 * spread null for Automatic: EpubPreferences only accepts null / NEVER / ALWAYS (Spread.AUTO crashed the reader).
 */
enum class Pages(val label: String, val columns: ColumnCount, val spread: Spread?) {
    Auto("Automatic (two pages on a wide screen, e.g. a tablet in landscape)", ColumnCount.AUTO, null),
    One("One page", ColumnCount.ONE, Spread.NEVER),
    Two("Two pages, like a book", ColumnCount.TWO, Spread.ALWAYS),
}

/**
 * Where "Read full chapter" opens: the saved position if it's already inside this chapter (continue),
 * else the chapter's start. href = path inside the EPUB from publish.py ("OEBPS/Text/Section0005.htm").
 */
fun chapterStart(publication: Publication, href: String, saved: Locator?): Locator? {
    val path = href.substringBefore('#')
    if (saved != null && saved.href.removeFragment().path == path) return saved
    fun List<Link>.flat(): List<Link> = flatMap { listOf(it) + it.children.flat() }
    val matches = { link: Link -> link.url().removeFragment().path == path }
    val link = publication.tableOfContents.flat().firstOrNull(matches) ?: publication.readingOrder.firstOrNull(matches)
    return link?.let { publication.locatorFromLink(it) }
}
