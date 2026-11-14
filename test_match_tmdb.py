"""Run: .venv/bin/python test_match_tmdb.py  (no network)"""
from match_tmdb import candidates, clean, clear_match, link

assert clean("Squid Game - Stagione 2", True) == ("Squid Game", "")
assert clean("One Punch Man - Stagioni 1 e 2", True) == ("One Punch Man", "")
assert clean("Log Horizon 2", True) == ("Log Horizon", "")
assert clean("Dragon Trainer 2", False) == ("Dragon Trainer 2", "")  # a movie keeps its number
assert clean("Il pianeta delle scimmie (1968)", False) == ("Il pianeta delle scimmie", "1968")

psycho = candidates("movie", [{"id": 539, "title": "Psyco", "original_title": "Psycho", "release_date": "1960-06-22"},
                              {"id": 11252, "title": "Psycho", "original_title": "Psycho", "release_date": "1998-12-04"}])
assert psycho[0] == {"tmdb": "movie/539", "title": "Psyco", "original": "Psycho", "year": "1960", "votes": 0}
assert clear_match("Psycho", psycho) is None  # a remake: ask
assert clear_match("Psyco", psycho) == "movie/539"
alien = candidates("movie", [{"id": 945961, "title": "Alien: Romulus"}, {"id": 348, "title": "Alien"}])
assert clear_match("Alien - Romulus", alien) == "movie/945961"  # punctuation ignored
dark = candidates("tv", [{"id": 70523, "name": "Dark", "first_air_date": "2017-12-01"}])
assert dark[0]["tmdb"] == "tv/70523" and clear_match("anything", dark) == "tv/70523"  # the only result
assert clear_match("Mercoledi", candidates("tv", [{"id": 1, "name": "Mercoledì"}, {"id": 2, "name": "Mercoledì Addams"}])) == "tv/1"

data = {"lists": [], "titles": [{"id": "a", "name": "A", "updated": 1}, {"id": "b", "name": "B", "updated": 1}]}
out = link(data, {"a": "tv/70523"}, 99)
assert out["titles"] == [{"id": "a", "name": "A", "updated": 99, "tmdb": "tv/70523"}, {"id": "b", "name": "B", "updated": 1}]
assert out["lists"] == [] and "tmdb" not in data["titles"][0]  # input untouched
print("ok")
