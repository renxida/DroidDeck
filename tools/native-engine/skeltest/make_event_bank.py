"""Writes sound/events.bank: a synthetic sound bank for the engine test. Event GUID 2 (the one
sound/paths.strings.bank names "Event:/x") plays a multi instrument whose one playlist entry is a waveform
instrument (-6 dB) for sound 0 of sound/tone.fsb, which is embedded as the bank's audio block. Event GUID 30
(no path) plays the same waveform instrument from a timeline with a loop region, so it loops until stopped.
Our own data in the layout fsb/event_table.h describes. Run: python3 make_event_bank.py   (from this directory)
"""
import struct


def guid(n):
    # {0000000n-0000-4000-8000-00000000000n}: first field LE u32, then two LE u16s, then 8 bytes as written.
    return struct.pack("<IHH", n, 0, 0x4000) + bytes([0x80, 0, 0, 0, 0, 0, 0, n])


def chunk(cid, body):
    return cid + struct.pack("<I", len(body)) + body + (b"\0" if len(body) & 1 else b"")


def lst(kind, *children):
    return chunk(b"LIST", kind + b"".join(children))


def inst(volume_db):
    b = bytearray(130)
    b[16:20] = struct.pack("<f", volume_db)
    return bytes(b)


EVENT, TIMELINE, MULTI, WAIT, WAVE = guid(2), guid(20), guid(21), guid(22), guid(23)
LOOP_EVENT, LOOP_TIMELINE, REGION = guid(30), guid(31), guid(32)


def evtb(event, timeline, instruments):
    return (event + b"\0" * 16 + timeline + b"\xff" * 21 + struct.pack("<HH", len(instruments) * 2 + 1, 16)
            + b"".join(instruments) + struct.pack("<f", 1))


tlnb = TIMELINE + struct.pack("<HHH", 1, 0 * 2 + 1, 24)
# The waveform instrument placed at 0..24000, then an empty array, then one loop region over 0..24000.
loop_tlnb = (LOOP_TIMELINE + struct.pack("<HHH", 1, 1 * 2 + 1, 24) + WAIT + struct.pack("<II", 0, 24000)
             + struct.pack("<HHH", 0, 1 * 2, 26) + REGION + struct.pack("<IHI", 0, 0, 24000) + struct.pack("<H", 1))
plst = struct.pack("<IIHH", 0, 1, 1 * 2 + 1, 20) + WAIT + struct.pack("<f", 100)
wav = WAVE + struct.pack("<IHII", 12, 0, 0, 0)
proj = lst(b"PROJ",
           chunk(b"BNKI", b"\0" * 32),
           lst(b"EVTS", lst(b"EVNT", chunk(b"EVTB", evtb(EVENT, TIMELINE, [MULTI]))),
               lst(b"EVNT", chunk(b"EVTB", evtb(LOOP_EVENT, LOOP_TIMELINE, [])))),
           lst(b"TLNS", lst(b"TMLN", chunk(b"TLNB", tlnb)), lst(b"TMLN", chunk(b"TLNB", loop_tlnb))),
           lst(b"MUIS", lst(b"MUIT", chunk(b"MUIB", MULTI), chunk(b"INST", inst(0)), chunk(b"PLST", plst))),
           lst(b"WAIS", lst(b"WAIT", chunk(b"WAIB", WAIT + WAVE), chunk(b"INST", inst(-6)))),
           lst(b"WAVS", chunk(b"WAV ", wav)),
           chunk(b"SND ", b"\0" * 8 + open("sound/tone.fsb", "rb").read()))
out = chunk(b"RIFF", b"FEV " + chunk(b"FMT ", b"\0" * 8) + proj)
open("sound/events.bank", "wb").write(out)
print("wrote sound/events.bank", len(out), "bytes")
