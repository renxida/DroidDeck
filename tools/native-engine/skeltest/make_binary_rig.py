"""Writes rig/rig.skel: the test rig (rig/rig.json) in the 4.2 binary skeleton layout.

Our own writer for our own synthetic rig, following the layout the clean-room loader reads
(tools/native-engine/modules/native_compat/skel/skel_binary.cpp). It covers what the rig uses:
bones, slots, region attachments, events, bone rotate timelines and event keys.
Run: python3 make_binary_rig.py  (from this directory; rewrites rig/rig.skel)
"""
import json
import struct
import sys


class Writer:
    def __init__(self):
        self.b = bytearray()

    def u8(self, v):
        self.b.append(v & 0xFF)

    def u32(self, v):
        self.b += struct.pack(">I", v & 0xFFFFFFFF)

    def f(self, v):
        self.b += struct.pack(">f", v)

    def v(self, x):
        x &= 0xFFFFFFFF
        while True:
            c = x & 0x7F
            x >>= 7
            self.u8(c | (0x80 if x else 0))
            if not x:
                return

    def zigzag(self, x):
        self.v((x << 1) ^ (x >> 31))

    def s(self, t):
        if t is None:
            self.v(0)
            return
        data = t.encode()
        self.v(len(data) + 1)
        self.b += data


def color(hexstr):
    hexstr = hexstr or "ffffffff"
    if len(hexstr) == 6:
        hexstr += "ff"
    return int(hexstr, 16)


def build(rig):
    w = Writer()
    w.b += b"skeltest"[:8].ljust(8, b"\0")
    w.s("4.2.43")
    for x in (0.0, 0.0, 0.0, 0.0, 100.0):
        w.f(x)
    w.u8(0)  # no editor-only data
    strings = []

    def ref(name):
        if name is None:
            return 0
        if name not in strings:
            strings.append(name)
        return strings.index(name) + 1

    # The string table comes first in the file, so collect names, then write the body after it.
    body = Writer()
    bones = rig["bones"]
    names = [b["name"] for b in bones]
    body.v(len(bones))
    for i, b in enumerate(bones):
        body.s(b["name"])
        if i:
            body.v(names.index(b["parent"]))
        for k, d in (("rotation", 0), ("x", 0), ("y", 0), ("scaleX", 1), ("scaleY", 1), ("shearX", 0), ("shearY", 0), ("length", 0)):
            body.f(b.get(k, d))
        body.u8(0)
        body.u8(0)
    slots = rig["slots"]
    slot_names = [s["name"] for s in slots]
    body.v(len(slots))
    for s in slots:
        body.s(s["name"])
        body.v(names.index(s["bone"]))
        body.u32(color(s.get("color")))
        body.u32(0xFFFFFFFF)
        body.v(ref(s.get("attachment")))
        body.v(0)
    for _ in range(4):  # ik, transform, path, physics constraints
        body.v(0)
    skin = rig["skins"][0]["attachments"]
    body.v(len(skin))
    for slot, atts in skin.items():
        body.v(slot_names.index(slot))
        body.v(len(atts))
        for key, a in atts.items():
            body.v(ref(key))
            body.u8(0)  # region, no extra fields
            for k, d in (("x", 0), ("y", 0), ("scaleX", 1), ("scaleY", 1), ("width", 32), ("height", 32)):
                body.f(a.get(k, d))
    body.v(0)  # named skins
    events = rig.get("events", {})
    event_names = list(events)
    body.v(len(events))
    for name, e in events.items():
        body.s(name)
        body.zigzag(e.get("int", 0))
        body.f(e.get("float", 0))
        body.s(e.get("string"))
        body.s(None)
    anims = rig.get("animations", {})
    body.v(len(anims))
    for name, a in anims.items():
        body.s(name)
        body.v(0)
        body.v(0)  # slot timelines
        bt = a.get("bones", {})
        body.v(len(bt))
        for bone, tls in bt.items():
            body.v(names.index(bone))
            body.v(len(tls))
            for kind, keys in tls.items():
                assert kind == "rotate", kind
                body.u8(0)
                body.v(len(keys))
                body.v(0)
                for i, k in enumerate(keys):
                    body.f(k.get("time", 0))
                    body.f(k.get("value", 0))
                    if i:
                        body.u8(0)  # linear
        for _ in range(5):  # ik, transform, path, physics, attachment timelines
            body.v(0)
        body.v(0)  # draw order
        evs = a.get("events", [])
        body.v(len(evs))
        for e in evs:
            ed = events[e["name"]]
            body.f(e.get("time", 0))
            body.v(event_names.index(e["name"]))
            body.zigzag(e.get("int", ed.get("int", 0)))
            body.f(e.get("float", ed.get("float", 0)))
            body.u8(0)
    w.v(len(strings))
    for t in strings:
        w.s(t)
    return bytes(w.b + body.b)


if __name__ == "__main__":
    rig = json.load(open("rig/rig.json"))
    out = build(rig)
    open("rig/rig.skel", "wb").write(out)
    print("wrote rig/rig.skel", len(out), "bytes")
    sys.exit(0)
