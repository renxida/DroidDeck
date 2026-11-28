#!/usr/bin/env python3
"""Count why the 2D renderer starts each new batch, for the render census.

    canvas_batch_census.py GODOT_SRC

Each batch the canvas renderer records becomes at least one draw, and on the phone every draw costs about
37 us of main-thread time (Nov 24 vkcount slope). RendererCanvasRenderRD starts a new batch from about twenty
places: a clip change, a material change, a texture change, a different command type and so on. This patch
gives each place an id and counts, per id, the batches that place actually started (a place whose batch was
already fresh counts nothing, so each new batch is credited to the first reason that broke it). It also counts
draw lists. The render census module reads and clears the counts through:

    int droiddeck_canvas_batch_sites();               // number of ids
    const char *droiddeck_canvas_batch_site(int id);  // short label, e.g. "rect: texture"
    uint32_t droiddeck_canvas_batch_take(int id);     // count since the last take, then zero

Counting is off unless DROIDDECK_NATIVE_RENDER_CENSUS is set (read once), so the default render path only gains
one well-predicted branch per new batch. Labels come from the condition at each call site; a site this script
does not recognise is labelled by its line. Fails unless the declaration and the definition are each found once
and at least ten call sites are found.
"""
import os
import re
import sys

DECL = "[[nodiscard]] Batch *_new_batch(bool &r_batch_broken);"
DECL_NEW = "[[nodiscard]] Batch *_new_batch(bool &r_batch_broken, int p_droiddeck_site = 0);"

DEF = "RendererCanvasRenderRD::Batch *RendererCanvasRenderRD::_new_batch(bool &r_batch_broken) {"
DEF_NEW = "RendererCanvasRenderRD::Batch *RendererCanvasRenderRD::_new_batch(bool &r_batch_broken, int p_droiddeck_site) {"

# Inside _new_batch: this line runs only when a new batch is really pushed.
PUSHED = "\tr_batch_broken = true;\n"
PUSHED_NEW = "\tr_batch_broken = true;\n\tdroiddeck_canvas_batch_count(p_droiddeck_site);\n"

RENDER_ITEMS = "void RendererCanvasRenderRD::_render_batch_items("
CALL = re.compile(r"_new_batch\((r_batch_broken|batch_broken)\)")

COMMAND_NAMES = {
    "TYPE_RECT": "rect",
    "TYPE_NINEPATCH": "ninepatch",
    "TYPE_POLYGON": "polygon",
    "TYPE_PRIMITIVE": "primitive",
    "TYPE_MESH": "mesh",
    "TYPE_MULTIMESH": "mesh",
    "TYPE_PARTICLES": "mesh",
    "TYPE_CLIP_IGNORE": "clip ignore",
}

# (substring of the condition or comment just above the call, label)
REASONS = [
    ("as triangles: command type", "as triangles, command type"),  # canvas_poly_batch.py
    ("as triangles: texture", "as triangles, texture"),
    ("final_clip_owner", "clip"),
    ("material != current_batch->material", "material"),
    ("use_lighting != ", "lighting"),
    ("primitive_points", "point count or command type"),
    ("command_type != ", "command type"),
    ("has_blend != ", "lcd blend or its colour"),
    ("tex_info != tex_info", "texture"),
    ("ignore != reclip", "clip ignore"),
    ("Polygon's can't be batched", "never batched"),
    ("Mesh's can't be batched", "never batched"),
    ("Force a new batch", "instance buffer full"),
    ("First item always forms its own batch", "draw list start"),
    ("current_batch->use_lighting) {", "after lighting"),
    ("current_batch->has_blend) {", "after blend"),
]


def label_for(lines, index):
    """Label a call on lines[index] from the nearest condition or comment above it and its switch case."""
    reason = None
    for back in range(0, 4):
        text = lines[index - back]
        for key, name in REASONS:
            if key in text:
                reason = name
                break
        if reason:
            break
    command = None
    debug = False
    for back in range(index, max(index - 400, 0), -1):
        text = lines[back]
        if "#ifdef DEBUG_ENABLED" in text:
            debug = True
            break
        if text.startswith("void ") or text.startswith("RendererCanvasRenderRD::"):
            break
        m = re.search(r"case Item::Command::(TYPE_\w+)", text)
        if m:
            command = COMMAND_NAMES.get(m.group(1), m.group(1).lower())
            break
    if reason is None:
        reason = "line %d" % (index + 1)
    if debug:
        return "debug redraw: " + reason
    if command and command != reason:
        return "%s: %s" % (command, reason)
    return reason


def patch_header(text):
    if text.count(DECL) != 1:
        raise SystemExit("canvas_batch_census: expected the _new_batch declaration exactly once")
    return text.replace(DECL, DECL_NEW)


