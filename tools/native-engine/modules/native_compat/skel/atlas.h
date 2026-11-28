// Clean-room code for DroidDeck/Kestrel: written from scratch; no third-party runtime source consulted.
// Reads the text texture-atlas format the skeleton editor exports (pages, then named regions).
#pragma once

#include <string>
#include <vector>

namespace skel {

struct SkeletonData;

struct AtlasPage {
	std::string name; // image file name, relative to the atlas file
	int width = 0, height = 0;
	bool pma = false; // premultiplied alpha
	bool linear = true; // filtering
	bool repeat_x = false, repeat_y = false;
};

struct AtlasRegion {
	std::string name;
	int page = 0;
	int x = 0, y = 0; // top-left in the page, pixels
	int width = 0, height = 0; // packed size, unrotated (as the image was before packing)
	int degrees = 0; // 0 or 90: stored turned a quarter in the page
	int offset_x = 0, offset_y = 0; // whitespace stripped from the left and bottom
	int original_width = 0, original_height = 0;
	int index = -1;
	float u = 0, v = 0, u2 = 0, v2 = 0; // page uv rectangle actually covered
};

struct Atlas {
	std::vector<AtlasPage> pages;
	std::vector<AtlasRegion> regions;
	const AtlasRegion *find(const std::string &name) const;
};

bool parse_atlas(const char *text, size_t length, Atlas &out, std::string &error);

// Points every region and mesh attachment at its atlas region: page, uvs and stripped-whitespace
// placement. Returns the attachment paths with no region (empty when all resolved).
std::vector<std::string> bind_atlas(SkeletonData &data, const Atlas &atlas);

} // namespace skel
