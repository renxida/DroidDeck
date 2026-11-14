#!/usr/bin/env python3
"""Rows for the nightly FEX soak: a GitHub Actions matrix (JSON on stdout).

    nightly-plan.py <newest .wcp> [<older .wcp> ...]   (names in the kestrel-fex release, newest first)

Rows:
  - stock FEX and each given Kestrel .wcp with default settings;
  - the newest .wcp with each Kestrel option alone, every pair, every option but one, and all together, at the
    values the phone's experiments use (droiddeck-game-env);
  - every row REPEATS times, to catch hangs that only show now and then;
  - a long soak of the newest .wcp (defaults, and all options on) when SOAK_SECONDS is set.
"""
import itertools
import json
import os
import sys

# Option name -> environment, as droiddeck-game-env sets them for the matching experiment.
OPTIONS = {
    "l1": "FEX_DYNAMICL1CACHE=0 FEX_L1CACHEENTRIES=262144",
    "codebuf": "FEX_INITIALCODEBUFFERMB=64",
    "sample": "FEX_SAMPLEHZ=250",
    "smaps": "FEX_NAMEWINDOWSMEMORY=60",
    "trim": "FEX_TRIMIDLETHREADS=30",
    "wholeblocks": "FEX_VOLATILEMETADATA=1 FEX_VOLATILEMETADATAWHOLEBLOCKS=1",
}


def main():
    wcps = sys.argv[1:]
    repeats = int(os.environ.get("REPEATS", "3"))
    seconds = os.environ.get("SECONDS_PER_RUN", "120")
    soak = os.environ.get("SOAK_SECONDS", "")
    rows = []

    def add(name, wcp, env, secs=seconds, programs="stress clr stress2"):
        for r in range(1, repeats + 1):
            rows.append({"name": f"{name} #{r}", "group": name, "id": f"{name}.{r}".replace("+", "_"), "wcp": wcp, "env": env, "seconds": secs, "programs": programs})

    add("stock", "stock", "")
    for w in wcps:
        add(w.removeprefix("Kestrel-FEXCore-").removesuffix(".wcp"), w, "")
    if wcps:
        newest = wcps[0]
        names = list(OPTIONS)
        combos = [(n,) for n in names]
        combos += list(itertools.combinations(names, 2))
        combos += [tuple(x for x in names if x != left) for left in names]
        combos.append(tuple(names))
        seen = set()
        for c in combos:
            if c in seen:
                continue
            seen.add(c)
            label = "all" if len(c) == len(names) else ("all-but-" + next(n for n in names if n not in c)
                                                         if len(c) == len(names) - 1 else "+".join(c))
            add(label, newest, " ".join(OPTIONS[n] for n in c))
        if soak:
            saved = repeats
            repeats = 1
            add("soak", newest, "", soak, "stress clr")
            add("soak-all", newest, " ".join(OPTIONS.values()), soak, "stress clr")
            repeats = saved
    print(json.dumps({"include": rows}))
    print(f"{len(rows)} rows", file=sys.stderr)


if __name__ == "__main__":
    main()
