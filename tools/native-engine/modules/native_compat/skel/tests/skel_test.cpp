// Host tests for the clean-room skeleton core. Build: see run_tests.sh.
#include "../atlas.h"
#include "../json.h"
#include "../skel.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace skel;

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)
#define NEAR(a, b) do { double _a = (a), _b = (b); if (std::fabs(_a - _b) > 1e-3) { std::printf("FAIL %s:%d %s = %g, want %g\n", __FILE__, __LINE__, #a, _a, _b); failures++; } } while (0)

static std::unique_ptr<SkeletonData> load(const std::string &json, float scale = 1) {
	std::string err;
	auto sd = load_json(json.data(), json.size(), scale, err);
	if (!sd) {
		std::printf("load error: %s\n", err.c_str());
		failures++;
	}
	return sd;
}

static const char *RIG4 = R"({
 "skeleton": {"spine": "4.2.43", "hash": "h"},
 "bones": [
  {"name": "root", "x": 10},
  {"name": "arm", "parent": "root", "rotation": 90, "length": 20},
  {"name": "hand", "parent": "arm", "x": 20},
  {"name": "flat", "parent": "arm", "inherit": "onlyTranslation", "x": 5},
  {"name": "target", "parent": "root", "x": 0, "y": 30}
 ],
 "slots": [
  {"name": "body", "bone": "root", "attachment": "body"},
  {"name": "skin", "bone": "root", "attachment": "skin", "color": "ff000080"},
  {"name": "fx", "bone": "hand", "blend": "additive"}
 ],
 "ik": [{"name": "aim", "bones": ["arm"], "target": "target", "mix": 0}],
 "skins": [{"name": "default", "attachments": {
   "body": {"body": {"x": 1, "y": 2, "rotation": 90, "width": 4, "height": 2}},
   "skin": {"skin": {"type": "mesh", "uvs": [0,0, 1,0],
      "vertices": [1, 0, 0, 0, 1,  2, 0, 2, 0, 0.5, 1, 2, 0, 0.5],
      "triangles": [0, 1, 0], "hull": 2}},
   "fx": {"spark": {"width": 2, "height": 2}, "flash": {"width": 4, "height": 4}}
 }}],
 "events": {"hit": {"int": 3}},
 "animations": {
  "spin": {"bones": {"root": {"rotate": [{"time": 0, "value": 0}, {"time": 1, "value": 90}]}}},
  "ease": {"bones": {"root": {"translatex": [{"time": 0, "value": 0, "curve": [0.25, 0, 0.75, 100]}, {"time": 1, "value": 100}]}}},
  "left": {"bones": {"root": {"translate": [{"x": 10}]}}},
  "right": {"bones": {"root": {"translate": [{"x": 20}]}}},
  "swap": {"slots": {"fx": {"attachment": [{"name": "spark"}, {"time": 0.5, "name": "flash"}, {"time": 0.75, "name": null}]},
                     "skin": {"rgba": [{"color": "00ff00ff"}]}},
           "events": [{"time": 0.25, "name": "hit"}, {"time": 0.9, "name": "hit", "int": 7}],
           "drawOrder": [{"time": 0.5, "offsets": [{"slot": "fx", "offset": -2}]}],
           "attachments": {"default": {"skin": {"skin": {"deform": [{"time": 0, "offset": 4, "vertices": [0, 4]}]}}}}},
  "aim": {"ik": {"aim": [{"mix": 1}]}}
 }
})";

static void test_world_transforms() {
	auto sd = load(RIG4);
	if (!sd) return;
	Skeleton sk(sd.get());
	sk.update_world_transform();
	const Bone &hand = sk.bones[2];
	NEAR(hand.world_x, 10);
	NEAR(hand.world_y, 20);
	NEAR(hand.world_rotation_x(), 90);
	const Bone &flat = sk.bones[3];
	NEAR(flat.world_x, 10);
	NEAR(flat.world_y, 5);
	NEAR(flat.world_rotation_x(), 0); // onlyTranslation ignores the arm's 90 degrees
	// Skeleton flip mirrors positions.
	sk.scale_x = -1;
	sk.update_world_transform();
	NEAR(sk.bones[2].world_x, -10);
}

