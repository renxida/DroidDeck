// droiddeck-ctex-astc: re-encodes a Godot 4 game's textures (.ctex inside its .pck) to ASTC 4x4, the format the
// phone's GPU reads natively. A BC texture (the PC export's format) costs this GPU about five times its size: the
// graphics layer keeps the BC data and a decoded RGBA copy. ASTC 4x4 is the same size as BC3/BC7 and needs neither.
//
// It reads jobs ("id offset size", one per line, offsets into --pack) on stdin, converts each texture on a pool of
// threads and appends the new .ctex to --out (O_APPEND; --out may be the pack itself). One line per job on stdout:
//   id ok <out offset> <out size> <kind> <w>x<h> <new w>x<new h> <psnr dB>
//   id keep <reason>
//   id fail <reason>
// A texture whose ASTC decode is worse than --min-psnr against the original's decode is kept as it was.
//
// --half-above N stores textures larger than N pixels on a side at half resolution: the .ctex keeps its logical
// size (Godot draws it at the same size), so only sharpness changes. With mipmaps the next level is used as is.

#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <mutex>
#include <string>
#include <thread>
#include <unistd.h>
#include <vector>

#include "astcenc.h"

#define BCDEC_IMPLEMENTATION
#include "bcdec.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_ONLY_PNG
#define STBI_NO_STDIO
#include "stb_image.h"

namespace {

// Image::Format (core/io/image.h, Godot 4.5).
enum : uint32_t {
	FMT_L8 = 0, FMT_LA8 = 1, FMT_RGB8 = 4, FMT_RGBA8 = 5,
	FMT_DXT1 = 17, FMT_DXT3 = 18, FMT_DXT5 = 19, FMT_BPTC_RGBA = 22, FMT_ASTC_4x4 = 35,
};
// CompressedTexture2D::DataFormat.
enum : uint32_t { DATA_IMAGE = 0, DATA_PNG = 1, DATA_WEBP = 2, DATA_BASIS = 3 };

const size_t HEAD = 52;

struct Options {
	float effort = ASTCENC_PRE_MEDIUM;
	int half_above = 0;
	double min_psnr = 30.0;
	unsigned threads = 2;
};

uint32_t rd32(const uint8_t *p) { return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24; }
uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
void wr32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }
void wr16(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; }

struct Level {
	int w, h;
	std::vector<uint8_t> rgba;  // w * h * 4
};

// Godot's mip chain: each level halves (at least 1 px) until 1x1; a block format stores ceil(w/4)*ceil(h/4) blocks.
std::vector<std::pair<int, int>> chain(int w, int h, bool mips) {
	std::vector<std::pair<int, int>> out{{w, h}};
	while (mips && (w > 1 || h > 1)) {
		w = w > 1 ? w >> 1 : 1;
		h = h > 1 ? h >> 1 : 1;
		out.push_back({w, h});
	}
	return out;
}

size_t blocks(int w, int h) { return (size_t)((w + 3) / 4) * (size_t)((h + 3) / 4); }

const char *kind_name(uint32_t fmt) {
	switch (fmt) {
		case FMT_DXT1: return "DXT1";
		case FMT_DXT3: return "DXT3";
		case FMT_DXT5: return "DXT5";
		case FMT_BPTC_RGBA: return "BPTC_RGBA";
		case FMT_L8: return "L8";
		case FMT_LA8: return "LA8";
		case FMT_RGB8: return "RGB8";
		case FMT_RGBA8: return "RGBA8";
	}
	return "?";
}

bool decode_bc(uint32_t fmt, const uint8_t *data, size_t size, int w, int h, bool mips, std::vector<Level> &out, std::string &why) {
	size_t block_bytes = fmt == FMT_DXT1 ? 8 : 16;
	size_t at = 0;
	for (auto [lw, lh] : chain(w, h, mips)) {
		size_t need = blocks(lw, lh) * block_bytes;
		if (at + need > size) {
			why = "short data";
			return false;
		}
		int bw = (lw + 3) / 4 * 4, bh = (lh + 3) / 4 * 4;
		std::vector<uint8_t> padded((size_t)bw * bh * 4);
		const uint8_t *src = data + at;
		for (int by = 0; by < bh; by += 4)
			for (int bx = 0; bx < bw; bx += 4, src += block_bytes) {
				uint8_t *dst = &padded[((size_t)by * bw + bx) * 4];
				switch (fmt) {
					case FMT_DXT1: bcdec_bc1(src, dst, bw * 4); break;
					case FMT_DXT3: bcdec_bc2(src, dst, bw * 4); break;
					case FMT_DXT5: bcdec_bc3(src, dst, bw * 4); break;
					case FMT_BPTC_RGBA: bcdec_bc7(src, dst, bw * 4); break;
				}
			}
		Level level{lw, lh, std::vector<uint8_t>((size_t)lw * lh * 4)};
		for (int y = 0; y < lh; y++)
			memcpy(&level.rgba[(size_t)y * lw * 4], &padded[(size_t)y * bw * 4], (size_t)lw * 4);
		out.push_back(std::move(level));
		at += need;
	}
	return true;
}

