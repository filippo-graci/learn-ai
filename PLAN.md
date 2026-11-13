# learn_ai — read books, remember them

Local pipeline: PDF/EPUB → Markdown → chapters → LLM (summary + flashcards + quiz) → spaced-repetition review app.
Everything runs locally on an RTX 5060 Laptop (8 GB VRAM). Personal use, own or public-domain books only.

## Layout

```
inbox/                drop .pdf / .epub here
books/<stem>/         one folder per book
  <stem>.md           PDF: marker output (paginated: {N}----- page markers); EPUB: pandoc gfm
  <stem>_meta.json    marker metadata (TOC, page stats), PDF only
  check.json          conversion check report
  chapters.json       chapter list: title, words, token estimate, pages, skipped segments + why (phase 2)
  chapters/NN.md      chapter text                      (phase 2)
  chapters/NN.json    summary + flashcards + quiz        (phase 3)
site/                 publish.py output: library.json + books/<id>/NN.json (phase 4a)
convert.py            phase 1 — standalone
split.py              phase 2
generate.py           phase 3
publish.py            phase 4a — export + deploy to Cloudflare Pages (private)
android/              phase 4b — native Kotlin app
```

## Stack (decided)

| Job | Tool | Why |
|---|---|---|
| PDF → md | marker-pdf 2.0 (`~/Workspace/papers/.venv-marker`) | best local accuracy, GPU |
| EPUB → md | pandoc 3.7 (`-t gfm`) | exact text, seconds, no GPU — prefer EPUB when both exist |
| MOBI/AZW3 | calibre `ebook-convert` → EPUB (not installed; DRM-free files only) | pandoc can't read them |
| Check | source text (PDF text layer / EPUB spine HTML) vs md, word coverage | deterministic, no LLM grading an LLM |
| LLM | Ollama `qwen3.5:9b-q4_K_M`, num_ctx ≈ 12k | best fit for 8 GB; model name is one setting |
| Structured output | `instructor` + Pydantic, `max_retries=3` | schema-valid JSON or a logged failure |
| Embeddings | Ollama `bge-m3` + numpy cosine | dedupe cards, grade typed answers, cross-chapter context |
| Scheduling | FSRS-6, `open-spaced-repetition/fsrs-kotlin` | due-review queue = the periodic test |
| Storage | Room (SQLite) on the phone; content JSON on Cloudflare Pages behind Access | one device, offline-first |
| UI | native Android, Kotlin + Jetpack Compose | your choice over a wrapped web app |

Machine rules (8 GB GPU, 22 GB RAM, shared with ai_news_pipeline):
- One heavy job at a time: marker, generate.py, an Android build never overlap (2026-11-07: a first Gradle build next to the 12k-context generation, with swap already full, froze the laptop).
- Generation runs overnight. **06:30-08:30 belongs to the news pipeline** (07:15, may run 60 min): `quiet.py` makes generate.py/convert.py refuse to start work in that window, and `stop_llm.py` (systemd user timer `learn-ai-stop.timer`, 06:30 daily; unit files in `systemd/`) stops this project's scripts, marker + surya servers + vLLM container, and unloads every Ollama model. Finished chapters/books are saved; rerun after 08:30 to continue.

## Phases and gates

Do not start a phase until the previous gate has been checked on real books.

### Phase 1 — convert.py (standalone)
- `python convert.py [book.pdf|book.epub ...]` (default: every book in `inbox/` not yet converted; `--force` to redo, `--check-only`).
- PDF: `marker_single --paginate_output` → `books/<stem>/`; afterwards kills leftover surya servers / vLLM containers.
- EPUB: `pandoc -t gfm` → `books/<stem>/`.
- Check: source words vs markdown words (NFKC, de-hyphenated, letters ≥3). `coverage` = share of source words found in the md.
  - PDF: per page (page markers). Page with no text layer → scanned; can't be verified, only counted as `ocr`.
  - EPUB: per spine file, each against the whole md (pandoc keeps no file boundaries).
  - FAIL if document coverage < 0.95, any page/file < 0.5 coverage, or (PDF) > 10 % blank pages.