static void test_region_and_weighted_mesh() {
	auto sd = load(RIG4);
	if (!sd) return;
	Skeleton sk(sd.get());
	sk.update_world_transform();
	std::vector<float> v;
	CHECK(sk.world_vertices(0, v) == 4);
	// width 4 height 2 rotated 90 about (1,2), root at (10,0): bottom-left corner (-2,-1) -> (1,-2)+(1,2)
	NEAR(v[0], 10 + 1 + 1);
	NEAR(v[1], 0 + 2 - 2);
	CHECK(sk.world_vertices(1, v) == 2);
	NEAR(v[0], 10); // vertex 0: one influence (root) at (0,0)
	NEAR(v[1], 0);
	// vertex 1: half root (10+2, 0) + half arm (arm at (10,0) rotated 90: local (2,0) -> (10, 2)) = (11, 1)
	NEAR(v[2], 11);
	NEAR(v[3], 1);
}

static void test_linear_and_bezier() {
	auto sd = load(RIG4);
	if (!sd) return;
	Skeleton sk(sd.get());
	apply_animation(*sd->find_animation("spin"), sk, -1, 0.5f, false, nullptr, 1, MixBlend::Replace);
	NEAR(sk.bones[0].rotation, 45);
	sk.setup_pose();
	// Symmetric ease-in-out bezier passes through the midpoint.
	apply_animation(*sd->find_animation("ease"), sk, -1, 0.5f, false, nullptr, 1, MixBlend::Replace);
	NEAR(sk.bones[0].x, 10 + 50);
	sk.setup_pose();
	apply_animation(*sd->find_animation("ease"), sk, -1, 0.25f, false, nullptr, 1, MixBlend::Replace);
	CHECK(sk.bones[0].x < 10 + 25); // eased in
	CHECK(sd->find_animation("ease")->duration == 1);
}

static void test_slots_events_draw_order_deform() {
	auto sd = load(RIG4);
	if (!sd) return;
	Skeleton sk(sd.get());
	const Animation &swap = *sd->find_animation("swap");
	std::vector<Event> ev;
	apply_animation(swap, sk, -1, 0.3f, true, &ev, 1, MixBlend::Replace);
	CHECK(sk.slots[2].attachment && sk.slots[2].attachment->name == "spark");
	CHECK(sk.slots[2].data->blend == BlendMode::Additive);
	NEAR(sk.slots[1].color.g, 1);
	NEAR(sk.slots[1].color.r, 0);
	CHECK(ev.size() == 1 && ev[0].int_value == 3);
	CHECK(sk.draw_order[0] == 0 && sk.draw_order[2] == 2);
	CHECK(sk.slots[1].deform.size() == 6);
	NEAR(sk.slots[1].deform[5], 4);
	ev.clear();
	apply_animation(swap, sk, 0.3f, 0.6f, true, &ev, 1, MixBlend::Replace);
	CHECK(sk.slots[2].attachment && sk.slots[2].attachment->name == "flash");
	CHECK(sk.draw_order[0] == 2 && sk.draw_order[1] == 0 && sk.draw_order[2] == 1);
	CHECK(ev.empty());
	apply_animation(swap, sk, 0.6f, 0.8f, true, &ev, 1, MixBlend::Replace);
	CHECK(sk.slots[2].attachment == nullptr);
	// Looping past the end fires the late event, then the early one.
	ev.clear();
	apply_animation(swap, sk, 0.8f, 0.3f, true, &ev, 1, MixBlend::Replace);
	CHECK(ev.size() == 2 && ev[0].int_value == 7 && ev[1].int_value == 3);
	// Deformed weighted vertex 1, influence 2 (arm) moved +4 in arm space (arm x axis is world up).
	sk.update_world_transform();
	std::vector<float> v;
	sk.world_vertices(1, v);
	NEAR(v[2], 11 - 2);
	NEAR(v[3], 1);
}

