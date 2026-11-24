#!/usr/bin/env python3
"""Let the 2D renderer pull a later canvas item forward to join the current batch when nothing it skips overlaps it.

    canvas_reorder.py GODOT_SRC

Godot 4's canvas renderer draws items strictly in order and starts a new batch (at least one more draw, about
37 us of main-thread time each on the phone) whenever the next item needs another texture, command type, clip
and so on. UI made of icons, panels and text alternates textures constantly. Godot 3's GLES batching had item
reordering for exactly this; Godot 4 does not.

This patch adds that, off by default:

    DROIDDECK_NATIVE_CANVAS_REORDER=<n>   look up to n items ahead (1..64); unset or 0 = stock order

Before a draw list records its items, for each item whose commands would all join one batch, when the next item
would break that batch, the first later item (within n) that would join it is moved up to follow it, provided
its bounds do not overlap the bounds of any item it moves past. Draw order only matters where pixels overlap,
so the image is unchanged. To keep that true, only plain items take part: no material (a shader may move
vertices or read the screen), no canvas group, no back-buffer copy, no skeleton, mesh,
multimesh or particles, no LCD text, and the whole list is skipped when 2D lights are in use. An item that
does not pass those checks stops the look-ahead, since nothing may move across bounds it can't trust.
Bounds come from the item's own commands (rects, nine-patches, polygons, primitives, with their transforms), as
Item::get_rect() works them out, not from the culler's rect: every Control sets its size as a custom rect, which
need not bound what it draws. They are grown by one unit. Disjoint shapes never cover the same pixel sample, so
moving an item past others whose bounds it does not meet cannot change a pixel.

The census reads how many items moved through droiddeck_canvas_reorder_take(). Fails unless _render_batch_items
is found exactly once.
"""
import os
import sys

RENDER_ITEMS = "void RendererCanvasRenderRD::_render_batch_items("

CALL = """	if (p_lights == nullptr && !using_directional_lights) {
		droiddeck_canvas_reorder(items, p_item_count, default_filter, default_repeat);
	}
"""

