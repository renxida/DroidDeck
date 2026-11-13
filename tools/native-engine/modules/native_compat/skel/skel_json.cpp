// Clean-room code for DroidDeck/Kestrel: written from scratch from public format notes.
// No third-party skeletal-animation runtime source was consulted.
//
// Reads skeleton JSON as exported by the 3.x and 4.x editors. Where the two versions name a field
// differently, both spellings are accepted.
#include "json.h"
#include "skel.h"

#include <algorithm>
#include <cstdlib>

namespace skel {

namespace {

Color parse_color(const std::string &hex, Color fallback) {
	if (hex.size() != 6 && hex.size() != 8) {
		return fallback;
	}
	auto byte = [&](int i) { return float(std::strtoul(hex.substr(i * 2, 2).c_str(), nullptr, 16)) / 255.0f; };
	Color c;
	c.r = byte(0);
	c.g = byte(1);
	c.b = byte(2);
	c.a = hex.size() == 8 ? byte(3) : 1.0f;
	return c;
}

Color color_of(const JsonValue &v, const char *key, Color fallback) {
	return parse_color(v.str(key, ""), fallback);
}

struct Loader {
	SkeletonData &sd;
	float scale;
	int major;
	std::string error;

	struct PendingLink {
		Attachment *mesh;
		int slot;
		std::string parent, skin;
		bool deform;
	};
	std::vector<PendingLink> links;

	bool fail(const std::string &what) {
		if (error.empty()) {
			error = what;
		}
		return false;
	}

	Inherit inherit_of(const std::string &s) {
		if (s == "onlyTranslation") return Inherit::OnlyTranslation;
		if (s == "noRotationOrReflection") return Inherit::NoRotationOrReflection;
		if (s == "noScale") return Inherit::NoScale;
		if (s == "noScaleOrReflection") return Inherit::NoScaleOrReflection;
		return Inherit::Normal;
	}

	BlendMode blend_of(const std::string &s) {
		if (s == "additive") return BlendMode::Additive;
		if (s == "multiply") return BlendMode::Multiply;
		if (s == "screen") return BlendMode::Screen;
		return BlendMode::Normal;
	}

	bool skeleton(const JsonValue &root) {
		if (const JsonValue *s = root.get("skeleton")) {
			sd.version = s->str("spine", s->str("version", ""));
			sd.hash = s->str("hash", "");
			sd.x = float(s->num("x", 0)) * scale;
			sd.y = float(s->num("y", 0)) * scale;
			sd.width = float(s->num("width", 0)) * scale;
			sd.height = float(s->num("height", 0)) * scale;
			sd.fps = float(s->num("fps", 30));
		}
		major = sd.major_version();
		return true;
	}

	bool bones(const JsonValue &root) {
		const JsonValue *list = root.get("bones");
		if (!list || !list->is_array()) {
			return true;
		}
		for (const JsonValue &b : list->array) {
			BoneData bd;
			bd.name = b.str("name", "");
			bd.index = int(sd.bones.size());
			std::string parent = b.str("parent", "");
			if (!parent.empty()) {
				bd.parent = sd.find_bone(parent);
				if (bd.parent < 0) {
					return fail("bone " + bd.name + ": parent " + parent + " not found (parents must come first)");
				}
			}
			bd.length = float(b.num("length", 0)) * scale;
			bd.x = float(b.num("x", 0)) * scale;
			bd.y = float(b.num("y", 0)) * scale;
			bd.rotation = float(b.num("rotation", 0));
			bd.scale_x = float(b.num("scaleX", 1));
			bd.scale_y = float(b.num("scaleY", 1));
			bd.shear_x = float(b.num("shearX", 0));
			bd.shear_y = float(b.num("shearY", 0));
			bd.inherit = inherit_of(b.str("inherit", b.str("transform", "normal")));
			bd.skin_required = b.flag("skin", false);
			sd.bones.push_back(bd);
		}
		return true;
	}

	bool slots(const JsonValue &root) {
		const JsonValue *list = root.get("slots");
		if (!list || !list->is_array()) {
			return true;
		}
		for (const JsonValue &s : list->array) {
			SlotData d;
			d.name = s.str("name", "");
			d.index = int(sd.slots.size());
			d.bone = sd.find_bone(s.str("bone", ""));
			if (d.bone < 0) {
				return fail("slot " + d.name + ": bone not found");
			}
			d.color = color_of(s, "color", Color());
			std::string dark = s.str("dark", "");
			if (!dark.empty()) {
				d.has_dark = true;
				d.dark = parse_color(dark, d.dark);
				d.dark.a = 1;
			}
			d.attachment = s.str("attachment", "");
			d.blend = blend_of(s.str("blend", "normal"));
			sd.slots.push_back(d);
		}
		return true;
	}

