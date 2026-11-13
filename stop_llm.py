"""06:30 stop: free the GPU for ai_news_pipeline (07:15). Run by systemd (systemd/learn-ai-stop.timer).

Stops every LLM/GPU job this machine may be running, whoever started it:
  1. this project's generate.py / convert.py and marker_single (SIGTERM; finished chapters are already saved).
     Not generate.py --backend gemini: it runs on Google's servers, not this GPU.
  2. marker's surya model servers and its vLLM container (they outlive a killed marker)
  3. every model loaded in Ollama (keep_alive=0), so the news pipeline's gpu_guard sees a free GPU
stdlib only.  --dry-run lists what it would do.
"""
import json
import os
import re
import signal
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OLLAMA = "http://localhost:11434"
PROCESSES = [r"marker_single", r"-m surya\.[a-z_]+\.server"]
SCRIPTS = re.compile(r"(^|/)(generate|convert)\.py$")


def run(cmd: list[str]) -> str:
    return subprocess.run(cmd, capture_output=True, text=True, check=False).stdout.strip()


def project_scripts() -> list[int]:
    """generate.py / convert.py of THIS project, also when started with a relative path
    (`python generate.py` from learn_ai/ has no "learn_ai" in its command line): match on the working dir."""
    pids = []
    for proc in Path("/proc").iterdir():
        if not proc.name.isdigit():
            continue
        try:
            args = (proc / "cmdline").read_bytes().decode(errors="replace").split("\0")
            cwd = Path(os.readlink(proc / "cwd"))
        except OSError:  # gone, or another user's process
            continue
        if re.search(r"--backend[= ]gemini", " ".join(args)):
            continue
        for arg in args[1:3]:
            if SCRIPTS.search(arg) and ((cwd / arg).resolve().parent == ROOT):
                pids.append(int(proc.name))
    return pids


def main(dry: bool) -> None:
    pids = project_scripts()
    if pids:
        print(f"{'would stop' if dry else 'stopping'} learn_ai scripts: pids {' '.join(map(str, pids))}")
        if not dry:
            for pid in pids:
                os.kill(pid, signal.SIGTERM)
    for pattern in PROCESSES:
        pids = run(["pgrep", "-f", pattern]).split()
        if pids:
            print(f"{'would stop' if dry else 'stopping'} {pattern}: pids {' '.join(pids)}")
            if not dry:
                run(["pkill", "-TERM", "-f", pattern])

    containers = run(["docker", "ps", "-q", "--filter", "name=surya-vllm"]).split()
    if containers:
        print(f"{'would remove' if dry else 'removing'} vLLM containers: {' '.join(containers)}")
        if not dry:
            run(["docker", "rm", "-f", *containers])

    try:
        with urllib.request.urlopen(f"{OLLAMA}/api/ps", timeout=10) as r:
            models = [m["name"] for m in json.load(r)["models"]]
    except OSError as e:
        print(f"Ollama not reachable ({e}): nothing to unload")
        models = []
    for name in models:
        print(f"{'would unload' if dry else 'unloading'} Ollama model {name}")
        if not dry:
            body = json.dumps({"model": name, "keep_alive": 0}).encode()
            req = urllib.request.Request(f"{OLLAMA}/api/generate", data=body, headers={"Content-Type": "application/json"})
            urllib.request.urlopen(req, timeout=60).read()
    if not dry:
        print("GPU now:", run(["nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader"]) or "?")


if __name__ == "__main__":
    main(dry="--dry-run" in sys.argv)
