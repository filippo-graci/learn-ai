"""Suggestions from your scores, for the app's Movies tab → Suggestions: "For you" and "Essentials outside your bubble".

    .venv/bin/python recommend.py     # token: $TMDB_TOKEN, else tmdb.token in android/local.properties

Weekly: systemd/learn-ai-recommend.timer (Sundays 10:00, or at the next start if the laptop was off).

Your titles' TMDB links: Title.tmdb, or your answers in tmdb_links.json (match_tmdb.py) until they are in the app.
For you: TMDB recommendations of the titles you rated ≥ 1 point above your mean, ranked by a taste profile (genres,
keywords, director / creator + top cast, language, weighted by your score − your mean). Every run checks the profile
leave-one-out (Spearman ρ against your scores); if it is below MIN_RHO or doesn't beat TMDB's own rating, it is not
trusted and the list is ranked by how many of your liked titles recommend it, then by TMDB rating. Essentials: TMDB top rated with many votes, in a genre you have (almost) never rated,
anime left out (your bubble), by weighted rating; it ignores the profile on purpose.
Writes KV "_doc_recommend" (functions/doc/[name].js), one write per run. TMDB answers are cached in tmdb_cache/ for
CACHE_DAYS (TMDB's recommendations change).
"""
import json
import statistics
import sys
import time
from collections import defaultdict
from datetime import datetime

from match_tmdb import NO_TOKEN, SAVED, tmdb, token
from notes import ROOT, kv

CACHE = ROOT / "tmdb_cache"
CACHE_DAYS = 30
K = 3            # shrinkage: a feature seen once counts a quarter, so one film doesn't make a director a favourite
MIN_RHO = 0.15   # leave-one-out ρ below this = the profile doesn't predict your scores
BUBBLE = 0.05    # essentials: a genre in < 5 % of your rated titles is outside your bubble
MIN_VOTES = {"movie": 3000, "tv": 1000}
NOT_ESSENTIAL = {16, 10762, 10763, 10764, 10766, 10767}  # animation (your bubble, with Japanese titles); kids, news, reality, soap, talk
PER_SOURCE = 3   # For you: at most 3 suggestions led by the same title of yours


def details(link: str) -> dict:
    f = CACHE / f"{link.replace('/', '_')}.json"
    if f.exists() and time.time() - f.stat().st_mtime < CACHE_DAYS * 86400:
        return json.loads(f.read_text(encoding="utf-8"))
    d = tmdb(link, append_to_response="keywords,credits,recommendations")
    CACHE.mkdir(exist_ok=True)
    f.write_text(json.dumps(d, ensure_ascii=False), encoding="utf-8")
    return d


def features(d: dict) -> dict[str, dict[str, str]]:
    """Per kind of feature, feature -> its name."""
    kw = d.get("keywords", {})
    credits = d.get("credits", {})
    people = [c for c in credits.get("crew", []) if c.get("job") == "Director"] + d.get("created_by", []) + credits.get("cast", [])[:5]
    return {"genre": {f"g{g['id']}": g["name"] for g in d.get("genres", [])},
            "keyword": {f"k{k['id']}": k["name"] for k in kw.get("keywords") or kw.get("results") or []},
            "people": {f"p{p['id']}": p["name"] for p in people},
            "language": {f"l{d['original_language']}": d["original_language"]} if d.get("original_language") else {}}


def profile(rated: dict) -> dict[str, float]:
    """rated: link -> (your score, features). Feature -> shrunk mean of (score - your mean) over the titles that have it."""
    mean = statistics.mean(r for r, _ in rated.values())
    tot, n = defaultdict(float), defaultdict(int)
    for r, fs in rated.values():
        for f in {f for kind in fs.values() for f in kind}:
            tot[f] += r - mean
            n[f] += 1
    return {f: tot[f] / (n[f] + K) for f in tot}


def predict(prof: dict, fs: dict) -> float:
    # per kind the mean of its features, then summed: 20 keywords don't outvote one director
    return sum(sum(prof.get(f, 0.0) for f in kind) / len(kind) for kind in fs.values() if kind)


def loo(rated: dict) -> float:
    """Spearman ρ between your scores and the profile's guess for each title, made without that title."""
    guesses = [predict(profile({k: v for k, v in rated.items() if k != link}), fs) for link, (_, fs) in rated.items()]
    return statistics.correlation(guesses, [r for r, _ in rated.values()], method="ranked")


def entry(link: str, d: dict, why: str) -> dict:
    return dict(tmdb=link, name=d.get("title") or d.get("name") or "?", original=d.get("original_title") or d.get("original_name") or "",
                year=(d.get("release_date") or d.get("first_air_date") or "")[:4], series=link.startswith("tv/"),
                why=why, overview=(d.get("overview") or "")[:400])


