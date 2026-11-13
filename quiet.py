"""The morning window reserved for ai_news_pipeline (stdlib only: imported by both venvs).

news_digest.timer starts at 07:15 and its service may run up to 60 min (TimeoutStartSec=3600), so no
LLM/GPU work of this project may run from 06:30 to 08:30. stop_llm.py (systemd timer, 06:30) kills what
is still running; generate.py and convert.py check in_quiet() before every chapter/book and won't start.
"""
from datetime import datetime, time

QUIET_START, QUIET_END = time(6, 30), time(8, 30)


def in_quiet(now: datetime | None = None) -> bool:
    t = (now or datetime.now()).time()
    return QUIET_START <= t < QUIET_END


MESSAGE = (f"{QUIET_START:%H:%M}-{QUIET_END:%H:%M} is reserved for the news pipeline: stopping. "
           f"Finished chapters/books are saved; rerun after {QUIET_END:%H:%M} to continue.")

if __name__ == "__main__":
    assert in_quiet(datetime(2026, 11, 8, 6, 30)) and in_quiet(datetime(2026, 11, 8, 8, 29))
    assert not in_quiet(datetime(2026, 11, 8, 6, 29)) and not in_quiet(datetime(2026, 11, 8, 8, 30))
    assert not in_quiet(datetime(2026, 11, 8, 23, 0))
    print("ok")
