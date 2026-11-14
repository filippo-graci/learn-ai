"""Run: .venv/bin/python test_notes.py"""
from notes import markdown

chapters = [{"n": 1, "title": "Uno", "href": "a.htm"}, {"n": 2, "title": "Due", "href": "b.htm#x"}]
h = lambda href, p, text, note="": {"href": href, "progression": p, "created": 0, "text": text, "note": note}
md = markdown("Libro", [h("b.htm", 0.5, "dopo"), h("a.htm", 0.9, "prima", "mia nota"), h("b.htm", 0.1, "riga 1\nriga 2"),
                        h("z.htm", 0, "fuori")], chapters)
assert md.index("## 1. Uno") < md.index("> prima") < md.index("**Note:** mia nota") < md.index("## 2. Due")
assert md.index("> riga 1\n> riga 2") < md.index("> dopo") < md.index("## z.htm") < md.index("> fuori")  # order in chapter
assert md.count("## 2. Due") == 1

from notes import reviews_markdown
md = reviews_markdown({"books": {"han": {"score": 9, "aspects": {"ideas": {"score": 5, "text": "Sharp."}}, "opinion": "Great."},
                                 "empty": {"status": "to-read"}},
                       "titles": [{"name": "Dark", "series": True, "score": 8, "tags": ["sci-fi"], "favorite": True},
                                  {"name": "Unrated", "created": 0}]}, {"han": "La società della stanchezza"})
assert "### La società della stanchezza — 9/10" in md and "**Ideas** ★★★★★\nSharp." in md and "Great." in md
assert "### Dark (series) #sci-fi ⭐ — 8/10" in md
assert "empty" not in md and "Unrated" not in md  # shelf only / nothing written: not a review
print("ok")

import tempfile
from pathlib import Path
from notes import obsidian_notes, write_vault
data = {"books": {"han": {"score": 9, "opinion": "Great.", "finished": 1767225600000}},
        "titles": [{"id": "a", "name": "Dark: S1", "series": True, "score": 8, "tags": ["with friends"], "created": 1,
                    "aspects": {"acting": {"score": 4, "text": "Strong cast."}}, "views": [1767225600000]},
                   {"id": "b", "name": "Dark: S1", "score": 5, "created": 2}, {"id": "c", "name": "Unrated", "created": 3}]}
notes = obsidian_notes(data, {"han": "La società"})
assert sorted(notes) == ["Reviews/Books/La società.md", "Reviews/Movies & TV/Dark S1 2.md", "Reviews/Movies & TV/Dark S1.md"]
dark = notes["Reviews/Movies & TV/Dark S1.md"]
assert dark.startswith("---\ntype: \"series\"\nscore: 8\n") and '  - "with-friends"' in dark and "watched:\n  - \"2026-01-0" in dark
assert "**Acting** ★★★★\nStrong cast." in dark and "# Dark: S1" in dark
with tempfile.TemporaryDirectory() as d:
    vault = Path(d)
    (vault / "Reviews/Books").mkdir(parents=True)
    (vault / "Reviews/Books/Mine.md").write_text("my own note")                       # not ours: kept
    (vault / "Reviews/Books/La società.md").write_text("hand-written")                # same name, not ours: kept
    (vault / "Reviews/Books/Gone.md").write_text("---\nsource: learn_ai\n---\n")       # ours, review deleted: pruned
    write_vault(vault, notes)
    assert sorted(p.name for p in vault.rglob("*.md")) == ["Dark S1 2.md", "Dark S1.md", "La società.md", "Mine.md"]
    assert (vault / "Reviews/Books/La società.md").read_text() == "hand-written"
print("ok")

data["lists"] = [{"id": "l", "name": "Weekend", "titles": ["b", "c", "gone"]}]
data["titles"][2].update(watched=5, series=True, season=2, episodes=3, totalEpisodes=8)
lst = obsidian_notes(data, {})["Reviews/Lists/Weekend.md"]
assert "titles: 2\nwatched: 1\n" in lst and "1 / 2 watched" in lst
assert "- [ ] [[Reviews/Movies & TV/Dark S1 2|Dark: S1]] (movie, 5/10)\n- [x] Unrated (series, S2 · ep 3/8)" in lst
print("ok")

from notes import import_vault, MARK
with tempfile.TemporaryDirectory() as d:
    d = Path(d)
    (d / "Cowboy Bebop.md").write_text('---\ntype: "series"\nscore: 8.5\nscore_from: colour\ntags:\n  - "review/series"\n  - "anime"\n---\n\n# Cowboy Bebop\n')
    (d / "possession.md").write_text('---\nscore: 7\n---\n\n# POSSESSION\n')  # already in the app (other case)
    (d / "Unrated.md").write_text('---\ntype: "movie"\n---\n\n# Unrated\n')
    (d / "Generated.md").write_text(f'---\nscore: 9\n{MARK}\n---\n\n# Generated\n')  # notes.py's own: not imported
    new, done = import_vault({"titles": [{"id": "x", "name": "Possession", "created": 1}]}, d, 1000)
    assert [f.name for f in done] == ["Cowboy Bebop.md"]
    t = new["titles"][-1]
    assert (t["name"], t["series"], t["score"], t["tags"], t["updated"]) == ("Cowboy Bebop", True, 8.5, ["anime"], 1000)
    assert len(new["titles"]) == 2
print("import ok")
