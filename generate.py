"""Phase 3: chapter -> summary + key ideas + flashcards + quiz, with a local LLM (Ollama).

    .venv/bin/python generate.py [books/<stem> ...] [--chapters 2,5] [--force]
Default: every book with a chapters.json. Reads chapters/NN.md, writes chapters/NN.json.

- Output is constrained to a JSON schema by Ollama, then validated by pydantic; on a validation error the
  model is shown the error and retried. Still invalid after RETRIES -> books/<stem>/failures.jsonl, never dropped silently.
- Every flashcard and quiz question carries a source_quote copied from the chapter. Items whose quote is not
  in the chapter (>= 80 % of its words as one contiguous run) are dropped and kept under "dropped" for review.
- Chapters longer than SECTION_WORDS are cut at headings/paragraphs, generated per section, and the section
  summaries are merged into one chapter summary.
- Cached: a chapter is regenerated only if its text, the model, or PROMPT_VERSION changed (or --force).
Env: LEARN_MODEL (default qwen3.5:9b-q4_K_M), OLLAMA_URL (default http://localhost:11434).
--backend gemini: Google's API instead of Ollama (the chapter text is sent to Google; use a paid-tier key so it
isn't used for training). Env: GEMINI_API_KEY (required), GEMINI_MODEL (default gemini-3.6-flash).
Same prompts, schema and checks; no GPU, so no 06:30 quiet window and stop_llm.py leaves it running.
"""
import argparse
import difflib
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

from pydantic import BaseModel, Field, ValidationError, field_validator

from quiet import MESSAGE as QUIET_MESSAGE, in_quiet

ROOT = Path(__file__).resolve().parent
BOOKS = ROOT / "books"
OLLAMA = os.environ.get("OLLAMA_URL", "http://localhost:11434")
MODEL = os.environ.get("LEARN_MODEL", "qwen3.5:9b-q4_K_M")  # main() swaps in GEMINI_MODEL for --backend gemini
BACKEND = "ollama"  # set by --backend in main()
GEMINI = "https://generativelanguage.googleapis.com/v1beta/models"
GEMINI_WAITS = (30, 60, 120)  # seconds between tries on 429 (rate limit) / 5xx
NUM_CTX = 16384  # prompt + thinking + answer (12k ran out on Han ch. 2); more of the model on CPU: slower, runs overnight
# Sections by characters, not words: prose is ~4 chars/token, LaTeX-heavy text ~2.7 (thesis ch. 4: 2350 words
# -> 6.6k tokens). 12k chars fits NUM_CTX for both; a section that still doesn't is halved and retried.
SECTION_CHARS = 12000
OUTPUT_RESERVE = 8000  # thinking + answer; a prompt above NUM_CTX - this would be truncated by Ollama
RETRIES = 3  # for hard problems (wrong language, invented names, lost accents)
SOFT_RETRIES = 1  # for soft ones (answer given away by length): each try is ~3 min with thinking
TEMPERATURE = 0.2
QUOTE_MATCH = 0.8  # share of the quote's words found in order near its best spot
QUOTE_SLACK = 30  # extra chapter words allowed around/inside the quote
PROMPT_VERSION = 5  # bump when a prompt or the schema changes: cached chapters get regenerated


# min_length everywhere: with thinking on, the model left every source_quote and half the explanations
# empty ("") on Han ch. 1; minLength makes the schema (and so Ollama's constrained decoding) refuse that.
QUOTE = Field(min_length=20, description="one sentence copied word for word from the chapter that supports the answer")


class Flashcard(BaseModel):
    front: str = Field(min_length=8, description="a question or cue, one line")
    back: str = Field(min_length=8, description="the answer, at most 2 sentences")
    source_quote: str = QUOTE


