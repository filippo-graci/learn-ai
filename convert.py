"""Book -> Markdown, then check the Markdown against the source text.

  .pdf   marker (GPU, OCR for scanned pages); checked page by page against the PDF text layer
  .epub  pandoc (seconds, no GPU); checked file by file against the EPUB's own HTML

Run with the marker venv (it has marker + pypdfium2; EPUB needs only pandoc + stdlib):
    ~/Workspace/papers/.venv-marker/bin/python convert.py [book.pdf|book.epub ...] [--force] [--check-only]
No files given: every book in inbox/ without books/<stem>/<stem>.md yet.
Output: books/<stem>/<stem>.md, images, check.json. Exit code 1 if any book FAILs.
"""
import argparse
import json
import os
import posixpath
import re
import subprocess
import sys
import unicodedata
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote

from quiet import MESSAGE as QUIET_MESSAGE, in_quiet

ROOT = Path(__file__).resolve().parent
INBOX, BOOKS = ROOT / "inbox", ROOT / "books"
FORMATS = (".pdf", ".epub")
MARKER = os.environ.get("MARKER_BIN", str(Path(sys.executable).with_name("marker_single")))
# Fit marker 2 on an 8 GB card: by default its vLLM container grabs 85 % of VRAM and the
# torch OCR-error model then OOMs. 55 % peaked at 5.3 GB in testing. Env vars override.
# ponytail: vLLM boots once per book (~4 min); batch many books with SURYA_INFERENCE_KEEP_ALIVE if it hurts.
MARKER_ENV = {"VLLM_GPU_MEMORY_UTILIZATION": "0.55", "VLLM_GPU_TYPE": "t4", "VLLM_MAX_MODEL_LEN": "16384"}

# Thresholds: first guesses, tune them at the phase 1 gate after reading the low pages by hand.
MIN_DOC_COVERAGE = 0.95  # share of source words that must appear somewhere in the md
MIN_PAGE_COVERAGE = 0.80  # below this a page/file is listed as "low" (warning)
MISSING_COVERAGE = 0.50  # below this a page/file counts as lost (FAIL)
MIN_WORDS = 20  # fewer words than this = page has (almost) no text
MAX_BLANK_SHARE = 0.10  # PDF only: more blank pages than this = OCR probably failed

PAGE_MARK = re.compile(r"^\{(\d+)\}-{48}$", re.M)  # marker --paginate_output separator, 0-based
WORD = re.compile(r"[^\W\d_]{3,}")  # letters only, 3+ chars: skips numbers and math noise
BLOCK_TAGS = {"p", "div", "br", "li", "tr", "td", "th", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "section"}


def words(text: str) -> Counter:
    text = unicodedata.normalize("NFKC", text).replace("￾", "")  # ligatures; pdfium's line-break hyphen
    text = re.sub(r"[-­\x02]\s*\n\s*", "", text)  # join words hyphenated at line breaks
    return Counter(w.lower() for w in WORD.findall(text))


def clean_md(md: str) -> str:
    md = re.sub(r"!\[[^\]]*\]\([^)]*\)", " ", md)  # image links (file names are not content)
    md = re.sub(r"</?[a-zA-Z][^<>]*>", " ", md)  # html tags (<sup>, <br>, <span id=..>), not math "a < b > c"
    return re.sub(r"\\[a-zA-Z]+", " ", md)  # latex commands: \frac, \alpha


def md_pages(md: str, n_pages: int) -> list[str]:
    parts = PAGE_MARK.split(md)  # ["", "0", text0, "1", text1, ...]
    pages = [""] * n_pages
    for pid, text in zip(parts[1::2], parts[2::2]):
        if int(pid) < n_pages:
            pages[int(pid)] += text
    return pages