// A lossless texture stores each level as its own PNG, prefixed by its size.
bool decode_png(uint32_t fmt, const uint8_t *data, size_t size, int w, int h, uint32_t mipmaps, std::vector<Level> &out, std::string &why) {
	if (fmt != FMT_L8 && fmt != FMT_LA8 && fmt != FMT_RGB8 && fmt != FMT_RGBA8) {
		why = std::string("png ") + std::to_string(fmt);
		return false;
	}
	size_t at = 0;
	for (uint32_t i = 0; i < mipmaps + 1; i++) {
		if (at + 4 > size) {
			why = "short data";
			return false;
		}
		uint32_t n = rd32(data + at);
		at += 4;
		if (at + n > size) {
			why = "short data";
			return false;
		}
		int pw, ph, comp;
		uint8_t *pixels = stbi_load_from_memory(data + at, (int)n, &pw, &ph, &comp, 4);
		if (!pixels) {
			why = "png decode";
			return false;
		}
		Level level{pw, ph, std::vector<uint8_t>(pixels, pixels + (size_t)pw * ph * 4)};
		stbi_image_free(pixels);
		if (fmt == FMT_RGB8 || fmt == FMT_L8)
			for (size_t p = 3; p < level.rgba.size(); p += 4) level.rgba[p] = 255;
		out.push_back(std::move(level));
		at += n;
	}
	if (out.empty() || out[0].w != w || out[0].h != h) {
		why = "png size";
		return false;
	}
	if (mipmaps) {
		auto want = chain(w, h, true);
		if (want.size() != out.size()) {
			why = "png mip chain";
			return false;
		}
	}
	return true;
}

// Half size by 2x2 averages weighted by alpha, so transparent texels do not darken the edges they border.
Level half(const Level &in) {
	Level out{in.w > 1 ? in.w / 2 : 1, in.h > 1 ? in.h / 2 : 1, {}};
	out.rgba.resize((size_t)out.w * out.h * 4);
	for (int y = 0; y < out.h; y++)
		for (int x = 0; x < out.w; x++) {
			double c[3] = {0, 0, 0}, a = 0, plain[3] = {0, 0, 0};
			int n = 0;
			for (int dy = 0; dy < 2; dy++)
				for (int dx = 0; dx < 2; dx++) {
					int sx = x * 2 + dx, sy = y * 2 + dy;
					if (sx >= in.w || sy >= in.h) continue;
					const uint8_t *p = &in.rgba[((size_t)sy * in.w + sx) * 4];
					for (int k = 0; k < 3; k++) {
						c[k] += p[k] * (double)p[3];
						plain[k] += p[k];
					}
					a += p[3];
					n++;
				}
			uint8_t *q = &out.rgba[((size_t)y * out.w + x) * 4];
			for (int k = 0; k < 3; k++) q[k] = (uint8_t)lround(a > 0 ? c[k] / a : plain[k] / n);
			q[3] = (uint8_t)lround(a / n);
		}
	return out;
}

double psnr(const std::vector<uint8_t> &a, const std::vector<uint8_t> &b) {
	double sum = 0;
	for (size_t i = 0; i < a.size(); i++) {
		double d = (double)a[i] - b[i];
		sum += d * d;
	}
	if (sum == 0) return 99.0;
	return 10.0 * log10(255.0 * 255.0 / (sum / a.size()));
}

struct Result {
	std::string status;  // ok, keep, fail
	std::string detail;
	std::vector<uint8_t> ctex;
};

struct Worker {
	astcenc_context *ctx = nullptr;
	Options opt;

