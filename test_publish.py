"""Run: .venv/bin/python test_publish.py"""
from publish import card_id, slug

assert slug("La societa della stanchezza - Byung-Chul Han") == "la-societa-della-stanchezza-byung-chul-han"
assert slug("Città  è  bella!") == "citta-e-bella"
a = card_id("han", "card", "Che cos'è la noia profonda?")
assert a == card_id("han", "card", "Che cos'è la noia profonda?  ") and len(a) == 16  # stable, whitespace-insensitive
assert a != card_id("han", "quiz", "Che cos'è la noia profonda?") != card_id("other", "card", "Che cos'è la noia profonda?")
print("ok")
