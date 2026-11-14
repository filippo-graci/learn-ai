"""Run: .venv/bin/python test_recommend.py  (no network)"""
from recommend import essentials, features, for_you, loo, predict, profile

def det(genres, kw=(), recs=(), lang="en", title="T"):
    return {"title": title, "genres": [{"id": g, "name": f"G{g}"} for g in genres], "keywords": {"keywords": [{"id": k, "name": f"k{k}"} for k in kw]},
            "credits": {"crew": [{"id": 9, "job": "Director", "name": "Dir"}], "cast": []}, "original_language": lang,
            "recommendations": {"results": [{"id": i, "media_type": "movie", "vote_count": 500} for i in recs]}}

assert features(det([1], [7]))["genre"] == {"g1": "G1"} and features(det([1], [7]))["people"] == {"p9": "Dir"}
assert features({"keywords": {"results": [{"id": 3, "name": "x"}]}, "created_by": [{"id": 4, "name": "C"}]})["keyword"] == {"k3": "x"}

# you love genre 1 (horror-ish), dislike genre 2: the profile must find it, even left one out
dets = {f"movie/{i}": det([1 if i % 2 else 2], [i % 3], recs=[100, 101] if i % 2 else [102]) for i in range(1, 21)}
rated = {l: (9.0 if int(l.split("/")[1]) % 2 else 5.0, features(d)) for l, d in dets.items()}
prof = profile(rated)
assert prof["g1"] > 0 > prof["g2"] and abs(prof["p9"]) < 1e-9  # everyone has the director: says nothing
assert predict(prof, features(det([1]))) > predict(prof, features(det([2])))
assert loo(rated) > 0.8

fetched = {"movie/100": det([1], title="Liked genre"), "movie/101": det([2], title="Other genre")}
names = {l: f"N{l}" for l in dets}
mine = for_you(rated, dets, names, store=set(dets) | {"movie/102"}, trusted=True, fetch=fetched.__getitem__)
assert [e["name"] for e in mine] == ["Liked genre", "Other genre"]  # 102 is already yours; disliked titles don't recommend
assert mine[0]["why"].startswith("like N") and "genre: G1" in mine[0]["why"] and mine[0]["series"] is False

c = lambda gs, votes=5000, avg=8.5, lang="en", name="X": {"genre_ids": gs, "vote_count": votes, "vote_average": avg, "original_language": lang, "title": name}
pool = [("movie/1", c([37], name="Western")), ("movie/2", c([1], name="Your genre")), ("movie/3", c([16, 37], name="Anime")),
        ("movie/4", c([37], lang="ja", name="Japanese")), ("movie/5", c([37], votes=10, name="Obscure")),
        ("movie/6", c([37, 1], avg=9.0, name="Western too")), ("movie/7", c([37], name="Seen")), ("tv/8", c([10764], avg=9.5, name="Reality"))]
for i in range(8, 12):
    pool.append((f"movie/{i}", c([37], avg=7.0, name=f"W{i}")))
ess = essentials(pool, rated, {37: "Western"}, exclude={"movie/7"})
assert [e["name"] for e in ess] == ["Western too", "Western", "W8"], ess  # ≤ 3 per genre, best first
assert ess[1]["why"] == "Western: 0 of your 20"
print("ok")
