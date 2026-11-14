# Wine copies an embedded Godot pack into memory (Nov 14, 2026)

On the phone, Slay the Spire 2's process had a 766 MB read-only anonymous region next to its code, 6 MB
resident and 760 MB in swap; the .exe is 895 MB. A Godot Windows export with the pack embedded keeps the
.pck in a PE section of the .exe.

## Cause

Wine maps a PE section straight from the file only when the section starts at a multiple of the host page
(4096) in the file. Otherwise `mmap` fails with EINVAL and Wine `pread`s the whole section into the image's
anonymous memory at load (`dlls/ntdll/unix/virtual.c`, `map_file_into_view`, wine-11.16). PE linkers default to
a 512-byte FileAlignment, so a large section usually starts at an offset Wine cannot map. Godot reads its pack
through the file (`FileAccess` on the executable path), never through the mapped section, so the copy is read
once at load and then only costs memory, and on the phone that memory is compressed swap.

## Reproduced off-device

`tools/wine/pe-align/` on `emu/winetest-pe` (`.github/workflows/wine-pe-align.yml`, run 43630466348): a
stand-in .exe with a 256 MB random `.pck` section at file offset 0x2600, run under Hangover 11.16 on an arm64
runner, x86-64 (through FEX) and arm64 alike. No game files.

| exe | `.pck` mapping | Rss | Anonymous | load to `main` |
|---|---|---|---|---|
| as linked (FileAlignment 512) | anonymous | 256 MB | 256 MB | 1.0-1.5 s |
| realigned (every section at 4096) | the .exe file | 8 kB | 8 kB | 0.6 s |
| realigned, every page read | the .exe file | 256 MB | 8 kB | 0.9 s |

Realigned, the section is page cache: nothing is copied, untouched pages cost nothing, and touched ones can be
dropped and read back instead of going to swap.

Still to confirm on the real .exe: `objdump -h` (or `llvm-readobj --sections`) of the game's .exe should show the
pack section with a file offset that is not a multiple of 0x1000. Run that only where the game files are; never
copy them into a public run.

## Fix (experiment `pe-align`, off by default)

With `DROIDDECK_EXP_PE_ALIGN=1`, `droiddeck-game-env` runs the game from a copy of its .exe in which every
section starts at a 4096-byte file offset (`droiddeck-warmup`, `pe_aligned_exe`), when the .exe is 64 MB or more
and has a section of 1 MB or more Wine would copy. The copy:

- keeps the original's name, in `.droiddeck-aligned/` inside the game's folder, which links to everything else in
  the folder, so the game finds its data folder and DLLs and its process keeps its name;
- keeps every section's size and the bytes after the last section (Godot finds the pack from the end of the
  file); the symbol table, certificate table and debug directory pointers move with their data; the checksum is
  cleared;
- is made once, streaming (no full read into memory), and remade when the original's size or time changes, i.e.
  after a game update; it needs free space for one more copy of the .exe (895 MB for this game) and is skipped
  with a log line if there is not that plus 512 MB.

Costs and risks: one extra copy of the .exe on disk; the first launch after an update spends a few seconds
copying; a signed .exe's signature no longer matches (Wine does not check it); a DRM wrapper that checks its own
file would notice. Steam leaves the extra folder alone and does not remove it on uninstall.

A Wine-side fix (fill such sections on first touch instead of at load) would need no copy, but it means
patching the Proton build's ntdll.
