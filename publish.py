"""Phase 4a: export generated chapters into the format the Android app reads, optionally deploy it.

    .venv/bin/python publish.py            # writes site/
    .venv/bin/python publish.py --deploy   # + wrangler pages deploy site (Cloudflare project PROJECT, private)
                                           #   with the key guard (functions/_middleware.js, key: make_key.py);
                                           #   fails unless every address refuses requests without the key

site/library.json         {"version", "generated", "books": [{id, title, author, language, identifier,
                            chapters: [{n, title, words, href, cards, questions}]}]}
                          EPUB books only; identifier = the EPUB's unique identifier, href = the chapter's
                          path inside the EPUB (the app opens the user's own copy of the book there)
site/books/<id>/NN.json   {n, title, summary, key_ideas, flashcards: [{id, front, back, source_quote}],
                            quiz: [{id, question, options, answer, explanation, source_quote}]}
Card ids are sha1(book id, kind, front/question): a regenerated chapter keeps the review history of every
card whose text did not change. Only chapters generated with the current PROMPT_VERSION are exported;
stats and dropped items stay on the laptop.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from generate import PROMPT_VERSION

ROOT = Path(__file__).resolve().parent
BOOKS, SITE = ROOT / "books", ROOT / "site"
PROJECT = "learn-ai"
URL = "https://<your-project>.pages.dev"  # the Pages project domain; the app reads it too
FORMAT_VERSION = 2  # bump on any incompatible change (the app checks it). 2: EPUB only, identifier, chapter href


def slug(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")


def card_id(book_id: str, kind: str, text: str) -> str:
    return hashlib.sha1(f"{book_id}\x00{kind}\x00{text.strip()}".encode()).hexdigest()[:16]


def epub_meta(path: Path) -> dict:
    with zipfile.ZipFile(path) as z:
        opf_path = ET.fromstring(z.read("META-INF/container.xml")).find(".//{*}rootfile").get("full-path")
        package = ET.fromstring(z.read(opf_path))
    meta = package.find("{*}metadata")
    get = lambda tag: next((e.text.strip() for e in meta.findall(f"{{*}}{tag}") if e.text), None)
    # The package's unique identifier = Readium's publication.metadata.identifier: how the app matches
    # an EPUB the user imports on the phone to its summaries.
    uid = package.get("unique-identifier")
    ident = next((e.text.strip() for e in meta.findall("{*}identifier") if e.text and e.get("id") == uid), None)
    return {"title": get("title"), "author": get("creator"), "language": get("language"),
            "identifier": ident or get("identifier")}


def export() -> dict:
    if SITE.exists():
        shutil.rmtree(SITE)  # generated output only
    library = {"version": FORMAT_VERSION, "generated": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "books": []}
    for book in sorted(p for p in BOOKS.iterdir() if (p / "chapters.json").exists()):
        index = json.loads((book / "chapters.json").read_text(encoding="utf-8"))
        source = Path(index["source"])
        if source.suffix.lower() != ".epub":  # the app is an EPUB reader: no PDFs (decided 2026-11-07)
            continue
        book_id = slug(book.name)
        chapters = []
        for ch in index["chapters"]:
            src = book / ch["file"].replace(".md", ".json")
            if not src.exists():
                continue
            r = json.loads(src.read_text(encoding="utf-8"))
            if r["prompt_version"] != PROMPT_VERSION:  # older prompts made English/invented cards: never ship them
                print(f"  skip {book.name[:30]} #{ch['n']}: generated with prompt v{r['prompt_version']}, "
                      f"current is v{PROMPT_VERSION} (rerun generate.py)")
                continue
            out = {
                "n": ch["n"], "title": ch["title"], "summary": r["summary"], "key_ideas": r["key_ideas"],
                "flashcards": [{"id": card_id(book_id, "card", c["front"]), **{k: c[k] for k in
                               ("front", "back", "source_quote")}} for c in r["flashcards"]],
                "quiz": [{"id": card_id(book_id, "quiz", q["question"]), **{k: q[k] for k in
                         ("question", "options", "answer", "explanation", "source_quote")}} for q in r["quiz"]],
            }
            path = SITE / "books" / book_id / f"{ch['n']:02d}.json"
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(out, ensure_ascii=False), encoding="utf-8")
            chapters.append({"n": ch["n"], "title": ch["title"], "words": ch["words"], "href": ch.get("href"),
                             "cards": len(out["flashcards"]), "questions": len(out["quiz"])})
        if chapters:
            meta = epub_meta(source)
            library["books"].append({"id": book_id, "title": meta["title"] or book.name, "author": meta["author"],
                                     "language": meta["language"], "identifier": meta["identifier"],
                                     "chapters": chapters})
    (SITE / "library.json").write_text(json.dumps(library, ensure_ascii=False, indent=1), encoding="utf-8")
    return library


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--deploy", action="store_true", help=f"wrangler pages deploy to the '{PROJECT}' project")
    a = ap.parse_args()
    lib = export()
    for b in lib["books"]:
        n_cards = sum(c["cards"] + c["questions"] for c in b["chapters"])
        print(f"{b['id']}: {b['title']} ({b['author'] or '?'}, {b['language'] or '?'}) "
              f"{len(b['chapters'])} chapters, {n_cards} cards+questions")
    if a.deploy:
        return deploy()
    return 0


MIDDLEWARE = ROOT / "functions" / "_middleware.js"  # the guard: no X-Learnai-Key header, no content
MARKER = "learn-ai: key required"  # what the guard answers; must match MIDDLEWARE


def is_private(url: str) -> bool:
    """True only if an anonymous request gets the guard's own refusal (401 + MARKER).
    Any other refusal proves nothing: Cloudflare's bot filter sends a 403 to unknown clients even on a public site."""
    req = urllib.request.Request(f"{url}/library.json", headers={"User-Agent": "Mozilla/5.0 (learn-ai privacy check)"})
    try:
        urllib.request.urlopen(req, timeout=20)
        return False  # 200: anyone can read it
    except urllib.error.HTTPError as e:
        return e.code == 401 and MARKER in e.read().decode(errors="replace")
    except OSError:
        return False  # can't tell: treat as not private


def deploy() -> int:
    """Upload site/ together with the guard (functions/, picked up from the working dir), then check that every
    address of the new deployment refuses anonymous requests. The summaries quote copyrighted books: never public."""
    if MARKER not in MIDDLEWARE.read_text(encoding="utf-8"):
        print(f"NOT deploying: {MIDDLEWARE} is missing or doesn't answer {MARKER!r}.")
        return 3
    r = subprocess.run(["wrangler", "pages", "deploy", str(SITE), "--project-name", PROJECT, "--branch", "main"],
                       capture_output=True, text=True, cwd=ROOT)
    print((r.stdout + r.stderr).strip()[-1500:])
    if r.returncode:
        return r.returncode
    # Each deployment also gets its own <hash>.<project>.pages.dev address: check it too.
    for url in sorted({URL, *re.findall(r"https://[a-z0-9.-]+\.pages\.dev", r.stdout + r.stderr)}):
        # A new deployment's address takes a few seconds to come online; until then the check can't tell.
        for _ in range(6):
            if is_private(url):
                break
            time.sleep(5)
        else:
            print(f"WARNING: {url} did not refuse a request without the key (or could not be reached for 30 s). "
                  "Check it with curl; if it serves content, delete this deployment in the Cloudflare dashboard "
                  "(Workers & Pages > learn-ai > Deployments) and check functions/_middleware.js.")
            return 4
        print(f"private: {url}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