class _HtmlText(HTMLParser):
    """Visible text of an (X)HTML file: stdlib only, skips <head>/<script>/<style>."""
    SKIP = {"head", "script", "style"}

    def __init__(self):
        super().__init__()
        self.out, self.skip = [], 0

    def handle_starttag(self, tag, attrs):
        self.skip += tag in self.SKIP
        if tag in BLOCK_TAGS:
            self.out.append(" ")

    def handle_endtag(self, tag):
        self.skip -= tag in self.SKIP
        if tag in BLOCK_TAGS:
            self.out.append(" ")

    def handle_data(self, data):
        if not self.skip:
            self.out.append(data)


def epub_texts(path: Path):
    """(href, visible text) of every spine document, in reading order."""
    with zipfile.ZipFile(path) as z:
        opf_path = ET.fromstring(z.read("META-INF/container.xml")).find(".//{*}rootfile").get("full-path")
        opf = ET.fromstring(z.read(opf_path))
        hrefs = {i.get("id"): i.get("href") for i in opf.findall(".//{*}item")}
        for ref in opf.findall(".//{*}itemref"):
            href = posixpath.normpath(posixpath.join(posixpath.dirname(opf_path), unquote(hrefs[ref.get("idref")])))
            p = _HtmlText()
            p.feed(z.read(href).decode("utf-8", "replace"))
            yield href, "".join(p.out)


def units(src: Path, md: str):
    """(label, source words, md words to compare against) per page (PDF) or spine file (EPUB)."""
    if src.suffix.lower() == ".epub":  # pandoc keeps no file boundaries: check each file against the whole md
        md_words = words(clean_md(md))
        return [(href, words(text), md_words) for href, text in epub_texts(src)]
    import pypdfium2 as pdfium  # only the PDF route needs it

    doc = pdfium.PdfDocument(src)
    mds = md_pages(md, len(doc))
    return [(i + 1, words(doc[i].get_textpage().get_text_range()), words(clean_md(mds[i])))
            for i in range(len(doc))]


def check(src: Path, md_path: Path) -> dict:
    is_pdf = src.suffix.lower() == ".pdf"
    md = md_path.read_text(encoding="utf-8")
    all_src, pages = Counter(), []
    for label, raw, md_words in units(src, md):
        all_src += raw
        n_raw, n_md, hit = sum(raw.values()), sum(md_words.values()), sum((raw & md_words).values())
        if n_raw >= MIN_WORDS:
            cov = hit / n_raw
            status = ("missing" if n_md < MIN_WORDS or cov < MISSING_COVERAGE
                      else "low" if cov < MIN_PAGE_COVERAGE else "ok")
        else:  # no text: scanned page (OCR'd, can't verify), or a real blank/figure/cover page
            cov, status = None, ("ocr" if is_pdf and n_md >= MIN_WORDS else "blank")
        pages.append({"page": label, "status": status, "src_words": n_raw, "md_words": n_md,
                      "coverage": None if cov is None else round(cov, 3)})

    n_src = sum(all_src.values())
    doc_cov = sum((all_src & words(clean_md(md))).values()) / n_src if n_src else None
    count = Counter(p["status"] for p in pages)
    problems = []
    if doc_cov is not None and doc_cov < MIN_DOC_COVERAGE:
        problems.append(f"document coverage {doc_cov:.3f} < {MIN_DOC_COVERAGE}")
    if count["missing"]:
        problems.append(f"{count['missing']} pages/files have source text but (almost) none in the markdown")
    if is_pdf and count["blank"] > MAX_BLANK_SHARE * len(pages):
        problems.append(f"{count['blank']}/{len(pages)} pages blank (OCR failed?)")
    if doc_cov is None and not count["ocr"]:
        problems.append("no text at all")
    return {
        "source": str(src), "markdown": str(md_path), "pages": len(pages),
        "status": "FAIL" if problems else "PASS", "problems": problems,
        "doc_coverage": None if doc_cov is None else round(doc_cov, 3),
        "page_status": dict(count), "page_detail": pages,
    }


def convert_pdf(src: Path) -> Path:
    # marker writes to <output_dir>/<stem>/<stem>.md
    subprocess.run([MARKER, str(src), "--output_dir", str(BOOKS),
                    "--output_format", "markdown", "--paginate_output", "--disable_tqdm"],
                   check=True, env={**MARKER_ENV, **os.environ})
    return BOOKS / src.stem / f"{src.stem}.md"


