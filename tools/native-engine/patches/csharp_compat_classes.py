#!/usr/bin/env python3
"""Let the game's C# reach our compat classes the way it reached the GDExtensions they stand in for.

    csharp_compat_classes.py GODOT_SRC REGISTER_TYPES_CPP

The game's GodotSharp.dll was generated from an engine without our classes, so it has no C# wrapper for
SpineSprite, FmodServer and the rest. When such an object reaches C# (GetNode, a signal argument, a call's
return), Godot looks up the wrapper for the object's class and throws "Wrapper class not found for type:
SpineSprite" (Nov 15, the phone: NLogoAnimation._Ready, NSpineAutoPlayer._Ready, MegaSpineBinding..ctor).
In the game's own engine those classes come from GDExtensions, which Godot's C# skips on purpose, handing
out the nearest engine base class (Node2D, RefCounted, Object) instead; the game then talks to them by
method name. Godot keeps a list for exactly this, csharp_script.cpp's ignored_types; this fills it with
every class native_compat registers, read from its register_types.cpp, so the two never drift apart.
Fails unless the line is found exactly once and at least one class is listed.
"""
import os
import re
import sys

LINE = "const Vector<String> ignored_types = {};"


def compat_classes(register_types):
    return sorted(set(re.findall(r"^\s*GDREGISTER_(?:ABSTRACT_|VIRTUAL_)?CLASS\((\w+)\)", register_types, re.M)))


def patch(source, names):
    if source.count(LINE) != 1:
        raise SystemExit(f"csharp_compat_classes: expected exactly one '{LINE}' in csharp_script.cpp")
    if not names:
        raise SystemExit("csharp_compat_classes: no GDREGISTER_CLASS in register_types.cpp")
    listed = ", ".join(f'"{n}"' for n in names)
    return source.replace(LINE, "// Kestrel native_compat classes: C# sees their engine base class, as for the GDExtensions they"
                          " replace.\nconst Vector<String> ignored_types = { " + listed + " };")


def main(argv):
    godot, register = argv
    path = os.path.join(godot, "modules", "mono", "csharp_script.cpp")
    with open(register) as f:
        names = compat_classes(f.read())
    with open(path) as f:
        text = f.read()
    with open(path, "w") as f:
        f.write(patch(text, names))
    print(f"csharp_compat_classes: {len(names)} classes C# sees by their engine base: {', '.join(names)}")


if __name__ == "__main__":
    main(sys.argv[1:])