def for_you(rated: dict, dets: dict, names: dict, store: set, trusted: bool, fetch=details, n=30) -> list[dict]:
    mean = statistics.mean(r for r, _ in rated.values())
    recs = defaultdict(lambda: [0.0, []])  # candidate -> [sum of (score - mean) of your titles recommending it, their names]
    for link, (r, _) in sorted(rated.items(), key=lambda x: -x[1][0]):
        if r < mean + 1:
            continue
        for c in dets[link].get("recommendations", {}).get("results", []):
            cl = f"{c.get('media_type') or link.split('/')[0]}/{c['id']}"
            if cl not in store and c.get("vote_count", 0) >= 100:
                recs[cl][0] += r - mean
                recs[cl][1].append(names[link])
    prof = profile(rated)
    out = []
    for cl in sorted(recs, key=lambda c: -recs[c][0])[:3 * n]:  # ponytail: details for the 90 most recommended only
        d, (weight, by) = fetch(cl), recs[cl]
        fs = features(d)
        best = max(((prof.get(f, 0.0), f"{kind}: {name}") for kind, v in fs.items() if kind != "language" for f, name in v.items()), default=(0, ""))
        why = "like " + ", ".join(by[:3]) + (f" · {best[1]}" if trusted and best[0] > 0 else "")
        rank = predict(prof, fs) + 0.1 * weight if trusted else weight + d.get("vote_average", 0) / 10
        out.append((rank, by[0], entry(cl, d, why)))
    picked, per = [], defaultdict(int)
    for _, source, e in sorted(out, key=lambda x: -x[0]):
        if per[source] < PER_SOURCE and len(picked) < n:  # not 10 Evangelion sequels because you loved one
            per[source] += 1
            picked.append(e)
    return picked


def essentials(pool: list[tuple[str, dict]], rated: dict, genre_names: dict, exclude: set, n=30) -> list[dict]:
    """pool: (link, TMDB list item). Top rated in a genre you rarely rate, by IMDb's weighted rating, ≤ 3 per genre."""
    share = defaultdict(float)
    for _, fs in rated.values():
        for g in fs["genre"]:
            share[int(g[1:])] += 1 / len(rated)
    mean_vote = {k: statistics.mean([c["vote_average"] for l, c in pool if l.startswith(k)] or [0]) for k in MIN_VOTES}
    picked, per_genre = [], defaultdict(int)
    def wr(item):
        (l, c), m = item, MIN_VOTES[item[0].split("/")[0]]
        v = c["vote_count"]
        return v / (v + m) * c["vote_average"] + m / (v + m) * mean_vote[l.split("/")[0]]
    for l, c in sorted(pool, key=wr, reverse=True):
        gs = c.get("genre_ids", [])
        if l in exclude or not gs or NOT_ESSENTIAL & set(gs) or c.get("original_language") == "ja" or c.get("vote_count", 0) < MIN_VOTES[l.split("/")[0]]:
            continue
        rare = min(gs, key=lambda g: share[g])
        if share[rare] >= BUBBLE or per_genre[rare] >= 3:
            continue
        per_genre[rare] += 1
        picked.append(entry(l, c, f"{genre_names.get(rare, rare)}: {round(share[rare] * len(rated))} of your {len(rated)}"))
        if len(picked) == n:
            break
    return picked


def main() -> int:
    if not token():
        sys.exit(NO_TOKEN)
    saved = json.loads(SAVED.read_text()) if SAVED.exists() else {}
    titles = json.loads(kv("get", "_movies", "--text")).get("titles", [])
    link_of = {t["id"]: t.get("tmdb") or saved.get(t["id"], "") for t in titles}
    store = {l for l in link_of.values() if l and l != "none"}  # rated or only on a list: never suggested
    scores, names = defaultdict(list), {}
    for t in titles:
        if (l := link_of[t["id"]]) in store and t.get("score"):
            scores[l].append(t["score"])  # a series' seasons: their mean
            names.setdefault(l, t["name"])
    print(f"{len(scores)} rated TMDB titles; fetching details (cached in {CACHE.name}/)")
    dets = {l: details(l) for l in scores}
    rated = {l: (statistics.mean(v), features(dets[l])) for l, v in scores.items()}
    rho = loo(rated)
    base = statistics.correlation([dets[l].get("vote_average", 0) for l in rated], [r for r, _ in rated.values()], method="ranked")
    trusted = rho >= MIN_RHO and rho > base  # worse than TMDB's rating = your taste isn't in these features
    check = (f"Taste profile, leave-one-out over {len(rated)} titles: ρ = {rho:.2f} (TMDB rating alone: {base:.2f}). "
             + ("Ranked by it." if trusted else "Not better than TMDB's rating, so not used: ranked by how many of your liked titles recommend it, then by TMDB rating."))
    print(check)
    mine = for_you(rated, dets, names, store, trusted)
    genre_names = {g["id"]: g["name"] for k in MIN_VOTES for g in tmdb(f"genre/{k}/list")["genres"]}
    pool = [(f"{k}/{c['id']}", c) for k in MIN_VOTES for page in range(1, 11) for c in tmdb(f"{k}/top_rated", page=page)["results"]]
    ess = essentials(pool, rated, genre_names, store | {e["tmdb"] for e in mine})
    for head, lst in (("For you", mine), ("Essentials outside your bubble", ess)):
        print(f"\n{head}:\n" + "\n".join(f"  {e['name']} ({e['year']}) — {e['why']}" for e in lst))
    doc = dict(made=int(datetime.now().timestamp() * 1000), check=check, forYou=mine, essentials=ess)
    tmp = ROOT / ".movies_recommend.json"
    tmp.write_text(json.dumps(doc, ensure_ascii=False), encoding="utf-8")
    kv("put", "_doc_recommend", "--path", str(tmp))
    tmp.unlink()
    print("\n-> the app's Suggestions page (KV _doc_recommend)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
