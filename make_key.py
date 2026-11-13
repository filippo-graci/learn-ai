"""Create the app's key for the private content site and store it in the two places that need it, without
ever printing it: the Cloudflare Pages project (secret LEARNAI_KEY, read by functions/_middleware.js) and
android/local.properties (learnai.key, built into the app, which sends it as the X-Learnai-Key header).

    .venv/bin/python make_key.py     # run again to rotate: then rebuild + reinstall the app
stdlib only; needs wrangler logged in.
"""
import re
import secrets
import subprocess
import sys
from pathlib import Path

from publish import PROJECT

PROPS = Path(__file__).resolve().parent / "android" / "local.properties"


def main() -> int:
    key = secrets.token_urlsafe(32)
    r = subprocess.run(["wrangler", "pages", "secret", "put", "LEARNAI_KEY", "--project-name", PROJECT],
                       input=key, capture_output=True, text=True)
    if r.returncode:
        print("wrangler failed, nothing changed:\n" + (r.stdout + r.stderr).replace(key, "***")[-1500:])
        return 1
    text = PROPS.read_text(encoding="utf-8") if PROPS.exists() else ""
    text = re.sub(r"(?m)^learnai\.key=.*\n?", "", text)
    PROPS.write_text(text.rstrip("\n") + f"\nlearnai.key={key}\n", encoding="utf-8")
    print(f"key stored on Cloudflare ({PROJECT}: LEARNAI_KEY) and in {PROPS.name} (learnai.key). Not shown.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
