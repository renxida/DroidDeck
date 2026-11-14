// Plays every animation of real skeletons through the core and checks the pose stays finite.
// Usage: real_check skeleton.spskel|.skel|.json [atlas.spatlas|.atlas] ...   (pairs; atlas may be "-")
// Reads only the files passed in; nothing is bundled.
#include "../atlas.h"
#include "../json.h"
#include "../skel.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace skel;

static std::vector<uint8_t> slurp(const char *path) {
	std::vector<uint8_t> d;
	FILE *f = fopen(path, "rb");
	if (!f) {
		return d;
	}
	uint8_t b[65536];
	size_t n;
	while ((n = fread(b, 1, sizeof(b), f)) > 0) {
		d.insert(d.end(), b, b + n);
	}
	fclose(f);
	return d;
}

static bool ends_with(const std::string &s, const char *e) {
	const size_t n = strlen(e);
	return s.size() >= n && s.compare(s.size() - n, n, e) == 0;
}

// Atlas text from a .spatlas JSON wrapper ({"atlas_data": "...", ...}) or a plain .atlas file.
static std::string atlas_text(const char *path) {
	std::vector<uint8_t> d = slurp(path);
	std::string s(d.begin(), d.end());
	if (!ends_with(path, ".spatlas")) {
		return s;
	}
	JsonValue v;
	std::string error;
	if (!parse_json(s.data(), s.size(), v, error)) {
		return std::string();
	}
	return v.str("atlas_data", "");
}

int main(int argc, char **argv) {
	int bad_total = 0;
	for (int i = 1; i < argc; i += 2) {
		const std::string path = argv[i];
		std::vector<uint8_t> d = slurp(argv[i]);
		std::string error;
		std::unique_ptr<SkeletonData> data = ends_with(path, ".json") ?
				load_json(reinterpret_cast<const char *>(d.data()), d.size(), 1, error) :
				load_binary(d.data(), d.size(), 1, error);
		if (!data) {
			printf("%s: LOAD FAILED %s\n", path.c_str(), error.c_str());
			bad_total++;
			continue;
		}
		std::vector<std::string> missing;
		if (i + 1 < argc && strcmp(argv[i + 1], "-") != 0) {
			const std::string text = atlas_text(argv[i + 1]);
			Atlas atlas;
			if (!parse_atlas(text.data(), text.size(), atlas, error)) {
				printf("%s: ATLAS FAILED %s\n", argv[i + 1], error.c_str());
				bad_total++;
			} else {
				missing = bind_atlas(*data, atlas);
			}
		}
		AnimationStateData sd;
		sd.skeleton = data.get();
		sd.default_mix = 0.2f;
		int bad = 0, events = 0, steps = 0, verts = 0;
		for (const Animation &a : data->animations) {
			Skeleton sk(data.get());
			if (!data->skins.empty() && !sk.skin) {
				sk.set_skin(data->skins.front()->name);
			}
			AnimationState st(&sd);
			st.set_animation(0, a.name, true);
			const int n = int(std::ceil(std::max(a.duration, 0.1f) * 30)) + 15;
			std::vector<float> out;
			for (int s = 0; s < n; s++) {
				st.update(1.0f / 30);
				st.apply(sk);
				sk.update(1.0f / 30);
				sk.update_world_transform();
				events += int(st.pending.size());
				st.pending.clear();
				steps++;
				for (const Bone &b : sk.bones) {
					if (!std::isfinite(b.a + b.b + b.c + b.d + b.world_x + b.world_y)) {
						bad++;
					}
				}
				for (int slot : sk.draw_order) {
					out.clear();
					const int k = sk.world_vertices(slot, out);
					verts += k;
					for (float f : out) {
						if (!std::isfinite(f)) {
							bad++;
							break;
						}
					}
				}
			}
		}
		std::string unsupported;
		for (const std::string &u : data->unsupported) {
			unsupported += u + ";";
		}
		printf("%s: version %s bones %zu slots %zu skins %zu animations %zu steps %d events %d vertices %d "
			   "missing_regions %zu unsupported [%s] bad %d\n",
				path.c_str(), data->version.c_str(), data->bones.size(), data->slots.size(), data->skins.size(),
				data->animations.size(), steps, events, verts, missing.size(), unsupported.c_str(), bad);
		bad_total += bad;
	}
	printf("REAL done bad=%d\n", bad_total);
	return bad_total ? 1 : 0;
}
