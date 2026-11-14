"""Loads an assembly the way Godot's exported builds load the game: hostfxr_initialize_for_runtime_config, then
load_assembly_and_get_function_pointer on the assembly itself, and calls ProbeLib.Probe.Entry(7) through the
returned [UnmanagedCallersOnly] pointer. Prints FXR_CHECK ok|refused and exits 0 either way; the caller compares.

usage: fxrhost.py <assembly.dll> <runtimeconfig.json> <libhostfxr.so>"""
import ctypes
import os
import sys

dll, config, fxr = sys.argv[1:4]
hostfxr = ctypes.CDLL(fxr)
handle = ctypes.c_void_p()
rc = hostfxr.hostfxr_initialize_for_runtime_config(config.encode(), None, ctypes.byref(handle))
if rc < 0:
    sys.exit("hostfxr_initialize_for_runtime_config failed: %#x" % (rc & 0xFFFFFFFF))
load = ctypes.c_void_p()
rc = hostfxr.hostfxr_get_runtime_delegate(handle, 5, ctypes.byref(load))  # hdt_load_assembly_and_get_function_pointer
if rc < 0:
    sys.exit("hostfxr_get_runtime_delegate failed: %#x" % (rc & 0xFFFFFFFF))
LOAD = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p,
                        ctypes.c_void_p, ctypes.POINTER(ctypes.c_void_p))
UNMANAGED_CALLERS_ONLY = ctypes.c_void_p(2 ** 64 - 1)  # (const char_t*)-1
entry = ctypes.c_void_p()
rc = LOAD(load.value)(os.path.abspath(dll).encode(), b"ProbeLib.Probe, ProbeLib", b"Entry", UNMANAGED_CALLERS_ONLY,
                      None, ctypes.byref(entry))
if rc < 0:
    print("FXR_CHECK refused %s: load_assembly_and_get_function_pointer %#x" % (dll, rc & 0xFFFFFFFF))
    sys.exit(0)
answer = ctypes.CFUNCTYPE(ctypes.c_int, ctypes.c_int)(entry.value)(7)
print("FXR_CHECK %s %s: Entry(7) = %d" % ("ok" if answer == 42 else "wrong", dll, answer))