- Writes `check.json`, prints PASS/FAIL per book and the weakest pages.
- **Gate:** run on 2–3 real PDFs (one with a TOC, one scanned/no TOC) and 1 real EPUB. Overall coverage ≥ 0.95, no empty pages, md opens cleanly. Open the low pages by hand and decide if thresholds are right.
  - 2026-11-07: thesis PDF PASS 0.97 (p.97 low = figure text); scanned van der Klis PASS, 44 OCR pages spot-checked by hand, good. Real EPUB (Han, *La società della stanchezza*, Calibre/Sigil-made, Italian): PASS 1.0 in 0.3 s; needed `gfm-raw_html` + SVG strip to remove 318 `<span>`s; toc.ncx = 1 file per chapter. Front-matter blurb comes out as a giant `#` heading → phase 2 must split on the TOC, not on `#`.

### Phase 2 — chapter split (split.py)
- Boundaries, best first: book TOC (EPUB toc.ncx / EPUB 3 nav, PDF bookmarks; shallowest level with ≥ 3 entries), located on a matching heading (PDF: within 2 pages of the bookmark, else the page start) → numbered top-level headings ("1. …", "Chapter 3") → ~3000-word chunks.
  - Not `#` levels: marker's heading levels are inconsistent (`# 2.1` but `## 2.1.3`), and EPUB blurbs come out as `#`.
- Also always a boundary: an Introduction/Preface heading before the first chapter (thesis TOC omits it), References/Bibliography/Index headings anywhere.
- Skipped by title: front matter, contents, notes, bibliography, index, acknowledgements (EN/IT). Segments < 150 words are merged into the next chapter (title pages), or the previous one at the end.
- Chapter text: page markers and anchor spans removed, whitespace-only lines collapsed. Every skip/merge is listed in `chapters.json` with its reason.
- Long chapters (> 6k tokens est.) are flagged `long`; cutting them into sections is phase 3's job (it depends on the model's context).
- **Gate:** chapter list for each test book looks like the real table of contents.
  - 2026-11-07: Han 8 chapters = its toc.ncx (Cover dropped, title page merged into the Premessa, Note skipped). Thesis 5 = Introduction + 4 bookmarked chapters; empty draft "Results" merged back, Bibliography skipped; ch. 1 is short because the draft is still an outline there. van der Klis (no TOC) 7 = its numbered sections, repository cover page + References skipped. 3 thesis chapters LONG (5k–14k words).

### Phase 3 — LLM generation (generate.py, `.venv/bin/python`)
- Target books: discursive/essay books (Han-like EPUBs) first; physics/LaTeX papers are secondary.
- Schema (pydantic, every string with a min length): `summary`, `key_ideas[]`, `flashcards[{front, back, source_quote}]`, `quiz[{question, options[4], answer, explanation, source_quote}]`.
- Model call: Ollama `qwen3.5:9b-q4_K_M`, **thinking on**, `format` = JSON schema, `num_ctx` 16384 (12k ran out mid-answer on Han ch. 2; running out = retry asking for briefer reasoning, not halving), temperature 0.2. ~3 min/call, overnight.
  - Why thinking on: Ollama 0.30.5 ignores `format` when `think=false` (also on `/v1`), and the no-thinking runs broke JSON (2/8 chapters), answered in English for an Italian book (3/8), dropped accents (4 attempts / 2 chapters) and invented an attribution (Agamben in ch. 1).
  - Ollama adds ~3.3k tokens to each prompt (schema); a retry is the same prompt + a short note, never the previous answer.