static void test_crossfade() {
	auto sd = load(RIG4);
	if (!sd) return;
	AnimationStateData asd;
	asd.skeleton = sd.get();
	asd.set_mix("left", "right", 1);
	AnimationState st(&asd);
	Skeleton sk(sd.get());
	st.set_animation(0, "left", true);
	st.update(0.1f);
	st.apply(sk);
	NEAR(sk.bones[0].x, 20);
	st.set_animation(0, "right", true);
	st.update(0.5f);
	st.apply(sk);
	NEAR(sk.bones[0].x, 25); // halfway from setup+10 to setup+20
	st.update(0.6f);
	st.apply(sk);
	NEAR(sk.bones[0].x, 30);
	CHECK(!st.current(0)->from);
	// Queued animation starts after the current one.
	st.add_animation(0, "spin", false, 0.2f);
	st.update(0.25f);
	CHECK(st.current(0)->animation == sd->find_animation("spin"));
}

static void test_ik() {
	auto sd = load(RIG4);
	if (!sd) return;
	Skeleton sk(sd.get());
	sk.bones[4].x = 20;
	sk.bones[4].y = 0; // target to the right of root
	apply_animation(*sd->find_animation("aim"), sk, -1, 0, false, nullptr, 1, MixBlend::Replace);
	sk.update_world_transform();
	NEAR(sk.bones[1].world_rotation_x(), 0); // arm now points along +x at the target
	NEAR(sk.bones[2].world_x, 30);
}

static void test_spine3_layout() {
	const char *rig3 = R"({
	 "skeleton": {"spine": "3.8.99"},
	 "bones": [{"name": "root"}, {"name": "b", "parent": "root", "transform": "noScale", "x": 1}],
	 "slots": [{"name": "s", "bone": "b", "attachment": "a"}],
	 "skins": {"default": {"s": {"a": {"width": 2, "height": 2}}}, "alt": {"s": {"a": {"width": 8, "height": 8}}}},
	 "animations": {"go": {"bones": {"b": {"rotate": [{"time": 0, "angle": 0, "curve": [0.5, 0, 0.5, 1]}, {"time": 2, "angle": 180}]}},
	                "deform": {}}}
	})";
	auto sd = load(rig3, 2);
	if (!sd) return;
	CHECK(sd->major_version() == 3);
	CHECK(sd->bones[1].inherit == Inherit::NoScale);
	NEAR(sd->bones[1].x, 2); // scaled on load
	Skeleton sk(sd.get());
	apply_animation(*sd->find_animation("go"), sk, -1, 1, false, nullptr, 1, MixBlend::Replace);
	NEAR(sk.bones[1].rotation, 90); // 3.x normalized curve, symmetric
	std::vector<float> v;
	sk.setup_pose();
	sk.update_world_transform();
	sk.world_vertices(0, v);
	NEAR(v[4] - v[0], 4); // default skin region is 2 wide at scale 2
	CHECK(sk.set_skin("alt"));
	sk.update_world_transform();
	sk.world_vertices(0, v);
	NEAR(v[4] - v[0], 16);
}

static void test_json_reader() {
	JsonValue v;
	std::string err;
	const char *t = R"({"a": [1, -2.5e1, true, null, "xé\n"], "b": {}})";
	CHECK(parse_json(t, std::strlen(t), v, err));
	CHECK(v.get("a")->array.size() == 5);
	NEAR(v.get("a")->array[1].number, -25);
	CHECK(v.get("a")->array[4].string == "x\xc3\xa9\n");
	CHECK(!parse_json("{\"a\": }", 7, v, err));
}