class QuizItem(BaseModel):
    question: str = Field(min_length=8)
    options: list[str] = Field(min_length=4, max_length=4)
    answer: int = Field(ge=0, le=3, description="index (0-3) of the one correct option")
    explanation: str = Field(min_length=8, description="why that option is right, 1 sentence")
    source_quote: str = QUOTE

    @field_validator("options")
    @classmethod
    def distinct(cls, v):
        if len({o.strip().lower() for o in v}) != len(v):
            raise ValueError("the 4 options must all be different")
        return v


class Cards(BaseModel):
    summary: str = Field(description="150-250 words")
    key_ideas: list[str] = Field(min_length=3, max_length=7)
    flashcards: list[Flashcard] = Field(min_length=3, max_length=8)
    quiz: list[QuizItem] = Field(min_length=3, max_length=6)


class Merged(BaseModel):
    summary: str = Field(description="200-300 words")
    key_ideas: list[str] = Field(min_length=3, max_length=8)


SYSTEM = """You write study material for a reader who wants to understand and remember a book.
Use ONLY the text you are given. Write everything in the same language as that text.
- summary: 150-250 words. State the central claim first, then the steps of the argument in order,
  using the author's own key terms.
- key_ideas: the 3-7 most important ideas, one sentence each.
- Attribution: when the text reports or quotes another thinker ("Benjamin defines...", "according to
  Nietzsche"), say whose idea it is. Never present a cited author's view as the author's, or the reverse.
  Name a person in a flashcard or question only if its source_quote sentence, or the sentence just
  before it, names that person.
- flashcards: 5 cards. Front: a question or cue. Back: the answer. Prefer the meaning of the author's key
  terms, the claims and the reasons given for them, contrasts the text draws (X versus Y), causes and
  consequences. No trivia: no page numbers; names and dates only when central.
- quiz: 5 multiple-choice questions, 4 options each, exactly one correct. Wrong options are plausible
  misreadings of the text: a reversed cause and effect, the idea attributed to the wrong person, two of the
  author's concepts confused. Every option has about the same length and detail as the correct one.
  "answer" is the index (0-3) of the correct option.
- Before writing each answer, re-read its source sentence and check the direction: who does what, what
  causes what, negations ("not", "without", "instead of"). The answer must say what the sentence says.
- source_quote: copy ONE sentence from the text word for word (same language, no translation, no paraphrase)
  that supports the answer."""

MERGE = """These are the summaries of the consecutive sections of one chapter, in order.
Write one summary of 200-300 words and 3-8 key ideas for the whole chapter, in the same language as the
summaries. Use only what the summaries say."""


class GenerationError(Exception):
    pass


class PromptTooLong(GenerationError):
    pass


def extract_json(content: str) -> str:
    """The model's JSON without ```json fences or chatter around it."""
    start, end = content.find("{"), content.rfind("}")
    return content[start:end + 1] if start != -1 < end else content


