#!/usr/bin/env python3
"""Sum a process's /proc/<pid>/smaps by owner, the way the app's fex-smaps experiment does (SmapsRollup.kt), for the
off-device bench. FEX names its anonymous memory (FEXMemJIT, FEXMem_Lookup, FEXMem_Lookup_L1, ...), so FEX's share is
exact; the guest's own memory is unnamed anon.

  smaps_rollup.py watch <pid> <out.json> [period_s]   # sample until the process exits; keep the last and peak sample
  smaps_rollup.py table <label>=<out.json> ...         # markdown table of FEX categories per config
"""
import json, os, re, sys, time

HEAD = re.compile(r"^([0-9a-f]+)-([0-9a-f]+)\s+(\S+)\s+\S+\s+\S+\s+\d+\s*(.*)$")
FIELD = re.compile(r"^(\w+):\s+(\d+) kB")


def parse(text):
    out, cur = [], None
    for line in text.splitlines():
        m = HEAD.match(line)
        if m:
            cur = {"start": int(m.group(1), 16), "end": int(m.group(2), 16), "path": m.group(4).strip()}
            out.append(cur)
            continue
        f = FIELD.match(line)
        if f and cur is not None:
            cur[f.group(1)] = int(f.group(2))
    return out


def category(path):
    if path.startswith("[anon:FEXMem"):
        n = path[len("[anon:FEXMem"):].rstrip("]").lstrip("_").lower()
        return {"jit": "fex-code", "lookup": "fex-lookup-l2", "lookup_l1": "fex-lookup-l1", "": "fex-other"}.get(
            n, "fex-" + n.replace("_", "-"))
    if path == "":
        return "anon"
    if path.startswith("[anon:"):
        return "anon-named"
    if path.startswith("["):
        return "kernel" if path not in ("[heap]", "[stack]") else path.strip("[]")
    return "file"


def rollup(vmas):
    cats = {}
    for v in vmas:
        c = cats.setdefault(category(v["path"]), {"vmas": 0, "rss": 0, "swap": 0, "size": 0})
        c["vmas"] += 1
        c["rss"] += v.get("Rss", 0)
        c["swap"] += v.get("Swap", 0)
        c["size"] += (v["end"] - v["start"]) // 1024
    per_vma = {}
    for name in ("fex-lookup-l2", "fex-lookup-l1"):
        per_vma[name] = sorted((v.get("Rss", 0) for v in vmas if category(v["path"]) == name), reverse=True)
    return cats, per_vma


def threads(pid):
    try:
        return int(re.search(r"^Threads:\s+(\d+)", open(f"/proc/{pid}/status").read(), re.M).group(1))
    except Exception:
        return 0


def pids_under(root):
    """root and its descendants (FEX may exec the guest as a child)."""
    kids = {}
    for d in os.listdir("/proc"):
        if d.isdigit():
            try:
                ppid = int(open(f"/proc/{d}/stat").read().rsplit(")", 1)[1].split()[1])
                kids.setdefault(ppid, []).append(int(d))
            except Exception:
                pass
    out, todo = [], [root]
    while todo:
        p = todo.pop()
        out.append(p)
        todo += kids.get(p, [])
    return out


def watch(pid, out, period):
    last = peak = None
    while os.path.exists(f"/proc/{pid}"):
        best = None
        for p in pids_under(pid):
            try:
                vmas = parse(open(f"/proc/{p}/smaps").read())
            except Exception:
                continue
            cats, per = rollup(vmas)
            fex = sum(c["rss"] for k, c in cats.items() if k.startswith("fex-"))
            if best is None or fex > best["fex_rss"]:
                best = {"pid": p, "threads": threads(p), "cats": cats, "per_vma": per, "fex_rss": fex,
                        "rss": sum(c["rss"] for c in cats.values())}
        if best:
            last = best
            if peak is None or best["fex_rss"] > peak["fex_rss"]:
                peak = best
        time.sleep(period)
    json.dump({"last": last, "peak": peak}, open(out, "w"))


ORDER = ["fex-code", "fex-lookup-l2", "fex-lookup-l1", "fex-blocklinks", "fex-threadstate", "fex-callretstacks", "fex-misc", "fex-other"]


def table(args):
    print("| config | threads | " + " | ".join(ORDER) + " | other FEX | all FEX | guest anon | process RSS | L2 per thread (largest, median) | L1 per thread (largest, median) |")
    print("|" + "---|" * (len(ORDER) + 8))
    mb = lambda kb: "%.1f" % (kb / 1024)
    for a in args:
        label, path = a.split("=", 1)
        try:
            s = json.load(open(path))["peak"]
        except Exception:
            print(f"| {label} | (no data) |")
            continue
        if not s:
            print(f"| {label} | (no data) |")
            continue
        cats = s["cats"]
        g = lambda k: cats.get(k, {}).get("rss", 0)
        other = sum(c["rss"] for k, c in cats.items() if k.startswith("fex-") and k not in ORDER)
        def dist(xs):
            return "%s, %s" % (mb(xs[0]), mb(xs[len(xs) // 2])) if xs else "-"
        print(f"| {label} | {s['threads']} | " + " | ".join(mb(g(k)) for k in ORDER) +
              f" | {mb(other)} | {mb(s['fex_rss'])} | {mb(g('anon'))} | {mb(s['rss'])} | {dist(s['per_vma']['fex-lookup-l2'])} ({len(s['per_vma']['fex-lookup-l2'])}) | {dist(s['per_vma']['fex-lookup-l1'])} |")
    print("\nMB resident at the sample where FEX's own memory peaked. One L2 and one L1 mapping per translated thread.")


if __name__ == "__main__":
    if sys.argv[1] == "watch":
        watch(int(sys.argv[2]), sys.argv[3], float(sys.argv[4]) if len(sys.argv) > 4 else 0.5)
    elif sys.argv[1] == "table":
        table(sys.argv[2:])
