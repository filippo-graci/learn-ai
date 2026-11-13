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
