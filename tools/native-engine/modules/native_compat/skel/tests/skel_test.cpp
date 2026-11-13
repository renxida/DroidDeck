// Host tests for the clean-room skeleton core. Build: see run_tests.sh.
#include "../json.h"
#include "../skel.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

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

int main() {
	test_json_reader();
	test_world_transforms();
	test_region_and_weighted_mesh();
	test_linear_and_bezier();
	test_slots_events_draw_order_deform();
	test_crossfade();
	test_ik();
	test_spine3_layout();
	if (failures) {
		std::printf("%d failure(s)\n", failures);
		return 1;
	}
	std::printf("skel tests OK\n");
	return 0;
}