def convert_epub(src: Path) -> Path:
    out = BOOKS / src.stem
    out.mkdir(parents=True, exist_ok=True)
    md = out / f"{src.stem}.md"
    # gfm-raw_html: Calibre/Sigil EPUBs carry hundreds of <span class="font"> that would reach the LLM
    # as noise and hide headings; images land under out/ with relative links
    subprocess.run(["pandoc", str(src.resolve()), "-t", "gfm-raw_html", "--wrap=none",
                    "--extract-media=.", "-o", md.name], cwd=out, check=True)
    md.write_text(re.sub(r"<svg\b.*?</svg>", "", md.read_text(encoding="utf-8"), flags=re.S),
                  encoding="utf-8")  # inline SVG covers survive -raw_html
    return md


def free_gpu() -> None:
    """surya spawns its model servers detached; only the spawning process kills them on exit.
    If a run dies by signal they linger (1+ GB VRAM each) and later runs silently reuse them.
    ponytail: kills every surya server, also another project's marker run; the GPU lock prevents overlap."""
    subprocess.run(["pkill", "-f", r"-m surya\.[a-z_]+\.server"], check=False)
    ids = subprocess.run(["docker", "ps", "-q", "--filter", "name=surya-vllm"],
                         capture_output=True, text=True, check=False).stdout.split()
    if ids:
        subprocess.run(["docker", "rm", "-f", *ids], capture_output=True, check=False)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("books", nargs="*", type=Path)
    ap.add_argument("--force", action="store_true", help="re-convert even if the md exists")
    ap.add_argument("--check-only", action="store_true", help="only re-run the check")
    a = ap.parse_args()

    todo = a.books or sorted(p for p in INBOX.glob("*") if p.suffix.lower() in FORMATS)
    if not a.books and not (a.force or a.check_only):
        todo = [p for p in todo if not (BOOKS / p.stem / f"{p.stem}.md").exists()]
    if not todo:
        print(f"nothing to do (put {'/'.join(FORMATS)} files in inbox/, or use --force / --check-only)")
        return 0
    try:
        return run(todo, a)
    finally:
        if not a.check_only and any(p.suffix.lower() == ".pdf" for p in todo):
            free_gpu()


def run(todo: list[Path], a: argparse.Namespace) -> int:
    failed = 0
    for src in todo:
        if src.suffix.lower() == ".pdf" and in_quiet():  # marker needs the GPU; the morning is the news pipeline's
            print(QUIET_MESSAGE)
            break
        convert = {".pdf": convert_pdf, ".epub": convert_epub}.get(src.suffix.lower())
        if convert is None:
            print(f"FAIL  {src.name}: unsupported format (mobi/azw3: convert to epub with calibre first)")
            failed += 1
            continue
        md = BOOKS / src.stem / f"{src.stem}.md"
        if not a.check_only and (a.force or not md.exists()):
            try:
                md = convert(src)
            except subprocess.CalledProcessError as e:
                print(f"FAIL  {src.name}: converter exited {e.returncode}")
                failed += 1
                continue
        if not md.exists():
            print(f"FAIL  {src.name}: no markdown at {md}")
            failed += 1
            continue
        r = check(src, md)
        (md.parent / "check.json").write_text(json.dumps(r, indent=2), encoding="utf-8")
        failed += r["status"] == "FAIL"
        print(f"{r['status']}  {src.name}: {r['pages']} {'pages' if src.suffix.lower() == '.pdf' else 'files'}, "
              f"coverage {r['doc_coverage']}, {r['page_status']}")
        for p in r["problems"]:
            print(f"      - {p}")
        weak = sorted((p for p in r["page_detail"] if p["status"] in ("low", "missing")),
                      key=lambda p: p["coverage"])[:5]
        for p in weak:
            print(f"      weak {p['page']}: coverage {p['coverage']}, {p['src_words']} -> {p['md_words']} words")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
