"""Phase 2: split each converted book into chapters.

    ~/Workspace/papers/.venv-marker/bin/python split.py [books/<stem> ...] [--force]
Default: every book in books/ whose check.json PASSed and that has no chapters.json yet.
Writes books/<stem>/chapters/NN.md (kept chapters, page markers and anchor spans removed) and
books/<stem>/chapters.json (every segment, kept or skipped, and why).

Chapter boundaries, best first:
  1. the book's own table of contents (EPUB toc.ncx / nav, PDF bookmarks), located on a matching heading
  2. numbered top-level headings ("1. Introduction", "Chapter 3"), for books without a TOC
  3. fixed ~3000-word chunks at paragraph breaks, when the book has no usable structure
plus: an Introduction/Preface heading before the first chapter, and References/Bibliography/Index headings
anywhere, always start their own segment (the thesis TOC omits its Introduction, its last chapter runs
into the bibliography).
"""
import argparse
import bisect
import json
import posixpath
import re
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path
from urllib.parse import unquote

from convert import BOOKS, PAGE_MARK

MIN_TOP_ENTRIES = 3  # a TOC level / heading pattern with fewer entries than this is not a chapter list
MIN_CHAPTER_WORDS = 150  # shorter segments (title pages, epigraphs) are merged into the next chapter
CHUNK_WORDS = 3000
LONG_TOKENS = 6000  # phase 3 must map-reduce chapters above this
# ponytail: ~1.4 tokens/word (Qwen, EN/IT prose); count with the real tokenizer in phase 3 if it matters
TOKENS_PER_WORD = 1.4

SKIP = re.compile(r"(table of )?contents?|indice|sommario|index|indice analitico|references|bibliography|"
                  r"bibliografia|riferimenti bibliografici|notes?|acknowledge?ments?|ringraziamenti|"
                  r"copyright|cover|copertina|front matter")
BACK = re.compile(r"references|bibliography|bibliografia|riferimenti bibliografici|index|indice analitico|"
                  r"acknowledge?ments?|ringraziamenti")
INTRO = re.compile(r"introduction|introduzione|preface|prefazione|premessa|foreword|prologue|prologo")
HEADING = re.compile(r"^#{1,6}[ \t]+(.+)$", re.M)
CHAPTER_ONLY = re.compile(r"(chapter|capitolo)\s+\w+", re.I)


def plain(t: str) -> str:
    """Heading/TOC text without markdown/html decoration."""
    t = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", t)  # [text](link) -> text
    return " ".join(re.sub(r"<[^>]*>|[*_`]", "", unicodedata.normalize("NFKC", t)).split())


def norm(t: str) -> str:
    """Comparable title: no numbering ("2.1", "Chapter 3"), no punctuation, lowercase."""
    t = re.sub(r"^((chapter|capitolo)\s+\w+|\d+(\.\d+)*)\.?\s*", "", plain(t), flags=re.I)
    return " ".join(re.sub(r"[^\w\s]", " ", t.lower()).split())


def same_title(heading: str, title: str) -> bool:
    if not heading or not title:
        return False
    if heading == title:
        return True
    short, long_ = sorted((heading, title), key=len)  # TOC entries get truncated or gain subtitles
    return len(short) >= 8 and long_.startswith(short)


# ---------- table of contents ----------

# TOC entries: (level, title, page, href). PDFs: page index, href None. EPUBs: page None, href = the
# chapter's path inside the EPUB from its root, "#fragment" kept ("OEBPS/Text/Section0005.htm"): the app's
# Readium reader opens the chapter by matching this against its own table of contents.

def pdf_toc(src: Path) -> list[tuple[int, str, int | None, None]]:
    import pypdfium2 as pdfium

    out = []
    for b in pdfium.PdfDocument(src).get_toc():
        dest = b.get_dest()
        out.append((b.level, b.get_title(), dest.get_index() if dest else None, None))
    return out


def resolve(base_file: str, href: str | None) -> str | None:
    """A TOC href (relative to the toc file) -> path from the EPUB root, fragment kept."""
    if not href:
        return None
    path, _, frag = unquote(href).partition("#")
    full = posixpath.normpath(posixpath.join(posixpath.dirname(base_file), path)) if path else base_file
    return full + (f"#{frag}" if frag else "")


