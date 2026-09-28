#!/usr/bin/env python3
"""Download every Chinese Kokoro voice pack this server can speak.

The server runs with HF_HUB_OFFLINE=1 (see the LaunchDaemon plist), so a voice
pack that is not already in the HuggingFace cache cannot be fetched at runtime -
a missing speaker used to raise a bare 500. Run this once (and again after a
Kokoro release) with the server's own venv, which is allowed to use the network:

    cd tts-server && .venv-local/bin/python fetch_voices.py

It asks the Hub which voices exist instead of trusting a hard-coded list, so a
new model version needs no edit here.

    --list      only print what is available / missing, download nothing
    --repo R    limit to one repo (default: both Chinese ones)
"""
import argparse
import json
import os
import time
import urllib.request

V1 = "hexgrad/Kokoro-82M"
V11 = "hexgrad/Kokoro-82M-v1.1-zh"
ZH_PREFIXES = ("zf_", "zm_")


def voices_of(repo):
    """The Mandarin voice packs of [repo], from the Hub's own file list."""
    url = "https://huggingface.co/api/models/" + repo
    with urllib.request.urlopen(url, timeout=60) as r:
        files = [s["rfilename"] for s in json.load(r)["siblings"]]
    return sorted(
        f.split("/")[-1][:-3] for f in files
        if f.startswith("voices/") and f.split("/")[-1][:3] in ZH_PREFIXES
    )


def cached(repo, voice):
    from huggingface_hub import try_to_load_from_cache
    path = try_to_load_from_cache(repo_id=repo, filename=f"voices/{voice}.pt")
    return isinstance(path, str) and os.path.exists(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--list", action="store_true", help="report only, download nothing")
    ap.add_argument("--repo", default=None, help="only this repo")
    args = ap.parse_args()

    repos = [args.repo] if args.repo else [V1, V11]
    total = missing = 0
    for repo in repos:
        voices = voices_of(repo)
        todo = [v for v in voices if not cached(repo, v)]
        total += len(voices)
        missing += len(todo)
        print("%s: %d Mandarin voices, %d not cached" % (repo, len(voices), len(todo)),
              flush=True)
        if args.list:
            continue
        from huggingface_hub import hf_hub_download
        for v in todo:
            t0 = time.time()
            hf_hub_download(repo_id=repo, filename="voices/%s.pt" % v)
            print("  downloaded %-10s %.1fs" % (v, time.time() - t0), flush=True)
    print("\n%d voices, %d were missing" % (total, missing))


if __name__ == "__main__":
    main()