	bool ik(const JsonValue &root) {
		const JsonValue *list = root.get("ik");
		if (!list || !list->is_array()) {
			return true;
		}
		for (const JsonValue &k : list->array) {
			IkData d;
			d.name = k.str("name", "");
			d.order = int(k.num("order", 0));
			if (const JsonValue *bs = k.get("bones")) {
				for (const JsonValue &b : bs->array) {
					int i = sd.find_bone(b.string);
					if (i < 0) {
						return fail("ik " + d.name + ": bone " + b.string + " not found");
					}
					d.bones.push_back(i);
				}
			}
			d.target = sd.find_bone(k.str("target", ""));
			if (d.target < 0 || d.bones.empty() || d.bones.size() > 2) {
				return fail("ik " + d.name + ": bad bones or target");
			}
			d.mix = float(k.num("mix", 1));
			d.softness = float(k.num("softness", 0)) * scale;
			d.bend_positive = k.flag("bendPositive", true);
			d.compress = k.flag("compress", false);
			d.stretch = k.flag("stretch", false);
			d.uniform = k.flag("uniform", false);
			sd.ik.push_back(d);
		}
		std::stable_sort(sd.ik.begin(), sd.ik.end(), [](const IkData &a, const IkData &b) { return a.order < b.order; });
		return true;
	}

	void read_vertices(const JsonValue &a, Attachment &att, int vertex_count) {
		const JsonValue *v = a.get("vertices");
		if (!v) {
			return;
		}
		att.vertex_count = vertex_count;
		const auto &arr = v->array;
		if (int(arr.size()) == vertex_count * 2) {
			for (const JsonValue &n : arr) {
				att.vertices.push_back(float(n.number) * scale);
			}
			return;
		}
		size_t i = 0;
		for (int vtx = 0; vtx < vertex_count && i < arr.size(); vtx++) {
			int count = int(arr[i++].number);
			att.bones.push_back(count);
			for (int k = 0; k < count && i + 3 < arr.size() + 1; k++) {
				att.bones.push_back(int(arr[i].number));
				att.vertices.push_back(float(arr[i + 1].number) * scale);
				att.vertices.push_back(float(arr[i + 2].number) * scale);
				att.vertices.push_back(float(arr[i + 3].number));
				i += 4;
			}
		}
	}

	std::unique_ptr<Attachment> attachment(const JsonValue &a, const std::string &key, int slot, const std::string &skin_name) {
		auto att = std::make_unique<Attachment>();
		att->name = a.str("name", key);
		att->path = a.str("path", att->name);
		std::string type = a.str("type", "region");
		if (type == "region") {
			att->type = AttachmentType::Region;
			att->x = float(a.num("x", 0)) * scale;
			att->y = float(a.num("y", 0)) * scale;
			att->rotation = float(a.num("rotation", 0));
			att->scale_x = float(a.num("scaleX", 1));
			att->scale_y = float(a.num("scaleY", 1));
			att->width = float(a.num("width", 32)) * scale;
			att->height = float(a.num("height", 32)) * scale;
			att->color = color_of(a, "color", Color());
			att->update_region_offsets();
		} else if (type == "mesh" || type == "linkedmesh" || type == "skinnedmesh") {
			att->type = AttachmentType::Mesh;
			att->color = color_of(a, "color", Color());
			att->width = float(a.num("width", 0)) * scale;
			att->height = float(a.num("height", 0)) * scale;
			if (type == "linkedmesh") {
				links.push_back({ att.get(), slot, a.str("parent", ""), a.str("skin", ""),
						a.flag("timelines", a.flag("deform", true)) });
				return att;
			}
			if (const JsonValue *uv = a.get("uvs")) {
				for (const JsonValue &n : uv->array) {
					att->uvs.push_back(float(n.number));
				}
			}
			if (const JsonValue *tri = a.get("triangles")) {
				for (const JsonValue &n : tri->array) {
					att->triangles.push_back(uint16_t(n.number));
				}
			}
			read_vertices(a, *att, int(att->uvs.size() / 2));
			att->hull = int(a.num("hull", 0));
		} else if (type == "boundingbox" || type == "clipping" || type == "path") {
			att->type = type == "boundingbox" ? AttachmentType::BoundingBox
					: type == "clipping"      ? AttachmentType::Clipping
											  : AttachmentType::Path;
			read_vertices(a, *att, int(a.num("vertexCount", 0)));
			if (att->type == AttachmentType::Clipping) {
				att->end_slot = sd.find_slot(a.str("end", ""));
			}
			if (att->type == AttachmentType::Path) {
				att->closed = a.flag("closed", false);
				att->constant_speed = a.flag("constantSpeed", true);
				if (const JsonValue *l = a.get("lengths")) {
					for (const JsonValue &n : l->array) {
						att->lengths.push_back(float(n.number) * scale);
					}
				}
			}
		} else if (type == "point") {
			att->type = AttachmentType::Point;
			att->x = float(a.num("x", 0)) * scale;
			att->y = float(a.num("y", 0)) * scale;
			att->point_rotation = float(a.num("rotation", 0));
		} else {
			fail("attachment " + key + " in skin " + skin_name + ": unknown type " + type);
			return nullptr;
		}
		return att;
	}

