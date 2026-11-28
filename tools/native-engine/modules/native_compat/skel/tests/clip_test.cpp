// Host test for the clean-room clipper (synthetic shapes only).
#include "../clip.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>

using namespace skel;

#define CHECK(c)                                                           \
	do {                                                                   \
		if (!(c)) {                                                        \
			fprintf(stderr, "%s:%d: CHECK(%s)\n", __FILE__, __LINE__, #c); \
			exit(1);                                                       \
		}                                                                  \
	} while (0)

static double area(const std::vector<ClipVertex> &v, const std::vector<int> &ix) {
	double a = 0;
	for (size_t i = 0; i + 2 < ix.size(); i += 3) {
		const ClipVertex &p = v[ix[i]], &q = v[ix[i + 1]], &r = v[ix[i + 2]];
		a += std::fabs((q.x - p.x) * (r.y - p.y) - (q.y - p.y) * (r.x - p.x)) / 2;
	}
	return a;
}

int main() {
	// Ear clipping: an L shape (6 points, concave) in both windings makes 4 triangles of total area 3.
	const float l_ccw[12] = { 0, 0, 2, 0, 2, 1, 1, 1, 1, 2, 0, 2 };
	float l_cw[12];
	for (int i = 0; i < 6; i++) {
		l_cw[i * 2] = l_ccw[(5 - i) * 2];
		l_cw[i * 2 + 1] = l_ccw[(5 - i) * 2 + 1];
	}
	for (const float *poly : { (const float *)l_ccw, (const float *)l_cw }) {
		std::vector<int> tris;
		CHECK(triangulate(poly, 6, tris));
		CHECK(tris.size() == 12);
		double a = 0;
		for (size_t i = 0; i < tris.size(); i += 3) {
			const float *p = poly + tris[i] * 2, *q = poly + tris[i + 1] * 2, *r = poly + tris[i + 2] * 2;
			a += ((q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0])) / 2;
		}
		CHECK(std::fabs(a - 3) < 1e-6); // counter-clockwise triangles, the L's area
	}
	// Clip a 2x2 square (two triangles) by the L: what stays is the L (area 3), uvs carried along.
	std::vector<ClipVertex> quad(4);
	quad[0] = { 0, 0, 0, 0 };
	quad[1] = { 2, 0, 1, 0 };
	quad[2] = { 2, 2, 1, 1 };
	quad[3] = { 0, 2, 0, 1 };
	const int qi[6] = { 0, 1, 2, 2, 3, 0 };
	Clipper c;
	c.begin(l_cw, 6);
	CHECK(c.active());
	std::vector<ClipVertex> ov;
	std::vector<int> oi;
	c.clip(quad, qi, 6, ov, oi);
	CHECK(std::fabs(area(ov, oi) - 3) < 1e-4);
	for (const ClipVertex &v : ov) {
		CHECK(std::fabs(v.u - v.x / 2) < 1e-5 && std::fabs(v.v - v.y / 2) < 1e-5); // uv follows position
		CHECK(!(v.x > 1.0001f && v.y > 1.0001f)); // nothing in the cut-out corner
	}
	// A triangle wholly inside keeps all its area (perhaps in pieces across the mask's triangles); one wholly
	// outside goes.
	std::vector<ClipVertex> in = { { 0.1f, 0.1f, 0, 0 }, { 0.5f, 0.1f, 0, 0 }, { 0.1f, 0.5f, 0, 0 } };
	const int ti[3] = { 0, 1, 2 };
	ov.clear();
	oi.clear();
	c.clip(in, ti, 3, ov, oi);
	CHECK(std::fabs(area(ov, oi) - 0.08) < 1e-5);
	std::vector<ClipVertex> out = { { 5, 5, 0, 0 }, { 6, 5, 0, 0 }, { 5, 6, 0, 0 } };
	ov.clear();
	oi.clear();
	c.clip(out, ti, 3, ov, oi);
	CHECK(ov.empty());
	c.end();
	CHECK(!c.active());
	printf("clip tests OK\n");
	return 0;
}