static void test_atlas() {
	const char *a4 = "\nmonsters.png\nsize: 100,50\nfilter: Linear,Linear\npma: true\nbody\nbounds: 10,20,4,2\nfx_spark\nbounds: 0,0,8,6\noffsets: 1,2,10,10\nrotate: 90\n\nsecond.png\nsize: 64,64\nspark\nbounds: 0,0,2,2\nflash\nbounds: 2,0,4,4\nskin\nbounds: 0,32,32,32\n";
	Atlas at;
	std::string err;
	CHECK(parse_atlas(a4, std::strlen(a4), at, err));
	CHECK(at.pages.size() == 2 && at.pages[0].pma && at.pages[0].width == 100);
	CHECK(at.regions.size() == 5);
	const AtlasRegion *body = at.find("body");
	CHECK(body && body->page == 0);
	NEAR(body->u, 0.1);
	NEAR(body->v2, 22.0 / 50);
	const AtlasRegion *fx = at.find("fx_spark");
	CHECK(fx && fx->degrees == 90 && fx->original_width == 10);
	NEAR(fx->u2, 6.0 / 100); // footprint is height wide when turned
	const char *a3 = "page.png\nsize: 16,16\nformat: RGBA8888\nfilter: Nearest,Nearest\nrepeat: none\nr\n  rotate: false\n  xy: 4, 4\n  size: 8, 4\n  orig: 8, 8\n  offset: 0, 2\n  index: -1\n";
	CHECK(parse_atlas(a3, std::strlen(a3), at, err));
	CHECK(at.pages.size() == 1 && !at.pages[0].linear);
	const AtlasRegion *r = at.find("r");
	CHECK(r && r->x == 4 && r->height == 4 && r->original_height == 8 && r->offset_y == 2);

	// Binding: a trimmed region shrinks its quad to the packed pixels; uvs land on the page.
	auto sd = load(RIG4);
	if (!sd) return;
	CHECK(parse_atlas(a4, std::strlen(a4), at, err));
	auto missing = bind_atlas(*sd, at);
	CHECK(missing.empty());
	const Attachment *bodyA = sd->default_skin->find(0, "body");
	CHECK(bodyA->page == 0);
	NEAR(bodyA->region_uv[0], 0.1); // bottom-left u
	NEAR(bodyA->region_uv[1], 22.0 / 50); // bottom-left v (bottom of the region in the page)
	NEAR(bodyA->region_uv[5], 20.0 / 50); // top-right v
	const Attachment *mesh = sd->default_skin->find(1, "skin");
	CHECK(mesh->page == 1 && mesh->page_uvs.size() == 4);
	NEAR(mesh->page_uvs[0], 0);
	NEAR(mesh->page_uvs[1], 0.5); // mesh uv v=0 is the image top: the top of the region in the page
}

// ------------------------------------------------------------------ binary (synthetic data only)

struct BinWriter {
	std::vector<uint8_t> b;
	void u8(int v) { b.push_back(uint8_t(v)); }
	void u32(uint32_t v) {
		for (int i = 3; i >= 0; i--) u8(int((v >> (i * 8)) & 0xff));
	}
	void f(float v) {
		uint32_t u;
		std::memcpy(&u, &v, 4);
		u32(u);
	}
	void v(uint32_t x) {
		do {
			uint8_t c = x & 0x7f;
			x >>= 7;
			u8(c | (x ? 0x80 : 0));
		} while (x);
	}
	void s(const char *t) {
		if (!t) { v(0); return; }
		v(uint32_t(std::strlen(t)) + 1);
		for (const char *c = t; *c; c++) u8(*c);
	}
	void bone(const char *name, int parent, float rot, float x, float y, float len) {
		s(name);
		if (parent >= 0) v(uint32_t(parent));
		for (float x8 : { rot, x, y, 1.0f, 1.0f, 0.0f, 0.0f, len }) f(x8);
		u8(0); // inherit normal
		u8(0); // skin required
	}
};

