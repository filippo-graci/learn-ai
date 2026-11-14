"""Link the app's movies & series to TMDB: sets Title.tmdb ("movie/<id>" / "tv/<id>") in the reviews store (KV "_movies").

    .venv/bin/python match_tmdb.py     # token: $TMDB_TOKEN, else tmdb.token in android/local.properties

Searches TMDB in Italian (names are mostly Italian titles), movies or TV by the title's series flag; a season
("Squid Game - Stagione 2", "Log Horizon 2") links to its series, asked once for all its seasons. A clear match
(the only result, or the only one whose title equals the name) is taken; anything else is asked: a number picks,
0 = not on TMDB, Enter = skip for now, other text = search that instead. Answers are saved as you go (tmdb_links.json):
Ctrl-C stops, a re-run only asks the rest and writes the saved ones.
Install the app build that has Title.tmdb on every device first: an older build drops the field on its next edit.
"""
import json
import os
import re
import sys
import unicodedata
import urllib.parse
import urllib.request
from datetime import datetime

from notes import ROOT, kv

SAVED = ROOT / "tmdb_links.json"  # title id -> tmdb, your answers (also a backup of the links)
LABELS = {"none": "not on TMDB"}  # tmdb -> "Title (year)", to show what was picked


def clean(name: str, series: bool) -> tuple[str, str]:
    """App name -> (TMDB query, year or ""): drops "(1968)", and for a series its season ("- Stagione 2", trailing " 3")."""
    year = m.group(1) if (m := re.search(r"\s*\((\d{4})\)\s*$", name)) else ""
    name = name[:m.start()] if m else name
    if series:
        name = re.sub(r"\s+-\s+Stagion[ei]\b.*$|\s+\d+$", "", name)
    return name.strip(), year


def norm(s: str) -> str:
    s = unicodedata.normalize("NFKD", s.lower())
    return re.sub(r"[^a-z0-9]+", " ", "".join(c for c in s if not unicodedata.combining(c))).strip()


def candidates(kind: str, results: list) -> list[dict]:
    t, o, d = ("title", "original_title", "release_date") if kind == "movie" else ("name", "original_name", "first_air_date")
    return [dict(tmdb=f"{kind}/{r['id']}", title=r.get(t, ""), original=r.get(o, ""), year=(r.get(d) or "")[:4],
                 votes=r.get("vote_count", 0)) for r in results]


def clear_match(query: str, cands: list[dict]) -> str | None:
    """The only candidate, or the only one titled exactly like the query (Italian or original title); else None = ask."""
    if len(cands) == 1:
        return cands[0]["tmdb"]
    exact = [c for c in cands if norm(query) in (norm(c["title"]), norm(c["original"]))]
    return exact[0]["tmdb"] if len(exact) == 1 else None


def link(data: dict, links: dict, now: int) -> dict:
    """links: title id -> tmdb. Stamps `updated` so the link wins the app's merge (latest change per title)."""
    return {**data, "titles": [{**t, "tmdb": links[t["id"]], "updated": now} if t["id"] in links else t
                               for t in data.get("titles", [])]}


def token() -> str:
    """Your TMDB API read access token: $TMDB_TOKEN, else tmdb.token in android/local.properties (the app's, never committed)."""
    props = ROOT / "android" / "local.properties"
    lines = props.read_text(encoding="utf-8").splitlines() if props.exists() else []
    return os.environ.get("TMDB_TOKEN") or next((l.split("=", 1)[1].strip() for l in lines if l.startswith("tmdb.token=")), "")


NO_TOKEN = "no TMDB token: set TMDB_TOKEN or add tmdb.token=<API read access token> to android/local.properties"


def tmdb(path: str, **params) -> dict:
    """GET api.themoviedb.org/3/<path> in Italian, with your read access token ([token])."""
    req = urllib.request.Request(f"https://api.themoviedb.org/3/{path}?{urllib.parse.urlencode({'language': 'it-IT', **params})}",
                                 headers={"Authorization": f"Bearer {token()}", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def search(kind: str, query: str, year: str = "") -> list[dict]:
    cands = candidates(kind, tmdb(f"search/{kind}", query=query, **({"year" if kind == "movie" else "first_air_date_year": year} if year else {}))["results"])
    LABELS.update((c["tmdb"], f"{c['title']} ({c['year'] or '?'})") for c in cands)
    return cands


def ask(name: str, kind: str, query: str, year: str) -> str | None:
    while True:
        cands = search(kind, query, year)
        if not cands and " - " in query:  # "Citizen Kane - Quarto potere", "Demon Slayer - Il treno Mugen"
            query, year = query.split(" - ")[0], ""
            cands = search(kind, query)
        if pick := clear_match(query, cands):
            return pick
        print(f"\n{name}  [{kind}, searched: {query}{' ' + year if year else ''}]")
        for i, c in enumerate(cands[:8], 1):
            orig = f" / {c['original']}" if norm(c["original"]) != norm(c["title"]) else ""
            print(f"  {i}. {c['title']}{orig} ({c['year'] or '?'}, {c['votes']} votes)  {c['tmdb']}")
        a = input("number, 0 = not on TMDB, Enter = skip, or search for: ").strip()
        if not a:
            return None
        if a == "0":
            return "none"
        if a.isdigit() and 1 <= int(a) <= min(8, len(cands)):
            return cands[int(a) - 1]["tmdb"]
        query, year = a, ""


def main() -> int:
    if not token():
        sys.exit(NO_TOKEN)
    todo = [t for t in json.loads(kv("get", "_movies", "--text")).get("titles", []) if not t.get("tmdb")]
    links = json.loads(SAVED.read_text()) if SAVED.exists() else {}  # every answer so far: re-runs don't ask again
    asked = {}  # (kind, query) -> answer, so a series' seasons are asked once
    try:
        for t in todo:
            if t["id"] in links:
                continue
            kind, (query, year) = "tv" if t.get("series") else "movie", clean(t["name"], t.get("series", False))
            if (kind, query) not in asked:
                asked[kind, query] = ask(t["name"], kind, query, year)
            if asked[kind, query]:
                links[t["id"]] = asked[kind, query]
                SAVED.write_text(json.dumps(links, indent=1))
                print(f"{t['name']} -> {LABELS.get(asked[kind, query], '')} {asked[kind, query]}")
    except (KeyboardInterrupt, EOFError):
        print("\nstopped")
    links = {k: v for k, v in links.items() if any(t["id"] == k for t in todo)}  # not in the app yet
    if not links or input(f"\nwrite {len(links)} links to the app? Only once every device has the build with "
                          f"Title.tmdb, else its next sync wipes them [y/N] ").strip().lower() != "y":
        print(f"nothing written; answers kept in {SAVED.name}")
        return 0
    data = json.loads(kv("get", "_movies", "--text"))  # again: the phone may have synced while you were answering
    tmp = ROOT / ".movies_tmdb.json"
    tmp.write_text(json.dumps(link(data, links, int(datetime.now().timestamp() * 1000)), ensure_ascii=False), encoding="utf-8")
    kv("put", "_movies", "--path", str(tmp))
    tmp.unlink()
    print(f"{len(links)} linked; {len(todo) - len(links)} left for the next run (the phone gets them on its next sync)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
