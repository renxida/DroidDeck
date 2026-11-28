// Clean-room clipping for skeleton drawing: a clipping attachment's polygon masks the slots drawn after it,
// up to its end slot. The polygon (any simple polygon, either winding) is cut into triangles by ear
// clipping; each drawn triangle is clipped against each of those (Sutherland-Hodgman), carrying texture
// coordinates along. Standard textbook algorithms; no runtime code was consulted.
#pragma once

#include <vector>

namespace skel {

struct ClipVertex {
	float x = 0, y = 0, u = 0, v = 0;
};

class Clipper {
public:
	// Starts masking with the polygon of `count` points (x, y pairs). An unusable polygon masks everything.
	void begin(const float *points, int count);
	void end() {
		on = false;
		parts.clear();
	}
	bool active() const { return on; }
	// Clips `count / 3` triangles (indices into `verts`) and appends the visible pieces to `out_verts` and
	// `out_indices` (indices into out_verts, offset by out_verts' size on entry).
	void clip(const std::vector<ClipVertex> &verts, const int *indices, int count, std::vector<ClipVertex> &out_verts,
			std::vector<int> &out_indices) const;

private:
	bool on = false;
	std::vector<float> parts; // counter-clockwise triangles, 6 floats each
	float min_x = 0, min_y = 0, max_x = 0, max_y = 0;
};

// Ear clipping of a simple polygon (x, y pairs). Appends index triples; returns false if it could not
// cut the whole polygon (degenerate or self-intersecting input), keeping what it cut.
bool triangulate(const float *points, int count, std::vector<int> &out);

} // namespace skel
