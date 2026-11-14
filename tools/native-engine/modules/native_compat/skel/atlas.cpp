// Clean-room code for DroidDeck/Kestrel: written from scratch; no third-party runtime source consulted.
#include "atlas.h"
#include "skel.h"

#include <cstdlib>

namespace skel {

namespace {

std::string trim(const std::string &s) {
	size_t a = s.find_first_not_of(" \t\r");
	if (a == std::string::npos) {
		return "";
	}
	size_t b = s.find_last_not_of(" \t\r");
	return s.substr(a, b - a + 1);
}

std::vector<std::string> split_values(const std::string &s) {
	std::vector<std::string> out;
	size_t start = 0;
	while (start <= s.size()) {
		size_t comma = s.find(',', start);
		if (comma == std::string::npos) {
			comma = s.size();
		}
		out.push_back(trim(s.substr(start, comma - start)));
		start = comma + 1;
	}
	return out;
}

int to_int(const std::vector<std::string> &v, size_t i) {
	return i < v.size() ? std::atoi(v[i].c_str()) : 0;
}

} // namespace

const AtlasRegion *Atlas::find(const std::string &name) const {
	for (const auto &r : regions) {
		if (r.name == name) {
			return &r;
		}
	}
	return nullptr;
}

bool parse_atlas(const char *text, size_t length, Atlas &out, std::string &error) {
	out = Atlas();
	std::string all(text, length);
	size_t pos = 0;
	bool expect_page = true;
	AtlasRegion *region = nullptr;
	bool have_size = false, have_orig = false;
	auto finish_region = [&]() {
		if (!region) {
			return;
		}
		if (!have_orig) {
			region->original_width = region->width;
			region->original_height = region->height;
		}
		const AtlasPage &p = out.pages[region->page];
		int pw = region->degrees == 90 ? region->height : region->width;
		int ph = region->degrees == 90 ? region->width : region->height;
		if (p.width > 0 && p.height > 0) {
			region->u = float(region->x) / p.width;
			region->v = float(region->y) / p.height;
			region->u2 = float(region->x + pw) / p.width;
			region->v2 = float(region->y + ph) / p.height;
		}
		region = nullptr;
		have_size = have_orig = false;
	};
	int line_no = 0;
	while (pos <= all.size()) {
		size_t nl = all.find('\n', pos);
		if (nl == std::string::npos) {
			nl = all.size();
		}
		std::string raw = all.substr(pos, nl - pos);
		pos = nl + 1;
		line_no++;
		std::string line = trim(raw);
		if (line.empty()) {
			finish_region();
			expect_page = true;
			if (nl == all.size()) {
				break;
			}
			continue;
		}
		size_t colon = line.find(':');
		if (colon == std::string::npos) {
			finish_region();
			if (expect_page) {
				AtlasPage page;
				page.name = line;
				out.pages.push_back(page);
				expect_page = false;
			} else {
				if (out.pages.empty()) {
					error = "region before any page at line " + std::to_string(line_no);
					return false;
				}
				out.regions.emplace_back();
				region = &out.regions.back();
				region->name = line;
				region->page = int(out.pages.size()) - 1;
			}
			continue;
		}
		std::string key = trim(line.substr(0, colon));
		std::vector<std::string> vals = split_values(line.substr(colon + 1));
		if (!region) {
			if (out.pages.empty()) {
				error = "property before any page at line " + std::to_string(line_no);
				return false;
			}
			AtlasPage &p = out.pages.back();
			if (key == "size") {
				p.width = to_int(vals, 0);
				p.height = to_int(vals, 1);
			} else if (key == "filter") {
				p.linear = vals.empty() || vals[0].find("Linear") != std::string::npos || vals[0].find("MipMap") != std::string::npos;
			} else if (key == "repeat") {
				std::string r = vals.empty() ? "" : vals[0];
				p.repeat_x = r == "x" || r == "xy";
				p.repeat_y = r == "y" || r == "xy";
			} else if (key == "pma") {
				p.pma = !vals.empty() && vals[0] == "true";
			}
			// format and unknown keys are ignored
			continue;
		}
		if (key == "bounds") { // 4.x
			region->x = to_int(vals, 0);
			region->y = to_int(vals, 1);
			region->width = to_int(vals, 2);
			region->height = to_int(vals, 3);
			have_size = true;
		} else if (key == "xy") { // 3.x
			region->x = to_int(vals, 0);
			region->y = to_int(vals, 1);
		} else if (key == "size") {
			region->width = to_int(vals, 0);
			region->height = to_int(vals, 1);
			have_size = true;
		} else if (key == "offsets") { // 4.x
			region->offset_x = to_int(vals, 0);
			region->offset_y = to_int(vals, 1);
			region->original_width = to_int(vals, 2);
			region->original_height = to_int(vals, 3);
			have_orig = true;
		} else if (key == "orig") {
			region->original_width = to_int(vals, 0);
			region->original_height = to_int(vals, 1);
			have_orig = true;
		} else if (key == "offset") {
			region->offset_x = to_int(vals, 0);
			region->offset_y = to_int(vals, 1);
		} else if (key == "rotate") {
			std::string r = vals.empty() ? "" : vals[0];
			region->degrees = r == "true" ? 90 : (r == "false" ? 0 : std::atoi(r.c_str()));
		} else if (key == "index") {
			region->index = to_int(vals, 0);
		}
		(void)have_size;
	}
	finish_region();
	return true;
}

namespace {

// UV for a corner given in unrotated region space: s across (0 left .. 1 right), t up (0 bottom .. 1 top).
// Texture v grows downward. A 90 degree region is stored turned clockwise in the page.
// NOTE: the turn direction is unverified against real data; check a rotated region once the game's atlas is read.
void region_uv(const AtlasRegion &r, float s, float t, float &u, float &v) {
	if (r.degrees == 90) {
		// Turned clockwise in the page: image top-left lands top-right, image bottom-left lands top-left.
		u = r.u + t * (r.u2 - r.u);
		v = r.v + s * (r.v2 - r.v);
		return;
	}
	u = r.u + s * (r.u2 - r.u);
	v = r.v + (1 - t) * (r.v2 - r.v);
}

bool bind_one(Attachment &a, const Atlas &atlas, std::vector<std::string> &missing) {
	const AtlasRegion *r = atlas.find(a.path);
	if (!r) {
		missing.push_back(a.path);
		return false;
	}
	a.page = r->page;
	a.region_rotated = r->degrees == 90;
	a.region_u = r->u;
	a.region_v = r->v;
	a.region_u2 = r->u2;
	a.region_v2 = r->v2;
	if (a.type == AttachmentType::Region) {
		// Corners in unrotated image space: BL, BR, TR, TL; trimmed to the packed pixels.
		float ow = r->original_width ? float(r->original_width) : 1, oh = r->original_height ? float(r->original_height) : 1;
		float l = r->offset_x / ow, b = r->offset_y / oh;
		float rr = (r->offset_x + r->width) / ow, tt = (r->offset_y + r->height) / oh;
		const float st[8] = { l, b, rr, b, rr, tt, l, tt };
		for (int i = 0; i < 4; i++) {
			region_uv(*r, (st[i * 2] - l) / (rr - l), (st[i * 2 + 1] - b) / (tt - b), a.region_uv[i * 2], a.region_uv[i * 2 + 1]);
		}
		a.trim[0] = l;
		a.trim[1] = b;
		a.trim[2] = rr;
		a.trim[3] = tt;
		a.update_region_offsets();
	} else {
		// Mesh uvs are 0..1 over the original (untrimmed) image; map into the page.
		a.page_uvs.resize(a.uvs.size());
		float ow = r->original_width ? float(r->original_width) : 1, oh = r->original_height ? float(r->original_height) : 1;
		for (size_t i = 0; i + 1 < a.uvs.size(); i += 2) {
			float px = a.uvs[i] * ow - r->offset_x; // pixels from the packed left edge
			float py = (1 - a.uvs[i + 1]) * oh - r->offset_y; // pixels from the packed bottom edge
			float s = r->width ? px / r->width : 0, t = r->height ? py / r->height : 0;
			region_uv(*r, s, t, a.page_uvs[i], a.page_uvs[i + 1]);
		}
	}
	return true;
}

} // namespace

std::vector<std::string> bind_atlas(SkeletonData &data, const Atlas &atlas) {
	std::vector<std::string> missing;
	for (auto &skin : data.skins) {
		for (auto &slot : skin->slots) {
			for (auto &entry : slot) {
				Attachment &a = *entry.second;
				if (a.type != AttachmentType::Region && a.type != AttachmentType::Mesh) {
					continue;
				}
				bind_one(a, atlas, missing);
				if (!a.has_sequence) {
					continue;
				}
				// Each sequence image is its own region; frames copy the attachment and rebind it.
				a.sequence_frames.clear();
				Attachment base = a;
				base.sequence_frames.clear();
				for (int i = 0; i < a.sequence.count; i++) {
					Attachment f = base;
					f.path = a.sequence.frame_path(a.sequence_base, i);
					bind_one(f, atlas, missing);
					a.sequence_frames.push_back(std::move(f));
				}
			}
		}
	}
	return missing;
}

} // namespace skel
