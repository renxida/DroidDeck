#!/usr/bin/env python3
"""Pick the engine component the app build embeds, so a published engine reaches the app without anyone
copying its tag.

    resolve_component.py --wrapper PATH --out PIN.json [--report REPORT.json] [--repo OWNER/NAME] [--tag native-engine-N]

Candidates are the wrapper's own COMPONENT (the committed pin) and every published (not draft) release of this
repository tagged native-engine-N whose component.json asset says it is that tag, passed the startup gate, and
has the wrapper's COMPONENT_API, with a sha256 that matches the release's own record of native-engine.tar.zst.
The highest N wins. With --tag only that release (or the committed pin, if it is that tag) is accepted, so a
caller can reuse an earlier resolution. The pin is written to --out in the form the wrapper reads
(droiddeck-native-godot, PIN_FILE); --report gets the same plus where it came from and what was passed over.
When GitHub cannot be read the committed pin is used and the report says so; the build still gates it.
"""
import argparse
import json
import os
import re
import runpy
import sys
import urllib.request

TAG = re.compile(r"native-engine-(\d+)")
ASSET = "native-engine.tar.zst"
MANIFEST = "component.json"


def http_json(url, token=None, opener=urllib.request.urlopen):
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "droiddeck-ci"}
    if token and url.startswith("https://api.github.com/"):
        headers["Authorization"] = "Bearer " + token
    with opener(urllib.request.Request(url, headers=headers), timeout=30) as r:
        return json.load(r)


def releases(repo, token=None, get=http_json):
    out, page = [], 1
    while True:
        batch = get(f"https://api.github.com/repos/{repo}/releases?per_page=100&page={page}", token)
        out += batch
        if len(batch) < 100:
            return out
        page += 1


def number(tag):
    m = TAG.fullmatch(tag or "")
    return int(m.group(1)) if m else -1


def candidate(release, api, get, token=None):
    """(pin, None) for a release the app may embed, or (None, why not)."""
    tag = release.get("tag_name", "")
    if not TAG.fullmatch(tag):
        return None, None
    if release.get("draft"):
        return None, f"{tag}: draft"
    assets = {a.get("name"): a for a in release.get("assets") or []}
    if ASSET not in assets:
        return None, f"{tag}: no {ASSET}"
    if MANIFEST not in assets:
        return None, f"{tag}: no {MANIFEST} (published before releases carried one)"
    try:
        manifest = get(assets[MANIFEST]["browser_download_url"], token)
    except Exception as error:
        return None, f"{tag}: {MANIFEST} unreadable ({error})"
    if manifest.get("tag") != tag:
        return None, f"{tag}: {MANIFEST} names {manifest.get('tag')}"
    if manifest.get("gate") != "passed":
        return None, f"{tag}: gate {manifest.get('gate')!r}"
    if manifest.get("api") != api:
        return None, f"{tag}: component api {manifest.get('api')}, this wrapper takes {api}"
    sha = str(manifest.get("sha256", ""))
    digest = str(assets[ASSET].get("digest") or "")
    if not re.fullmatch(r"[0-9a-f]{64}", sha) or (digest and digest != "sha256:" + sha):
        return None, f"{tag}: sha256 {sha[:12]} does not match the release's {digest[:19]}"
    url = assets[ASSET].get("browser_download_url", "")
    return {"tag": tag, "url": url, "sha256": sha, "api": api,
            "source_sha": manifest.get("source_sha"), "gate_run": manifest.get("gate_run")}, None


def resolve(committed, api, listing, get, token=None, want=None):
    """(pin, source, skipped). listing is the repository's releases, or None when they could not be read."""
    best = dict(committed, api=api)
    source, skipped = "committed", []
    if listing is None:
        return best, "committed (releases unreadable)", skipped
    for release in listing:
        if want and release.get("tag_name") != want:
            continue
        pin, why = candidate(release, api, get, token)
        if why:
            skipped.append(why)
        if pin and (want or number(pin["tag"]) > number(best["tag"])):
            best, source = pin, "release"
    if want and best["tag"] != want:
        raise SystemExit(f"{want} is not a usable engine release: " + "; ".join(skipped or ["not found"]))
    return best, source, skipped


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--wrapper", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--report")
    ap.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "familiar-of-cedar/lanner"))
    ap.add_argument("--tag", help="accept only this release")
    a = ap.parse_args(argv)
    ng = runpy.run_path(a.wrapper)
    committed = {k: ng["COMPONENT"][k] for k in ("tag", "url", "sha256")}
    api = ng["COMPONENT_API"]
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    try:
        listing = releases(a.repo, token)
    except Exception as error:
        print(f"::warning::engine releases unreadable ({error}); embedding the committed pin {committed['tag']}")
        listing = None
    pin, source, skipped = resolve(committed, api, listing, http_json, token, a.tag)
    if not ng["COMPONENT_URL"].fullmatch(pin["url"]):
        raise SystemExit(f"{pin['tag']}: unexpected download url {pin['url']}")
    os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
    with open(a.out, "w") as f:
        json.dump({k: pin[k] for k in ("tag", "url", "sha256", "api")}, f, indent=1)
        f.write("\n")
    report = dict(pin, source=source, committed=committed["tag"], passed_over=skipped)
    if a.report:
        with open(a.report, "w") as f:
            json.dump(report, f, indent=1)
            f.write("\n")
    print(f"engine component: {pin['tag']} ({source}; committed pin {committed['tag']}) sha256 {pin['sha256']}")
    for why in skipped:
        print(f"  passed over {why}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