def post(url: str, body: dict, headers: dict, timeout: int) -> dict:
    req = urllib.request.Request(url, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", **headers})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def ask_ollama(messages: list[dict], schema: type[BaseModel]) -> tuple[str, int, int, bool]:
    """-> (content, prompt tokens, output tokens, ran out of space)"""
    resp = post(f"{OLLAMA}/api/chat", {"model": MODEL, "messages": messages, "stream": False, "think": True,
                                       "format": schema.model_json_schema(),
                                       "options": {"temperature": TEMPERATURE, "num_ctx": NUM_CTX}}, {}, 900)
    return (resp["message"]["content"], resp.get("prompt_eval_count", 0), resp.get("eval_count", 0),
            resp.get("done_reason") == "length")


def ask_gemini(messages: list[dict], schema: type[BaseModel]) -> tuple[str, int, int, bool]:
    """Same contract as ask_ollama, via Gemini's generateContent with a JSON schema (key in a header, not the URL)."""
    body = {"systemInstruction": {"parts": [{"text": messages[0]["content"]}]},
            "contents": [{"role": "user", "parts": [{"text": m["content"]}]} for m in messages[1:]],
            "generationConfig": {"temperature": TEMPERATURE, "responseMimeType": "application/json",
                                 "responseJsonSchema": schema.model_json_schema()}}
    for wait in (*GEMINI_WAITS, None):
        try:
            resp = post(f"{GEMINI}/{MODEL}:generateContent", body, {"x-goog-api-key": os.environ["GEMINI_API_KEY"]}, 300)
            break
        except urllib.error.HTTPError as e:
            if e.code in (429, 500, 503) and wait is not None:
                print(f"      Gemini HTTP {e.code} (busy / rate limit): waiting {wait}s", flush=True)
                time.sleep(wait)
                continue
            # urllib's message is just "Bad Request": the body says why (bad schema, unknown model, bad key)
            raise OSError(f"Gemini HTTP {e.code}: {e.read().decode(errors='replace')[:500]}") from e
    if not resp.get("candidates"):
        raise GenerationError(f"Gemini gave no answer: {resp.get('promptFeedback')}")
    cand, usage = resp["candidates"][0], resp.get("usageMetadata", {})
    reason = cand.get("finishReason")
    if reason not in ("STOP", "MAX_TOKENS"):  # e.g. RECITATION: it refuses to copy long passages of a book
        raise GenerationError(f"Gemini stopped: {reason}")
    text = "".join(p.get("text", "") for p in cand.get("content", {}).get("parts", []) if not p.get("thought"))
    return (text, usage.get("promptTokenCount", 0),
            usage.get("candidatesTokenCount", 0) + usage.get("thoughtsTokenCount", 0), reason == "MAX_TOKENS")


def chat(system: str, user: str, schema: type[BaseModel], check=None) -> tuple[BaseModel, dict]:
    """check(obj) -> (message, hard) or None. A failing check is retried like a schema error; after the
    last try a hard failure raises, a soft one is accepted (and counted in stats["errors"])."""
    # Thinking ON: Ollama 0.30.5 ignores "format" for qwen3.5 when think=false (also on /v1, tested
    # 2026-11-07), and without it the 9B model broke JSON, answered in English (3/8 Han chapters), dropped
    # accents and invented attributions. With thinking + format: valid on the first try, ~3 min/chapter.
    base = [{"role": "system", "content": system}, {"role": "user", "content": user}]
    messages = base
    stats = {"retries": 0, "prompt_tokens": 0, "output_tokens": 0, "errors": []}
    for attempt in range(RETRIES):
        content, prompt_tokens, output_tokens, out_of_space = (ask_gemini if BACKEND == "gemini" else ask_ollama)(
            messages, schema)
        stats["prompt_tokens"] = max(stats["prompt_tokens"], prompt_tokens)
        stats["output_tokens"] += output_tokens
        if BACKEND == "ollama" and stats["prompt_tokens"] > NUM_CTX - OUTPUT_RESERVE:
            # Ollama silently drops the start of the prompt (Gemini's context is far larger)
            raise PromptTooLong(f"prompt is {stats['prompt_tokens']} tokens, too long for num_ctx {NUM_CTX}")
        error, hard = None, True
        if out_of_space:
            # Thinking + answer ran out of context (Han ch. 2: 7k tokens left after a 5.3k prompt). Halving the
            # input doesn't help, thinking length doesn't shrink with it: retry, asking for shorter reasoning.
            error = "You ran out of space before finishing the JSON. Keep your reasoning brief and write the JSON sooner."
        else:
            try:
                obj = schema.model_validate_json(extract_json(content))
            except ValidationError as e:
                error = f"That JSON is invalid:\n{e}"
        if error is None:
            problem = check(obj) if check else None
            if problem is None:
                return obj, stats
            error, hard = problem
            if attempt >= SOFT_RETRIES and not hard:
                stats["errors"].append("accepted: " + error[:160])
                return obj, stats
        stats["retries"] = attempt + 1
        stats["errors"].append(error[:160].replace("\n", " "))
        # Retry = the same prompt + a short note on what to fix. Sending back the previous answer and
        # pydantic's full error (which quotes the bad values) grew a 200-word chapter's prompt to 11k tokens.
        note = error[:600] if hard else error
        messages = [base[0], {"role": "user", "content": f"{user}\n\nIMPORTANT, a previous attempt was rejected: "
                                                         f"{note}\nAvoid that this time."}]
    raise GenerationError(f"invalid after {RETRIES} tries: {error}\n--- last output ---\n{content}")


LENGTH_RATIO = 1.15  # correct option may be at most this much longer than the longest wrong one


def non_ascii_share(s: str) -> float:
    letters = [c for c in s if c.isalpha()]
    return sum(ord(c) > 127 for c in letters) / max(1, len(letters))


SAME_LANGUAGE = 0.4  # share of output words found in the chapter: same language 0.69-0.82, English 0.02-0.12


def names_in(text: str) -> list[str]:
    """Capitalised words that are not sentence-initial: names (Benjamin, Nietzsche, Bartleby)."""
    out = []
    for m in re.finditer(r"\b[A-ZÀ-ÖØ-Þ][\w'’-]{3,}", text):
        before, name = text[:m.start()].rstrip(), m.group().rstrip("'’-")  # "Bartleby'" -> "Bartleby"
        if before and before[-1] not in ".!?:;\"'«“(—–-#*":
            out.append(name)
    return out


def foreign_names(fields: list[str], source: str) -> list[str]:
    """Names the chapter never contains: invented attributions
    (Han ch. 1: "Giorgio Agamben reinterpreta il mito di Prometeo"; Agamben is not in that chapter)."""
    low = source.lower()
    return sorted({n for f in fields for n in names_in(f) if n.lower() not in low})


NEAR_WORDS = 60  # a person named on a card must be named within this many words of the card's quote
SUBJECT_EVERY = 150  # named at least once per this many words = what the chapter is about (Bartleby): exempt


def attribution_filter(text: str):
    """Like ai_news_pipeline's repair_citations: don't trust who the model says said it, check it against
    the source. A card naming a person the chapter names, but not near the passage the card quotes,
    is attributing the idea to someone the text doesn't (e.g. Nietzsche for Benjamin's idea).
    Returns reason(item, kind) -> None if the item is fine, else why it is dropped."""
    chapter_tokens = tokens(text)
    freq = Counter(chapter_tokens)
    n_words = len(chapter_tokens)
    capitalised = set(re.findall(r"\b[A-ZÀ-ÖØ-Þ][\w’'-]*", text))
    lower = set(re.findall(r"\b[a-zà-öø-ÿ][\w’'-]*", text))

    def is_name(n: str) -> bool:  # the chapter writes it only capitalised ("Nietzsche", never "nietzsche")
        return n in capitalised and n.lower() not in lower

    def reason(item: dict, kind: str) -> str | None:
        span = quote_span(item["source_quote"], chapter_tokens)
        if span is None:
            return "quote not found in the chapter"
        claims = [item["front"], item["back"]] if kind == "flashcards" else \
            [item["question"], item["options"][item["answer"]], item["explanation"]]  # wrong options may misattribute on purpose
        near = set(chapter_tokens[max(0, span[0] - NEAR_WORDS):span[1] + NEAR_WORDS])
        far = sorted({n for c in claims for n in names_in(c)
                      if is_name(n) and freq[n.lower()] * SUBJECT_EVERY < n_words
                      and n.lower() not in near})
        return f"{', '.join(far)} not named near the quoted passage" if far else None

    return reason


def card_checks(source: str):
    src_share = non_ascii_share(source)
    vocab = set(tokens(source))

    def check(cards: "Cards"):
        fields = [cards.summary, *cards.key_ideas] + [x for f in cards.flashcards for x in (f.front, f.back)] \
            + [x for q in cards.quiz for x in (q.question, *q.options, q.explanation)]
        out = " ".join(fields)
        words = tokens(out)
        if words and sum(w in vocab for w in words) / len(words) < SAME_LANGUAGE:
            return ("You wrote in a different language from the text. Write everything (summary, key ideas, "
                    "cards, quiz) in the same language as the text you were given.", True)
        names = foreign_names(fields, source)
        if names:
            return (f"You mention {', '.join(names)}, who/which never appear in this text. Use only names the "
                    "text itself contains, and attribute each idea to the person the text attributes it to.", True)
        # seen on Han ch. 2 and 7 ("Perch", "societa"): the model dropped every accented letter
        if src_share > 0.005 and non_ascii_share(out) < src_share * 0.2:
            return ("Your text lost the accented letters (à è é ì ò ù ö ü ...). Write every word with its "
                    "correct accents and special letters, exactly as the source text spells them.", True)
        bad = []
        for i, q in enumerate(cards.quiz, 1):
            lens = [len(o) for o in q.options]
            wrong = max(l for j, l in enumerate(lens) if j != q.answer)
            if lens[q.answer] > LENGTH_RATIO * wrong:
                bad.append(f"question {i}: correct option {lens[q.answer]} chars, longest wrong option {wrong} chars")
        if bad:
            return ("In these questions the correct option is clearly longer than the wrong ones, which gives the "
                    "answer away: " + "; ".join(bad) + ". Rewrite them so all 4 options have about the same length "
                    "(shorten the correct one or make the wrong ones as detailed). Keep everything else.", False)
        return None

    return check


def tokens(s: str) -> list[str]:
    """Prose words only: the model rewrites math ($P_{j}$ vs P_j), so LaTeX and symbols are ignored."""
    s = re.sub(r"\$[^$]*\$|\\[a-zA-Z]+", " ", s.lower())  # $...$ math spans, then stray \commands
    return [w for w in re.findall(r"[^\W\d_]+", s) if len(w) >= 3]


def quote_span(quote: str, chapter_tokens: list[str]) -> tuple[int, int] | None:
    """Where the quote is in the chapter (token indices), if >= QUOTE_MATCH of its words appear in order
    around its best-matching spot; None if it isn't there."""
    q = tokens(quote)
    if len(q) < 4:  # "Yes." is not evidence
        return None
    m = difflib.SequenceMatcher(None, chapter_tokens, q, autojunk=False).find_longest_match(
        0, len(chapter_tokens), 0, len(q))
    # slack: room for a footnote marker dropped mid-sentence at a page break, or a "..." elision
    lo = max(0, m.a - m.b - QUOTE_SLACK)
    window = chapter_tokens[lo:m.a - m.b + len(q) + QUOTE_SLACK]
    blocks = [b for b in difflib.SequenceMatcher(None, window, q, autojunk=False).get_matching_blocks() if b.size]
    if sum(b.size for b in blocks) / len(q) < QUOTE_MATCH:
        return None
    return lo + blocks[0].a, lo + blocks[-1].a + blocks[-1].size


def grounded(quote: str, chapter_tokens: list[str]) -> bool:
    return quote_span(quote, chapter_tokens) is not None


def pack(blocks: list[str], limit: int) -> list[str]:
    out, cur = [], ""
    for b in blocks:
        if cur and len(cur) + 2 + len(b) > limit:  # 2 = the "\n\n" joining them
            out.append(cur)
            cur = ""
        cur += ("\n\n" if cur else "") + b
    return out + [cur] if cur else out


def sections(text: str) -> list[str]:
    """Pieces of at most ~SECTION_CHARS, cut at headings, else at paragraphs."""
    if len(text) <= SECTION_CHARS:
        return [text]
    blocks = []
    for block in re.split(r"\n(?=#{1,6} )", text):
        # ponytail: a single paragraph > SECTION_CHARS stays whole (halved later if the prompt is too long)
        blocks += [block] if len(block) <= SECTION_CHARS else block.split("\n\n")
    return pack(blocks, SECTION_CHARS)


def halves(text: str) -> list[str]:
    paras = text.split("\n\n")
    return pack(paras, len(text) // 2 + 1) if len(paras) > 1 else [text]


def run_part(part: str, where: str) -> list[tuple[BaseModel, dict]]:
    try:
        return [chat(SYSTEM, f"Text ({where}):\n\n{part}", Cards, card_checks(part))]
    except PromptTooLong:
        pieces = halves(part)
        if len(pieces) == 1:
            raise
        return [r for p in pieces for r in run_part(p, where)]


def generate(book: Path, ch: dict) -> dict:
    text = (book / ch["file"]).read_text(encoding="utf-8")
    parts = sections(text)
    t0, stats, results = time.time(), [], []
    for i, part in enumerate(parts, 1):
        where = f'chapter "{ch["title"]}"' + (f", section {i} of {len(parts)}" if len(parts) > 1 else "")
        for cards, s in run_part(part, where):
            results.append(cards)
            stats.append(s)
    if len(results) > 1:
        joined = "\n\n".join(f"Section {i}:\n{c.summary}" for i, c in enumerate(results, 1))
        merged, s = chat(MERGE, f'Chapter "{ch["title"]}"\n\n{joined}', Merged)
        stats.append(s)
        summary, key_ideas = merged.summary, merged.key_ideas
    else:
        summary, key_ideas = results[0].summary, results[0].key_ideas

    kept, dropped = sort_items({k: [i.model_dump() for c in results for i in getattr(c, k)]
                                for k in ("flashcards", "quiz")}, text)
    return {
        "book": book.name, "chapter": ch["n"], "title": ch["title"],
        "model": MODEL, "prompt_version": PROMPT_VERSION,
        "text_sha": hashlib.sha256(text.encode()).hexdigest(), "sections": len(results),
        "summary": summary, "key_ideas": key_ideas, **kept, "dropped": dropped,
        "stats": {"seconds": round(time.time() - t0, 1), "calls": len(stats),
                  "retries": sum(s["retries"] for s in stats),
                  "errors": [e for s in stats for e in s["errors"]],
                  "max_prompt_tokens": max(s["prompt_tokens"] for s in stats),
                  "output_tokens": sum(s["output_tokens"] for s in stats),
                  # answer giveaway: correct option > LENGTH_RATIO x every wrong one (the app shuffles positions)
                  "correct_is_longest": sum(longest(q) for q in kept["quiz"])},
    }


def sort_items(items: dict, text: str) -> tuple[dict, dict]:
    """Split generated cards into kept and dropped (with the reason) by quote grounding + attribution."""
    reason = attribution_filter(text)
    kept, dropped = {k: [] for k in items}, {k: [] for k in items}
    for kind, lst in items.items():
        for item in lst:
            item = {k: v for k, v in item.items() if k != "reason"}
            why = reason(item, kind)
            if why:
                dropped[kind].append({**item, "reason": why})
            else:
                kept[kind].append(item)
    return kept, dropped


def refilter(book: Path, ch: dict) -> dict | None:
    """Re-run the post-generation checks on an existing chapters/NN.json (no LLM): for when a check changes."""
    out = book / ch["file"].replace(".md", ".json")
    if not out.exists():
        return None
    r = json.loads(out.read_text(encoding="utf-8"))
    pool = {k: r[k] + r["dropped"][k] for k in ("flashcards", "quiz")}
    kept, dropped = sort_items(pool, (book / ch["file"]).read_text(encoding="utf-8"))
    r.update(kept)
    r["dropped"] = dropped
    r["stats"]["correct_is_longest"] = sum(longest(q) for q in kept["quiz"])
    out.write_text(json.dumps(r, indent=2, ensure_ascii=False), encoding="utf-8")
    return r


def longest(q: dict) -> bool:
    lens = [len(o) for o in q["options"]]
    return lens[q["answer"]] > LENGTH_RATIO * max(l for i, l in enumerate(lens) if i != q["answer"])


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("books", nargs="*", type=Path)
    ap.add_argument("--chapters", help="comma-separated chapter numbers, e.g. 2,5")
    ap.add_argument("--force", action="store_true", help="regenerate even if cached")
    ap.add_argument("--refilter", action="store_true",
                    help="no LLM: re-run quote + attribution checks on existing outputs")
    ap.add_argument("--backend", choices=["ollama", "gemini"], default="ollama",
                    help="gemini: Google's API (needs GEMINI_API_KEY; sends the chapter text to Google)")
    ap.add_argument("--no-publish", action="store_true",
                    help="don't run publish.py --deploy afterwards (it runs whenever a chapter changed)")
    a = ap.parse_args()
    rc = run(a)
    if changed and not a.no_publish:  # new/updated chapters reach the app on its next open
        print("publishing ...", flush=True)
        rc = subprocess.run([sys.executable, str(ROOT / "publish.py"), "--deploy"]).returncode or rc
    return rc


changed = 0  # chapters written this run (generated or refiltered)


def run(a) -> int:
    global BACKEND, MODEL, changed
    BACKEND = a.backend
    if BACKEND == "gemini" and not a.refilter:
        if not os.environ.get("GEMINI_API_KEY"):
            print("--backend gemini needs GEMINI_API_KEY in the environment")
            return 2
        MODEL = os.environ.get("GEMINI_MODEL", "gemini-3.6-flash")
    only = {int(n) for n in a.chapters.split(",")} if a.chapters else None

    failed = 0
    for book in a.books or sorted(p for p in BOOKS.iterdir() if (p / "chapters.json").exists()):
        for ch in json.loads((book / "chapters.json").read_text(encoding="utf-8"))["chapters"]:
            if only and ch["n"] not in only:
                continue
            if not a.refilter and BACKEND == "ollama" and in_quiet():  # the morning GPU belongs to ai_news_pipeline
                print(QUIET_MESSAGE)
                return 1 if failed else 0
            if a.refilter:
                r = refilter(book, ch)
                if r:
                    changed += 1
                    print(f"{book.name[:30]} #{ch['n']}: cards {len(r['flashcards'])}, quiz {len(r['quiz'])} kept")
                    for kind in ("flashcards", "quiz"):
                        for d in r["dropped"][kind]:
                            print(f"   dropped {kind[:5]}: {d['reason']}")
                continue
            out = book / ch["file"].replace(".md", ".json")
            sha = hashlib.sha256((book / ch["file"]).read_bytes()).hexdigest()
            if out.exists() and not a.force:
                old = json.loads(out.read_text(encoding="utf-8"))
                if (old["text_sha"], old["model"], old["prompt_version"]) == (sha, MODEL, PROMPT_VERSION):
                    continue
            label = f"{book.name[:30]} #{ch['n']} {ch['title'][:40]}"
            try:
                r = generate(book, ch)
            except (GenerationError, OSError) as e:  # OSError: backend down / timeout / HTTP error
                failed += 1
                print(f"FAIL  {label}: {str(e).splitlines()[0]}")
                with open(book / "failures.jsonl", "a", encoding="utf-8") as f:
                    f.write(json.dumps({"time": time.strftime("%F %T"), "chapter": ch["n"], "model": MODEL,
                                        "error": str(e)}, ensure_ascii=False) + "\n")
                continue
            out.write_text(json.dumps(r, indent=2, ensure_ascii=False), encoding="utf-8")
            changed += 1
            st, d = r["stats"], r["dropped"]
            print(f"ok    {label}: {r['sections']} sect, {st['seconds']}s, "
                  f"cards {len(r['flashcards'])}/{len(r['flashcards']) + len(d['flashcards'])}, "
                  f"quiz {len(r['quiz'])}/{len(r['quiz']) + len(d['quiz'])} grounded, "
                  f"correct=longest {st['correct_is_longest']}/{len(r['quiz'])}, "
                  f"retries {st['retries']}, max prompt {st['max_prompt_tokens']} tok")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