	bool skin(const std::string &name, const JsonValue &slots_map, const JsonValue *bones_list) {
		auto skin = std::make_unique<Skin>();
		skin->name = name;
		skin->slots.resize(sd.slots.size());
		if (bones_list) {
			for (const JsonValue &b : bones_list->array) {
				int i = sd.find_bone(b.string);
				if (i >= 0) {
					skin->bones.push_back(i);
				}
			}
		}
		for (const auto &sm : slots_map.members) {
			int slot = sd.find_slot(sm.first);
			if (slot < 0) {
				return fail("skin " + name + ": slot " + sm.first + " not found");
			}
			for (const auto &am : sm.second.members) {
				auto att = attachment(am.second, am.first, slot, name);
				if (!att) {
					return false;
				}
				skin->slots[slot][am.first] = std::move(att);
			}
		}
		if (name == "default") {
			sd.default_skin = skin.get();
		}
		sd.skins.push_back(std::move(skin));
		return true;
	}

	bool skins(const JsonValue &root) {
		const JsonValue *s = root.get("skins");
		if (!s) {
			return true;
		}
		if (s->is_array()) { // 4.x
			for (const JsonValue &k : s->array) {
				static const JsonValue empty_obj = [] { JsonValue v; v.kind = JsonValue::OBJECT; return v; }();
				const JsonValue *att = k.get("attachments");
				if (!skin(k.str("name", ""), att ? *att : empty_obj, k.get("bones"))) {
					return false;
				}
			}
		} else { // 3.x
			for (const auto &m : s->members) {
				if (!skin(m.first, m.second, nullptr)) {
					return false;
				}
			}
		}
		for (const PendingLink &l : links) {
			const Skin *from = l.skin.empty() ? sd.default_skin : sd.find_skin(l.skin);
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
		return true;
	}

	bool events(const JsonValue &root) {
		const JsonValue *e = root.get("events");
		if (!e) {
			return true;
		}
		for (const auto &m : e->members) {
			EventData d;
			d.name = m.first;
			d.int_value = int(m.second.num("int", 0));
			d.float_value = float(m.second.num("float", 0));
			d.string_value = m.second.str("string", "");
			d.audio = m.second.str("audio", "");
			d.volume = float(m.second.num("volume", 1));
			d.balance = float(m.second.num("balance", 0));
			sd.events.push_back(d);
		}
		return true;
	}

	// ------------------------------------------------------------ keyframes

	void curves(const JsonValue &list, Curve &cv, bool percent_values) {
		// 4.x: absolute controls, 4 numbers per channel. 3.x: normalized controls shared by channels.
		int n = cv.frame_count();
		for (int i = 0; i + 1 < n; i++) {
			const JsonValue *c = list.array[i].get("curve");
			if (!c) {
				continue;
			}
			if (c->kind == JsonValue::STRING) {
				if (c->string == "stepped") {
					cv.kinds[i] = Curve::STEPPED;
				}
				continue;
			}
			float t0 = cv.time(i), t1 = cv.time(i + 1);
			if (major >= 4 && c->is_array()) {
				for (int ch = 0; ch < cv.channels && size_t(ch * 4 + 3) < c->array.size(); ch++) {
					const auto &a = c->array;
					float cy1 = float(a[ch * 4 + 1].number), cy2 = float(a[ch * 4 + 3].number);
					if (percent_values) { // channel value is the frame index; controls are 0..1
						cy1 += float(i);
						cy2 += float(i);
					}
					cv.set_bezier(i, ch, float(a[ch * 4].number), cy1, float(a[ch * 4 + 2].number), cy2);
				}
				continue;
			}
			float n1, n2, n3, n4;
			if (c->is_array() && c->array.size() >= 4) {
				n1 = float(c->array[0].number);
				n2 = float(c->array[1].number);
				n3 = float(c->array[2].number);
				n4 = float(c->array[3].number);
			} else if (c->kind == JsonValue::NUMBER) {
				const JsonValue &k = list.array[i];
				n1 = float(c->number);
				n2 = float(k.num("c2", 0));
				n3 = float(k.num("c3", 1));
				n4 = float(k.num("c4", 1));
			} else {
				continue;
			}
			for (int ch = 0; ch < cv.channels; ch++) {
				float v0 = cv.value(i, ch), v1 = cv.value(i + 1, ch);
				cv.set_bezier(i, ch, t0 + n1 * (t1 - t0), v0 + n2 * (v1 - v0), t0 + n3 * (t1 - t0), v0 + n4 * (v1 - v0));
			}
		}
	}

	float last_time(const Curve &cv) { return cv.frame_count() ? cv.time(cv.frame_count() - 1) : 0; }

	// A numeric timeline: fields name the channel per key, with defaults.
	Timeline numeric(TimelineType type, int target, const JsonValue &list, std::vector<const char *> fields,
			std::vector<float> defaults, float value_scale) {
		Timeline tl;
		tl.type = type;
		tl.target = target;
		tl.curve.channels = int(fields.size());
		for (const JsonValue &k : list.array) {
			float v[8];
			for (size_t c = 0; c < fields.size(); c++) {
				v[c] = float(k.num(fields[c], defaults[c])) * value_scale;
			}
			tl.curve.add(float(k.num("time", 0)), v, Curve::LINEAR);
		}
		curves(list, tl.curve, false);
		return tl;
	}

	Timeline colors(TimelineType type, int target, const JsonValue &list) {
		Timeline tl;
		tl.type = type;
		tl.target = target;
		int ch = type == TimelineType::RGBA ? 4 : type == TimelineType::RGB ? 3 : type == TimelineType::RGBA2 ? 7 : 6;
		tl.curve.channels = ch;
		for (const JsonValue &k : list.array) {
			float v[8];
			Color light = color_of(k, type == TimelineType::RGBA || type == TimelineType::RGB ? "color" : "light", Color());
			v[0] = light.r;
			v[1] = light.g;
			v[2] = light.b;
			int at = 3;
			if (type == TimelineType::RGBA || type == TimelineType::RGBA2) {
				v[at++] = light.a;
			}
			if (type == TimelineType::RGBA2 || type == TimelineType::RGB2) {
				Color dark = color_of(k, "dark", Color());
				v[at++] = dark.r;
				v[at++] = dark.g;
				v[at++] = dark.b;
			}
			tl.curve.add(float(k.num("time", 0)), v, Curve::LINEAR);
		}
		curves(list, tl.curve, false);
		return tl;
	}

	bool animation(const std::string &name, const JsonValue &a) {
		Animation anim;
		anim.name = name;
		float duration = 0;
		auto keep = [&](Timeline &&tl) {
			duration = std::max(duration, last_time(tl.curve));
			anim.timelines.push_back(std::move(tl));
		};

		if (const JsonValue *bones = a.get("bones")) {
			for (const auto &bm : bones->members) {
				int bone = sd.find_bone(bm.first);
				if (bone < 0) {
					return fail("animation " + name + ": bone " + bm.first + " not found");
				}
				for (const auto &tm : bm.second.members) {
					const std::string &t = tm.first;
					const JsonValue &list = tm.second;
					if (t == "rotate") {
						keep(numeric(TimelineType::Rotate, bone, list, { major >= 4 ? "value" : "angle" }, { 0 }, 1));
					} else if (t == "translate") {
						keep(numeric(TimelineType::Translate, bone, list, { "x", "y" }, { 0, 0 }, scale));
					} else if (t == "translatex") {
						keep(numeric(TimelineType::TranslateX, bone, list, { "value" }, { 0 }, scale));
					} else if (t == "translatey") {
						keep(numeric(TimelineType::TranslateY, bone, list, { "value" }, { 0 }, scale));
					} else if (t == "scale") {
						keep(numeric(TimelineType::Scale, bone, list, { "x", "y" }, { 1, 1 }, 1));
					} else if (t == "scalex") {
						keep(numeric(TimelineType::ScaleX, bone, list, { "value" }, { 1 }, 1));
					} else if (t == "scaley") {
						keep(numeric(TimelineType::ScaleY, bone, list, { "value" }, { 1 }, 1));
					} else if (t == "shear") {
						keep(numeric(TimelineType::Shear, bone, list, { "x", "y" }, { 0, 0 }, 1));
					} else if (t == "shearx") {
						keep(numeric(TimelineType::ShearX, bone, list, { "value" }, { 0 }, 1));
					} else if (t == "sheary") {
						keep(numeric(TimelineType::ShearY, bone, list, { "value" }, { 0 }, 1));
					} else {
						unsupported.push_back("bone timeline " + t);
					}
				}
			}
		}

		if (const JsonValue *slots = a.get("slots")) {
			for (const auto &sm : slots->members) {
				int slot = sd.find_slot(sm.first);
				if (slot < 0) {
					return fail("animation " + name + ": slot " + sm.first + " not found");
				}
				for (const auto &tm : sm.second.members) {
					const std::string &t = tm.first;
					const JsonValue &list = tm.second;
					if (t == "attachment") {
						Timeline tl;
						tl.type = TimelineType::Attachment;
						tl.target = slot;
						tl.curve.channels = 0;
						for (const JsonValue &k : list.array) {
							float none = 0;
							tl.curve.channels = 1;
							tl.curve.add(float(k.num("time", 0)), &none, Curve::STEPPED);
							tl.names.push_back(k.str("name", ""));
						}
						keep(std::move(tl));
					} else if (t == "rgba" || t == "color") {
						keep(colors(TimelineType::RGBA, slot, list));
					} else if (t == "rgb") {
						keep(colors(TimelineType::RGB, slot, list));
					} else if (t == "alpha") {
						keep(numeric(TimelineType::Alpha, slot, list, { "value" }, { 1 }, 1));
					} else if (t == "rgba2" || t == "twoColor") {
						keep(colors(TimelineType::RGBA2, slot, list));
					} else if (t == "rgb2") {
						keep(colors(TimelineType::RGB2, slot, list));
					} else {
						unsupported.push_back("slot timeline " + t);
					}
				}
			}
		}

		if (const JsonValue *iks = a.get("ik")) {
			for (const auto &km : iks->members) {
				int k = sd.find_ik(km.first);
				if (k < 0) {
					return fail("animation " + name + ": ik " + km.first + " not found");
				}
				Timeline tl = numeric(TimelineType::Ik, k, km.second, { "mix", "softness" }, { 1, 0 }, 1);
				for (size_t c = 0; c < tl.curve.frames.size(); c += 3) {
					tl.curve.frames[c + 2] *= scale;
				}
				for (const JsonValue &key : km.second.array) {
					uint8_t f = 0;
					f |= key.flag("bendPositive", sd.ik[k].bend_positive) ? 1 : 0;
					f |= key.flag("compress", false) ? 2 : 0;
					f |= key.flag("stretch", false) ? 4 : 0;
					tl.flags.push_back(f);
				}
				keep(std::move(tl));
			}
		}

		// Deform: 4.x nests it under attachments/<skin>/<slot>/<attachment>/deform; 3.x under deform/<skin>/<slot>/<attachment>.
		const JsonValue *deform_root = a.get("attachments");
		bool nested = deform_root != nullptr;
		if (!deform_root) {
			deform_root = a.get("deform");
		}
		if (deform_root) {
			for (const auto &skm : deform_root->members) {
				const Skin *skin = sd.find_skin(skm.first);
				if (!skin) {
					return fail("animation " + name + ": skin " + skm.first + " not found");
				}
				for (const auto &slm : skm.second.members) {
					int slot = sd.find_slot(slm.first);
					if (slot < 0) {
						return fail("animation " + name + ": slot " + slm.first + " not found");
					}
					for (const auto &am : slm.second.members) {
						const Attachment *att = skin->find(slot, am.first);
						if (!att) {
							return fail("animation " + name + ": deform attachment " + am.first + " not found");
						}
						const JsonValue *list = &am.second;
						if (nested) {
							for (const auto &kind : am.second.members) {
								if (kind.first != "deform") {
									unsupported.push_back("attachment timeline " + kind.first);
								}
							}
							list = am.second.get("deform");
							if (!list) {
								continue;
							}
						}
						Timeline tl;
						tl.type = TimelineType::Deform;
						tl.target = slot;
						tl.attachment = att;
						tl.curve.channels = 1;
						size_t length = att->weighted() ? att->vertices.size() / 3 * 2 : att->vertices.size();
						for (const JsonValue &k : list->array) {
							std::vector<float> d(length, 0.0f);
							if (const JsonValue *vs = k.get("vertices")) {
								size_t off = size_t(k.num("offset", 0));
								for (size_t i = 0; i < vs->array.size() && off + i < length; i++) {
									d[off + i] = float(vs->array[i].number) * scale;
								}
							}
							float index = float(tl.deforms.size());
							tl.curve.add(float(k.num("time", 0)), &index, Curve::LINEAR);
							tl.deforms.push_back(std::move(d));
						}
						curves(*list, tl.curve, true);
						keep(std::move(tl));
					}
				}
			}
		}

		const JsonValue *order = a.get("drawOrder");
		if (!order) {
			order = a.get("draworder");
		}
		if (order) {
			Timeline tl;
			tl.type = TimelineType::DrawOrder;
			tl.curve.channels = 1;
			int n = int(sd.slots.size());
			for (const JsonValue &k : order->array) {
				float none = 0;
				tl.curve.add(float(k.num("time", 0)), &none, Curve::STEPPED);
				const JsonValue *offsets = k.get("offsets");
				if (!offsets) {
					tl.draw_orders.emplace_back();
					continue;
				}
				std::vector<int> result(n, -1);
				std::vector<bool> moved(n, false);
				for (const JsonValue &o : offsets->array) {
					int s = sd.find_slot(o.str("slot", ""));
					int to = s + int(o.num("offset", 0));
					if (s < 0 || to < 0 || to >= n) {
						return fail("animation " + name + ": bad draw order offset");
					}
					result[to] = s;
					moved[s] = true;
				}
				int fill = 0;
				for (int s = 0; s < n; s++) {
					if (moved[s]) {
						continue;
					}
					while (fill < n && result[fill] != -1) {
						fill++;
					}
					if (fill < n) {
						result[fill] = s;
					}
				}
				tl.draw_orders.push_back(std::move(result));
			}
			keep(std::move(tl));
		}

		if (const JsonValue *evs = a.get("events")) {
			Timeline tl;
			tl.type = TimelineType::Event;
			tl.curve.channels = 1;
			for (const JsonValue &k : evs->array) {
				const EventData *ed = sd.find_event(k.str("name", ""));
				if (!ed) {
					return fail("animation " + name + ": event " + k.str("name", "") + " not found");
				}
				Event e;
				e.data = ed;
				e.time = float(k.num("time", 0));
				e.int_value = int(k.num("int", ed->int_value));
				e.float_value = float(k.num("float", ed->float_value));
				e.string_value = k.str("string", ed->string_value);
				e.volume = float(k.num("volume", ed->volume));
				e.balance = float(k.num("balance", ed->balance));
				float none = 0;
				tl.curve.add(e.time, &none, Curve::STEPPED);
				tl.events.push_back(e);
			}
			keep(std::move(tl));
		}

		for (const char *k : { "transform", "path", "physics" }) {
			if (a.has(k)) {
				unsupported.push_back(std::string("animation ") + k + " timelines");
			}
		}
		anim.duration = duration;
		sd.animations.push_back(std::move(anim));
		return true;
	}

	std::vector<std::string> unsupported;
};

} // namespace

std::unique_ptr<SkeletonData> load_json(const char *text, size_t length, float scale, std::string &error) {
	JsonValue root;
	if (!parse_json(text, length, root, error)) {
		return nullptr;
	}
	auto sd = std::make_unique<SkeletonData>();
	Loader l{ *sd, scale, 0, {}, {}, {} };
	bool ok = l.skeleton(root) && l.bones(root) && l.slots(root) && l.ik(root) && l.skins(root) && l.events(root);
	if (ok) {
		if (const JsonValue *anims = root.get("animations")) {
			for (const auto &m : anims->members) {
				if (!l.animation(m.first, m.second)) {
					ok = false;
					break;
				}
			}
		}
	}
	if (!ok) {
		error = l.error;
		return nullptr;
	}
	std::sort(l.unsupported.begin(), l.unsupported.end());
	l.unsupported.erase(std::unique(l.unsupported.begin(), l.unsupported.end()), l.unsupported.end());
	sd->unsupported = std::move(l.unsupported);
	return sd;
}

} // namespace skel
