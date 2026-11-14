"""Write the phone's highlights & notes (stored on Cloudflare KV by the app) as readable Markdown on the laptop.

    .venv/bin/python notes.py

Books from this pipeline -> books/<book>/highlights.md, grouped by chapter (chapters.json);
books only imported on the phone -> highlights/<book id>.md. Reviews (books, movies & series; Movies.kt) -> reviews.md,
and one note per review in the Obsidian vault ($OBSIDIAN_VAULT, default ~/Obsidian): Reviews/Books/, Reviews/Movies & TV/, Reviews/Lists/ (watch lists, linking the reviews),
with frontmatter (score, tags, dates) for Bases / Dataview. Those notes are rewritten each run: edit reviews in the app.
`notes.py --import` does the reverse once: your own (unmarked) notes in Reviews/Movies & TV/ become rated titles in the app.
Reads KV with wrangler (logged in), not the app key.
"""
import json
import os
import re
import subprocess
import sys
import tomllib
import uuid
from pathlib import Path

from datetime import datetime

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
MOVIE_ASPECTS = [("characters", "Characters"), ("acting", "Acting"), ("plot", "Plot"), ("setting", "Setting"),
                 ("meaning", "Meaning")]


def review_body(r: dict, aspects: list) -> str:
    """1-5 aspects with text, then opinions."""
    parts = []
    for key, label in aspects:
        a = r.get("aspects", {}).get(key, {})
        if a.get("score") or a.get("text", "").strip():
            parts.append(f"**{label}** {'★' * a.get('score', 0)}\n{a.get('text', '').strip()}".strip())
    if r.get("opinion", "").strip():
        parts.append(r["opinion"].strip())
    return "\n\n".join(parts)


def review_md(heading: str, r: dict, aspects: list) -> str:
    """One review: score, aspects, opinions. Empty string if there's nothing written or rated."""
    body = review_body(r, aspects)
    if not body and not r.get("score"):
        return ""
    score = f" — {r['score']}/10" if r.get("score") else ""
    return f"\n### {heading}{score}\n\n{body}\n"


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


MARK = "source: learn_ai"  # in every generated note's frontmatter: only those are replaced or pruned


def day(ms) -> str | None:
    return datetime.fromtimestamp(ms / 1000).date().isoformat() if ms else None


def frontmatter(**props) -> str:
    """YAML properties; empty ones left out. Strings via JSON, which is valid YAML."""
    out = ["---"]
    for k, v in props.items():
        if v in (None, "", [], 0, False):
            continue
        if isinstance(v, list):
            out += [f"{k}:"] + [f"  - {json.dumps(x, ensure_ascii=False)}" for x in v]
        else:
            out.append(f"{k}: {json.dumps(v, ensure_ascii=False)}")
    return "\n".join(out + [MARK, "---", ""])


def obsidian_notes(data: dict, book_titles: dict) -> dict:
    """Vault-relative path -> note: one per review (same "is it a review" rule as reviews.md), one per watch list."""
    notes, review_of = {}, {}  # review_of: title id -> its note's path, for the lists' links

    def add(folder: str, name: str, props: dict, body: str) -> str:
        base = re.sub(r'[\\/:*?"<>|#^\[\]]', "", name).strip() or "Untitled"  # characters Obsidian rejects in names
        path, n = f"Reviews/{folder}/{base}.md", 2
        while path in notes:  # two titles with the same name
            path, n = f"Reviews/{folder}/{base} {n}.md", n + 1
        notes[path] = frontmatter(**props) + f"\n# {name}\n\n{body}\n"
        return path

    for bid, r in sorted(data.get("books", {}).items()):
        body = review_body(r, BOOK_ASPECTS)
        if body or r.get("score"):
            title = book_titles.get(bid, bid)
            add("Books", title, dict(type="book", score=r.get("score"), shelf=r.get("status"), started=day(r.get("started")),
                                     finished=day(r.get("finished")), tags=["review/book"]), body)
    for t in sorted(data.get("titles", []), key=lambda t: (t.get("created", 0), t["id"])):
        body = review_body(t, MOVIE_ASPECTS)
        if body or t.get("score"):
            kind = "series" if t.get("series") else "movie"
            review_of[t["id"]] = add("Movies & TV", t["name"], dict(
                type=kind, score=t.get("score"), favorite=t.get("favorite"), runtime=t.get("runtime"),
                watched=[day(v) for v in t.get("views", [])],
                tags=[f"review/{kind}"] + [x.replace(" ", "-") for x in t.get("tags", [])]), body)  # no spaces in tags
    titles = {t["id"]: t for t in data.get("titles", [])}
    for l in sorted(data.get("lists", []), key=lambda l: (l.get("created", 0), l["id"])):
        ts = [titles[i] for i in l.get("titles", []) if i in titles]
        rows = []
        for t in ts:
            name = f"[[{review_of[t['id']][:-3]}|{t['name']}]]" if t["id"] in review_of else t["name"]
            info = ["series" if t.get("series") else "movie"]
            if t.get("series") and (t.get("episodes") or t.get("totalEpisodes")):  # as the app's episodesText
                info.append(f"S{t.get('season', 1)} · ep {t.get('episodes', 0)}" + (f"/{t['totalEpisodes']}" if t.get("totalEpisodes") else ""))
            if t.get("score"):
                info.append(f"{t['score']}/10")
            rows.append(f"- [{'x' if t.get('watched') else ' '}] {name} ({', '.join(info)})")
        watched = sum(1 for t in ts if t.get("watched"))
        add("Lists", l["name"], dict(type="list", titles=len(ts), watched=watched, tags=["review/list"]),
            f"{watched} / {len(ts)} watched\n\n" + "\n".join(rows))
    return notes


