// Clean-room code for DroidDeck/Kestrel: written from scratch from public format notes and from
// walking skeleton files byte by byte. No third-party skeletal-animation runtime source was consulted.
//
// Reads the 4.2 binary skeleton format. Numbers are big-endian; varints are 7 bits per byte, low
// group first; strings are a varint byte length plus one (0 = none) followed by UTF-8. Many
// strings are written once in a table after the header and referenced by index plus one.
//
// Layouts confirmed by walking real files end to end: header, bones, slots, IK and transform
// constraints, skins with region, mesh and linked mesh attachments, events, and every animation
// section those files use. Bounding box, point and clipping attachments follow the same pattern but
// have not been seen yet. Path and physics constraints have unknown layouts and fail the load.
#include "skel.h"

#include <algorithm>
#include <cstring>

namespace skel {

namespace {

struct Reader {
	const uint8_t *p, *end;
	bool bad = false;

	size_t left() const { return size_t(end - p); }
	uint8_t byte() {
		if (p >= end) {
			bad = true;
			return 0;
		}
		return *p++;
	}
	bool boolean() { return byte() != 0; }
	uint32_t u32() {
		uint32_t v = 0;
		for (int i = 0; i < 4; i++) {
			v = (v << 8) | byte();
		}
		return v;
	}
	int32_t i32() { return int32_t(u32()); }
	float f32() {
		uint32_t v = u32();
		float f;
		std::memcpy(&f, &v, 4);
		return f;
	}
	uint32_t varint() {
		uint32_t r = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			uint8_t b = byte();
			r |= uint32_t(b & 0x7f) << shift;
			if (!(b & 0x80)) {
				return r;
			}
		}
		bad = true;
		return r;
	}
	int zigzag() {
		uint32_t v = varint();
		return int(v >> 1) ^ -int(v & 1);
	}
	// An element count; every element takes at least one byte, so a larger count means a broken file.
	int count() {
		uint32_t n = varint();
		if (n > left()) {
			bad = true;
			return 0;
		}
		return int(n);
	}
	bool string(std::string &out) {
		uint32_t n = varint();
		if (n == 0) {
			out.clear();
			return false;
		}
		n -= 1;
		if (n > left()) {
			bad = true;
			out.clear();
			return false;
		}
		out.assign(reinterpret_cast<const char *>(p), n);
		p += n;
		return true;
	}
	std::string string() {
		std::string s;
		string(s);
		return s;
	}
};

Color color_of(uint32_t rgba) {
	Color c;
	c.r = float((rgba >> 24) & 0xff) / 255.0f;
	c.g = float((rgba >> 16) & 0xff) / 255.0f;
	c.b = float((rgba >> 8) & 0xff) / 255.0f;
	c.a = float(rgba & 0xff) / 255.0f;
	return c;
}

struct BinaryLoader {
	SkeletonData &sd;
	Reader r;
	float scale;
	bool nonessential = false;
	std::vector<std::string> strings;
	std::vector<std::string> unsupported;
	std::string error;

	struct PendingLink {
		Attachment *mesh;
		int slot;
		std::string parent, skin;
		bool skin_null;
		bool deform;
	};
	std::vector<PendingLink> links;

	bool fail(const std::string &what) {
		if (error.empty()) {
			error = what;
		}
		return false;
	}
	bool ok() {
		if (r.bad) {
			return fail("file ends early or holds a bad count");
		}
		return error.empty();
	}

	// A string-table reference: 0 is none.
	bool ref(std::string &out) {
		uint32_t i = r.varint();
		if (i == 0) {
			out.clear();
			return false;
		}
		if (i > strings.size()) {
			r.bad = true;
			out.clear();
			return false;
		}
		out = strings[i - 1];
		return true;
	}
	std::string ref() {
		std::string s;
		ref(s);
		return s;
	}

	int index(int limit, const char *what) {
		uint32_t i = r.varint();
		if (int(i) >= limit || i > 0x7fffffff) {
			fail(std::string("bad ") + what + " index " + std::to_string(i));
			return 0;
		}
		return int(i);
	}

