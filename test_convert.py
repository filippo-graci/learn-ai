"""Run: ~/Workspace/papers/.venv-marker/bin/python test_convert.py  (EPUB part needs pandoc)"""
import subprocess
import tempfile
from pathlib import Path

import convert
from convert import clean_md, md_pages, words

sep = "-" * 48
md = f"\n\n{{0}}{sep}\n\n# Title\nfirst page\n\n{{1}}{sep}\n\nsecond ![](_page_1_Figure_1.jpeg) page"
assert [p.strip() for p in md_pages(md, 2)] == ["# Title\nfirst page", "second ![](_page_1_Figure_1.jpeg) page"]
assert md_pages(md, 1)[0].strip().endswith("first page")  # page ids beyond the PDF are ignored
assert words(clean_md(md_pages(md, 2)[1])) == {"second": 1, "page": 1}  # image name dropped
assert words("detec-\ntion of the ﬁrst nor￾malisation") == {"detection": 1, "the": 1, "first": 1, "normalisation": 1}
math = "$-\\frac{T}{2N} < t < \\frac{T}{2N}$ By the convolution theorem <sup>1</sup> $a > b$"
assert words(clean_md(math)) == {"the": 1, "convolution": 1, "theorem": 1}  # math "<" must not eat text

# EPUB round trip: build a 3-chapter EPUB with pandoc, convert it back, check it; then lose a chapter.
with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    texts = ["Rivers carry sediment toward distant deltas", "Bakers knead dough before sunrise daily",
             "Comets trail glowing tails past frozen planets"]  # distinct words, like real chapters
    chapters = [f"# Chapter {n}\n\n" + f"{t}. " * 30 + "[^n]\n\n[^n]: A footnote." for n, t in enumerate(texts, 1)]
    (tmp / "b.md").write_text("\n\n".join(c.replace("[^n]", f"[^{i}]") for i, c in enumerate(chapters)))
    subprocess.run(["pandoc", "b.md", "-o", "book.epub", "--metadata", "title=Test"], cwd=tmp, check=True)
    convert.BOOKS = tmp / "books"
    md = convert.convert_epub(tmp / "book.epub")
    r = convert.check(tmp / "book.epub", md)
    assert r["status"] == "PASS" and r["doc_coverage"] >= 0.99, r
    md.write_text(md.read_text().replace(chapters[1].split("[^")[0].strip(), ""))  # drop chapter 2's text
    r = convert.check(tmp / "book.epub", md)
    assert r["status"] == "FAIL" and r["page_status"].get("missing") == 1, r
print("ok")
