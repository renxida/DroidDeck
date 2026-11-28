"""Writes sound/tone.fsb: a synthetic FSB5 container with one Vorbis sound (0.5 s, stereo, 48 kHz:
440 Hz left, 660 Hz right) for the engine test. Our own data, encoded with the system libvorbis (BSD)
at the setting the game's banks use, so its setup id is the one NativeSoundBank rebuilds.
Run: python3 make_sound_bank.py   (from this directory; needs libvorbisenc.so.2 and libvorbis.so.0)
"""
import ctypes
import math
import struct
import zlib

RATE, CHANNELS, QUALITY, SECONDS = 48000, 2, 0.3, 0.5
SETUP_ID = 0xC4C30A29

venc = ctypes.CDLL("libvorbisenc.so.2")
vorbis = ctypes.CDLL("libvorbis.so.0")


class Packet(ctypes.Structure):
    _fields_ = [("packet", ctypes.POINTER(ctypes.c_ubyte)), ("bytes", ctypes.c_long), ("b_o_s", ctypes.c_long),
                ("e_o_s", ctypes.c_long), ("granulepos", ctypes.c_int64), ("packetno", ctypes.c_int64)]


vorbis.vorbis_analysis_buffer.restype = ctypes.POINTER(ctypes.POINTER(ctypes.c_float))


def encode():
    info, comment = ctypes.create_string_buffer(4096), ctypes.create_string_buffer(4096)
    dsp, block = ctypes.create_string_buffer(65536), ctypes.create_string_buffer(65536)
    vorbis.vorbis_info_init(info)
    assert venc.vorbis_encode_init_vbr(info, CHANNELS, RATE, ctypes.c_float(QUALITY)) == 0
    vorbis.vorbis_comment_init(comment)
    vorbis.vorbis_analysis_init(dsp, info)
    vorbis.vorbis_block_init(dsp, block)
    heads = [Packet(), Packet(), Packet()]
    vorbis.vorbis_analysis_headerout(dsp, comment, *[ctypes.byref(p) for p in heads])
    setup = bytes(ctypes.string_at(heads[2].packet, heads[2].bytes))
    assert zlib.crc32(setup) & 0xFFFFFFFF == SETUP_ID, "libvorbis wrote a different setup header"
    frames = int(RATE * SECONDS)
    packets = []

    def drain():
        p = Packet()
        while vorbis.vorbis_analysis_blockout(dsp, block) == 1:
            vorbis.vorbis_analysis(block, None)
            vorbis.vorbis_bitrate_addblock(block)
            while vorbis.vorbis_bitrate_flushpacket(dsp, ctypes.byref(p)):
                packets.append(bytes(ctypes.string_at(p.packet, p.bytes)))

    step = 1024
    for start in range(0, frames, step):
        n = min(step, frames - start)
        buf = vorbis.vorbis_analysis_buffer(dsp, n)
        for i in range(n):
            t = (start + i) / RATE
            buf[0][i] = 0.5 * math.sin(2 * math.pi * 440 * t)
            buf[1][i] = 0.5 * math.sin(2 * math.pi * 660 * t)
        vorbis.vorbis_analysis_wrote(dsp, n)
        drain()
    vorbis.vorbis_analysis_wrote(dsp, 0)
    drain()
    return packets, frames


def container(name, packets, frames):
    data = b"".join(struct.pack("<H", len(p)) + p for p in packets) + b"\0\0"
    data += b"\0" * (-len(data) % 32)
    mode = 1 | (9 << 1) | (1 << 5) | (0 << 7) | (frames << 34)  # 48 kHz, stereo, offset 0, extra chunk
    headers = struct.pack("<QI", mode, (8 << 1) | (11 << 25)) + struct.pack("<II", SETUP_ID, 0)
    names = struct.pack("<I", 4) + name.encode() + b"\0"
    names += b"\0" * (-len(names) % 4)
    head = b"FSB5" + struct.pack("<6I", 1, 1, len(headers), len(names), len(data), 15)
    head += b"\0" * (0x3C - len(head))
    return head + headers + names + data


if __name__ == "__main__":
    packets, frames = encode()
    out = container("tone", packets, frames)
    open("sound/tone.fsb", "wb").write(out)
    print("wrote sound/tone.fsb", len(out), "bytes,", len(packets), "packets,", frames, "frames")