def write_vault(vault: Path, notes: dict) -> None:
    """Writes the notes; removes generated notes whose review is gone or renamed. Other files are left alone."""
    for old in (vault / "Reviews").rglob("*.md"):
        if old.relative_to(vault).as_posix() not in notes and f"\n{MARK}\n" in old.read_text(encoding="utf-8"):
            old.unlink()
    for rel, text in notes.items():
        f = vault / rel
        if f.exists() and f"\n{MARK}\n" not in f.read_text(encoding="utf-8"):
            print(f"kept your own note {rel}: not overwritten")
            continue
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(text, encoding="utf-8")


def read_note(text: str) -> tuple[dict, str]:
    """Frontmatter (flat keys, "- item" lists, JSON-or-bare scalars) and the "# " heading."""
    props, key = {}, None
    head, _, body = text.removeprefix("---\n").partition("\n---\n")
    for line in head.splitlines():
        if line.startswith("  - ") and key:
            props.setdefault(key, []).append(json.loads(line[4:]) if line[4:].startswith('"') else line[4:])
        elif ":" in line:
            key, v = (s.strip() for s in line.split(":", 1))
            if v:
                try:
                    props[key] = json.loads(v)
                except ValueError:
                    props[key] = v
    title = next((l[2:].strip() for l in body.splitlines() if l.startswith("# ")), "")
    return props, title


def import_vault(data: dict, folder: Path, now: int) -> tuple[dict, list[Path]]:
    """Your own notes in Reviews/Movies & TV/ (no MARK) -> new rated titles in the app's store; titles already there
    (same name, any case) and notes without a score are skipped. Scores: 1-10 in half points."""
    have = {t["name"].lower() for t in data.get("titles", [])}
    titles, done = list(data.get("titles", [])), []
    for i, f in enumerate(sorted(folder.glob("*.md"))):
        text = f.read_text(encoding="utf-8")
        if f"\n{MARK}\n" in text:
            continue
        props, name = read_note(text)
        name = name or f.stem
        if name.lower() in have or not props.get("score"):
            print(f"skipped {f.name}: {'already in the app' if name.lower() in have else 'no score'}")
            continue
        tags = [t.lower() for t in props.get("tags", []) if not t.startswith("review/")]
        titles.append(dict(id=str(uuid.uuid4()), name=name, series=props.get("type") == "series",
                           score=max(1, min(10, round(float(props["score"]) * 2) / 2)), tags=tags, created=now + i, updated=now))
        have.add(name.lower())
        done.append(f)
    return {**data, "titles": titles}, done


def import_main(vault: Path) -> int:
    """notes.py --import: upload the vault's own review notes to the app, then mark them as generated."""
    keys = json.loads(kv("list"))
    data = json.loads(kv("get", "_movies", "--text")) if any(k["name"] == "_movies" for k in keys) else {}
    new, done = import_vault(data, vault / "Reviews" / "Movies & TV", int(datetime.now().timestamp() * 1000))
    if not done:
        print("nothing to import")
        return 0
    tmp = ROOT / ".movies_import.json"
    tmp.write_text(json.dumps(new, ensure_ascii=False), encoding="utf-8")
    kv("put", "_movies", "--path", str(tmp))
    tmp.unlink()
    for f in done:  # the app owns them now: the next notes.py run rewrites them from the app
        f.write_text(f.read_text(encoding="utf-8").replace("\n---\n", f"\n{MARK}\n---\n", 1), encoding="utf-8")
    print(f"{len(done)} titles -> app (the phone gets them on its next sync)")
    return 0


def main() -> int:
    if "--import" in sys.argv:
        return import_main(Path(os.environ.get("OBSIDIAN_VAULT", Path.home() / "Obsidian")))
    books = {slug(p.name): p for p in BOOKS.iterdir() if (p / "chapters.json").exists()}
    keys = json.loads(kv("list"))
    if any(k["name"] == "_movies" for k in keys):
        titles = {slug(p.name): p.name for p in BOOKS.iterdir()}
        data = json.loads(kv("get", "_movies", "--text"))
        (ROOT / "reviews.md").write_text(reviews_markdown(data, titles), encoding="utf-8")
        print("reviews -> reviews.md")
        vault = Path(os.environ.get("OBSIDIAN_VAULT", Path.home() / "Obsidian"))
        if (vault / ".obsidian").is_dir():
            notes = obsidian_notes(data, titles)
            write_vault(vault, notes)
            print(f"{len(notes)} notes (reviews, lists) -> {vault}/Reviews/")
        else:
            print(f"no Obsidian vault at {vault} (set OBSIDIAN_VAULT): skipped the review notes")
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