static std::vector<uint8_t> synthetic_binary() {
	BinWriter w;
	for (int i = 0; i < 8; i++) w.u8(i);
	w.s("4.2.43");
	for (float x : { 0.0f, 0.0f, 100.0f, 100.0f, 100.0f }) w.f(x); // bounds, reference scale
	w.u8(0); // no editor-only data
	const char *strings[] = { "body", "skin", "mesh", "linked" };
	w.v(4);
	for (const char *t : strings) w.s(t);
	// Bones: an arm of two 10-unit bones bent counter-clockwise, its IK target at the tip, and a follower.
	w.v(5);
	w.bone("root", -1, 0, 0, 0, 0);
	w.bone("upper", 0, 90, 0, 0, 10);
	w.bone("lower", 1, 90, 10, 0, 10);
	w.bone("target", 0, 0, -10, 10, 0);
	w.bone("follow", 0, 0, 0, -5, 0);
	// Slots.
	w.v(2);
	w.s("a"); w.v(0); w.u32(0xffffffff); w.u32(0xffffffff); w.v(1); w.v(0);
	w.s("b"); w.v(2); w.u32(0xff000080); w.u32(0x10203040); w.v(3); w.v(1);
	// IK: bend positive, mix 1.
	w.v(1);
	w.s("ik"); w.v(0); w.v(2); w.v(1); w.v(2); w.v(3); w.u8(2 | 32);
	// Transform (world): offsets rotation 10, x 1, y 2; mixes rotate 1, x 1, y 0.5.
	w.v(1);
	w.s("tc"); w.v(1); w.v(1); w.v(4); w.v(3);
	w.u8(8 | 16 | 32); w.f(10); w.f(1); w.f(2);
	w.u8(2 | 4 | 8); w.f(1); w.f(1); w.f(0.5f);
	w.v(0); // path constraints
	w.v(0); // physics constraints
	// Default skin.
	w.v(2);
	w.v(0); w.v(1); w.v(1);
	w.u8(0 | 32 | 128); w.u32(0x00ff00ff); w.f(90); // region with color and rotation
	for (float x : { 1.0f, 2.0f, 1.0f, 1.0f, 20.0f, 10.0f }) w.f(x);
	w.v(1); w.v(2);
	w.v(3); w.u8(2); w.v(3); w.v(3); // mesh: hull 3, 3 vertices
	for (float x : { 0.0f, 0.0f, 10.0f, 0.0f, 0.0f, 10.0f }) w.f(x);
	for (float x : { 0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f }) w.f(x);
	w.v(0); w.v(1); w.v(2);
	w.v(4); w.u8(3 | 128); w.v(0); w.v(3); // linked mesh sharing deform keys with "mesh"
	w.v(0); // named skins
	// Events.
	w.v(1);
	w.s("hit"); w.v(5); w.f(1.5f); w.s("str"); w.s(nullptr);
	// One animation.
	w.v(1);
	w.s("anim"); w.v(0);
	w.v(1); w.v(1); w.v(2); // slot 1, two timelines
	w.u8(0); w.v(2); w.f(0); w.v(4); w.f(0.75f); w.v(3); // attachment: linked, then mesh
	w.u8(5); w.v(2); w.v(0); w.f(0); w.u8(255); w.f(1); w.u8(0); w.u8(0); // alpha 1 -> 0, linear
	w.v(1); w.v(0); w.v(2); // bone 0, two timelines
	w.u8(0); w.v(2); w.v(1); w.f(0); w.f(0); w.f(1); w.f(90); w.u8(2);
	for (float x : { 0.25f, 0.0f, 0.75f, 90.0f }) w.f(x); // rotate 0 -> 90, eased symmetric bezier
	w.u8(1); w.v(2); w.v(0); w.f(0); w.f(0); w.f(0); w.f(1); w.f(10); w.f(20); w.u8(1); // translate, stepped
	w.v(1); w.v(0); w.v(2); w.v(0); // ik timeline
	w.u8(1 | 8 | 64); w.f(0); // bend positive, mix 1, stepped
	w.u8(4 | 16); w.f(1); w.f(0.25f); // stretch, bend negative, mix 0.25
	w.v(1); w.v(0); w.v(1); w.v(0); w.f(0); for (float x : { 0.5f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f }) w.f(x);
	w.v(0); w.v(0); // path, physics timelines
	w.v(1); w.v(0); w.v(1); w.v(1); w.v(1); w.v(3); w.u8(0); w.v(2); w.v(0); // deform on "mesh"
	w.f(0); w.v(0);
	w.f(1); w.v(4); w.v(2); w.f(3); w.f(4); w.u8(0);
	w.v(1); w.f(0.25f); w.v(1); w.v(1); w.v(0xffffffffu); // draw order: slot 1 moves back one
	w.v(1); w.f(0.75f); w.v(0); w.v(14); w.f(2); w.u8(0); // event: int 7, float 2, data string
	return w.b;
}

