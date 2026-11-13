# learn-ai

A pipeline for reading books and remembering them. It turns a PDF or EPUB into chapters, and a local LLM writes a summary, key ideas, flashcards and a quiz for each chapter. A native Android app shows the results and schedules the flashcards with spaced repetition.

> Built with AI coding assistants, checked by hand. The idea, the design and the architecture are mine.

The same app also shows the [Morning Brief](https://github.com/filippo-graci/local_ai_news) news digest and the session stats of [focus-cam](https://github.com/filippo-graci/focus-cam). See [Part of a larger system](#part-of-a-larger-system).

## How it works

```
PDF / EPUB ─▶ convert.py ─▶ split.py ─▶ generate.py ─▶ publish.py ─▶ Cloudflare Pages ─▶ Android app
             (markdown +    (chapters)   (summary, cards,  (export +              (Library, Review,
              coverage check)            quiz, checks)      deploy)                News, Stats, Movies)
```

| Step | File | What it does |
|---|---|---|
| Convert | `convert.py` | PDF through marker-pdf, EPUB through pandoc. Compares source words with the markdown and fails the book below 95% coverage. |
| Split | `split.py` | Cuts the book into chapters from its table of contents and skips front and back matter, with the reason recorded. |
| Generate | `generate.py` | One Ollama call per chapter, constrained to a JSON schema and validated with pydantic. Every card carries a quote from the chapter; cards whose quote isn't in the text are dropped and kept for review. Invalid output after 3 retries goes to `failures.jsonl`. |
| Publish | `publish.py` | Exports the cards to `site/` and deploys it with `wrangler pages deploy`. `functions/_middleware.js` refuses every request without the app key. |
| Notes | `notes.py` | Writes the highlights, notes and reviews made on the phone back to Markdown on the laptop. |

The app (`android/`, Kotlin + Jetpack Compose) has a Library with an EPUB reader, a Review tab with FSRS-6 scheduling, the news digest, a Stats tab, and movie and book reviews. Highlights, progress and reviews sync between devices through Cloudflare KV.

`PLAN.md` is the design log, with each phase's gate and the measurements behind the decisions.

## Requirements

- Linux with an NVIDIA GPU (8 GB is enough) and Python 3.11+ with `pydantic`
- [Ollama](https://ollama.com) with `qwen3.5:9b-q4_K_M` (`generate.py --backend gemini` uses Google's API instead)
- `marker-pdf` for PDFs, `pandoc` for EPUBs
- `wrangler` and a Cloudflare account for publishing
- Android SDK command-line tools and JDK 21 for the app

## Setup

1. Create a Cloudflare Pages project and a KV namespace. Put the namespace id in `wrangler.toml` and the project domain in `publish.py` (`URL`) and `android/app/build.gradle.kts` (`CONTENT_URL`).
2. Run `python make_key.py`. It stores a random key as the project secret `LEARNAI_KEY` and in `android/local.properties`, without printing it.
3. Drop books in `inbox/`, then run `convert.py`, `split.py`, `generate.py` and `publish.py --deploy`.
4. Build the app with `cd android && ./gradlew assembleDebug` and install the APK.

The app key is built into the APK, so the APK must not be shared. Summaries quote the books, so `site/` must never be made public; it is git-ignored along with `inbox/`, `epub/` and `books/`. The pipeline is meant for owned or public-domain books only.

## Tests

```bash
python test_convert.py && python test_split.py && python test_generate.py && python test_publish.py && python test_notes.py
cd android && ./gradlew testDebugUnitTest
```

## Part of a larger system

learn-ai is one of three projects that run on the same laptop and show up in the same phone app:

| Project | Role |
|---|---|
| **learn-ai** (this repo) | Book pipeline and the Android app that shows all three |
| [local_ai_news](https://github.com/filippo-graci/local_ai_news) | Morning Brief: a daily news digest made with local models, shown in the app's News tab |
| [focus-cam](https://github.com/filippo-graci/focus-cam) | Webcam focus guard; its sessions feed the app's Stats tab and streak |

They share one 8 GB GPU. `stop_llm.py` (a systemd timer at 06:30) frees it for the news run, and `quiet.py` keeps generation from starting again before 08:30. Any project can still be used alone.

## License

MIT
