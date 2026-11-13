"""Run: ~/Workspace/papers/.venv-marker/bin/python test_split.py  (needs pandoc)"""
import re
import subprocess
import tempfile
import zipfile
from pathlib import Path

from split import HEADING, chunked, epub_toc, locate, norm, numbered, plain, resolve, same_title

assert norm('<span id="page-16-0"></span>**2.1 The EPIC cameras**') == "the epic cameras"
assert norm("Chapter 3") == "" and norm("3. POWER SPECTRAL STATISTICS") == "power spectral statistics"
assert same_title("premessa alla sesta edizione tedesca il prometeo stanco", "premessa alla sesta edizione tedesca")
assert not same_title("note", "notes on method")  # too short to prefix-match

md = ("# Contents\n\n[2 Methods](#x) 5\n\n# Intro\n\nfront\n\n"
      "# Chapter 1\n\n## Methods\n\nbody one\n\n# Chapter 2\n\n## Results\n\nbody two\n")
heads = [(m.start(), plain(m.group(1)), norm(m.group(1))) for m in HEADING.finditer(md)]
hrefs = {}
found = locate(md, [("Methods", None, "a.htm"), ("Results", None, "b.htm#x"), ("Missing chapter", None, "c.htm")], {}, heads, hrefs)
assert sorted(hrefs.values()) == ["a.htm", "b.htm#x"]  # each located entry keeps its EPUB href
assert [(md[o:].split("\n")[0], t) for o, t in found] == [("# Chapter 1", "Methods"), ("# Chapter 2", "Results")]
assert [t for _, t in numbered(heads)] == []  # only 2 numbered chapters: not a chapter list
assert len(chunked("word " * 2000 + "\n\n" + "word " * 2000 + "\n\n" + "end")) == 2

# EPUB TOC: toc.ncx and, with the ncx removed, the EPUB 3 <nav> must give the same chapters.
with tempfile.TemporaryDirectory() as tmp:
    tmp = Path(tmp)
    (tmp / "b.md").write_text("".join(f"# Title {i}\n\nText {i}.\n\n## Sub {i}\n\nMore.\n\n" for i in (1, 2, 3)))
    subprocess.run(["pandoc", "b.md", "-o", "book.epub", "--metadata", "title=T"], cwd=tmp, check=True)
    ncx = [(lvl, t) for lvl, t, _, _ in epub_toc(tmp / "book.epub")]
    with zipfile.ZipFile(tmp / "book.epub") as src, zipfile.ZipFile(tmp / "nav.epub", "w") as dst:
        for item in src.infolist():
            if not item.filename.endswith(".ncx"):
                dst.writestr(item, src.read(item))
    nav = [(lvl, t) for lvl, t, _, _ in epub_toc(tmp / "nav.epub")]
    assert [t for lvl, t in ncx if re.match(r"Title \d", t)] == ["Title 1", "Title 2", "Title 3"], ncx
    assert [(l, t) for l, t in nav if "Title" in t or "Sub" in t] == [(l, t) for l, t in ncx if "Title" in t or "Sub" in t], (nav, ncx)
assert resolve("OEBPS/toc.ncx", "Text/Section0003.htm#sigil_toc_id_1") == "OEBPS/Text/Section0003.htm#sigil_toc_id_1"
assert resolve("OEBPS/Text/nav.xhtml", "../Text/La%20societ%C3%A0.htm") == "OEBPS/Text/La società.htm"
assert resolve("OEBPS/toc.ncx", None) is None
print("ok")