def patch_source(text):
    if text.count(DEF) != 1:
        raise SystemExit("canvas_batch_census: expected the _new_batch definition exactly once")
    if text.count(RENDER_ITEMS) != 1:
        raise SystemExit("canvas_batch_census: expected _render_batch_items exactly once")
    lines = text.split("\n")
    labels = ["draw lists"]
    for i, line in enumerate(lines):
        if DEF.split("(")[0] in line:
            continue
        if not CALL.search(line):
            continue
        labels.append(label_for(lines, i))
        site = len(labels) - 1
        lines[i] = CALL.sub(lambda m: "_new_batch(%s, %d)" % (m.group(1), site), line, count=1)
    if len(labels) < 11:
        raise SystemExit("canvas_batch_census: found only %d _new_batch calls" % (len(labels) - 1))
    out = "\n".join(lines)

    # The definition: count only when a batch is really pushed.
    start = out.index(DEF)
    end = out.index("\n}\n", start)
    body = out[start:end]
    if body.count(PUSHED) != 1:
        raise SystemExit("canvas_batch_census: expected one 'r_batch_broken = true;' in _new_batch")
    out = out[:start] + body.replace(DEF, DEF_NEW).replace(PUSHED, PUSHED_NEW) + out[end:]

    # Each draw list.
    at = out.index(RENDER_ITEMS)
    brace = out.index("{\n", at) + 2
    out = out[:brace] + "\tdroiddeck_canvas_batch_count(0);\n" + out[brace:]

    table = ",\n".join('\t"%s"' % l.replace('"', "'") for l in labels)
    helpers = (
        "\n// Kestrel: why each canvas batch started (canvas_batch_census.py). On only with DROIDDECK_NATIVE_RENDER_CENSUS.\n"
        "#include <stdlib.h>\n#include <atomic>\n"
        "static const char *const droiddeck_canvas_batch_labels[] = {\n%s\n};\n"
        "static constexpr int DROIDDECK_CANVAS_BATCH_SITES = int(sizeof(droiddeck_canvas_batch_labels) / sizeof(droiddeck_canvas_batch_labels[0]));\n"
        "static std::atomic<uint32_t> droiddeck_canvas_batch_counts[DROIDDECK_CANVAS_BATCH_SITES];\n"
        "static const bool droiddeck_canvas_batch_on = [] {\n"
        "\tconst char *v = getenv(\"DROIDDECK_NATIVE_RENDER_CENSUS\");\n"
        "\treturn v && *v && atof(v) > 0;\n"
        "}();\n"
        "static inline void droiddeck_canvas_batch_count(int p_site) {\n"
        "\tif (droiddeck_canvas_batch_on && p_site >= 0 && p_site < DROIDDECK_CANVAS_BATCH_SITES) {\n"
        "\t\tdroiddeck_canvas_batch_counts[p_site].fetch_add(1, std::memory_order_relaxed);\n"
        "\t}\n"
        "}\n"
        "int droiddeck_canvas_batch_sites() { return DROIDDECK_CANVAS_BATCH_SITES; }\n"
        "const char *droiddeck_canvas_batch_site(int p_site) {\n"
        "\treturn p_site >= 0 && p_site < DROIDDECK_CANVAS_BATCH_SITES ? droiddeck_canvas_batch_labels[p_site] : \"\";\n"
        "}\n"
        "uint32_t droiddeck_canvas_batch_take(int p_site) {\n"
        "\treturn p_site >= 0 && p_site < DROIDDECK_CANVAS_BATCH_SITES ? droiddeck_canvas_batch_counts[p_site].exchange(0, std::memory_order_relaxed) : 0;\n"
        "}\n" % table
    )
    anchor = '#include "renderer_canvas_render_rd.h"\n'
    if out.count(anchor) != 1:
        raise SystemExit("canvas_batch_census: expected the renderer's own header include exactly once")
    # After the whole include block, so nothing in it is redeclared after use.
    first_code = re.search(r"\n(?!#include|//|\n)", out[out.index(anchor):])
    insert_at = out.index(anchor) + first_code.start()
    out = out[:insert_at] + "\n" + helpers + out[insert_at:]
    return out, labels


def main(argv):
    (godot,) = argv
    base = os.path.join(godot, "servers", "rendering", "renderer_rd", "renderer_canvas_render_rd")
    with open(base + ".h") as f:
        header = f.read()
    with open(base + ".cpp") as f:
        source = f.read()
    header = patch_header(header)
    source, labels = patch_source(source)
    with open(base + ".h", "w") as f:
        f.write(header)
    with open(base + ".cpp", "w") as f:
        f.write(source)
    print("canvas_batch_census: %d sites: %s" % (len(labels), "; ".join(labels)))


if __name__ == "__main__":
    main(sys.argv[1:])
