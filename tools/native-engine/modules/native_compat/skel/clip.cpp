// Clean-room clipping; see clip.h.
#include "clip.h"

#include <algorithm>
#include <cmath>

namespace skel {

namespace {

float cross(float ax, float ay, float bx, float by, float px, float py) {
	return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
}

bool in_triangle(float px, float py, const float *t) {
	return cross(t[0], t[1], t[2], t[3], px, py) >= 0 && cross(t[2], t[3], t[4], t[5], px, py) >= 0 &&
			cross(t[4], t[5], t[0], t[1], px, py) >= 0;
}

} // namespace

bool triangulate(const float *p, int count, std::vector<int> &out) {
	if (count < 3) {
		return false;
	}
	double area = 0;
	for (int i = 0; i < count; i++) {
		const int j = (i + 1) % count;
		area += double(p[i * 2]) * p[j * 2 + 1] - double(p[j * 2]) * p[i * 2 + 1];
	}
	std::vector<int> ring(count);
	for (int i = 0; i < count; i++) {
		ring[i] = area >= 0 ? i : count - 1 - i; // walk counter-clockwise
	}
	int guard = count * count + 8;
	while (ring.size() > 3 && guard-- > 0) {
		bool cut = false;
		const int n = int(ring.size());
		for (int i = 0; i < n; i++) {
			const int a = ring[(i + n - 1) % n], b = ring[i], c = ring[(i + 1) % n];
			const float t[6] = { p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1], p[c * 2], p[c * 2 + 1] };
			if (cross(t[0], t[1], t[2], t[3], t[4], t[5]) <= 0) {
				continue; // reflex or flat corner
			}
			bool empty = true;
			for (int k = 0; k < n && empty; k++) {
				const int q = ring[k];
				if (q != a && q != b && q != c && in_triangle(p[q * 2], p[q * 2 + 1], t)) {
					empty = false;
				}
			}
			if (!empty) {
				continue;
			}
			out.push_back(a);
			out.push_back(b);
			out.push_back(c);
			ring.erase(ring.begin() + i);
			cut = true;
			break;
		}
		if (!cut) {
			// Drop a flat corner if there is one, else give up.
			bool dropped = false;
			for (int i = 0; i < n && !dropped; i++) {
				const int a = ring[(i + n - 1) % n], b = ring[i], c = ring[(i + 1) % n];
				if (std::fabs(cross(p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1], p[c * 2], p[c * 2 + 1])) < 1e-6f) {
					ring.erase(ring.begin() + i);
					dropped = true;
				}
			}
			if (!dropped) {
				return false;
			}
		}
	}
	if (ring.size() == 3) {
		const float *a = p + ring[0] * 2, *b = p + ring[1] * 2, *c = p + ring[2] * 2;
		if (cross(a[0], a[1], b[0], b[1], c[0], c[1]) > 0) {
			out.push_back(ring[0]);
			out.push_back(ring[1]);
			out.push_back(ring[2]);
		}
	}
	return ring.size() <= 3;
}

void Clipper::begin(const float *points, int count) {
	on = true;
	parts.clear();
	std::vector<int> tris;
	triangulate(points, count, tris);
	for (size_t i = 0; i + 2 < tris.size(); i += 3) {
		for (int k = 0; k < 3; k++) {
			parts.push_back(points[tris[i + k] * 2]);
			parts.push_back(points[tris[i + k] * 2 + 1]);
		}
	}
	min_x = min_y = 1e30f;
	max_x = max_y = -1e30f;
	for (int i = 0; i < count; i++) {
		min_x = std::min(min_x, points[i * 2]);
		max_x = std::max(max_x, points[i * 2]);
		min_y = std::min(min_y, points[i * 2 + 1]);
		max_y = std::max(max_y, points[i * 2 + 1]);
	}
}

void Clipper::clip(const std::vector<ClipVertex> &verts, const int *indices, int count, std::vector<ClipVertex> &out_verts,
		std::vector<int> &out_indices) const {
	std::vector<ClipVertex> poly, next;
	for (int t = 0; t + 2 < count; t += 3) {
		const ClipVertex &a = verts[indices[t]], &b = verts[indices[t + 1]], &c = verts[indices[t + 2]];
		if (std::max({ a.x, b.x, c.x }) < min_x || std::min({ a.x, b.x, c.x }) > max_x ||
				std::max({ a.y, b.y, c.y }) < min_y || std::min({ a.y, b.y, c.y }) > max_y) {
			continue; // wholly outside the mask's bounds
		}
		for (size_t p = 0; p + 5 < parts.size(); p += 6) {
			const float *m = &parts[p];
			if (in_triangle(a.x, a.y, m) && in_triangle(b.x, b.y, m) && in_triangle(c.x, c.y, m)) {
				const int base = int(out_verts.size());
				out_verts.push_back(a);
				out_verts.push_back(b);
				out_verts.push_back(c);
				out_indices.push_back(base);
				out_indices.push_back(base + 1);
				out_indices.push_back(base + 2);
				break; // inside one piece: inside no other (the pieces do not overlap)
			}
			poly.assign({ a, b, c });
			for (int e = 0; e < 3 && !poly.empty(); e++) {
				const float ex = m[e * 2], ey = m[e * 2 + 1], fx = m[(e + 1) % 3 * 2], fy = m[(e + 1) % 3 * 2 + 1];
				next.clear();
				for (size_t i = 0; i < poly.size(); i++) {
					const ClipVertex &s = poly[i], &d = poly[(i + 1) % poly.size()];
					const float ds = cross(ex, ey, fx, fy, s.x, s.y), dd = cross(ex, ey, fx, fy, d.x, d.y);
					if (ds >= 0) {
						next.push_back(s);
					}
					if ((ds >= 0) != (dd >= 0)) {
						const float k = ds / (ds - dd);
						ClipVertex v;
						v.x = s.x + (d.x - s.x) * k;
						v.y = s.y + (d.y - s.y) * k;
						v.u = s.u + (d.u - s.u) * k;
						v.v = s.v + (d.v - s.v) * k;
						next.push_back(v);
					}
				}
				poly.swap(next);
			}
			if (poly.size() < 3) {
				continue;
			}
			const int base = int(out_verts.size());
			out_verts.insert(out_verts.end(), poly.begin(), poly.end());
			for (size_t i = 1; i + 1 < poly.size(); i++) {
				out_indices.push_back(base);
				out_indices.push_back(base + int(i));
				out_indices.push_back(base + int(i) + 1);
			}
		}
	}
}

} // namespace skel
