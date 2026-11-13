"""Write the phone's highlights & notes (stored on Cloudflare KV by the app) as readable Markdown on the laptop.

    .venv/bin/python notes.py

Books from this pipeline -> books/<book>/highlights.md, grouped by chapter (chapters.json);
books only imported on the phone -> highlights/<book id>.md. Reviews (books, movies & series; Movies.kt) -> reviews.md.
Reads KV with wrangler (logged in), not the app key.
"""
import json
import subprocess
import sys
import tomllib
from pathlib import Path

from publish import BOOKS, ROOT, slug

NAMESPACE = tomllib.loads((ROOT / "wrangler.toml").read_text())["kv_namespaces"][0]["id"]


def kv(*args: str) -> str:
    r = subprocess.run(["wrangler", "kv", "key", *args, "--namespace-id", NAMESPACE, "--remote"],
                       capture_output=True, text=True, cwd=ROOT)
    if r.returncode:
        sys.exit(f"wrangler kv {' '.join(args)} failed:\n{(r.stdout + r.stderr)[-1500:]}")
    return r.stdout


def markdown(title: str, highlights: list, chapters: list) -> str:
    """chapters: chapters.json entries (n, title, href); a highlight belongs to the last one with its href."""
    def chapter(h):
        return next((c for c in reversed(chapters) if (c.get("href") or "").split("#")[0] == h["href"]), None)
    rows = sorted(highlights, key=lambda h: ((chapter(h) or {}).get("n", 10**9), h["progression"], h["created"]))
    out, current = [f"# {title}: highlights & notes\n"], object()
    for h in rows:
        c = chapter(h)
        if c is not current:
            out.append(f"\n## {c['n']}. {c['title']}\n" if c else f"\n## {h['href']}\n")
            current = c
        out.append("\n" + "\n".join("> " + line for line in h["text"].strip().splitlines()) + "\n")
        if h.get("note", "").strip():
            out.append(f"\n**Note:** {h['note'].strip()}\n")
    return "".join(out)


BOOK_ASPECTS = [("writing", "Writing"), ("ideas", "Ideas"), ("characters", "Characters"), ("plot", "Plot")]
MOVIE_ASPECTS = [("characters", "Characters"), ("plot", "Plot"), ("setting", "Setting"), ("meaning", "Meaning")]


def review_md(heading: str, r: dict, aspects: list) -> str:
    """One review: score, 1-5 aspects with text, opinions. Empty string if there's nothing written or rated."""
    parts = []
    for key, label in aspects:
        a = r.get("aspects", {}).get(key, {})
        if a.get("score") or a.get("text", "").strip():
            parts.append(f"**{label}** {'★' * a.get('score', 0)}\n{a.get('text', '').strip()}".strip())
    if r.get("opinion", "").strip():
        parts.append(r["opinion"].strip())
    if not parts and not r.get("score"):
        return ""
    score = f" — {r['score']}/10" if r.get("score") else ""
    return f"\n### {heading}{score}\n\n" + "\n\n".join(parts) + "\n"


def reviews_markdown(data: dict, book_titles: dict) -> str:
    """The app's reviews store (KV "_movies"): book reviews, then movies & series, best score first."""
    out = ["# Reviews\n", "\n## Books\n"]
    books = sorted(data.get("books", {}).items(), key=lambda kv: -kv[1].get("score", 0))
    out += [review_md(book_titles.get(bid, bid), r, BOOK_ASPECTS) for bid, r in books]
    out.append("\n## Movies & TV\n")
    for t in sorted(data.get("titles", []), key=lambda t: (-t.get("score", 0), t["name"].lower())):
        kind = "series" if t.get("series") else "movie"
        tags = " ".join(f"#{x}" for x in t.get("tags", []))
        out.append(review_md(f"{t['name']} ({kind}){' ' + tags if tags else ''}{' ⭐' if t.get('favorite') else ''}", t, MOVIE_ASPECTS))
    return "".join(out)


def main() -> int:
    books = {slug(p.name): p for p in BOOKS.iterdir() if (p / "chapters.json").exists()}
    keys = json.loads(kv("list"))
    if any(k["name"] == "_movies" for k in keys):
        titles = {slug(p.name): p.name for p in BOOKS.iterdir()}
        (ROOT / "reviews.md").write_text(reviews_markdown(json.loads(kv("get", "_movies", "--text")), titles), encoding="utf-8")
        print("reviews -> reviews.md")
    if not keys:
        print("no highlights stored yet (the app uploads a book's list after its first highlight)")
    for key in keys:
        book_id = key["name"]
        if book_id.startswith("_"):
            continue  # not a book: "_focus" holds the focus guard's sessions (functions/focus.js)
        # deleted = a tombstone the app keeps so the deletion reaches its other devices (Books.kt mergeHighlights)
        highlights = [h for h in json.loads(kv("get", book_id, "--text") or "[]") if not h.get("deleted")]
        if book := books.get(book_id):
            chapters = json.loads((book / "chapters.json").read_text(encoding="utf-8"))["chapters"]
            path, title = book / "highlights.md", book.name
        else:
            path, title, chapters = ROOT / "highlights" / f"{book_id}.md", book_id, []
        path.parent.mkdir(exist_ok=True)
        path.write_text(markdown(title, highlights, chapters), encoding="utf-8")
        print(f"{len(highlights)} highlights -> {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