	bool header() {
		if (r.left() < 8) {
			return fail("file too short");
		}
		static const char hex[] = "0123456789abcdef";
		for (int i = 0; i < 8; i++) {
			uint8_t b = r.byte();
			sd.hash += hex[b >> 4];
			sd.hash += hex[b & 15];
		}
		sd.version = r.string();
		if (sd.version.compare(0, 4, "4.2.") != 0) {
			return fail("binary skeleton version '" + sd.version + "' is not supported (4.2 only)");
		}
		sd.x = r.f32() * scale;
		sd.y = r.f32() * scale;
		sd.width = r.f32() * scale;
		sd.height = r.f32() * scale;
		r.f32(); // reference scale
		nonessential = r.boolean();
		if (nonessential) {
			sd.fps = r.f32();
			r.string(); // images path
			r.string(); // audio path
		}
		int n = r.count();
		strings.resize(n);
		for (int i = 0; i < n && !r.bad; i++) {
			r.string(strings[i]);
		}
		return ok();
	}

	bool bones() {
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			BoneData b;
			b.name = r.string();
			b.index = i;
			if (i > 0) {
				b.parent = index(i, "parent bone");
			}
			b.rotation = r.f32();
			b.x = r.f32() * scale;
			b.y = r.f32() * scale;
			b.scale_x = r.f32();
			b.scale_y = r.f32();
			b.shear_x = r.f32();
			b.shear_y = r.f32();
			b.length = r.f32() * scale;
			uint8_t inherit = r.byte();
			b.inherit = inherit <= 4 ? Inherit(inherit) : Inherit::Normal;
			b.skin_required = r.boolean();
			if (nonessential) {
				r.u32(); // editor color
				r.string(); // icon
				r.boolean(); // visible
			}
			sd.bones.push_back(std::move(b));
		}
		return ok();
	}