def epub_toc(src: Path) -> list[tuple[int, str, None, str | None]]:
    with zipfile.ZipFile(src) as z:
        try:
            ncx = next((n for n in z.namelist() if n.endswith(".ncx")), None)
            if ncx:  # EPUB 2 (and most EPUB 3)
                def walk_ncx(el, lvl):
                    for point in el.findall("{*}navPoint"):
                        content = point.find("{*}content")
                        yield (lvl, "".join(point.find("{*}navLabel/{*}text").itertext()), None,
                               resolve(ncx, content.get("src") if content is not None else None))
                        yield from walk_ncx(point, lvl + 1)

                return list(walk_ncx(ET.fromstring(z.read(ncx)).find("{*}navMap"), 0))
            for name in z.namelist():  # EPUB 3 only: <nav epub:type="toc"><ol><li><a>
                data = z.read(name)
                if name.endswith((".xhtml", ".html", ".htm")) and b'epub:type="toc"' in data:
                    nav = next(e for e in ET.fromstring(data).iter()
                               if e.tag.endswith("nav") and "toc" in e.get("{http://www.idpf.org/2007/ops}type", ""))

                    def walk_nav(ol, lvl):
                        for li in ol.findall("{*}li"):
                            label = li.find("{*}a")
                            label = li.find("{*}span") if label is None else label
                            if label is not None:
                                yield lvl, "".join(label.itertext()), None, resolve(name, label.get("href"))
                            for sub in li.findall("{*}ol"):
                                yield from walk_nav(sub, lvl + 1)

                    return [e for ol in nav.findall("{*}ol") for e in walk_nav(ol, 0)]
        except (ET.ParseError, AttributeError, StopIteration):
            return []  # malformed TOC: fall back to headings
    return []


def chapter_level(toc):
    """Shallowest TOC level that is an actual chapter list (skips a lone root or 'Part I/II')."""
    for lvl in sorted({e[0] for e in toc}):
        entries = [(title, page, href) for level, title, page, href in toc if level == lvl]
        if len(entries) >= MIN_TOP_ENTRIES:
            return entries
    return []


# ---------- locating boundaries in the markdown ----------

def locate(md, entries, page_at, heads, hrefs: dict):
    """TOC entries -> (offset, title); fills hrefs[offset] for EPUB entries.
    PDF entries carry a page: search near it, else use the page start."""
    out, pos = [], 0
    for title, page, href in entries:
        t = norm(title)
        lo = max(pos, page_at.get(page, pos)) if page is not None else pos
        hi = page_at.get(page + 2, len(md)) if page is not None else len(md)
        i = next((i for i, (o, _, h) in enumerate(heads) if lo <= o < hi and same_title(h, t)), None)
        if i is None:
            off = page_at.get(page) if page is not None else None  # EPUB entry without heading: dropped
        else:
            if i > 0 and CHAPTER_ONLY.fullmatch(heads[i - 1][1]) and not \
                    re.sub(r"<[^>]*>", "", md[heads[i - 1][0]:heads[i][0]].split("\n", 1)[-1]).strip():
                i -= 1  # "# Chapter 1" right above "## Temporal variability": start at "Chapter 1"
            off = heads[i][0]
        if off is not None and off >= pos:
            out.append((off, plain(title)))
            if href:
                hrefs[off] = href
            pos = off
    return out


def numbered(heads):
    """'1. Introduction', '2 Methods', 'Chapter 3' headings in sequence 1, 2, 3..."""
    out, want = [], 1
    for off, raw, _ in heads:
        m = re.match(r"(?:(?:chapter|capitolo)\s+)?(\d+)\.?(?:\s|$)", raw, re.I)
        if m and int(m.group(1)) == want:
            out.append((off, raw))
            want += 1
    return out if len(out) >= MIN_TOP_ENTRIES else []


def chunked(md):
    out, count, last = [(0, "Part 1")], 0, 0
    for m in re.finditer(r"\n\s*\n", md):
        count += len(md[last:m.end()].split())
        last = m.end()
        if count >= CHUNK_WORDS:
            out.append((m.end(), f"Part {len(out) + 1}"))
            count = 0
    return out


def clean(text: str) -> str:
    text = PAGE_MARK.sub("", text)
    text = re.sub(r"<span id=\"[^\"]*\"></span>", "", text)  # marker/pandoc anchors
    return re.sub(r"\n\s*\n", "\n\n", text).strip() + "\n"  # \s also eats &nbsp;-only lines


# ---------- per book ----------

