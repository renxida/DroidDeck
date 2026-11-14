"""Writes header variants of a Windows x64 assembly into OUT/<variant>/<name> for the load probe."""
import os, runpy, struct, sys
ng = runpy.run_path(sys.argv[1])
src, out = sys.argv[2], sys.argv[3]
data = open(src, "rb").read()
info = ng["pe_info"](data)
name = os.path.basename(src)
print(name, "machine %#x flags %#x r2r %s native %s fixups %s" % (info["machine"], info["flags"], info["ready_to_run"], info["native_header"], info["vtable_fixups"]))

def variant(tag, machine=None, flags=None, clear_native=False):
    b = bytearray(data)
    if machine is not None:
        struct.pack_into("<H", b, info["machine_at"], machine)
    if flags is not None:
        struct.pack_into("<I", b, info["flags_at"], flags)
    if clear_native:
        struct.pack_into("<II", b, info["native_header_at"], 0, 0)
    os.makedirs(os.path.join(out, tag), exist_ok=True)
    open(os.path.join(out, tag, name), "wb").write(b)

f = info["flags"]
variant("a-untouched")
variant("b-arm64-label", machine=0xAA64)
variant("c-nonative", clear_native=True, flags=f & ~4)
variant("d-current", machine=0xAA64, clear_native=True, flags=(f & ~4) | 1)
variant("e-amd64-ilonly-nonative", clear_native=True, flags=(f & ~4) | 1)
variant("f-arm64-nonative-noilonly", machine=0xAA64, clear_native=True, flags=f & ~4)
try:
    img, note = ng["portable_image"](data)
    if img is not None:
        os.makedirs(os.path.join(out, "z-portable_image"), exist_ok=True)
        open(os.path.join(out, "z-portable_image", name), "wb").write(img)
        print("portable_image:", note)
except Exception as e:
    print("portable_image raised", e)