static void test_binary() {
	std::vector<uint8_t> bin = synthetic_binary();
	std::string err;
	auto sd = load_binary(bin.data(), bin.size(), 1, err);
	if (!sd) {
		std::printf("binary load error: %s\n", err.c_str());
		failures++;
		return;
	}
	CHECK(sd->version == "4.2.43" && sd->hash == "0001020304050607");
	CHECK(sd->bones.size() == 5 && sd->bones[2].parent == 1);
	CHECK(sd->slots[1].blend == BlendMode::Additive && sd->slots[1].has_dark);
	NEAR(sd->slots[1].color.a, 128.0 / 255);
	CHECK(sd->ik.size() == 1 && sd->ik[0].bend_positive && sd->ik[0].mix == 1);
	CHECK(sd->transforms.size() == 1 && !sd->transforms[0].local);
	CHECK(sd->events[0].int_value == -3 && sd->events[0].string_value == "str");
	const Attachment *region = sd->default_skin->find(0, "body");
	CHECK(region && region->type == AttachmentType::Region);
	NEAR(region->rotation, 90);
	NEAR(region->color.g, 1);
	NEAR(region->color.r, 0);
	const Attachment *mesh = sd->default_skin->find(1, "mesh");
	const Attachment *linked = sd->default_skin->find(1, "linked");
	CHECK(mesh && linked && linked->triangles.size() == 3 && linked->timeline_attachment == mesh);

	// The solved IK chain reproduces the authored bend; the follower takes the target's rotation and position.
	Skeleton sk(sd.get());
	sk.update_world_transform();
	NEAR(std::cos(sk.bones[2].world_rotation_x() * 3.14159265 / 180), -1); // pointing along -x
	NEAR(sk.bones[4].world_rotation_x(), 10);
	NEAR(sk.bones[4].world_x, -9);
	NEAR(sk.bones[4].world_y, 3.5);
	NEAR(sk.bones[4].y, -5); // the animated pose is kept; constraints only change the world transform

	const Animation *anim = sd->find_animation("anim");
	CHECK(anim && anim->duration == 1);
	std::vector<Event> ev;
	sk.setup_pose();
	apply_animation(*anim, sk, -1, 0.5f, false, &ev, 1, MixBlend::Replace);
	NEAR(sk.bones[0].rotation, 45); // symmetric ease: halfway at half time
	NEAR(sk.bones[0].x, 0); // stepped
	NEAR(sk.slots[1].color.a, 0.5);
	CHECK(sk.slots[1].attachment == linked);
	CHECK(sk.draw_order[0] == 1 && sk.draw_order[1] == 0);
	CHECK(sk.ik[0].mix == 1 && sk.ik[0].bend_positive);
	NEAR(sk.transforms[0].mix_rotate, 0.5);
	CHECK(ev.empty());
	apply_animation(*anim, sk, 0.5f, 1, false, &ev, 1, MixBlend::Replace);
	NEAR(sk.bones[0].x, 10);
	NEAR(sk.bones[0].y, 20);
	CHECK(sk.slots[1].attachment == mesh);
	CHECK(ev.size() == 1 && ev[0].int_value == 7 && ev[0].string_value == "str");
	NEAR(sk.ik[0].mix, 0.25);
	CHECK(!sk.ik[0].bend_positive && sk.ik[0].stretch);
	CHECK(sk.slots[1].deform.size() == 6);
	if (sk.slots[1].deform.size() == 6) {
		NEAR(sk.slots[1].deform[2], 3);
		NEAR(sk.slots[1].deform[3], 4);
	}

	// Damaged files fail cleanly.
	CHECK(!load_binary(bin.data(), bin.size() - 1, 1, err));
	bin.push_back(0);
	CHECK(!load_binary(bin.data(), bin.size(), 1, err));
	bin.pop_back();
	bin[9] = '3'; // version "4.3.43"
	CHECK(!load_binary(bin.data(), bin.size(), 1, err));
}

int main() {
	test_atlas();
	test_json_reader();
	test_world_transforms();
	test_region_and_weighted_mesh();
	test_linear_and_bezier();
	test_slots_events_draw_order_deform();
	test_crossfade();
	test_ik();
	test_spine3_layout();
	test_binary();
	if (failures) {
		std::printf("%d failure(s)\n", failures);
		return 1;
	}
	std::printf("skel tests OK\n");
	return 0;
}