- Checks after each answer — hard (retry ×3, then failures.jsonl): output language = chapter language (≥ 40 % of output words occur in the chapter; same language measured 0.69–0.82, English 0.02–0.12); no names the chapter never contains; accents not lost. Soft (1 retry, then accepted): correct option > 15 % longer than every wrong one.
- Item filters (drop to `dropped` with a reason, no LLM): `source_quote` found in the chapter (≥ 80 % of its prose words in order, ±30 words slack for footnotes/elisions, math ignored); **attribution**: a person named on a card must be named within 60 words of its quote (like ai_news_pipeline's `repair_citations`); names that are the chapter's subject (≥ 1 per 150 words) exempt; wrong quiz options exempt (misattribution is a legit distractor). `--refilter` re-runs these on existing outputs.
- Long chapters: sections ≤ 12k chars at headings/paragraphs (chars, not words: LaTeX is ~2.7 chars/token); a section whose prompt is still too long is halved and retried; section summaries merged by one more call.
- Cached as `chapters/NN.json` keyed on text sha + model + PROMPT_VERSION.
- Deferred until seen in real output: cross-chapter flashcard dedupe (bge-m3), cross-chapter context in the prompt.
- Known, not solvable by checks: an answer can misread its (real) quote — v1 ch. 4 inverted "who tolerates boredom invents dancing". Thinking + the "check the direction" rule fixed that instance; the app should let the reader flag a card.
- **Gate:** read 3 chapters' output by hand; ≥ 90 % quiz questions grounded and correct.

### Phase 4 — Android app (native Kotlin, Jetpack Compose) — decided 2026-11-07, replaces the Streamlit app
Content is made on the laptop (GPU) and read from the cloud; the app is the only client.

**4a. Cloud content — `publish.py` (laptop)**
- Exports `books/*/chapters/NN.json` → `site/library.json` (books: id, title, author, language, chapters: n, title, words) + `site/books/<id>/NN.json` (summary, key ideas, flashcards, quiz; no stats/dropped).
- Stable card ids = sha1(book id + kind + front/question): review history survives a re-generation unless the card's text changes.
- `wrangler pages deploy site --project-name learn-ai` (same tooling as ai_news_pipeline). Ask before the first deploy.
- **Private**: no Cloudflare Access (it needs a card on file). `functions/_middleware.js` refuses every request without the app's key (header `X-Learnai-Key`; fails closed if the secret is unset). `make_key.py` creates the key and stores it as the project secret `LEARNAI_KEY` and in `android/local.properties` (never committed, never printed). `publish.py --deploy` uploads the guard with the content and checks every address refuses anonymous requests. Domain: <project>.pages.dev. Summaries quote copyrighted books: never public.
- **Highlights & notes backup**: synced per book with `/notes/<bookId>` (`functions/notes/[book].js`, KV namespace `learn-ai-notes`, binding `NOTES` in `wrangler.toml`) after every change and on Library visits, behind the same key guard. `notes.py` writes them as Markdown (`books/<book>/highlights.md`), skipping deleted ones. Sync scheme: see **Sync** below.

- **Focus sessions** (added 2026-11-09): the laptop focus guard (`~/Workspace/focus`) PUTs its whole `sessions.jsonl` (as a JSON list) to `/focus` (`functions/focus.js`) after every session, with the app key read from `android/local.properties`. Stored in the NOTES namespace under `_focus` (book ids can't contain `_`; `notes.py` skips `_` keys).

**4b. App — `android/`**
- Tabs: **Library** (book → chapter → summary + key ideas → chapter quiz), **Review** (all due cards across books), **News**.
- Scheduling: FSRS-6 via `open-spaced-repetition/fsrs-kotlin` (MIT). Flashcard: front → tap → back → Again/Hard/Good/Easy. Quiz: options shuffled on every show (generation puts answers at 1–2), right → Good, wrong → Again + show explanation and quote.
- Local storage: Room (SQLite) for card state, review log, XP, streak, flagged cards. Content JSON cached on disk → works offline; refresh on pull-down.
- Flag a card (wrong/unclear) → suspended from review (misreadings the checks can't catch).
- Notifications: WorkManager daily at a chosen time: "N cards due" (+ news ready).
- Gamification: XP per correct answer, daily streak, per-book mastery bar.
  - Built first for focus only (2026-11-09, `Focus.kt`, tab 🎯 Focus): XP per 5 focused minutes (soft 1, focus 2, hard 3), levels (since 2026-11-10: a week level and a month level, focus + learning XP, one level every 20 / 60 XP, starting again each Monday / each 1st; same steps as the laptop app), streak of days with ≥ 60 focused min, 14-day bar chart, session list. Level, XP, learning counters, session-time breakdown (focused / distracted / away / untracked of the wall-clock session time) and session list cover **this month** by default (the level starts again each month); buttons fixed at the bottom switch to All time. Streak, today and the 14-day chart don't depend on the period. Cached in `filesDir/focus.json`, refreshed on tab visit. Logic tested in `FocusStatsTest` (`./gradlew testDebugUnitTest`).
  - Learning XP (2026-11-09, `Progress.kt`, `filesDir/progress.json`, one observable `Learning` state): chapter finished +50 (checkbox in the book's chapter list, button in the chapter hub, or automatic in the reader when a page turn leaves the chapter's last file), first right answer to a quiz question +10 (retakes can't farm), news article marked read +2. XP is derived from the stored sets, so unticking takes it back. One level for focus + learning XP (Focus tab, with chapter / quiz / news counters). Per-book progress bar in the Library (chapters finished). EPUBs imported without summaries get a chapter list from their table of contents (`tocChapters`: shallowest level with ≥ 3 entries, like split.py; cached as `filesDir/books/<id>.toc.json`), with the same checkboxes, XP and reader auto-finish; tapping a chapter opens it in the reader. No TOC at all: the bar is Readium's whole-book progression. Quiz best score per chapter. News: a ✓ injected next to each article's 👍/👎 (`READ_BUTTONS` in `News.kt`, JS bridge `LearnAI`), counters above the page. Tested in `LearningStatsTest`. Cloud copy at `/progress` (`functions/progress.js`, NOTES key `_progress`), see **Sync**.
- **Movies & TV** (tab 🎬, `Movies.kt`, 2026-11-09): lists to watch (checkbox per title, watched/total bar) and Reviews (every rated title, best first). One review per title, wherever it's listed: score 1–10 = its colour (1–3 red, 4 orange, 5 yellow, 6 light green, 7 green, 8 vivid dark green, 9 pink, 10 violet), characters / plot / setting / meaning (1–5 ★ + text), opinions; saved as you type. Picture from the gallery (photo picker, no permission), stored at ≤ 600 px JPEG. `filesDir/movies/` (movies.json + pictures). Cloud copy: JSON at `/movies` (`functions/movies/index.js`, KV key `_movies`), pictures at `/movies/<name>` (`functions/movies/[image].js`, KV `_mimg_<name>`, JPEG only, DELETE when no longer used). Sync: see **Sync**; plus `<name>.pending` / `<name>.deleted` markers for pictures and download of pictures missing on the phone. Uploads wait 10 s after the last change: reviews save per keystroke, KV free tier ≈ 1000 writes/day. Series: episode counter (+1 ep in lists, −/+1 and total in the review; reaching the total ticks it watched; list bars count episodes, so half a series = half a title). Tags (lowercase, `#sci-fi`): add / remove in the review, reuse earlier tags, filter chips in Reviews and in each list. Deleting a list keeps titles that have a review. Tested in `MoviesTest`.
- News: v1 = WebView of morning-brief (built 2026-11-07: bottom tabs Library | News; digest pages stay in-app, source links open in the browser, votes keep their key in the WebView's localStorage); v2 = native list from a `digest.json` written by ai_news_pipeline's `core/delivery.py` (change in the other project: its owner's call).
- No cloud sync of review state (one device; Android Auto Backup keeps the Room DB). Add Cloudflare D1 sync only when a second device exists.
- Build: Android SDK command-line tools + JDK 21 side by side (system Java is 25, newer than the Gradle tooling expects), Gradle wrapper, `./gradlew assembleDebug`, sideload the APK (no Play Store).

**Order:** 4a publish.py + export format (no GPU, runnable now) → app skeleton showing one book offline from bundled JSON → cloud fetch behind Access → review + FSRS + Room → notifications → News tab.
- **Gate:** a week of real use on the phone; due reviews appear on the right days; reading works offline.

- **Reader options** (2026-11-09): tap the middle of a page → Reader: text size A− / A+ (60–250 %, 10 % steps, `fontSize`), and Pages: Automatic (Readium: two when the screen is wide, e.g. a tablet in landscape) / One page / Two pages like a book. `ColumnCount` for reflowable EPUBs, `Spread` for fixed-layout; saved per device (SharedPreferences `reader`), applied live. Readium's `EpubPreferences` only accepts spread null / NEVER / ALWAYS: `Spread.AUTO` crashed the reader on every book (2026-11-09, found on the tablet); Automatic passes null. `ReaderPagesTest` builds every option.
- **Book reviews** (2026-11-09): Library → book → "Write a review": same 1–10 colours as movies, writing / ideas / characters / plot (1–5 ★ + text), opinions; score badge on the Library row. Stored in the reviews store (`MovieData.books`, keyed by book id), so it shares the movies' debounced cloud backup. E-books with no table of contents open straight in the reader, so they have no review entry yet.

- **Sync** (2026-11-09, for phone + tablet): highlights, progress and reviews all use `syncDoc` (Content.kt): pull the cloud copy, merge it per item, save, push back only if the cloud lacked something. Every device does the same, so they converge; a reinstall gets everything back on its first sync. Per item the latest change wins (`updated` / timestamps); deletions and unticks are timestamped tombstones (`removed` maps, `Highlight.deleted`) that win over anything older. Merges give a canonical order (by creation), so equal data compares equal and nothing is re-pushed. Runs a few seconds after a change (debounced) and on tab visits. Tested in `LearningStatsTest` / `MoviesTest` (both orders give the same result), and on the phone against a simulated tablet (tick, then untick, of a chapter written into the cloud copy).
  - ponytail: two devices pushing in the same second can overwrite each other; the loser's next sync sees the cloud lacking its changes and pushes again. Tombstones are never pruned (a few bytes each).

### Review 2026-11-09 — state of the app
- **The core loop is missing:** generate.py makes flashcards for every chapter, but the app never shows them. The Review tab (FSRS-6), its notifications, and the phase-4 gate ("due reviews appear on the right days") are not built. Everything since (focus, news ✓, movies) is around the core, not the core.
- ~~Rotating the phone resets the Library / Movies screen to its start~~ fixed: MainActivity handles rotation itself (`configChanges`), no recreate.
- Three copies of the same cloud-backup scheme (NotesSync, Learning, Movies): extract one helper before a fourth.
- ~~Second device (tablet): backups were "merge once per install, then last write wins"~~ fixed: see **Sync** below.
- KV free tier ≈ 1000 writes/day shared by notes, focus, progress, reviews. Progress uploads now wait 5 s after the last change (fixed). FSRS review history must stay on the phone (Room), not one KV write per answer.
- ~~`Content.sync` re-downloads every chapter on every Library visit~~ fixed: skipped when library.json is unchanged (still all chapters when it changed).
- Small, fixed: MoviesTab no longer sets state during composition; score squares and stars are 48 dp tall; list-row posters decode at 1/4. Open: the app key is inside the APK (never share the APK).

### Built 2026-11-09 (roadmap batch) — unit-tested (`NewFeaturesTest`), not yet tried on a device
- **Review tab** (`Review.kt`): FSRS-6 via `io.github.open-spaced-repetition:fsrs:1.0.0` (java-fsrs, MIT). Cards = flashcards + quiz items of finished chapters; ≤ 20 new a day; due first. Flashcard: Show answer → Again/Hard/Good/Easy (next interval shown); quiz: right = Good, wrong = Again. ⚑ Flag = suspended. +2 XP per answer. States sync at `/doc/reviews` (`functions/doc/[name].js`, generic endpoint, KV `_doc_<name>`), newest change per card wins; answers per day per device. Daily notification ~18:00 "N cards to review" (WorkManager `DueWorker`, POST_NOTIFICATIONS asked on first visit), opens the Review tab. Today's 3 highlights (seeded by date).
- **Daily goal streak** (`Stats.kt`): a day counts with ≥ 60 focus min, ≥ 30 reading min, ≥ 20 reviews or a finished chapter; 7 days in a row earn a ❄️ freeze (max 2) that covers a missed day.
- **Reader**: reading time (gaps between page turns < 5 min; saved on pause; per day per device in `Progress.reading`; 1 XP / 5 min); Define (selection → process-text apps, else web search); theme System / Light / Sepia / Dark; highlight colours (yellow / green / blue / pink); "about N min left in this chapter" (chapter words, 230 wpm) in the options.
- **Books**: shelves To read / Reading / Finished / Abandoned with dates (in `BookReview`; opening the reader moves "" / to-read to reading), shelf filter in Library; EPUB covers (Readium `coverFitting`, cached); favourite quotes (⭐ highlights) on the review; Share highlights + review as Markdown; Search (books, chapter summaries / key ideas / cards, highlights, reviews, movies; accents ignored).
- **News**: 🔖 save for later + Saved list; ✓ also stores the article's section and lean → "What you read" in Stats.
- **Movies**: diary (viewings by date, Watched again), seasons, runtime → time watched, ⭐ favourites (top 4).
- **Stats tab** (was Focus): Month / Year / All; level incl. review + reading XP; books (finished, reading, avg score, yearly goal per device), movies (viewings, time, avg score), news by section and lean, reading time, reviews.
- **Laptop**: `notes.py` also writes `reviews.md` (books, then movies & series).
- Not built (need you): TMDB lookup (needs a TMDB API key), native news list (needs a `digest.json` from ai_news_pipeline).

### Built 2026-11-10 — unit-tested (`NewsTest`), JS checked on the live digest, not yet tried on a device
- **Fix: 👍/👎 did nothing in the app.** The page asks for the vote key with `prompt()`; a WebView without a `WebChromeClient` drops JS dialogs, so `prompt()` returned null and the vote was skipped. `newsWebView` now sets one: the first vote asks for the key once (votes.key in ai_news_pipeline's config.yaml), then localStorage keeps it.
- **News ⭐ Essentials**: the injected script moves the top 10 articles into a section at the top of the digest. Score = outlets covering the story × your interest in its section (1 + 4 × share of your reads in the last 60 days, a 👍/👎 = 3 reads; disliked sections down to 0.25); disliked articles out; ≤ 4 per section while others are left; ties keep the page order (`essentials()` / `sectionWeights()` in `News.kt`). 👍/👎 are also recorded in the app (SharedPreferences `news-votes`, this device only).
- **Stats**: overview grouped by section (Focus, Reading & learning, News, Books, Movies & TV), each with **More stats ›** → a detail page (same period buttons; back returns). News on the overview: read in the last 14 days and average per day. Details: Focus (time breakdown, per mode, average session, best day, time of day, 14-day chart, sessions); Learning (reading + reviews 14-day charts, reading days, deck state, chapters per book, quiz, goal days); News (14-day chart, per-day averages, best day, sections/lean, votes per section, saved); Books (shelves, finished with score + days to read, highlights per book); Movies (12-month viewings, movies/series, rewatches, top rated, tags).

### Built 2026-11-11
- **News profile**: votes no longer ask for a key per browser: the digest page has a 👤 login (one password, a 1-year cookie, `functions/api/profile.js` in ai_news_pipeline), the same in the app's WebView and any browser. The app flushes WebView cookies on stop so the login survives a kill. Login is per device (phone, tablet, each browser), once.
- ai_news_pipeline now drops paywalled and teaser-only articles (`core/ingestion.py`, see its CHANGELOG 11 Nov).

### Built 2026-11-13 — JS syntax-checked, not yet rebuilt or tried on a device
- **Essentials moved to ai_news_pipeline**: the digest page now ships its own ⭐ Essentials (`#essentials`, ranked by outlet count, see its CHANGELOG 13 Nov). The injected script skips the app's Essentials on those pages (archive pages before 13 Nov still get it) and takes a moved bullet's section from `data-section`, so reads/votes on it still count for the right section. The app's read-based section weighting no longer applies to new pages.

### Phase 5 — roadmap (ideas from the best apps per category; in priority order within each)
**Learning (Anki, Duolingo, Readwise)** — the app's reason to exist, do first.
1. Review tab: FSRS-6 flashcards + quiz items across books, Room, Again/Hard/Good/Easy, XP per review; daily "N cards due" notification (WorkManager).
2. Daily highlights resurfacing (Readwise): 3–5 of your own highlights a day, inside the Review tab.
3. Streak freeze / daily goal mixing focus, reviews and reading (Duolingo).

**E-reader (Kindle, KOReader, Apple Books)**
4. Reading time: minutes per day from the reader → focus-style chart, XP, "time left in chapter" (KOReader / Kindle).
5. Dictionary / translate on selection (Kindle): a "Define" action next to Highlight / Note.
6. Reader settings: font size, font, margins, sepia (Readium preferences already support them).
7. Highlight colours and export of highlights + notes per book to Markdown (notes.py already writes them; add the review).

**Books (Goodreads, StoryGraph)**
8. Shelves: to read / reading / finished / abandoned, start and finish dates; yearly reading goal.
9. Stats (StoryGraph): books, pages, genres, average score per month / year; favourite quotes from highlights on the review.
10. EPUB cover as the book's picture (Readium gives it), half-scores not needed (1–10 already).

**News (Readwise Reader, Ground News, Feedly)**
11. Save for later + "highlight in article" (Readwise Reader); saved articles in a list with ✓.
12. News v2: native list from ai_news_pipeline's digest.json (needs a change in that project), monthly "what you read" by category and lean (Ground News blindspot).

**Movies & TV (Letterboxd, Trakt, TV Time)**
13. TMDB lookup (free API, attribution required): poster, year, runtime, episodes per season filled in by name.
14. Diary: watch date per viewing, rewatches; year-in-review (Letterboxd); seasons for series (Trakt).
15. Time spent watching (TV Time), top 4 favourites on the Reviews page.

**Everywhere**
16. Search across books, highlights, reviews (embeddings already stored for books).
17. Monthly / yearly report page: focus, reading, reviews, news in one screen.
18. Export reviews to Markdown on the laptop (like notes.py) → Obsidian vault.
- Older ideas, still valid: Gutenberg / Standard Ebooks fetcher (public domain only); MOBI/AZW3 via calibre.