	bool slots() {
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			SlotData s;
			s.name = r.string();
			s.index = i;
			s.bone = index(int(sd.bones.size()), "slot bone");
			s.color = color_of(r.u32());
			uint32_t dark = r.u32();
			if (dark != 0xffffffffu) {
				s.has_dark = true;
				s.dark = color_of(dark);
				s.dark.a = 1;
			}
			ref(s.attachment);
			uint32_t blend = r.varint();
			s.blend = blend <= 3 ? BlendMode(blend) : BlendMode::Normal;
			if (nonessential) {
				r.boolean(); // visible
			}
			sd.slots.push_back(std::move(s));
		}
		return ok();
	}

	void constraint_bones(std::vector<int> &out) {
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			out.push_back(index(int(sd.bones.size()), "constraint bone"));
		}
	}

	bool ik() {
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			IkData k;
			k.name = r.string();
			k.order = int(r.varint());
			constraint_bones(k.bones);
			k.target = index(int(sd.bones.size()), "ik target");
			uint8_t f = r.byte();
			// 1 skin required, 2 bend positive, 4 compress, 8 stretch, 16 uniform,
			// 32 mix is 1, 64 mix value follows, 128 softness follows.
			k.bend_positive = f & 2;
			k.compress = f & 4;
			k.stretch = f & 8;
			k.uniform = f & 16;
			k.mix = (f & 64) ? r.f32() : (f & 32) ? 1.0f : 0.0f;
			k.softness = (f & 128) ? r.f32() * scale : 0.0f;
			if (k.bones.empty() || k.bones.size() > 2) {
				return fail("ik " + k.name + ": needs one or two bones");
			}
			sd.ik.push_back(std::move(k));
		}
		return ok();
	}

	bool transforms() {
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			TransformData t;
			t.name = r.string();
			t.order = int(r.varint());
			constraint_bones(t.bones);
			t.target = index(int(sd.bones.size()), "transform target");
			uint8_t f = r.byte();
			// 1 skin required, 2 local, 4 relative, then which offsets follow.
			t.local = f & 2;
			t.relative = f & 4;
			if (f & 8) t.offset_rotation = r.f32();
			if (f & 16) t.offset_x = r.f32() * scale;
			if (f & 32) t.offset_y = r.f32() * scale;
			if (f & 64) t.offset_scale_x = r.f32();
			if (f & 128) t.offset_scale_y = r.f32();
			f = r.byte();
			// Which of the shear offset and the six mixes follow; absent values are 0.
			if (f & 1) t.offset_shear_y = r.f32();
			if (f & 2) t.mix_rotate = r.f32();
			if (f & 4) t.mix_x = r.f32();
			if (f & 8) t.mix_y = r.f32();
			if (f & 16) t.mix_scale_x = r.f32();
			if (f & 32) t.mix_scale_y = r.f32();
			if (f & 64) t.mix_shear_y = r.f32();
			if (t.relative) {
				unsupported.push_back("relative transform constraints (approximated)");
			}
			sd.transforms.push_back(std::move(t));
		}
		return ok();
	}

	void vertices(Attachment &a, int vertex_count, bool weighted) {
		a.vertex_count = vertex_count;
		if (!weighted) {
			a.vertices.reserve(size_t(vertex_count) * 2);
			for (int i = 0; i < vertex_count * 2 && !r.bad; i++) {
				a.vertices.push_back(r.f32() * scale);
			}
			return;
		}
		for (int v = 0; v < vertex_count && ok(); v++) {
			int n = r.count();
			a.bones.push_back(n);
			for (int k = 0; k < n && ok(); k++) {
				a.bones.push_back(index(int(sd.bones.size()), "vertex bone"));
				a.vertices.push_back(r.f32() * scale);
				a.vertices.push_back(r.f32() * scale);
				a.vertices.push_back(r.f32());
			}
		}
	}

	// Common flag bits on region, mesh and linked mesh: 8 name, 16 path, 32 color, 64 sequence.
	void common(Attachment &a, uint8_t f, const std::string &key) {
		a.name = key;
		if (f & 8) {
			ref(a.name);
		}
		a.path = a.name;
		if (f & 16) {
			ref(a.path);
		}
		if (f & 32) {
			a.color = color_of(r.u32());
		}
		if (f & 64) {
			a.has_sequence = true;
			a.sequence.count = int(r.varint());
			a.sequence.start = int(r.varint());
			a.sequence.digits = int(r.varint());
			a.sequence.setup = int(r.varint());
			a.sequence_base = a.path;
			a.path = a.sequence.frame_path(a.sequence_base, a.sequence.setup);
		}
	}

	std::unique_ptr<Attachment> attachment(const std::string &key, int slot) {
		auto a = std::make_unique<Attachment>();
		uint8_t f = r.byte();
		switch (f & 7) {
			case 0: // region
				a->type = AttachmentType::Region;
				common(*a, f, key);
				a->rotation = (f & 128) ? r.f32() : 0.0f;
				a->x = r.f32() * scale;
				a->y = r.f32() * scale;
				a->scale_x = r.f32();
				a->scale_y = r.f32();
				a->width = r.f32() * scale;
				a->height = r.f32() * scale;
				a->update_region_offsets();
				break;
			case 1: { // bounding box (layout not yet seen in a file)
				a->type = AttachmentType::BoundingBox;
				a->name = a->path = key;
				int vc = r.count();
				vertices(*a, vc, f & 16);
				if (nonessential) {
					r.u32();
				}
				break;
			}
			case 2: { // mesh
				a->type = AttachmentType::Mesh;
				common(*a, f, key);
				a->hull = int(r.varint());
				int vc = r.count();
				vertices(*a, vc, f & 128);
				a->uvs.reserve(size_t(vc) * 2);
				for (int i = 0; i < vc * 2 && !r.bad; i++) {
					a->uvs.push_back(r.f32());
				}
				// Triangle indices are not counted in the file: a hull of h around v vertices makes 2v - h - 2 triangles.
				int tris = (2 * vc - a->hull - 2) * 3;
				if (tris < 0 || size_t(tris) > r.left()) {
					fail("mesh " + key + ": bad triangle count");
					return nullptr;
				}
				for (int i = 0; i < tris && ok(); i++) {
					uint32_t t = r.varint();
					if (int(t) >= vc) {
						fail("mesh " + key + ": triangle index out of range");
						return nullptr;
					}
					a->triangles.push_back(uint16_t(t));
				}
				if (nonessential) {
					int edges = r.count();
					for (int i = 0; i < edges && !r.bad; i++) {
						r.varint();
					}
					a->width = r.f32() * scale;
					a->height = r.f32() * scale;
				}
				break;
			}
			case 3: { // linked mesh
				a->type = AttachmentType::Mesh;
				common(*a, f, key);
				PendingLink link{ a.get(), slot, {}, {}, false, bool(f & 128) };
				link.skin_null = !ref(link.skin);
				ref(link.parent);
				if (nonessential) {
					a->width = r.f32() * scale;
					a->height = r.f32() * scale;
				}
				links.push_back(std::move(link));
				break;
			}
			case 5: // point (layout not yet seen in a file)
				a->type = AttachmentType::Point;
				a->name = a->path = key;
				a->point_rotation = r.f32();
				a->x = r.f32() * scale;
				a->y = r.f32() * scale;
				if (nonessential) {
					r.u32();
				}
				break;
			case 6: { // clipping (layout not yet seen in a file)
				a->type = AttachmentType::Clipping;
				a->name = a->path = key;
				a->end_slot = index(int(sd.slots.size()), "clipping end slot");
				int vc = r.count();
				vertices(*a, vc, f & 16);
				if (nonessential) {
					r.u32();
				}
				break;
			}
			default:
				fail("attachment " + key + ": type " + std::to_string(f & 7) + " layout unknown");
				return nullptr;
		}
		if (!ok()) {
			return nullptr;
		}
		return a;
	}

	bool skin_attachments(Skin &skin) {
		int slots_n = r.count();
		for (int i = 0; i < slots_n && ok(); i++) {
			int slot = index(int(sd.slots.size()), "skin slot");
			if (!ok()) {
				return false;
			}
			int n = r.count();
			for (int k = 0; k < n && ok(); k++) {
				std::string key = ref();
				auto a = attachment(key, slot);
				if (!a) {
					return fail(error.empty() ? "bad attachment " + key : error);
				}
				skin.slots[slot][key] = std::move(a);
			}
		}
		return ok();
	}

	bool skins() {
		auto def = std::make_unique<Skin>();
		def->name = "default";
		def->slots.resize(sd.slots.size());
		if (!skin_attachments(*def)) {
			return false;
		}
		sd.default_skin = def.get();
		sd.skins.push_back(std::move(def));
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			auto s = std::make_unique<Skin>();
			s->slots.resize(sd.slots.size());
			ref(s->name);
			if (nonessential) {
				r.u32();
			}
			int bones_n = r.count();
			for (int k = 0; k < bones_n && ok(); k++) {
				s->bones.push_back(index(int(sd.bones.size()), "skin bone"));
			}
			for (int list = 0; list < 4 && ok(); list++) { // ik, transform, path, physics constraints
				int c = r.count();
				for (int k = 0; k < c && !r.bad; k++) {
					r.varint();
				}
			}
			if (!skin_attachments(*s)) {
				return false;
			}
			sd.skins.push_back(std::move(s));
		}
		for (const PendingLink &l : links) {
			const Skin *from = l.skin_null ? sd.default_skin : sd.find_skin(l.skin);
			const Attachment *parent = from ? from->find(l.slot, l.parent) : nullptr;
			if (!parent) {
				return fail("linked mesh " + l.mesh->name + ": parent " + l.parent + " not found");
			}
			Attachment &m = *l.mesh;
			m.bones = parent->bones;
			m.vertices = parent->vertices;
			m.vertex_count = parent->vertex_count;
			m.uvs = parent->uvs;
			m.triangles = parent->triangles;
			m.hull = parent->hull;
			m.timeline_attachment = l.deform ? parent->timeline_attachment : &m;
		}
		return ok();
	}

	bool events() {
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			EventData e;
			e.name = r.string();
			e.int_value = r.zigzag();
			e.float_value = r.f32();
			e.string_value = r.string();
			if (r.string(e.audio)) {
				e.volume = r.f32();
				e.balance = r.f32();
			}
			sd.events.push_back(std::move(e));
		}
		return ok();
	}

	// ------------------------------------------------------------ keyframes

	// The segment from frame i-1 to frame i: a kind byte after frame i's values, plus 4 bezier
	// controls per channel (absolute time and value) for kind 2.
	void segment(Curve &cv, int i, const float *value_scales, float value_offset) {
		uint8_t kind = r.byte();
		if (kind == 1) {
			cv.kinds[i - 1] = Curve::STEPPED;
		} else if (kind == 2) {
			for (int ch = 0; ch < cv.channels; ch++) {
				float cx1 = r.f32(), cy1 = r.f32(), cx2 = r.f32(), cy2 = r.f32();
				float s = value_scales ? value_scales[ch] : 1.0f;
				cv.set_bezier(i - 1, ch, cx1, cy1 * s + value_offset, cx2, cy2 * s + value_offset);
			}
		} else if (kind != 0) {
			fail("unknown curve kind " + std::to_string(kind));
		}
	}

	// Frames of floats (time, values...), each frame after the first followed by its segment curve.
	void float_frames(Timeline &tl, int frames, int channels, float value_scale) {
		tl.curve.channels = channels;
		float scales[8];
		for (int c = 0; c < channels; c++) {
			scales[c] = value_scale;
		}
		for (int i = 0; i < frames && ok(); i++) {
			float v[8];
			float t = r.f32();
			for (int c = 0; c < channels; c++) {
				v[c] = r.f32() * value_scale;
			}
			tl.curve.add(t, v, Curve::LINEAR);
			if (i > 0) {
				segment(tl.curve, i, scales, 0);
			}
		}
	}

	void color_frames(Timeline &tl, int frames, int type) {
		// 1 rgba as an int, 2 rgb as 3 bytes, 3 rgba int + dark rgb bytes, 4 light rgb + dark rgb bytes, 5 alpha byte.
		static const int channels[] = { 0, 4, 3, 7, 6, 1 };
		tl.curve.channels = channels[type];
		for (int i = 0; i < frames && ok(); i++) {
			float t = r.f32();
			float v[8];
			int at = 0;
			if (type == 1 || type == 3) {
				Color c = color_of(r.u32());
				v[at++] = c.r;
				v[at++] = c.g;
				v[at++] = c.b;
				v[at++] = c.a;
			}
			int rgb_triples = type == 2 || type == 3 ? 1 : type == 4 ? 2 : 0;
			for (int k = 0; k < rgb_triples * 3; k++) {
				v[at++] = float(r.byte()) / 255.0f;
			}
			if (type == 5) {
				v[at++] = float(r.byte()) / 255.0f;
			}
			tl.curve.add(t, v, Curve::LINEAR);
			if (i > 0) {
				segment(tl.curve, i, nullptr, 0);
			}
		}
	}

	bool animation(Animation &anim) {
		anim.name = r.string();
		r.varint(); // total timeline count
		float duration = 0;
		auto keep = [&](Timeline &&tl) {
			if (tl.curve.frame_count()) {
				duration = std::max(duration, tl.curve.time(tl.curve.frame_count() - 1));
			}
			anim.timelines.push_back(std::move(tl));
		};
		const std::string where = "animation " + anim.name + ": ";

		// Slots.
		int n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			int slot = index(int(sd.slots.size()), "slot");
			int timelines = r.count();
			for (int k = 0; k < timelines && ok(); k++) {
				int type = r.byte();
				int frames = r.count();
				Timeline tl;
				tl.target = slot;
				if (type == 0) {
					tl.type = TimelineType::Attachment;
					tl.curve.channels = 1;
					for (int f = 0; f < frames && ok(); f++) {
						float none = 0;
						tl.curve.add(r.f32(), &none, Curve::STEPPED);
						tl.names.push_back(ref());
					}
					keep(std::move(tl));
					continue;
				}
				if (type > 5) {
					return fail(where + "slot timeline type " + std::to_string(type) + " unknown");
				}
				r.varint(); // bezier count
				static const TimelineType types[] = { TimelineType::Attachment, TimelineType::RGBA, TimelineType::RGB,
					TimelineType::RGBA2, TimelineType::RGB2, TimelineType::Alpha };
				tl.type = types[type];
				color_frames(tl, frames, type);
				keep(std::move(tl));
			}
		}

		// Bones.
		n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			int bone = index(int(sd.bones.size()), "bone");
			int timelines = r.count();
			for (int k = 0; k < timelines && ok(); k++) {
				int type = r.byte();
				int frames = r.count();
				Timeline tl;
				tl.target = bone;
				if (type == 10) {
					tl.type = TimelineType::Inherit;
					tl.curve.channels = 1;
					for (int f = 0; f < frames && ok(); f++) {
						float none = 0;
						tl.curve.add(r.f32(), &none, Curve::STEPPED);
						uint8_t mode = r.byte();
						tl.flags.push_back(mode <= 4 ? mode : 0);
					}
					keep(std::move(tl));
					continue;
				}
				if (type > 9) {
					return fail(where + "bone timeline type " + std::to_string(type) + " unknown");
				}
				r.varint(); // bezier count
				static const TimelineType types[] = { TimelineType::Rotate, TimelineType::Translate, TimelineType::TranslateX,
					TimelineType::TranslateY, TimelineType::Scale, TimelineType::ScaleX, TimelineType::ScaleY,
					TimelineType::Shear, TimelineType::ShearX, TimelineType::ShearY };
				static const int channels[] = { 1, 2, 1, 1, 2, 1, 1, 2, 1, 1 };
				tl.type = types[type];
				float value_scale = type >= 1 && type <= 3 ? scale : 1.0f;
				float_frames(tl, frames, channels[type], value_scale);
				keep(std::move(tl));
			}
		}

		// IK: each frame carries flags, then its own curve (for the segment that starts at it).
		n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			Timeline tl;
			tl.type = TimelineType::Ik;
			tl.target = index(int(sd.ik.size()), "ik");
			int frames = r.count();
			r.varint(); // bezier count
			tl.curve.channels = 2;
			const float scales[2] = { 1.0f, scale };
			for (int f = 0; f < frames && ok(); f++) {
				// 1 bend positive, 2 compress, 4 stretch, 8 mix is 1, 16 mix follows, 32 softness follows,
				// 64 stepped, 128 bezier. The bit order follows the constraint's own flags.
				uint8_t fl = r.byte();
				float t = r.f32();
				float v[2];
				v[0] = (fl & 16) ? r.f32() : (fl & 8) ? 1.0f : 0.0f;
				v[1] = (fl & 32) ? r.f32() * scale : 0.0f;
				tl.curve.add(t, v, Curve::LINEAR);
				tl.flags.push_back(fl & 7);
				if (f + 1 < frames) {
					if (fl & 64) {
						tl.curve.kinds[f] = Curve::STEPPED;
					} else if (fl & 128) {
						for (int ch = 0; ch < 2; ch++) {
							float cx1 = r.f32(), cy1 = r.f32(), cx2 = r.f32(), cy2 = r.f32();
							tl.curve.set_bezier(f, ch, cx1, cy1 * scales[ch], cx2, cy2 * scales[ch]);
						}
					}
				}
			}
			keep(std::move(tl));
		}

		// Transform constraints: mix rotate, x, y, scale x, scale y, shear y.
		n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			Timeline tl;
			tl.type = TimelineType::Transform;
			tl.target = index(int(sd.transforms.size()), "transform");
			int frames = r.count();
			r.varint(); // bezier count
			float_frames(tl, frames, 6, 1.0f);
			keep(std::move(tl));
		}

		if (r.count() != 0) {
			return fail(where + "path constraint timelines (layout unknown)");
		}
		if (r.count() != 0) {
			return fail(where + "physics constraint timelines (layout unknown)");
		}

		// Attachment timelines: deform and sequence, per skin, slot and attachment.
		n = r.count();
		for (int i = 0; i < n && ok(); i++) {
			int skin_index = index(int(sd.skins.size()), "skin");
			const Skin *skin = sd.skins[skin_index].get();
			int slots_n = r.count();
			for (int s = 0; s < slots_n && ok(); s++) {
				int slot = index(int(sd.slots.size()), "slot");
				int atts = r.count();
				for (int a = 0; a < atts && ok(); a++) {
					std::string key = ref();
					int type = r.byte();
					int frames = r.count();
					const Attachment *att = skin->find(slot, key);
					if (!att) {
						return fail(where + "attachment " + key + " not in skin " + skin->name);
					}
					if (type == 1) { // sequence: time, mode and index, delay
						for (int f = 0; f < frames && !r.bad; f++) {
							r.f32();
							r.i32();
							r.f32();
						}
						unsupported.push_back("sequence timelines");
						continue;
					}
					if (type != 0) {
						return fail(where + "attachment timeline type " + std::to_string(type) + " unknown");
					}
					r.varint(); // bezier count
					Timeline tl;
					tl.type = TimelineType::Deform;
					tl.target = slot;
					tl.attachment = att;
					tl.curve.channels = 1;
					size_t length = att->weighted() ? att->vertices.size() / 3 * 2 : att->vertices.size();
					for (int f = 0; f < frames && ok(); f++) {
						float t = r.f32();
						std::vector<float> d(length, 0.0f);
						uint32_t end = r.varint();
						if (end != 0) {
							uint32_t start = r.varint();
							if (start > end || end > length) {
								return fail(where + "deform range out of bounds on " + key);
							}
							for (uint32_t k = start; k < end && !r.bad; k++) {
								d[k] = r.f32() * scale;
							}
						}
						float index_value = float(f);
						tl.curve.add(t, &index_value, Curve::LINEAR);
						tl.deforms.push_back(std::move(d));
						if (f > 0) {
							// The curve's value is the frame index, so bezier values (0..1) are offset by it.
							segment(tl.curve, f, nullptr, float(f - 1));
						}
					}
					keep(std::move(tl));
				}
			}
		}

		// Draw order: per key, the slots that move and by how much (a negative move wraps as 32 bits).
		n = r.count();
		if (n > 0) {
			Timeline tl;
			tl.type = TimelineType::DrawOrder;
			tl.curve.channels = 1;
			int slots_n = int(sd.slots.size());
			for (int i = 0; i < n && ok(); i++) {
				float none = 0;
				tl.curve.add(r.f32(), &none, Curve::STEPPED);
				int changes = r.count();
				if (changes == 0) {
					tl.draw_orders.emplace_back();
					continue;
				}
				std::vector<int> result(slots_n, -1);
				std::vector<bool> moved(slots_n, false);
				for (int c = 0; c < changes && ok(); c++) {
					int s = index(slots_n, "draw order slot");
					int to = s + int32_t(r.varint());
					if (to < 0 || to >= slots_n || result[to] != -1) {
						return fail(where + "bad draw order move");
					}
					result[to] = s;
					moved[s] = true;
				}
				int fill = 0;
				for (int s = 0; s < slots_n; s++) {
					if (moved[s]) {
						continue;
					}
					while (fill < slots_n && result[fill] != -1) {
						fill++;
					}
					if (fill < slots_n) {
						result[fill] = s;
					}
				}
				tl.draw_orders.push_back(std::move(result));
			}
			keep(std::move(tl));
		}

		// Events.
		n = r.count();
		if (n > 0) {
			Timeline tl;
			tl.type = TimelineType::Event;
			tl.curve.channels = 1;
			for (int i = 0; i < n && ok(); i++) {
				Event e;
				e.time = r.f32();
				int ei = index(int(sd.events.size()), "event");
				if (!ok()) {
					break;
				}
				const EventData &ed = sd.events[ei];
				e.data = &ed;
				e.int_value = r.zigzag();
				e.float_value = r.f32();
				e.string_value = r.boolean() ? r.string() : ed.string_value;
				e.volume = ed.volume;
				e.balance = ed.balance;
				if (!ed.audio.empty()) {
					e.volume = r.f32();
					e.balance = r.f32();
				}
				float none = 0;
				tl.curve.add(e.time, &none, Curve::STEPPED);
				tl.events.push_back(std::move(e));
			}
			keep(std::move(tl));
		}
		anim.duration = duration;
		return ok();
	}
};

} // namespace