def split(book: Path) -> dict:
    report = json.loads((book / "check.json").read_text(encoding="utf-8"))
    src = Path(report["source"])
    md = (book / f"{book.name}.md").read_text(encoding="utf-8")
    is_pdf = src.suffix.lower() == ".pdf"

    page_at = {int(m.group(1)): m.start() for m in PAGE_MARK.finditer(md)}
    heads = [(m.start(), plain(m.group(1)), norm(m.group(1))) for m in HEADING.finditer(md)]
    toc = pdf_toc(src) if is_pdf else epub_toc(src)
    hrefs: dict[int, str] = {}  # offset -> EPUB href of the TOC entry starting there
    bounds, method = locate(md, chapter_level(toc), page_at, heads, hrefs), "toc"
    if len(bounds) < MIN_TOP_ENTRIES:
        bounds, method = numbered(heads), "numbered headings"
    if not bounds:
        bounds, method = chunked(md), "chunks"

    first = bounds[0][0]
    extra = [(o, raw) for o, raw, n in heads if BACK.fullmatch(n) and o > first]
    intro = [(o, raw) for o, raw, n in heads if INTRO.fullmatch(n) and o < first]
    starts = dict(sorted(bounds + extra + intro[-1:], reverse=True))  # one title per offset, TOC wins
    starts = sorted(starts.items())
    if starts[0][0] > 0:
        starts.insert(0, (0, "front matter"))

    pages = sorted(page_at.items(), key=lambda kv: kv[1])
    page_offsets = [o for _, o in pages]

    def page_of(off):
        i = bisect.bisect_right(page_offsets, off) - 1
        return pages[i][0] + 1 if i >= 0 else 1

    out_dir = book / "chapters"
    out_dir.mkdir(exist_ok=True)
    for old in out_dir.glob("*.md"):  # only the text; phase 3 results (*.json) stay
        old.unlink()

    chapters, skipped, carry, carry_from, carried = [], [], "", None, []
    for (a, title), (b, _) in zip(starts, starts[1:] + [(len(md), "")]):
        text = clean(md[a:b])
        n_words = len(text.split())
        if SKIP.fullmatch(norm(title)):
            skipped.append({"title": title, "words": n_words, "reason": "front/back matter"})
            continue
        if n_words + len(carry.split()) < MIN_CHAPTER_WORDS:
            carried.append({"title": title, "words": n_words, "reason": "short: merged into the next chapter"})
            skipped.append(carried[-1])
            carry, carry_from = carry + text + "\n", carry_from or a
            continue
        chapters.append({"title": title, "text": carry + text, "start": carry_from or a, "end": b,
                         "href": hrefs.get(a)})  # the segment's own TOC entry, not a merged title page's
        carry, carry_from, carried = "", None, []
    if carry and chapters:  # short tail (e.g. an empty last chapter before the bibliography)
        chapters[-1]["text"] += "\n" + carry
        for s in carried:
            s["reason"] = "short: merged into the previous chapter"

    for n, ch in enumerate(chapters, 1):
        ch["n"], ch["file"] = n, f"chapters/{n:02d}.md"
        (book / ch["file"]).write_text(ch.pop("text"), encoding="utf-8")
        ch["words"] = len((book / ch["file"]).read_text(encoding="utf-8").split())
        ch["tokens_est"] = round(ch["words"] * TOKENS_PER_WORD)
        ch["long"] = ch["tokens_est"] > LONG_TOKENS
        if is_pdf:
            ch["pages"] = [page_of(ch["start"]), page_of(max(ch["start"], ch["end"] - 1))]
        if ch["href"] is None:
            del ch["href"]
        del ch["start"], ch["end"]

    result = {"book": book.name, "source": str(src), "method": method, "chapters": chapters, "skipped": skipped}
    (book / "chapters.json").write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding="utf-8")
    return result


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("books", nargs="*", type=Path)
    ap.add_argument("--force", action="store_true", help="re-split even if chapters.json exists, or check FAILed")
    a = ap.parse_args()

    todo = a.books or sorted(p for p in BOOKS.iterdir() if (p / "check.json").exists())
    status = 0
    for book in todo:
        check = json.loads((book / "check.json").read_text(encoding="utf-8"))
        if check["status"] != "PASS" and not a.force:
            print(f"SKIP  {book.name}: conversion check {check['status']} (fix it, or --force)")
            status = 1
            continue
        if (book / "chapters.json").exists() and not (a.force or a.books):
            continue
        r = split(book)
        print(f"{book.name}: {len(r['chapters'])} chapters by {r['method']}")
        for ch in r["chapters"]:
            pages = f"  p.{ch['pages'][0]}-{ch['pages'][1]}" if "pages" in ch else ""
            print(f"  {ch['n']:2d}. {ch['title'][:60]:60s} {ch['words']:6d} w{pages}{'  LONG' if ch['long'] else ''}")
        for s in r["skipped"]:
            print(f"   -  {s['title'][:60]:60s} {s['words']:6d} w  ({s['reason']})")
    return status


if __name__ == "__main__":
    sys.exit(main())