HELPERS = r"""
// Kestrel: canvas item reordering (canvas_reorder.py). On only with DROIDDECK_NATIVE_CANVAS_REORDER=<look-ahead>.
#include <stdlib.h>
#include <atomic>
static std::atomic<uint32_t> droiddeck_canvas_reorder_moves{ 0 };
uint32_t droiddeck_canvas_reorder_take() {
	return droiddeck_canvas_reorder_moves.exchange(0, std::memory_order_relaxed);
}
uint32_t droiddeck_canvas_reorder_window() {
	static const uint32_t window = [] {
		const char *v = getenv("DROIDDECK_NATIVE_CANVAS_REORDER");
		const int n = v && *v ? atoi(v) : 0;
		return uint32_t(n < 0 ? 0 : (n > 64 ? 64 : n));
	}();
	return window;
}

namespace {

typedef RendererCanvasRender::Item DroiddeckItem;

struct DroiddeckItemInfo {
	bool joins = false; // every command would join one batch, described by the fields below
	bool trusted = false; // rect bounds everything it draws
	Rect2 rect; // in the same space for every item of the list, grown by a pixel
	int type = 0;
	RID texture;
	uint32_t bits = 0;
	const DroiddeckItem *clip = nullptr;

	bool same_batch(const DroiddeckItemInfo &p_other) const {
		return joins && p_other.joins && type == p_other.type && texture == p_other.texture && bits == p_other.bits && clip == p_other.clip;
	}
};

DroiddeckItemInfo droiddeck_item_info(const DroiddeckItem *p_item, RS::CanvasItemTextureFilter p_default_filter, RS::CanvasItemTextureRepeat p_default_repeat) {
	DroiddeckItemInfo info;
	const RID material = p_item->material_owner ? p_item->material_owner->material : p_item->material;
	if (material.is_valid() || p_item->use_canvas_group || p_item->copy_back_buffer || p_item->repeat_source_item ||
			p_item->skeleton.is_valid() || p_item->commands == nullptr) {
		return info;
	}
	// The item's own drawing bounds, as Item::get_rect() works them out before any custom rect replaces them
	// (every Control sets its size as a custom rect, which need not bound what it draws).
	Transform2D xf;
	Rect2 bounds;
	bool have_bounds = false;
	const uint32_t filter = uint32_t(p_item->texture_filter == RS::CANVAS_ITEM_TEXTURE_FILTER_DEFAULT ? p_default_filter : p_item->texture_filter);
	const RS::CanvasItemTextureRepeat repeat = p_item->texture_repeat == RS::CANVAS_ITEM_TEXTURE_REPEAT_DEFAULT ? p_default_repeat : p_item->texture_repeat;
	bool joins = true;
	bool first = true;
	for (const DroiddeckItem::Command *c = p_item->commands; c; c = c->next) {
		int type = 0;
		RID texture;
		uint32_t bits = 0;
		Rect2 r;
		switch (c->type) {
			case DroiddeckItem::Command::TYPE_TRANSFORM:
				xf = static_cast<const DroiddeckItem::CommandTransform *>(c)->xform;
				continue;
			case DroiddeckItem::Command::TYPE_RECT: {
				const DroiddeckItem::CommandRect *rect = static_cast<const DroiddeckItem::CommandRect *>(c);
				r = rect->rect;
				if (rect->flags & RendererCanvasRender::CANVAS_RECT_LCD) {
					joins = false; // its batch also keys on the colour
				}
				const RS::CanvasItemTextureRepeat rect_repeat = (rect->flags & RendererCanvasRender::CANVAS_RECT_TILE) ? RS::CANVAS_ITEM_TEXTURE_REPEAT_ENABLED : repeat;
				type = 1;
				texture = rect->texture;
				bits = filter | (uint32_t(rect_repeat) << 4) | ((rect->flags & RendererCanvasRender::CANVAS_RECT_MSDF) ? (1u << 8) : 0u);
			} break;
			case DroiddeckItem::Command::TYPE_NINEPATCH: {
				const DroiddeckItem::CommandNinePatch *np = static_cast<const DroiddeckItem::CommandNinePatch *>(c);
				r = np->rect;
				type = 2;
				texture = np->texture;
				bits = filter | (uint32_t(repeat) << 4);
			} break;
			case DroiddeckItem::Command::TYPE_POLYGON: {
				r = xf.xform(static_cast<const DroiddeckItem::CommandPolygon *>(c)->polygon.rect_cache);
				bounds = have_bounds ? bounds.merge(r) : r;
				have_bounds = true;
				joins = false; // never joins another item's batch
				continue;
			}
			case DroiddeckItem::Command::TYPE_PRIMITIVE: {
				const DroiddeckItem::CommandPrimitive *primitive = static_cast<const DroiddeckItem::CommandPrimitive *>(c);
				if (primitive->point_count == 0) {
					continue;
				}
				r.position = primitive->points[0];
				for (uint32_t j = 1; j < primitive->point_count; j++) {
					r.expand_to(primitive->points[j]);
				}
				r = xf.xform(r);
				bounds = have_bounds ? bounds.merge(r) : r;
				have_bounds = true;
				joins = false;
				continue;
			}
			default:
				return info; // meshes, particles, clip ignore, animation slices: untrusted
		}
		r = xf.xform(r.abs()); // a flipped sprite has a negative size
		bounds = have_bounds ? bounds.merge(r) : r;
		have_bounds = true;
		if (first) {
			info.type = type;
			info.texture = texture;
			info.bits = bits;
			first = false;
		} else if (type != info.type || texture != info.texture || bits != info.bits) {
			joins = false;
		}
	}
	if (!have_bounds) {
		return info;
	}
	info.rect = p_item->final_transform.xform(bounds).grow(1);
	if (!info.rect.is_finite()) {
		return info;
	}
	info.trusted = true;
	info.joins = joins && !first;
	info.clip = p_item->final_clip_owner;
	return info;
}

} // namespace

static void droiddeck_canvas_reorder(DroiddeckItem **p_items, int p_count, RS::CanvasItemTextureFilter p_default_filter, RS::CanvasItemTextureRepeat p_default_repeat) {
	const int window = int(droiddeck_canvas_reorder_window());
	if (window <= 0 || p_count < 3) {
		return;
	}
	thread_local LocalVector<DroiddeckItemInfo> infos;
	infos.resize(p_count);
	for (int i = 0; i < p_count; i++) {
		infos[i] = droiddeck_item_info(p_items[i], p_default_filter, p_default_repeat);
	}
	uint32_t moves = 0;
	for (int i = 0; i + 2 < p_count; i++) {
		if (!infos[i].joins || infos[i].same_batch(infos[i + 1])) {
			continue;
		}
		const int limit = MIN(p_count, i + 2 + window);
		for (int k = i + 1; k < limit; k++) {
			if (k > i + 1 && infos[k].same_batch(infos[i])) {
				bool clear = true;
				for (int m = i + 1; m < k && clear; m++) {
					clear = !infos[k].rect.intersects(infos[m].rect);
				}
				if (clear) {
					DroiddeckItem *item = p_items[k];
					const DroiddeckItemInfo info = infos[k];
					for (int m = k; m > i + 1; m--) {
						p_items[m] = p_items[m - 1];
						infos[m] = infos[m - 1];
					}
					p_items[i + 1] = item;
					infos[i + 1] = info;
					moves++;
					break;
				}
			}
			if (!infos[k].trusted) {
				break;
			}
		}
	}
	if (moves) {
		droiddeck_canvas_reorder_moves.fetch_add(moves, std::memory_order_relaxed);
	}
}

"""


def patch_source(text):
    if text.count(RENDER_ITEMS) != 1:
        raise SystemExit("canvas_reorder: expected _render_batch_items exactly once")
    at = text.index(RENDER_ITEMS)
    line_start = text.rindex("\n", 0, at) + 1
    out = text[:line_start] + HELPERS.lstrip("\n") + "\n" + text[line_start:]
    at = out.index(RENDER_ITEMS)
    brace = out.index("{\n", at) + 2
    return out[:brace] + CALL + out[brace:]


def main(argv):
    (godot,) = argv
    path = os.path.join(godot, "servers", "rendering", "renderer_rd", "renderer_canvas_render_rd.cpp")
    with open(path) as f:
        source = f.read()
    with open(path, "w") as f:
        f.write(patch_source(source))
    print("canvas_reorder: patched", path)


if __name__ == "__main__":
    main(sys.argv[1:])