std::unique_ptr<SkeletonData> load_binary(const uint8_t *data, size_t length, float scale, std::string &error) {
	auto sd = std::make_unique<SkeletonData>();
	BinaryLoader l{ *sd, Reader{ data, data + length }, scale, false, {}, {}, {}, {} };
	bool ok = l.header() && l.bones() && l.slots() && l.ik() && l.transforms();
	if (ok && l.r.count() != 0) {
		ok = l.fail("path constraints (layout unknown)");
	}
	if (ok && l.r.count() != 0) {
		ok = l.fail("physics constraints (layout unknown)");
	}
	ok = ok && l.skins() && l.events();
	if (ok) {
		int n = l.r.count();
		sd->animations.resize(size_t(n));
		for (int i = 0; i < n && ok; i++) {
			ok = l.animation(sd->animations[size_t(i)]);
		}
	}
	if (ok && l.r.p != l.r.end) {
		ok = l.fail(std::to_string(size_t(l.r.end - l.r.p)) + " bytes left after the last animation");
	}
	if (!ok) {
		error = l.error.empty() ? "file ends early or holds a bad count" : l.error;
		error += " (at byte " + std::to_string(size_t(l.r.p - data)) + ")";
		return nullptr;
	}
	std::sort(l.unsupported.begin(), l.unsupported.end());
	l.unsupported.erase(std::unique(l.unsupported.begin(), l.unsupported.end()), l.unsupported.end());
	sd->unsupported = std::move(l.unsupported);
	return sd;
}

} // namespace skel
