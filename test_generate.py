"""Run: .venv/bin/python test_generate.py  (no Ollama needed)"""
from generate import QuizItem, extract_json, grounded, halves, sections, tokens

assert extract_json('```json\n{"a": {"b": 1}}\n```') == '{"a": {"b": 1}}'
assert extract_json("no json here") == "no json here"  # let pydantic report it

ch = tokens("Ogni epoca ha le sue malattie. Così, c’è stata un’epoca batterica, finita con gli antibiotici.")
assert grounded("Così, c'è stata un'epoca batterica", ch)  # different apostrophe, same words
assert not grounded("Ogni epoca ha le sue cure e i suoi rimedi.", ch)  # paraphrase: only 5/9 words in a run
assert not grounded("Ogni epoca.", ch)  # too short to count as evidence
src = tokens(r"For a wide range of types of noise, the noise powers $P_{j}$ follow the $\chi^2$ distribution "
             r"with 2 degrees of freedom. A correct procedure would, for example, be to divide the spectrum.")
assert grounded(r"the noise powers $P_{j,\text{noise}}$ follow the χ² (chi-squared) distribution with 2 degrees", src)
assert grounded("a correct procedure would for instance be to divide the spectrum", src)  # one word changed
assert halves("a\n\nb\n\nc\n\nd") == ["a\n\nb", "c\n\nd"] and halves("one paragraph") == ["one paragraph"]

text = "\n\n".join(f"## Part {i}\n\n" + "word " * 1000 for i in range(5))  # 5 x ~1000 words
parts = sections(text)
assert len(parts) == 3 and all(p.startswith("## Part") for p in parts), [len(p.split()) for p in parts]
assert sections("short text") == ["short text"]

try:
    QuizItem(question="question?", options=["a", "b", "a", "c"], answer=0, explanation="explanation",
             source_quote="a quote long enough to pass")
    raise AssertionError("duplicate options accepted")
except ValueError:
    pass
from generate import Cards, card_checks

def cards(text, correct, wrong):
    q = {"question": text + "?", "options": [correct, wrong, wrong[:-1] + "x", wrong[:-1] + "y"], "answer": 0,
         "explanation": text, "source_quote": text + " " + text}
    fc = {"front": text + "?", "back": text, "source_quote": text + " " + text}
    return Cards(summary=text, key_ideas=[text] * 3, flashcards=[fc] * 3, quiz=[q] * 3)

check = card_checks("Perché la società è stanca: perché così è più comodo. " * 5)
it = lambda n: ("società " * 10)[:n]  # Italian-looking option text of n chars
assert check(cards("Perché la società è stanca", it(40), it(38))) is None
assert check(cards("Perch la societa e stanca", "societa " * 5, "comunita " * 4))[1] is True  # accents lost: hard
msg, hard = check(cards("Perché la società è stanca", it(60), it(40)))
assert not hard and "question 1: correct option 60 chars" in msg, msg
assert card_checks("plain english text only " * 5)(cards("plain english text", "plain " * 7, "english " * 5)) is None

from generate import foreign_names
src = "Kafka racconta il mito di Prometeo. Secondo Benjamin la noia profonda cova l'uovo dell'esperienza."
assert foreign_names(["Giorgio Agamben reinterpreta il mito di Prometeo secondo Kafka."], src) == ["Agamben"]
assert foreign_names(["Inoltre, la noia: Questa idea viene da Benjamin."], src) == []  # sentence starts are fine
assert foreign_names(["il caso 'Kafka' e l'uovo"], src) == []  # trailing quote mark is not part of the name
msg, hard = card_checks(src * 3)(cards("The myth of Prometheus is told by Kafka", "plain " * 7, "english " * 5))
assert hard and "different language" in msg

# attribution: a named person must be named near the card's quote (like ai_news_pipeline's repair_citations)
from generate import attribution_filter
filler = "La società della prestazione produce soggetti stanchi e depressi ogni giorno. " * 12  # ~120 words
chapter = ("Secondo Benjamin la noia profonda cova l'uovo dell'esperienza. " + filler +
           "Nietzsche osserva che per mancanza di calma la nostra civiltà sbocca in una nuova barbarie. " + filler +
           "Bartleby preferisce di no. " + "Bartleby copia. " * 5)
reason = attribution_filter(chapter)
card = lambda front, quote: {"front": front, "back": "b", "source_quote": quote}
assert reason(card("Che cosa dice Benjamin della noia?", "la noia profonda cova l'uovo dell'esperienza"), "flashcards") is None
assert "Nietzsche" in reason(card("Che cosa dice Nietzsche della noia?", "la noia profonda cova l'uovo dell'esperienza"), "flashcards")
assert reason(card("Perché Bartleby rifiuta?", "la noia profonda cova l'uovo dell'esperienza"), "flashcards") is None  # the chapter's subject
assert reason(card("Che cosa dice Benjamin?", "una frase che non esiste nel capitolo affatto"), "flashcards") == "quote not found in the chapter"
quiz = {"question": "Di chi è l'idea della nuova barbarie?", "options": ["Di Nietzsche", "Di Benjamin", "Di Kafka", "Di Bartleby"],
        "answer": 0, "explanation": "e", "source_quote": "per mancanza di calma la nostra civiltà sbocca in una nuova barbarie"}
assert reason(quiz, "quiz") is None  # a wrong option naming the wrong person is a legitimate distractor

# Gemini backend: request shape and answer parsing, with the HTTP call faked (no key, no network)
import os
import generate
from generate import GenerationError, Merged, chat
sent = {}
def fake_post(url, body, headers, timeout, finish="STOP"):
    sent.update(url=url, body=body, headers=headers)
    answer = '{"summary": "Riassunto del capitolo.", "key_ideas": ["uno", "due", "tre"]}'
    return {"candidates": [{"finishReason": finish, "content": {"parts": [{"text": "pensiero...", "thought": True},
                                                                        {"text": answer}]}}],
            "usageMetadata": {"promptTokenCount": 100, "candidatesTokenCount": 40, "thoughtsTokenCount": 60}}
os.environ["GEMINI_API_KEY"] = "test-key"
generate.BACKEND, generate.MODEL, generate.post = "gemini", "gemini-test", fake_post
obj, stats = chat("sistema", "testo", Merged)
assert obj.key_ideas == ["uno", "due", "tre"] and stats["output_tokens"] == 100  # thinking skipped, but counted
assert sent["url"].endswith("/gemini-test:generateContent") and "test-key" not in sent["url"]  # key in header only
assert sent["headers"]["x-goog-api-key"] == "test-key"
assert sent["body"]["systemInstruction"]["parts"][0]["text"] == "sistema"
assert sent["body"]["generationConfig"]["responseJsonSchema"]["required"] == ["summary", "key_ideas"]
generate.post = lambda *a: fake_post(*a, finish="RECITATION")
try:
    chat("sistema", "testo", Merged)
    raise AssertionError("RECITATION should fail the chapter")
except GenerationError as e:
    assert "RECITATION" in str(e)
generate.BACKEND, generate.post = "ollama", None
print("ok")
