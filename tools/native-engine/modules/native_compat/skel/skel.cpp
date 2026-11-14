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
	float w = width * scale_x, h = height * scale_y;
	float l = (trim[0] - 0.5f) * w, b = (trim[1] - 0.5f) * h, r = (trim[2] - 0.5f) * w, t = (trim[3] - 0.5f) * h;
	float cs = std::cos(rotation * DEG), sn = std::sin(rotation * DEG);
	const float corners[8] = { l, b, r, b, r, t, l, t }; // BL, BR, TR, TL
	for (int i = 0; i < 4; i++) {
		float lx = corners[i * 2], ly = corners[i * 2 + 1];
		offset[i * 2] = x + lx * cs - ly * sn;
		offset[i * 2 + 1] = y + lx * sn + ly * cs;
	}
}

std::string Sequence::frame_path(const std::string &base, int index) const {
	std::string n = std::to_string(start + index);
	while (int(n.size()) < digits) {
		n.insert(n.begin(), '0');
	}
	return base + n;
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

int SkeletonData::find_transform(const std::string &name) const {
	for (size_t i = 0; i < transforms.size(); i++) {
		if (transforms[i].name == name) {
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
	inherit = data->inherit;
}

float Bone::world_rotation_x() const { return std::atan2(c, a) / DEG; }
float Bone::world_scale_x() const { return std::sqrt(a * a + c * c); }
float Bone::world_scale_y() const { return std::sqrt(b * b + d * d); }

void Slot::setup(const Skeleton &skeleton) {
	color = data->color;
	dark = data->dark;
	attachment = data->attachment.empty() ? nullptr : skeleton.attachment(data->index, data->attachment);
	deform.clear();
	sequence_index = -1;
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
		constraint_order.push_back({ IK, int(i) });
	}
	transforms.resize(d->transforms.size());
	for (size_t i = 0; i < transforms.size(); i++) {
		transforms[i].data = &d->transforms[i];
		constraint_order.push_back({ TRANSFORM, int(i) });
	}
	paths.resize(d->paths.size());
	for (size_t i = 0; i < paths.size(); i++) {
		paths[i].data = &d->paths[i];
		constraint_order.push_back({ PATH, int(i) });
	}
	physics.resize(d->physics.size());
	for (size_t i = 0; i < physics.size(); i++) {
		physics[i].data = &d->physics[i];
		constraint_order.push_back({ PHYSICS, int(i) });
	}
	auto order_of = [&](const std::pair<int, int> &c) {
		switch (c.first) {
			case IK: return d->ik[c.second].order;
			case TRANSFORM: return d->transforms[c.second].order;
			case PATH: return d->paths[c.second].order;
			default: return d->physics[c.second].order;
		}
	};
	std::stable_sort(constraint_order.begin(), constraint_order.end(),
			[&](const std::pair<int, int> &a, const std::pair<int, int> &b) { return order_of(a) < order_of(b); });
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
	for (auto &t : transforms) {
		const TransformData &d = *t.data;
		t.mix_rotate = d.mix_rotate;
		t.mix_x = d.mix_x;
		t.mix_y = d.mix_y;
		t.mix_scale_x = d.mix_scale_x;
		t.mix_scale_y = d.mix_scale_y;
		t.mix_shear_y = d.mix_shear_y;
	}
	for (auto &p : paths) {
		const PathData &d = *p.data;
		p.position = d.position;
		p.spacing = d.spacing;
		p.mix_rotate = d.mix_rotate;
		p.mix_x = d.mix_x;
		p.mix_y = d.mix_y;
	}
	for (auto &p : physics) {
		const PhysicsData &d = *p.data;
		p.inertia = d.inertia;
		p.strength = d.strength;
		p.damping = d.damping;
		p.mass_inverse = d.mass_inverse;
		p.wind = d.wind;
		p.gravity = d.gravity;
		p.mix = d.mix;
	}
}

void Skeleton::reset_physics() {
	for (auto &p : physics) {
		p.reset();
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
		slots[s].sequence_index = -1;
	}
	slots[s].attachment = a;
	return true;
}

namespace {

// The matrix a bone's local transform is multiplied by: the skeleton scale for a root, otherwise the
// parent's world matrix reduced by the bone's inherit mode.
void parent_basis(const Skeleton &s, const Bone &bone, float &pa, float &pb, float &pc, float &pd) {
	const BoneData &bd = *bone.data;
	if (bd.parent < 0) {
		pa = s.scale_x;
		pb = 0;
		pc = 0;
		pd = s.scale_y;
		return;
	}
	const Bone &p = s.bones[bd.parent];
	pa = p.a;
	pb = p.b;
	pc = p.c;
	pd = p.d;
	switch (bone.inherit) {
		case Inherit::Normal:
			break;
		case Inherit::OnlyTranslation:
			pa = s.scale_x;
			pb = 0;
			pc = 0;
			pd = s.scale_y;
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
}

} // namespace

void Skeleton::update_bone(Bone &bone) {
	const BoneData &bd = *bone.data;
	float rx = (bone.rotation + bone.shear_x) * DEG;
	float ry = (bone.rotation + 90 + bone.shear_y) * DEG;
	float la = std::cos(rx) * bone.scale_x, lb = std::cos(ry) * bone.scale_y;
	float lc = std::sin(rx) * bone.scale_x, ld = std::sin(ry) * bone.scale_y;
	if (bd.parent < 0) {
		bone.world_x = bone.x * scale_x + x;
		bone.world_y = bone.y * scale_y + y;
	} else {
		bones[bd.parent].local_to_world(bone.x, bone.y, bone.world_x, bone.world_y);
	}
	float pa, pb, pc, pd;
	parent_basis(*this, bone, pa, pb, pc, pd);
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
		// Bending positive turns the second bone counter-clockwise from the first (y up), which puts the
		// joint on the clockwise side of the reach line. Checked against setup poses of game skeletons,
		// where the solved chain must reproduce the authored pose.
		r1 += k.bend_positive ? -bend : bend;
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

void Skeleton::set_world(Bone &bone, float a, float b, float c, float d, float wx, float wy) {
	// Store the world transform as local values, so recomputing the bone (and its children) keeps it.
	if (bone.data->parent < 0) {
		bone.x = scale_x ? (wx - x) / scale_x : 0;
		bone.y = scale_y ? (wy - y) / scale_y : 0;
	} else {
		const Bone &p = bones[bone.data->parent];
		float det = p.a * p.d - p.b * p.c;
		if (std::fabs(det) > 1e-12f) {
			float dx = wx - p.world_x, dy = wy - p.world_y;
			bone.x = (dx * p.d - dy * p.b) / det;
			bone.y = (dy * p.a - dx * p.c) / det;
		}
	}
	float pa, pb, pc, pd;
	parent_basis(*this, bone, pa, pb, pc, pd);
	float det = pa * pd - pb * pc;
	if (std::fabs(det) > 1e-12f) {
		float la = (pd * a - pb * c) / det, lb = (pd * b - pb * d) / det;
		float lc = (pa * c - pc * a) / det, ld = (pa * d - pc * b) / det;
		float rx = std::atan2(lc, la) / DEG;
		bone.rotation = rx - bone.shear_x;
		bone.scale_x = std::hypot(la, lc);
		bone.scale_y = std::hypot(lb, ld);
		bone.shear_y = wrap180(std::atan2(ld, lb) / DEG - bone.rotation - 90);
	}
	bone.a = a;
	bone.b = b;
	bone.c = c;
	bone.d = d;
	bone.world_x = wx;
	bone.world_y = wy;
}

void Skeleton::apply_transform(TransformConstraint &t) {
	const TransformData &d = *t.data;
	const Bone &target = bones[d.target];
	if (d.local) {
		for (int i : d.bones) {
			Bone &b = bones[i];
			if (!b.active) {
				continue;
			}
			if (d.relative) {
				b.rotation += (target.rotation + d.offset_rotation) * t.mix_rotate;
				b.x += (target.x + d.offset_x) * t.mix_x;
				b.y += (target.y + d.offset_y) * t.mix_y;
				b.scale_x *= ((target.scale_x - 1 + d.offset_scale_x) * t.mix_scale_x) + 1;
				b.scale_y *= ((target.scale_y - 1 + d.offset_scale_y) * t.mix_scale_y) + 1;
				b.shear_y += (target.shear_y + d.offset_shear_y) * t.mix_shear_y;
			} else {
				b.rotation += wrap180(target.rotation + d.offset_rotation - b.rotation) * t.mix_rotate;
				b.x += (target.x + d.offset_x - b.x) * t.mix_x;
				b.y += (target.y + d.offset_y - b.y) * t.mix_y;
				b.scale_x += (target.scale_x + d.offset_scale_x - b.scale_x) * t.mix_scale_x;
				b.scale_y += (target.scale_y + d.offset_scale_y - b.scale_y) * t.mix_scale_y;
				b.shear_y += wrap180(target.shear_y + d.offset_shear_y - b.shear_y) * t.mix_shear_y;
			}
		}
		return;
	}
	float ta = target.a, tb = target.b, tc = target.c, td = target.d;
	// A reflected target turns its offsets the other way.
	float sign = ta * td - tb * tc < 0 ? -1.0f : 1.0f;
	float off_rotation = d.offset_rotation * DEG * sign, off_shear = d.offset_shear_y * DEG * sign;
	float target_rotation = std::atan2(tc, ta);
	for (int i : d.bones) {
		Bone &b = bones[i];
		if (!b.active) {
			continue;
		}
		float a = b.a, bb = b.b, c = b.c, dd = b.d, wx = b.world_x, wy = b.world_y;
		if (t.mix_rotate != 0) {
			float r = d.relative ? target_rotation + off_rotation : target_rotation - std::atan2(c, a) + off_rotation;
			r = wrap180(r / DEG) * DEG * t.mix_rotate;
			float cs = std::cos(r), sn = std::sin(r);
			float na = cs * a - sn * c, nb = cs * bb - sn * dd, nc = sn * a + cs * c, nd = sn * bb + cs * dd;
			a = na;
			bb = nb;
			c = nc;
			dd = nd;
		}
		if (t.mix_x != 0 || t.mix_y != 0) {
			float tx, ty;
			target.local_to_world(d.offset_x, d.offset_y, tx, ty);
			if (d.relative) {
				wx += (tx - target.world_x) * t.mix_x;
				wy += (ty - target.world_y) * t.mix_y;
			} else {
				wx += (tx - wx) * t.mix_x;
				wy += (ty - wy) * t.mix_y;
			}
		}
		if (t.mix_scale_x != 0) {
			float s = std::hypot(a, c);
			if (s > 1e-5f) {
				float ts = std::hypot(ta, tc);
				float k = d.relative ? ((ts - 1 + d.offset_scale_x) * t.mix_scale_x + 1) : (s + (ts - s + d.offset_scale_x) * t.mix_scale_x) / s;
				a *= k;
				c *= k;
			}
		}
		if (t.mix_scale_y != 0) {
			float s = std::hypot(bb, dd);
			if (s > 1e-5f) {
				float ts = std::hypot(tb, td);
				float k = d.relative ? ((ts - 1 + d.offset_scale_y) * t.mix_scale_y + 1) : (s + (ts - s + d.offset_scale_y) * t.mix_scale_y) / s;
				bb *= k;
				dd *= k;
			}
		}
		if (t.mix_shear_y != 0) {
			// Angle between the y and x axes, matched to the target's.
			float by = std::atan2(dd, bb), bx = std::atan2(c, a);
			float tshear = std::atan2(td, tb) - target_rotation;
			float r = d.relative ? tshear - 0.5f * 3.14159265f + off_shear : tshear - (by - bx) + off_shear;
			r = wrap180(r / DEG) * DEG * t.mix_shear_y;
			float len = std::hypot(bb, dd), ang = by + r;
			bb = std::cos(ang) * len;
			dd = std::sin(ang) * len;
		}
		set_world(b, a, bb, c, dd, wx, wy);
	}
}

namespace {

// Point and unit tangent on a cubic bezier at s.
void bezier_at(const float *c, float s, float &x, float &y, float &tx, float &ty) {
	float u = 1 - s;
	x = u * u * u * c[0] + 3 * u * u * s * c[2] + 3 * u * s * s * c[4] + s * s * s * c[6];
	y = u * u * u * c[1] + 3 * u * u * s * c[3] + 3 * u * s * s * c[5] + s * s * s * c[7];
	tx = 3 * u * u * (c[2] - c[0]) + 6 * u * s * (c[4] - c[2]) + 3 * s * s * (c[6] - c[4]);
	ty = 3 * u * u * (c[3] - c[1]) + 6 * u * s * (c[5] - c[3]) + 3 * s * s * (c[7] - c[5]);
	float len = std::hypot(tx, ty);
	if (len > 1e-9f) {
		tx /= len;
		ty /= len;
	} else {
		tx = 1;
		ty = 0;
	}
}

// Rotates a bone's world basis by r radians.
void rotate_basis(float &a, float &b, float &c, float &d, float r) {
	float cs = std::cos(r), sn = std::sin(r);
	float na = cs * a - sn * c, nc = sn * a + cs * c;
	float nb = cs * b - sn * d, nd = sn * b + cs * d;
	a = na;
	b = nb;
	c = nc;
	d = nd;
}

} // namespace

// Path vertices are triples (handle in, anchor, handle out). An open path runs anchor to anchor through
// the handles between them; a closed one also joins the last anchor back to the first. Each curve is
// sampled into short segments so points can be found by arc length (constant speed along the path).
void Skeleton::apply_path(PathConstraint &p) {
	const PathData &d = *p.data;
	if (d.bones.empty() || (p.mix_rotate <= 0 && p.mix_x <= 0 && p.mix_y <= 0)) {
		return;
	}
	const Attachment *att = slots[d.target].attachment;
	if (!att || att->type != AttachmentType::Path) {
		return;
	}
	int n = world_vertices(d.target, path_world_);
	int curves = att->closed ? n / 3 : n / 3 - 1;
	if (curves < 1) {
		return;
	}
	const int samples = 16;
	std::vector<float> &pts = path_points_; // per sample: x, y, tangent x, tangent y
	std::vector<float> &cum = path_lengths_;
	pts.clear();
	cum.clear();
	for (int i = 0; i < curves; i++) {
		float c[8];
		for (int k = 0; k < 4; k++) {
			int v = (3 * i + 1 + k) % n;
			c[k * 2] = path_world_[v * 2];
			c[k * 2 + 1] = path_world_[v * 2 + 1];
		}
		for (int k = (i == 0 ? 0 : 1); k <= samples; k++) {
			float x, y, tx, ty;
			bezier_at(c, float(k) / samples, x, y, tx, ty);
			if (!cum.empty()) {
				size_t last = pts.size() - 4;
				cum.push_back(cum.back() + std::hypot(x - pts[last], y - pts[last + 1]));
			} else {
				cum.push_back(0);
			}
			pts.insert(pts.end(), { x, y, tx, ty });
		}
	}
	float total = cum.back();
	if (total < 1e-6f) {
		return;
	}
	// Point and tangent at a distance along the path; an open path extends straight past its ends.
	auto at = [&](float dist, float &x, float &y, float &tx, float &ty) {
		if (att->closed) {
			dist = std::fmod(dist, total);
			if (dist < 0) {
				dist += total;
			}
		}
		size_t last = cum.size() - 1;
		if (dist <= 0 || dist >= total) {
			size_t i = dist <= 0 ? 0 : last;
			float over = dist <= 0 ? dist : dist - total;
			tx = pts[i * 4 + 2];
			ty = pts[i * 4 + 3];
			x = pts[i * 4] + tx * over;
			y = pts[i * 4 + 1] + ty * over;
			return;
		}
		size_t i = size_t(std::upper_bound(cum.begin(), cum.end(), dist) - cum.begin());
		i = std::max<size_t>(1, std::min(i, last));
		float seg = cum[i] - cum[i - 1];
		float s = seg > 1e-9f ? (dist - cum[i - 1]) / seg : 0;
		const float *a = &pts[(i - 1) * 4], *b = &pts[i * 4];
		x = a[0] + (b[0] - a[0]) * s;
		y = a[1] + (b[1] - a[1]) * s;
		tx = a[2] + (b[2] - a[2]) * s;
		ty = a[3] + (b[3] - a[3]) * s;
		float len = std::hypot(tx, ty);
		if (len > 1e-9f) {
			tx /= len;
			ty /= len;
		}
	};
	size_t count = d.bones.size();
	float bone_sum = 0;
	for (int bi : d.bones) {
		bone_sum += bones[bi].data->length * bones[bi].world_scale_x();
	}
	auto gap = [&](size_t i) {
		const Bone &b = bones[d.bones[i]];
		float len = b.data->length * b.world_scale_x();
		switch (d.spacing_mode) {
			case 0: return len + p.spacing; // length
			case 1: return p.spacing; // fixed
			case 2: return p.spacing * total; // percent
			default: return bone_sum > 1e-6f ? len / bone_sum * p.spacing * total : 0.0f; // proportional
		}
	};
	float dist = d.position_mode == 1 ? p.position * total : p.position;
	for (size_t i = 0; i < count; i++) {
		Bone &b = bones[d.bones[i]];
		float x, y, tx, ty;
		at(dist, x, y, tx, ty);
		float step = gap(i);
		float angle = std::atan2(ty, tx);
		float stretch = 1;
		if (d.rotate_mode != 0) { // chain: aim at where the next bone starts
			float nx, ny, ntx, nty;
			at(dist + step, nx, ny, ntx, nty);
			float dx = nx - x, dy = ny - y;
			if (std::hypot(dx, dy) > 1e-6f) {
				angle = std::atan2(dy, dx);
			}
			float len = b.data->length * b.world_scale_x();
			if (d.rotate_mode == 2 && len > 1e-6f) {
				stretch = std::hypot(dx, dy) / len;
			}
		}
		angle += d.offset_rotation * DEG;
		float a = b.a, bb = b.b, c = b.c, dd = b.d;
		float current = std::atan2(c, a);
		float r = wrap180((angle - current) / DEG) * DEG * p.mix_rotate;
		rotate_basis(a, bb, c, dd, r);
		if (stretch != 1) {
			float s = 1 + (stretch - 1) * p.mix_rotate;
			a *= s;
			c *= s;
		}
		float wx = b.world_x + (x - b.world_x) * p.mix_x;
		float wy = b.world_y + (y - b.world_y) * p.mix_y;
		set_world(b, a, bb, c, dd, wx, wy);
		dist += step;
	}
}

void Skeleton::apply_physics(PhysicsConstraint &p, float dt) {
	const PhysicsData &d = *p.data;
	Bone &b = bones[d.bone];
	float len = b.data->length > 1e-3f ? b.data->length : 1.0f;
	float target_x = b.world_x + b.a * len, target_y = b.world_y + b.c * len;
	if (!p.started) {
		p.started = true;
		p.remaining = 0;
		p.origin_x = b.world_x;
		p.origin_y = b.world_y;
		p.tip_x = target_x;
		p.tip_y = target_y;
		p.tip_vx = p.tip_vy = p.off_x = p.off_y = p.off_vx = p.off_vy = 0;
	}
	float fps = d.fps > 0 ? d.fps : 60;
	float step = 1 / fps;
	p.remaining = std::min(p.remaining + std::max(dt, 0.0f), 0.25f);
	// The bone's movement since the last step, capped by the limit (units per second); a jump larger
	// than that (a teleport) is mostly absorbed instead of flinging the spring.
	float mx = b.world_x - p.origin_x, my = b.world_y - p.origin_y;
	p.origin_x = b.world_x;
	p.origin_y = b.world_y;
	float move = std::hypot(mx, my), cap = d.limit * std::max(dt, step);
	if (move > cap && move > 0) {
		mx *= cap / move;
		my *= cap / move;
	}
	// Inertia is the share of the movement the simulated points do not follow at once.
	float follow = 1 - std::min(std::max(p.inertia, 0.0f), 1.0f);
	// The tip keeps its world place except for the followed share; the origin offset lags the rest.
	p.tip_x += mx * follow;
	p.tip_y += my * follow;
	p.off_x -= mx * (1 - follow);
	p.off_y -= my * (1 - follow);
	float k = p.strength * p.mass_inverse;
	float keep = std::min(std::max(p.damping, 0.0f), 1.0f);
	while (p.remaining >= step) {
		p.remaining -= step;
		float ax = (target_x - p.tip_x) * k + p.wind * p.mass_inverse;
		float ay = (target_y - p.tip_y) * k - p.gravity * p.mass_inverse;
		p.tip_vx = (p.tip_vx + ax * step) * keep;
		p.tip_vy = (p.tip_vy + ay * step) * keep;
		p.tip_x += p.tip_vx * step;
		p.tip_y += p.tip_vy * step;
		float ox = -p.off_x * k + p.wind * p.mass_inverse;
		float oy = -p.off_y * k - p.gravity * p.mass_inverse;
		p.off_vx = (p.off_vx + ox * step) * keep;
		p.off_vy = (p.off_vy + oy * step) * keep;
		p.off_x += p.off_vx * step;
		p.off_y += p.off_vy * step;
	}
	// Keep the simulated tip within a few bone lengths so a bad frame cannot leave it far away.
	float reach = std::hypot(target_x - b.world_x, target_y - b.world_y);
	float far = std::max(reach, 1.0f) * 4;
	float ex = p.tip_x - target_x, ey = p.tip_y - target_y, e = std::hypot(ex, ey);
	if (e > far) {
		p.tip_x = target_x + ex * far / e;
		p.tip_y = target_y + ey * far / e;
	}
	float mix = p.mix;
	if (mix <= 0) {
		return;
	}
	float a = b.a, bb = b.b, c = b.c, dd = b.d;
	float sx = p.tip_x - b.world_x, sy = p.tip_y - b.world_y;
	float tx = target_x - b.world_x, ty = target_y - b.world_y;
	if (std::hypot(sx, sy) > 1e-6f && reach > 1e-6f) {
		float r = wrap180((std::atan2(sy, sx) - std::atan2(ty, tx)) / DEG) * DEG;
		if (d.rotate != 0) {
			rotate_basis(a, bb, c, dd, r * d.rotate * mix);
		}
		if (d.shear_x != 0) { // turns the x axis only
			float rs = r * d.shear_x * mix, cs = std::cos(rs), sn = std::sin(rs);
			float na = cs * a - sn * c, nc = sn * a + cs * c;
			a = na;
			c = nc;
		}
		if (d.scale_x != 0) {
			float s = 1 + (std::hypot(sx, sy) / reach - 1) * d.scale_x * mix;
			s = std::max(s, 0.0f);
			a *= s;
			c *= s;
		}
	}
	float wx = b.world_x + p.off_x * d.x * mix;
	float wy = b.world_y + p.off_y * d.y * mix;
	set_world(b, a, bb, c, dd, wx, wy);
}

void Skeleton::update_after(const std::vector<int> &constrained) {
	if (constrained.empty()) {
		return;
	}
	// Constrained bones changed: recompute them and everything after the first one (children follow parents).
	int first = *std::min_element(constrained.begin(), constrained.end());
	for (size_t i = first; i < bones.size(); i++) {
		update_bone(bones[i]);
	}
}

void Skeleton::update_world_transform() {
	for (auto &b : bones) {
		update_bone(b);
	}
	if (constraint_order.empty()) {
		return;
	}
	// Constraints solve by changing local values. Keep the animated pose and put it back afterwards,
	// so the next frame's keys mix from the pose and not from the constraint output.
	pose_ = bones;
	float dt = physics_time;
	physics_time = 0;
	for (const auto &c : constraint_order) {
		switch (c.first) {
			case TRANSFORM:
				apply_transform(transforms[c.second]);
				update_after(transforms[c.second].data->bones);
				break;
			case IK:
				apply_ik(ik[c.second]);
				update_after(ik[c.second].data->bones);
				break;
			case PATH:
				apply_path(paths[c.second]);
				update_after(paths[c.second].data->bones);
				break;
			default: {
				PhysicsConstraint &p = physics[c.second];
				apply_physics(p, dt);
				update_after({ p.data->bone });
				break;
			}
		}
	}
	for (size_t i = 0; i < bones.size(); i++) {
		Bone &b = bones[i];
		const Bone &p = pose_[i];
		b.x = p.x;
		b.y = p.y;
		b.rotation = p.rotation;
		b.scale_x = p.scale_x;
		b.scale_y = p.scale_y;
		b.shear_x = p.shear_x;
		b.shear_y = p.shear_y;
	}
}

int Skeleton::world_vertices(int slot_index, std::vector<float> &out) const {
	const Slot &slot = slots[slot_index];
	const Attachment *a = slot.drawn();
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
					s.sequence_index = -1;
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
			case TimelineType::Transform: {
				TransformConstraint &k = sk.transforms[tl.target];
				const TransformData &d = *k.data;
				mix_value(k.mix_rotate, d.mix_rotate, cv.evaluate(time, 0), alpha, blend);
				mix_value(k.mix_x, d.mix_x, cv.evaluate(time, 1), alpha, blend);
				mix_value(k.mix_y, d.mix_y, cv.evaluate(time, 2), alpha, blend);
				mix_value(k.mix_scale_x, d.mix_scale_x, cv.evaluate(time, 3), alpha, blend);
				mix_value(k.mix_scale_y, d.mix_scale_y, cv.evaluate(time, 4), alpha, blend);
				mix_value(k.mix_shear_y, d.mix_shear_y, cv.evaluate(time, 5), alpha, blend);
				break;
			}
			case TimelineType::PathPosition: {
				PathConstraint &k = sk.paths[tl.target];
				mix_value(k.position, k.data->position, cv.evaluate(time, 0), alpha, blend);
				break;
			}
			case TimelineType::PathSpacing: {
				PathConstraint &k = sk.paths[tl.target];
				mix_value(k.spacing, k.data->spacing, cv.evaluate(time, 0), alpha, blend);
				break;
			}
			case TimelineType::PathMix: {
				PathConstraint &k = sk.paths[tl.target];
				mix_value(k.mix_rotate, k.data->mix_rotate, cv.evaluate(time, 0), alpha, blend);
				mix_value(k.mix_x, k.data->mix_x, cv.evaluate(time, 1), alpha, blend);
				mix_value(k.mix_y, k.data->mix_y, cv.evaluate(time, 2), alpha, blend);
				break;
			}
			case TimelineType::Sequence: {
				Slot &s = sk.slots[tl.target];
				if (!s.attachment || s.attachment->timeline_attachment != tl.attachment || !s.attachment->has_sequence) {
					break;
				}
				int i = cv.search(time);
				int key = tl.sequence_keys[i];
				int mode = key & 15, index = key >> 4;
				int count = s.attachment->sequence.count;
				if (count <= 0) {
					break;
				}
				float delay = tl.sequence_delays[i];
				if (mode != int(SequenceMode::Hold) && delay > 0) {
					index += int((time - cv.time(i)) / delay + 1e-4f);
				}
				int bounce = 2 * count - 2;
				switch (SequenceMode(mode)) {
					case SequenceMode::Hold:
						break;
					case SequenceMode::Once:
						index = std::min(index, count - 1);
						break;
					case SequenceMode::Loop:
						index %= count;
						break;
					case SequenceMode::PingPong:
						index = bounce ? index % bounce : 0;
						if (index >= count) index = bounce - index;
						break;
					case SequenceMode::OnceReverse:
						index = std::max(count - 1 - index, 0);
						break;
					case SequenceMode::LoopReverse:
						index = count - 1 - index % count;
						break;
					case SequenceMode::PingPongReverse:
						index = bounce ? (index + count - 1) % bounce : 0;
						if (index >= count) index = bounce - index;
						break;
				}
				s.sequence_index = std::max(0, std::min(index, count - 1));
				break;
			}
			case TimelineType::Inherit: {
				if (discrete) {
					sk.bones[tl.target].inherit = Inherit(tl.flags[cv.search(time)]);
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
		pending.push_back({ StateEvent::Interrupt, track, prev->animation, {}, prev });
		if (e->mix_duration <= 0) {
			e->from.reset();
			pending.push_back({ StateEvent::End, track, prev->animation, {}, prev });
		}
	}
	tracks[track] = e;
	pending.push_back({ StateEvent::Start, track, animation, {}, e });
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
		for (auto f = tracks[track]->from; f; f = f->from) {
			pending.push_back({ StateEvent::End, track, f->animation, {}, f });
		}
		pending.push_back({ StateEvent::End, track, tracks[track]->animation, {}, tracks[track] });
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
			pending.push_back({ StateEvent::Complete, int(t), e->animation, {}, e });
		} else if (duration <= 0 && !e->complete_fired && e->animation) {
			e->complete_fired = true;
			pending.push_back({ StateEvent::Complete, int(t), e->animation, {}, e });
		}
		if (e->from) {
			e->mix_time += dt;
			for (auto f = e->from; f; f = f->from) {
				f->last_time = f->track_time;
				f->track_time += dt * f->time_scale;
			}
			if (e->mix_time >= e->mix_duration) {
				for (auto f = e->from; f; f = f->from) {
					pending.push_back({ StateEvent::End, int(t), f->animation, {}, f });
				}
				e->from.reset();
			}
		}
		if (e->next && e->track_time - e->delay >= e->next->delay) {
			auto n = e->next;
			float over = e->track_time - n->delay;
			n->delay = 0;
			n->from = n->mix_duration > 0 ? e : nullptr;
			pending.push_back({ StateEvent::Interrupt, int(t), e->animation, {}, e });
			if (!n->from) {
				pending.push_back({ StateEvent::End, int(t), e->animation, {}, e });
			}
			e->next.reset();
			n->track_time = std::max(0.0f, over);
			tracks[t] = n;
			pending.push_back({ StateEvent::Start, int(t), n->animation, {}, n });
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

void apply_entry(const std::shared_ptr<TrackEntry> &entry, Skeleton &sk, float alpha, MixBlend blend, bool discrete, int track,
		std::vector<StateEvent> &pending) {
	const TrackEntry &e = *entry;
	if (e.from) {
		float m = e.mix_duration > 0 ? std::min(1.0f, e.mix_time / e.mix_duration) : 1;
		apply_entry(e.from, sk, alpha * (1 - m), blend, false, track, pending);
		alpha *= m;
		blend = MixBlend::Add;
	}
	if (!e.animation || alpha <= 0) {
		return;
	}
	std::vector<Event> fired;
	apply_animation(*e.animation, sk, wrapped(e, e.last_time), wrapped(e, e.track_time), e.loop, &fired, alpha * e.alpha, blend, discrete);
	for (const Event &ev : fired) {
		pending.push_back({ StateEvent::Fire, track, e.animation, ev, entry });
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
		apply_entry(e, sk, 1, MixBlend::Replace, true, int(t), pending);
		e->last_time = e->track_time;
	}
}

} // namespace skel