	bool init(const Options &o) {
		opt = o;
		astcenc_config config;
		if (astcenc_config_init(ASTCENC_PRF_LDR, 4, 4, 1, o.effort, 0, &config) != ASTCENC_SUCCESS) return false;
		return astcenc_context_alloc(&config, 1, &ctx, nullptr) == ASTCENC_SUCCESS;
	}
	~Worker() {
		if (ctx) astcenc_context_free(ctx);
	}

	bool encode(Level &level, std::vector<uint8_t> &out, double *quality) {
		size_t len = blocks(level.w, level.h) * 16;
		size_t at = out.size();
		out.resize(at + len);
		void *slice = level.rgba.data();
		astcenc_image image{(unsigned)level.w, (unsigned)level.h, 1, ASTCENC_TYPE_U8, &slice};
		astcenc_swizzle swz{ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A};
		astcenc_compress_reset(ctx);
		if (astcenc_compress_image(ctx, &image, &swz, out.data() + at, len, 0) != ASTCENC_SUCCESS) return false;
		if (quality) {
			std::vector<uint8_t> back(level.rgba.size());
			void *back_slice = back.data();
			astcenc_image decoded{(unsigned)level.w, (unsigned)level.h, 1, ASTCENC_TYPE_U8, &back_slice};
			astcenc_decompress_reset(ctx);
			if (astcenc_decompress_image(ctx, out.data() + at, len, &decoded, &swz, 0) != ASTCENC_SUCCESS) return false;
			*quality = psnr(level.rgba, back);
		}
		return true;
	}

	Result convert(const uint8_t *src, size_t n) {
		Result r;
		if (n < HEAD || memcmp(src, "GST2", 4) != 0) return {"keep", "not ctex", {}};
		if (rd32(src + 4) > 1) return {"keep", "ctex version", {}};
		uint32_t data_format = rd32(src + 36);
		int w = rd16(src + 40), h = rd16(src + 42);
		uint32_t mipmaps = rd32(src + 44), fmt = rd32(src + 48);
		if (w <= 0 || h <= 0) return {"keep", "empty", {}};
		std::vector<Level> levels;
		std::string why;
		bool decoded = false;
		if (data_format == DATA_IMAGE) {
			if (fmt != FMT_DXT1 && fmt != FMT_DXT3 && fmt != FMT_DXT5 && fmt != FMT_BPTC_RGBA)
				return {"keep", std::string("format ") + std::to_string(fmt), {}};
			decoded = decode_bc(fmt, src + HEAD, n - HEAD, w, h, mipmaps != 0, levels, why);
		} else if (data_format == DATA_PNG) {
			decoded = decode_png(fmt, src + HEAD, n - HEAD, w, h, mipmaps, levels, why);
		} else {
			return {"keep", data_format == DATA_WEBP ? "webp" : data_format == DATA_BASIS ? "basis" : "data format", {}};
		}
		if (!decoded) return {why == "short data" || why == "png decode" ? "fail" : "keep", why, {}};

		bool mips = levels.size() > 1;
		if (opt.half_above > 0 && std::max(w, h) > opt.half_above && std::min(w, h) >= 8) {
			if (mips)
				levels.erase(levels.begin());
			else
				levels[0] = half(levels[0]);
		}
		int nw = levels[0].w, nh = levels[0].h;
		auto want = chain(nw, nh, mips);
		if (want.size() != levels.size()) return {"fail", "mip chain", {}};

		std::vector<uint8_t> out(src, src + 36);
		out.resize(HEAD);
		wr32(&out[36], DATA_IMAGE);
		wr16(&out[40], nw);
		wr16(&out[42], nh);
		wr32(&out[44], mips ? (uint32_t)(want.size() - 1) : 0);
		wr32(&out[48], FMT_ASTC_4x4);
		double quality = 0;
		for (size_t i = 0; i < levels.size(); i++)
			if (!encode(levels[i], out, i == 0 ? &quality : nullptr)) return {"fail", "astc encode", {}};
		if (quality < opt.min_psnr) {
			char buf[64];
			snprintf(buf, sizeof buf, "psnr %.1f", quality);
			return {"keep", buf, {}};
		}
		char buf[128];
		snprintf(buf, sizeof buf, "%s%s %dx%d %dx%d %.1f", data_format == DATA_PNG ? "PNG_" : "", kind_name(fmt), w, h, nw, nh, quality);
		return {"ok", buf, std::move(out)};
	}
};

