#!/usr/bin/env python3
"""Copies a PE file so that every section's file offset is a multiple of 4096 (FileAlignment 0x1000).

Wine maps a section straight from the file only when its file offset is a multiple of the host page; otherwise it
reads the whole section into anonymous memory at load (dlls/ntdll/unix/virtual.c, map_file_into_view). Godot's
Windows exports keep the game's .pck in a section at a 512-byte offset, so Wine copies hundreds of MB into
memory that is never read (Godot reads the pack through the file). The copy this writes loads with every section
file-backed. Only the gaps before sections grow: section sizes stay, and the last section and whatever follows it
(Godot's pck trailer, which Godot finds from the end of the file; a signature) stay together at the end. The
certificate directory, if any, moves with them, and the checksum is cleared.

usage: realign.py IN OUT
"""
import struct
import sys

PAGE = 0x1000


def up(value, align):
    return (value + align - 1) & ~(align - 1)


def rva_to_offset(data, sections_at, count, rva):
    for i in range(count):
        size, va, raw_size, raw_ptr = struct.unpack_from("<IIII", data, sections_at + 40 * i + 8)
        if va <= rva < va + max(size, raw_size):
            return raw_ptr + rva - va if rva - va < raw_size else None
    return None


def realign(data):
    if data[:2] != b"MZ":
        raise ValueError("not a PE file")
    pe = struct.unpack_from("<I", data, 0x3C)[0]
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("no PE header")
    count = struct.unpack_from("<H", data, pe + 6)[0]
    opt_size = struct.unpack_from("<H", data, pe + 20)[0]
    opt = pe + 24
    magic = struct.unpack_from("<H", data, opt)[0]
    plus = magic == 0x20B
    file_align_at = opt + 36
    headers_at = opt + 60
    checksum_at = opt + 64
    dirs_at = opt + (112 if plus else 96)
    sections_at = opt + opt_size
    sections = []
    for i in range(count):
        at = sections_at + 40 * i
        raw_size, raw_ptr = struct.unpack_from("<II", data, at + 16)
        sections.append((at, raw_ptr, raw_size))
    old_headers = struct.unpack_from("<I", data, headers_at)[0]
    headers = up(old_headers, PAGE)
    out = bytearray(data[:old_headers])
    out += b"\0" * (headers - len(out))
    struct.pack_into("<I", out, headers_at, headers)
    struct.pack_into("<I", out, file_align_at, PAGE)
    struct.pack_into("<I", out, checksum_at, 0)
    moved = []  # (old start, old end, new start)
    end = old_headers
    for at, raw_ptr, raw_size in sorted(sections, key=lambda s: s[1]):
        if not raw_ptr or not raw_size:
            continue
        if raw_ptr < end:
            raise ValueError("overlapping sections")
        out += b"\0" * (up(len(out), PAGE) - len(out))
        struct.pack_into("<I", out, at + 20, len(out))
        moved.append((raw_ptr, raw_ptr + raw_size, len(out)))
        out += data[raw_ptr:raw_ptr + raw_size]
        end = raw_ptr + raw_size
    # Anything after the last section (Godot's pck trailer, a COFF symbol table, an Authenticode signature) keeps
    # its place right after it, so it stays at the end of the file.
    moved.append((end, len(data), len(out)))
    out += data[end:]

    def new_offset(old):
        for start, stop, to in moved:
            if start <= old < stop or (old == stop == len(data)):
                return to + old - start
        return old

    symbols = struct.unpack_from("<I", data, pe + 12)[0]
    if symbols:
        struct.pack_into("<I", out, pe + 12, new_offset(symbols))
    entries = struct.unpack_from("<I", data, dirs_at - 4)[0]
    if entries > 4:  # the certificate table's "address" is a file offset
        cert_at, cert_size = struct.unpack_from("<II", data, dirs_at + 8 * 4)
        if cert_size:
            struct.pack_into("<I", out, dirs_at + 8 * 4, new_offset(cert_at))
    if entries > 6:  # debug directory entries carry the file offset of their data
        debug_rva, debug_size = struct.unpack_from("<II", data, dirs_at + 8 * 6)
        debug_at = rva_to_offset(data, sections_at, count, debug_rva) if debug_size else None
        if debug_at is not None:
            for i in range(debug_size // 28):
                old = struct.unpack_from("<I", data, debug_at + 28 * i + 24)[0]
                if old:
                    struct.pack_into("<I", out, new_offset(debug_at) + 28 * i + 24, new_offset(old))
    return bytes(out)


def main(argv):
    if len(argv) != 3:
        print(__doc__, file=sys.stderr)
        return 2
    with open(argv[1], "rb") as f:
        data = f.read()
    with open(argv[2], "wb") as f:
        f.write(realign(data))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
