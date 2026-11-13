// Clean-room code for DroidDeck/Kestrel: written from scratch from public format notes and first principles.
// No third-party skeletal-animation runtime source was consulted.
#include "skel.h"

#include <algorithm>
#include <cmath>

namespace skel {

namespace {

constexpr float DEG = 3.14159265358979323846f / 180.0f;

float wrap180(float degrees) {
	degrees = std::fmod(degrees, 360.0f);
	if (degrees > 180) {
		degrees -= 360;
	} else if (degrees < -180) {
		degrees += 360;
	}
	return degrees;
}

float cubic(float p0, float p1, float p2, float p3, float s) {
	float u = 1 - s;
	return u * u * u * p0 + 3 * u * u * s * p1 + 3 * u * s * s * p2 + s * s * s * p3;
}

} // namespace

// ---------------------------------------------------------------- attachments and skins

void Attachment::update_region_offsets() {
	float hw = width * 0.5f * scale_x, hh = height * 0.5f * scale_y;
	float cs = std::cos(rotation * DEG), sn = std::sin(rotation * DEG);
	const float corners[8] = { -hw, -hh, hw, -hh, hw, hh, -hw, hh }; // BL, BR, TR, TL
	for (int i = 0; i < 4; i++) {
		float lx = corners[i * 2], ly = corners[i * 2 + 1];
		offset[i * 2] = x + lx * cs - ly * sn;
		offset[i * 2 + 1] = y + lx * sn + ly * cs;
	}
}

Attachment *Skin::find(int slot, const std::string &key) const {
	if (slot < 0 || slot >= int(slots.size())) {
		return nullptr;
	}
	auto it = slots[slot].find(key);
	return it == slots[slot].end() ? nullptr : it->second.get();
}

// ---------------------------------------------------------------- curves

void Curve::add(float t, const float *values, Kind kind) {
	frames.push_back(t);
	for (int c = 0; c < channels; c++) {
		frames.push_back(values[c]);
	}
	kinds.push_back(kind);
	beziers.resize(kinds.size() * channels * 4, 0.0f);
}

void Curve::set_bezier(int frame, int channel, float cx1, float cy1, float cx2, float cy2) {
	kinds[frame] = BEZIER;
	float *b = &beziers[(frame * channels + channel) * 4];
	b[0] = cx1;
	b[1] = cy1;
	b[2] = cx2;
	b[3] = cy2;
}

int Curve::search(float t) const {
	int n = frame_count();
	int lo = 0, hi = n - 1;
	while (lo < hi) {
		int mid = (lo + hi + 1) / 2;
		if (time(mid) <= t) {
			lo = mid;
		} else {
			hi = mid - 1;
		}
	}
	return lo;
}

float Curve::evaluate(float t, int channel) const {
	int n = frame_count();
	if (n == 0) {
		return 0;
	}
	int i = search(t);
	float v0 = value(i, channel);
	if (i == n - 1 || t <= time(i)) {
		return v0;
	}
	float t0 = time(i), t1 = time(i + 1), v1 = value(i + 1, channel);
	switch (kinds[i]) {
		case STEPPED:
			return v0;
		case LINEAR:
			return v0 + (v1 - v0) * (t - t0) / (t1 - t0);
		case BEZIER: {
			const float *b = &beziers[(i * channels + channel) * 4];
			// x(s) is monotonic for valid curves; bisect for the s whose x equals t.
			float lo = 0, hi = 1;
			for (int k = 0; k < 24; k++) {
				float mid = (lo + hi) * 0.5f;
				if (cubic(t0, b[0], b[2], t1, mid) < t) {
					lo = mid;
				} else {
					hi = mid;
				}
			}
			return cubic(v0, b[1], b[3], v1, (lo + hi) * 0.5f);
		}
	}
	return v0;
}

// ---------------------------------------------------------------- setup data lookups

int SkeletonData::find_bone(const std::string &name) const {
	for (const auto &b : bones) {
		if (b.name == name) {
			return b.index;
		}
	}
	return -1;
}

int SkeletonData::find_slot(const std::string &name) const {
	for (const auto &s : slots) {
		if (s.name == name) {
			return s.index;
		}
	}
	return -1;
}

const Skin *SkeletonData::find_skin(const std::string &name) const {
	for (const auto &s : skins) {
		if (s->name == name) {
			return s.get();
		}
	}
	return nullptr;
}

const Animation *SkeletonData::find_animation(const std::string &name) const {
	for (const auto &a : animations) {
		if (a.name == name) {
			return &a;
		}
	}
	return nullptr;
}

const EventData *SkeletonData::find_event(const std::string &name) const {
	for (const auto &e : events) {
		if (e.name == name) {
			return &e;
		}
	}
	return nullptr;
}

int SkeletonData::find_ik(const std::string &name) const {
	for (size_t i = 0; i < ik.size(); i++) {
		if (ik[i].name == name) {
			return int(i);
		}
	}
	return -1;
}

int SkeletonData::major_version() const {
	return version.empty() ? 0 : std::atoi(version.c_str());
}

// ---------------------------------------------------------------- posed skeleton

void Bone::setup() {
	x = data->x;
	y = data->y;
	rotation = data->rotation;
	scale_x = data->scale_x;
	scale_y = data->scale_y;
	shear_x = data->shear_x;
	shear_y = data->shear_y;
}

float Bone::world_rotation_x() const { return std::atan2(c, a) / DEG; }
float Bone::world_scale_x() const { return std::sqrt(a * a + c * c); }
float Bone::world_scale_y() const { return std::sqrt(b * b + d * d); }

void Slot::setup(const Skeleton &skeleton) {
	color = data->color;
	dark = data->dark;
	attachment = data->attachment.empty() ? nullptr : skeleton.attachment(data->index, data->attachment);
	deform.clear();
}

Skeleton::Skeleton(const SkeletonData *d) : data(d) {
	bones.resize(d->bones.size());
	for (size_t i = 0; i < bones.size(); i++) {
		bones[i].data = &d->bones[i];
	}
	slots.resize(d->slots.size());
	for (size_t i = 0; i < slots.size(); i++) {
		slots[i].data = &d->slots[i];
	}
	ik.resize(d->ik.size());
	for (size_t i = 0; i < ik.size(); i++) {
		ik[i].data = &d->ik[i];
	}
	setup_pose();
}

void Skeleton::setup_bones() {
	for (auto &b : bones) {
		b.setup();
	}
	for (auto &k : ik) {
		k.mix = k.data->mix;
		k.softness = k.data->softness;
		k.bend_positive = k.data->bend_positive;
		k.compress = k.data->compress;
		k.stretch = k.data->stretch;
	}
}

void Skeleton::setup_slots() {
	draw_order.resize(slots.size());
	for (size_t i = 0; i < slots.size(); i++) {
		draw_order[i] = int(i);
		slots[i].setup(*this);
	}
}

void Skeleton::setup_pose() {
	setup_bones();
	setup_slots();
}

const Attachment *Skeleton::attachment(int slot, const std::string &key) const {
	if (key.empty()) {
		return nullptr;
	}
	if (skin) {
		if (const Attachment *a = skin->find(slot, key)) {
			return a;
		}
	}
	return data->default_skin ? data->default_skin->find(slot, key) : nullptr;
}

bool Skeleton::set_skin(const std::string &name) {
	const Skin *next = name.empty() ? nullptr : data->find_skin(name);
	if (!name.empty() && !next) {
		return false;
	}
	skin = next;
	for (auto &s : slots) {
		if (!s.data->attachment.empty()) {
			s.attachment = attachment(s.data->index, s.data->attachment);
			s.deform.clear();
		}
	}
	for (auto &b : bones) {
		b.active = !b.data->skin_required;
	}
	if (skin) {
		for (int i : skin->bones) {
			bones[i].active = true;
		}
	}
	return true;
}

bool Skeleton::set_attachment(const std::string &slot_name, const std::string &key) {
	int s = data->find_slot(slot_name);
	if (s < 0) {
		return false;
	}
	const Attachment *a = key.empty() ? nullptr : attachment(s, key);
	if (!key.empty() && !a) {
		return false;
	}
	if (slots[s].attachment != a) {
		slots[s].deform.clear();
	}
	slots[s].attachment = a;
	return true;
}

void Skeleton::update_bone(Bone &bone) {
	const BoneData &bd = *bone.data;
	float rx = (bone.rotation + bone.shear_x) * DEG;
	float ry = (bone.rotation + 90 + bone.shear_y) * DEG;
	float la = std::cos(rx) * bone.scale_x, lb = std::cos(ry) * bone.scale_y;
	float lc = std::sin(rx) * bone.scale_x, ld = std::sin(ry) * bone.scale_y;
	if (bd.parent < 0) {
		bone.a = la * scale_x;
		bone.b = lb * scale_x;
		bone.c = lc * scale_y;
		bone.d = ld * scale_y;
		bone.world_x = bone.x * scale_x + x;
		bone.world_y = bone.y * scale_y + y;
		return;
	}
	const Bone &p = bones[bd.parent];
	p.local_to_world(bone.x, bone.y, bone.world_x, bone.world_y);
	float pa = p.a, pb = p.b, pc = p.c, pd = p.d;
	switch (bd.inherit) {
		case Inherit::Normal:
			break;
		case Inherit::OnlyTranslation:
			pa = scale_x;
			pb = 0;
			pc = 0;
			pd = scale_y;
			break;
		case Inherit::NoRotationOrReflection: {
			// Strip the parent's rotation (and any reflection), keep its scale and shear.
			float th = std::atan2(pc, pa), cs = std::cos(th), sn = std::sin(th);
			float qa = cs * pa + sn * pc, qb = cs * pb + sn * pd, qd = -sn * pb + cs * pd;
			if (qa * qd < 0) {
				qb = -qb;
				qd = -qd;
			}
			pa = qa;
			pb = qb;
			pc = 0;
			pd = qd;
			break;
		}
		case Inherit::NoScale:
		case Inherit::NoScaleOrReflection: {
			// Keep only the parent's rotation; NoScale also keeps a reflection.
			float th = std::atan2(pc, pa), cs = std::cos(th), sn = std::sin(th);
			float flip = (bd.inherit == Inherit::NoScale && pa * pd - pb * pc < 0) ? -1.0f : 1.0f;
			pa = cs;
			pb = -sn * flip;
			pc = sn;
			pd = cs * flip;
			break;
		}
	}
	bone.a = pa * la + pb * lc;
	bone.b = pa * lb + pb * ld;
	bone.c = pc * la + pd * lc;
	bone.d = pc * lb + pd * ld;
}

namespace {

// Rotation (degrees, in the bone's parent space) that points the bone's x axis at a world point.
float aim(const Skeleton &s, const Bone &bone, float wx, float wy) {
	float lx, ly;
	if (bone.data->parent < 0) {
		lx = (wx - s.x) / (s.scale_x ? s.scale_x : 1);
		ly = (wy - s.y) / (s.scale_y ? s.scale_y : 1);
	} else {
		const Bone &p = s.bones[bone.data->parent];
		float det = p.a * p.d - p.b * p.c;
		if (std::fabs(det) < 1e-9f) {
			return bone.rotation;
		}
		float dx = wx - p.world_x, dy = wy - p.world_y;
		lx = (dx * p.d - dy * p.b) / det;
		ly = (dy * p.a - dx * p.c) / det;
	}
	return std::atan2(ly - bone.y, lx - bone.x) / DEG - bone.shear_x;
}

} // namespace

void Skeleton::apply_ik(IkConstraint &k) {
	const IkData &d = *k.data;
	if (k.mix <= 0 || d.bones.empty()) {
		return;
	}
	const Bone &target = bones[d.target];
	Bone &b1 = bones[d.bones[0]];
	if (d.bones.size() == 1) {
		float r = aim(*this, b1, target.world_x, target.world_y);
		b1.rotation += wrap180(r - b1.rotation) * k.mix;
		if ((k.stretch || k.compress) && b1.data->length > 0) {
			float dist = std::hypot(target.world_x - b1.world_x, target.world_y - b1.world_y);
			float len = b1.data->length * b1.world_scale_x();
			if (len > 1e-6f && ((k.stretch && dist > len) || (k.compress && dist < len))) {
				float s = (dist / len - 1) * k.mix + 1;
				b1.scale_x *= s;
				if (d.uniform) {
					b1.scale_y *= s;
				}
			}
		}
		return;
	}
	// Two bones: law of cosines on the first, then aim the second at the target.
	// Lengths are measured in world space from the current pose.
	Bone &b2 = bones[d.bones[1]];
	float ax = b1.world_x, ay = b1.world_y;
	float jx = b2.world_x, jy = b2.world_y;
	float la = std::hypot(jx - ax, jy - ay);
	float lb = b2.data->length * b2.world_scale_x();
	float tx = target.world_x, ty = target.world_y;
	float dist = std::hypot(tx - ax, ty - ay);
	float r1 = aim(*this, b1, tx, ty);
	if (la > 1e-6f && lb > 1e-6f && dist > 1e-6f) {
		float cosine = (la * la + dist * dist - lb * lb) / (2 * la * dist);
		cosine = std::max(-1.0f, std::min(1.0f, cosine));
		float bend = std::acos(cosine) / DEG;
		// The joint sits on the counter-clockwise side of the reach line when bending positive (y up).
		r1 += k.bend_positive ? bend : -bend;
		// Account for the joint not lying on the first bone's x axis.
		float jlx = b2.x, jly = b2.y;
		r1 -= std::atan2(jly * b1.scale_y, jlx * b1.scale_x) / DEG;
	}
	b1.rotation += wrap180(r1 - b1.rotation) * k.mix;
	update_bone(b1);
	update_bone(b2);
	float r2 = aim(*this, b2, tx, ty);
	b2.rotation += wrap180(r2 - b2.rotation) * k.mix;
}

void Skeleton::update_world_transform() {
	for (auto &b : bones) {
		update_bone(b);
	}
	for (auto &k : ik) {
		apply_ik(k);
		// Constrained bones changed: recompute them and everything after the first one (children follow parents).
		int first = *std::min_element(k.data->bones.begin(), k.data->bones.end());
		for (size_t i = first; i < bones.size(); i++) {
			update_bone(bones[i]);
		}
	}
}

int Skeleton::world_vertices(int slot_index, std::vector<float> &out) const {
	const Slot &slot = slots[slot_index];
	const Attachment *a = slot.attachment;
	out.clear();
	if (!a) {
		return 0;
	}
	const Bone &bone = bones[slot.data->bone];
	if (a->type == AttachmentType::Region) {
		out.resize(8);
		for (int i = 0; i < 4; i++) {
			bone.local_to_world(a->offset[i * 2], a->offset[i * 2 + 1], out[i * 2], out[i * 2 + 1]);
		}
		return 4;
	}
	if (a->type == AttachmentType::Point) {
		out.resize(2);
		bone.local_to_world(a->x, a->y, out[0], out[1]);
		return 1;
	}
	const std::vector<float> &def = slot.deform;
	out.resize(a->vertex_count * 2);
	if (!a->weighted()) {
		bool has = def.size() == a->vertices.size();
		for (int v = 0; v < a->vertex_count; v++) {
			float lx = a->vertices[v * 2] + (has ? def[v * 2] : 0);
			float ly = a->vertices[v * 2 + 1] + (has ? def[v * 2 + 1] : 0);
			bone.local_to_world(lx, ly, out[v * 2], out[v * 2 + 1]);
		}
		return a->vertex_count;
	}
	bool has = def.size() == a->vertices.size() / 3 * 2;
	size_t bi = 0, vi = 0;
	for (int v = 0; v < a->vertex_count; v++) {
		int count = a->bones[bi++];
		float wx = 0, wy = 0;
		for (int k = 0; k < count; k++, bi++, vi++) {
			const Bone &inf = bones[a->bones[bi]];
			float lx = a->vertices[vi * 3] + (has ? def[vi * 2] : 0);
			float ly = a->vertices[vi * 3 + 1] + (has ? def[vi * 2 + 1] : 0);
			float w = a->vertices[vi * 3 + 2];
			float px, py;
			inf.local_to_world(lx, ly, px, py);
			wx += px * w;
			wy += py * w;
		}
		out[v * 2] = wx;
		out[v * 2 + 1] = wy;
	}
	return a->vertex_count;
}

// ---------------------------------------------------------------- applying animations

namespace {

void mix_value(float &value, float setup, float key, float alpha, MixBlend blend) {
	if (blend == MixBlend::Replace) {
		value += (key - value) * alpha;
	} else {
		value += (key - setup) * alpha;
	}
}

void mix_color(Color &c, const Color &setup, float r, float g, float b, float a, bool rgb, bool alpha_channel,
		float alpha, MixBlend blend) {
	if (rgb) {
		mix_value(c.r, setup.r, r, alpha, blend);
		mix_value(c.g, setup.g, g, alpha, blend);
		mix_value(c.b, setup.b, b, alpha, blend);
	}
	if (alpha_channel) {
		mix_value(c.a, setup.a, a, alpha, blend);
	}
}

void fire_events(const Timeline &tl, float last, float time, float duration, bool loop, std::vector<Event> *out) {
	if (!out || tl.events.empty()) {
		return;
	}
	auto range = [&](float from, float to, bool inclusive_from) {
		for (const Event &e : tl.events) {
			if ((inclusive_from ? e.time >= from : e.time > from) && e.time <= to) {
				out->push_back(e);
			}
		}
	};
	if (last < 0) {
		range(0, time, true);
	} else if (loop && time < last) {
		range(last, duration, false);
		range(0, time, true);
	} else if (time > last) {
		range(last, time, false);
	}
}

} // namespace

void apply_animation(const Animation &anim, Skeleton &sk, float last_time, float time, bool loop,
		std::vector<Event> *events, float alpha, MixBlend blend, bool discrete) {
	for (const Timeline &tl : anim.timelines) {
		if (tl.type == TimelineType::Event) {
			fire_events(tl, last_time, time, anim.duration, loop, events);
			continue;
		}
		if (tl.type == TimelineType::DrawOrder) {
			if (!discrete || tl.draw_orders.empty()) {
				continue;
			}
			int i = tl.curve.search(time);
			if (time < tl.curve.time(0)) {
				continue;
			}
			const auto &order = tl.draw_orders[i];
			if (order.empty()) {
				for (size_t s = 0; s < sk.draw_order.size(); s++) {
					sk.draw_order[s] = int(s);
				}
			} else {
				sk.draw_order = order;
			}
			continue;
		}
		if (tl.curve.frame_count() == 0 || time < tl.curve.time(0)) {
			continue;
		}
		const Curve &cv = tl.curve;
		switch (tl.type) {
			case TimelineType::Rotate: {
				Bone &b = sk.bones[tl.target];
				if (!b.active) {
					break;
				}
				float key = b.data->rotation + cv.evaluate(time, 0);
				if (blend == MixBlend::Replace) {
					b.rotation += wrap180(key - b.rotation) * alpha;
				} else {
					b.rotation += (key - b.data->rotation) * alpha;
				}
				break;
			}
			case TimelineType::Translate:
			case TimelineType::TranslateX:
			case TimelineType::TranslateY:
			case TimelineType::Shear:
			case TimelineType::ShearX:
			case TimelineType::ShearY: {
				Bone &b = sk.bones[tl.target];
				if (!b.active) {
					break;
				}
				bool shear = tl.type == TimelineType::Shear || tl.type == TimelineType::ShearX || tl.type == TimelineType::ShearY;
				float &vx = shear ? b.shear_x : b.x;
				float &vy = shear ? b.shear_y : b.y;
				float sx = shear ? b.data->shear_x : b.data->x;
				float sy = shear ? b.data->shear_y : b.data->y;
				bool both = tl.type == TimelineType::Translate || tl.type == TimelineType::Shear;
				bool only_y = tl.type == TimelineType::TranslateY || tl.type == TimelineType::ShearY;
				if (both || !only_y) {
					mix_value(vx, sx, sx + cv.evaluate(time, 0), alpha, blend);
				}
				if (both) {
					mix_value(vy, sy, sy + cv.evaluate(time, 1), alpha, blend);
				} else if (only_y) {
					mix_value(vy, sy, sy + cv.evaluate(time, 0), alpha, blend);
				}
				break;
			}
			case TimelineType::Scale:
			case TimelineType::ScaleX:
			case TimelineType::ScaleY: {
				Bone &b = sk.bones[tl.target];
				if (!b.active) {
					break;
				}
				if (tl.type != TimelineType::ScaleY) {
					mix_value(b.scale_x, b.data->scale_x, b.data->scale_x * cv.evaluate(time, 0), alpha, blend);
				}
				if (tl.type == TimelineType::Scale) {
					mix_value(b.scale_y, b.data->scale_y, b.data->scale_y * cv.evaluate(time, 1), alpha, blend);
				} else if (tl.type == TimelineType::ScaleY) {
					mix_value(b.scale_y, b.data->scale_y, b.data->scale_y * cv.evaluate(time, 0), alpha, blend);
				}
				break;
			}
			case TimelineType::Attachment: {
				if (!discrete) {
					break;
				}
				Slot &s = sk.slots[tl.target];
				if (!sk.bones[s.data->bone].active) {
					break;
				}
				const Attachment *a = sk.attachment(tl.target, tl.names[cv.search(time)]);
				if (a != s.attachment) {
					s.deform.clear();
				}
				s.attachment = a;
				break;
			}
			case TimelineType::RGBA:
			case TimelineType::RGB:
			case TimelineType::Alpha:
			case TimelineType::RGBA2:
			case TimelineType::RGB2: {
				Slot &s = sk.slots[tl.target];
				const SlotData &sd = *s.data;
				float v[7];
				for (int c = 0; c < cv.channels && c < 7; c++) {
					v[c] = cv.evaluate(time, c);
				}
				if (tl.type == TimelineType::Alpha) {
					mix_color(s.color, sd.color, 0, 0, 0, v[0], false, true, alpha, blend);
				} else if (tl.type == TimelineType::RGB) {
					mix_color(s.color, sd.color, v[0], v[1], v[2], 1, true, false, alpha, blend);
				} else if (tl.type == TimelineType::RGBA) {
					mix_color(s.color, sd.color, v[0], v[1], v[2], v[3], true, true, alpha, blend);
				} else if (tl.type == TimelineType::RGBA2) {
					mix_color(s.color, sd.color, v[0], v[1], v[2], v[3], true, true, alpha, blend);
					mix_color(s.dark, sd.dark, v[4], v[5], v[6], 1, true, false, alpha, blend);
				} else {
					mix_color(s.color, sd.color, v[0], v[1], v[2], 1, true, false, alpha, blend);
					mix_color(s.dark, sd.dark, v[3], v[4], v[5], 1, true, false, alpha, blend);
				}
				break;
			}
			case TimelineType::Deform: {
				Slot &s = sk.slots[tl.target];
				if (!s.attachment || s.attachment->timeline_attachment != tl.attachment) {
					break;
				}
				// The curve's single channel holds the frame index, so it yields frame + eased fraction.
				float f = cv.evaluate(time, 0);
				int i = std::min(int(f), int(tl.deforms.size()) - 1);
				float p = f - float(i);
				const std::vector<float> &d0 = tl.deforms[i];
				const std::vector<float> &d1 = tl.deforms[std::min(i + 1, int(tl.deforms.size()) - 1)];
				size_t n = d0.size();
				if (s.deform.size() != n) {
					s.deform.assign(n, 0.0f);
				}
				for (size_t k = 0; k < n; k++) {
					float key = d0[k] + (d1[k] - d0[k]) * p;
					mix_value(s.deform[k], 0, key, alpha, blend);
				}
				break;
			}
			case TimelineType::Ik: {
				IkConstraint &k = sk.ik[tl.target];
				mix_value(k.mix, k.data->mix, cv.evaluate(time, 0), alpha, blend);
				mix_value(k.softness, k.data->softness, cv.evaluate(time, 1), alpha, blend);
				if (discrete) {
					uint8_t fl = tl.flags[cv.search(time)];
					k.bend_positive = fl & 1;
					k.compress = fl & 2;
					k.stretch = fl & 4;
				}
				break;
			}
			default:
				break;
		}
	}
}

// ---------------------------------------------------------------- animation state

float TrackEntry::animation_time() const {
	float duration = animation ? animation->duration : 0;
	if (duration <= 0) {
		return 0;
	}
	if (loop) {
		return std::fmod(track_time, duration);
	}
	return std::min(track_time, duration);
}

float AnimationStateData::mix(const Animation *from, const Animation *to) const {
	if (from && to) {
		auto it = mixes.find(from->name + "\n" + to->name);
		if (it != mixes.end()) {
			return it->second;
		}
	}
	return default_mix;
}

void AnimationStateData::set_mix(const std::string &from, const std::string &to, float duration) {
	mixes[from + "\n" + to] = duration;
}

std::shared_ptr<TrackEntry> AnimationState::current(int track) const {
	return track >= 0 && track < int(tracks.size()) ? tracks[track] : nullptr;
}

std::shared_ptr<TrackEntry> AnimationState::start(int track, const Animation *animation, bool loop) {
	if (track >= int(tracks.size())) {
		tracks.resize(track + 1);
	}
	auto e = std::make_shared<TrackEntry>();
	e->animation = animation;
	e->loop = loop;
	auto prev = tracks[track];
	if (prev) {
		e->from = prev;
		prev->next.reset();
		e->mix_duration = data->mix(prev->animation, animation);
		if (e->mix_duration <= 0) {
			e->from.reset();
			pending.push_back({ StateEvent::End, track, prev->animation, {} });
		}
	}
	tracks[track] = e;
	pending.push_back({ StateEvent::Start, track, animation, {} });
	return e;
}

std::shared_ptr<TrackEntry> AnimationState::set_animation(int track, const std::string &name, bool loop) {
	const Animation *a = data->skeleton->find_animation(name);
	if (!a) {
		return nullptr;
	}
	return start(track, a, loop);
}

std::shared_ptr<TrackEntry> AnimationState::add_animation(int track, const std::string &name, bool loop, float delay) {
	const Animation *a = data->skeleton->find_animation(name);
	if (!a) {
		return nullptr;
	}
	auto last = current(track);
	if (!last) {
		auto e = start(track, a, loop);
		e->delay = delay > 0 ? delay : 0;
		return e;
	}
	while (last->next) {
		last = last->next;
	}
	auto e = std::make_shared<TrackEntry>();
	e->animation = a;
	e->loop = loop;
	e->mix_duration = data->mix(last->animation, a);
	// A non-positive delay queues relative to the end of the previous animation.
	e->delay = delay > 0 ? delay : std::max(0.0f, (last->animation ? last->animation->duration : 0) + delay - e->mix_duration);
	last->next = e;
	return e;
}

std::shared_ptr<TrackEntry> AnimationState::set_empty(int track, float mix_duration) {
	auto e = start(track, nullptr, false);
	e->mix_duration = mix_duration;
	if (mix_duration > 0 && !e->from && track < int(tracks.size())) {
		// nothing to fade from
	}
	return e;
}

void AnimationState::clear_track(int track) {
	if (track < int(tracks.size()) && tracks[track]) {
		pending.push_back({ StateEvent::End, track, tracks[track]->animation, {} });
		tracks[track].reset();
	}
}

void AnimationState::clear_tracks() {
	for (size_t i = 0; i < tracks.size(); i++) {
		clear_track(int(i));
	}
}

void AnimationState::update(float dt) {
	dt *= time_scale;
	for (size_t t = 0; t < tracks.size(); t++) {
		auto e = tracks[t];
		if (!e) {
			continue;
		}
		float step = dt * e->time_scale;
		if (e->delay > 0) {
			e->delay -= step;
			if (e->delay > 0) {
				continue;
			}
			step = -e->delay;
			e->delay = 0;
		}
		float before = e->track_time;
		e->track_time += step;
		float duration = e->animation ? e->animation->duration : 0;
		if (duration > 0 && std::floor(e->track_time / duration) > std::floor(before / duration) && (e->loop || !e->complete_fired)) {
			e->complete_fired = true;
			pending.push_back({ StateEvent::Complete, int(t), e->animation, {} });
		} else if (duration <= 0 && !e->complete_fired && e->animation) {
			e->complete_fired = true;
			pending.push_back({ StateEvent::Complete, int(t), e->animation, {} });
		}
		if (e->from) {
			e->mix_time += dt;
			for (auto f = e->from; f; f = f->from) {
				f->last_time = f->track_time;
				f->track_time += dt * f->time_scale;
			}
			if (e->mix_time >= e->mix_duration) {
				pending.push_back({ StateEvent::End, int(t), e->from->animation, {} });
				e->from.reset();
			}
		}
		if (e->next && e->track_time - e->delay >= e->next->delay) {
			auto n = e->next;
			float over = e->track_time - n->delay;
			n->delay = 0;
			n->from = n->mix_duration > 0 ? e : nullptr;
			if (!n->from) {
				pending.push_back({ StateEvent::End, int(t), e->animation, {} });
			}
			e->next.reset();
			n->track_time = std::max(0.0f, over);
			tracks[t] = n;
			pending.push_back({ StateEvent::Start, int(t), n->animation, {} });
		}
	}
}

namespace {

float wrapped(const TrackEntry &e, float track_time) {
	float duration = e.animation ? e.animation->duration : 0;
	if (track_time < 0) {
		return -1;
	}
	if (duration <= 0) {
		return 0;
	}
	return e.loop ? std::fmod(track_time, duration) : std::min(track_time, duration);
}

void apply_entry(const TrackEntry &e, Skeleton &sk, float alpha, MixBlend blend, bool discrete, int track,
		std::vector<StateEvent> &pending) {
	if (e.from) {
		float m = e.mix_duration > 0 ? std::min(1.0f, e.mix_time / e.mix_duration) : 1;
		apply_entry(*e.from, sk, alpha * (1 - m), blend, false, track, pending);
		alpha *= m;
		blend = MixBlend::Add;
	}
	if (!e.animation || alpha <= 0) {
		return;
	}
	std::vector<Event> fired;
	apply_animation(*e.animation, sk, wrapped(e, e.last_time), wrapped(e, e.track_time), e.loop, &fired, alpha * e.alpha, blend, discrete);
	for (const Event &ev : fired) {
		pending.push_back({ StateEvent::Fire, track, e.animation, ev });
	}
}

} // namespace

void AnimationState::apply(Skeleton &sk) {
	if (reset_to_setup) {
		sk.setup_bones();
		for (auto &s : sk.slots) {
			s.color = s.data->color;
			s.dark = s.data->dark;
		}
	}
	for (size_t t = 0; t < tracks.size(); t++) {
		auto e = tracks[t];
		if (!e || e->delay > 0) {
			continue;
		}
		apply_entry(*e, sk, 1, MixBlend::Replace, true, int(t), pending);
		e->last_time = e->track_time;
	}
}

} // namespace skel