bool write_all(int fd, const uint8_t *p, size_t n) {
	while (n) {
		ssize_t k = write(fd, p, n);
		if (k < 0) {
			if (errno == EINTR) continue;
			return false;
		}
		p += k;
		n -= (size_t)k;
	}
	return true;
}

bool read_at(int fd, uint8_t *p, size_t n, off_t at) {
	while (n) {
		ssize_t k = pread(fd, p, n, at);
		if (k <= 0) {
			if (k < 0 && errno == EINTR) continue;
			return false;
		}
		p += k;
		n -= (size_t)k;
		at += k;
	}
	return true;
}

int usage() {
	fprintf(stderr, "usage: droiddeck-ctex-astc --pack PATH --out PATH [--threads N] [--effort 0-100] [--half-above PX] [--min-psnr DB] < jobs\n");
	return 2;
}

}  // namespace

int main(int argc, char **argv) {
	Options opt;
	const char *pack = nullptr, *out_path = nullptr;
	for (int i = 1; i < argc; i++) {
		std::string a = argv[i];
		const char *v = i + 1 < argc ? argv[i + 1] : nullptr;
		if (!v) return usage();
		if (a == "--pack") pack = v;
		else if (a == "--out") out_path = v;
		else if (a == "--threads") opt.threads = (unsigned)std::max(1, atoi(v));
		else if (a == "--effort") opt.effort = (float)atof(v);
		else if (a == "--half-above") opt.half_above = atoi(v);
		else if (a == "--min-psnr") opt.min_psnr = atof(v);
		else return usage();
		i++;
	}
	if (!pack || !out_path) return usage();
	int in = open(pack, O_RDONLY | O_CLOEXEC);
	if (in < 0) {
		fprintf(stderr, "droiddeck-ctex-astc: %s: %s\n", pack, strerror(errno));
		return 1;
	}
	int out = open(out_path, O_WRONLY | O_APPEND | O_CREAT | O_CLOEXEC, 0644);
	if (out < 0) {
		fprintf(stderr, "droiddeck-ctex-astc: %s: %s\n", out_path, strerror(errno));
		return 1;
	}

	struct Job {
		std::string id;
		unsigned long long offset, size;
	};
	std::vector<Job> jobs;
	char line[4096];
	while (fgets(line, sizeof line, stdin)) {
		char id[4000];
		unsigned long long offset, size;
		if (sscanf(line, "%3999s %llu %llu", id, &offset, &size) == 3) jobs.push_back({id, offset, size});
	}

	std::mutex lock;
	size_t next = 0;
	bool broken = false;
	auto run = [&]() {
		Worker worker;
		if (!worker.init(opt)) {
			std::lock_guard<std::mutex> g(lock);
			fprintf(stderr, "droiddeck-ctex-astc: astcenc context failed\n");
			broken = true;
			return;
		}
		std::vector<uint8_t> buffer;
		for (;;) {
			size_t mine;
			{
				std::lock_guard<std::mutex> g(lock);
				if (broken || next >= jobs.size()) return;
				mine = next++;
			}
			const Job &job = jobs[mine];
			Result r;
			if (job.size > (512ull << 20)) {
				r = {"keep", "too large", {}};
			} else {
				buffer.resize(job.size);
				if (!read_at(in, buffer.data(), job.size, (off_t)job.offset))
					r = {"fail", "read", {}};
				else
					r = worker.convert(buffer.data(), buffer.size());
			}
			std::lock_guard<std::mutex> g(lock);
			if (r.status == "ok") {
				off_t at = lseek(out, 0, SEEK_END);
				if (at < 0 || !write_all(out, r.ctex.data(), r.ctex.size())) {
					fprintf(stderr, "droiddeck-ctex-astc: write: %s\n", strerror(errno));
					broken = true;
					printf("%s fail write\n", job.id.c_str());
				} else {
					printf("%s ok %lld %zu %s\n", job.id.c_str(), (long long)at, r.ctex.size(), r.detail.c_str());
				}
			} else {
				printf("%s %s %s\n", job.id.c_str(), r.status.c_str(), r.detail.c_str());
			}
			fflush(stdout);
		}
	};
	std::vector<std::thread> pool;
	for (unsigned t = 0; t < opt.threads; t++) pool.emplace_back(run);
	for (auto &t : pool) t.join();
	if (fsync(out) != 0 && errno != EINVAL) broken = true;
	close(out);
	close(in);
	return broken ? 1 : 0;
}
