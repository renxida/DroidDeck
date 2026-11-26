// Builds alone (no engine): c++ -std=c++17 -O1 -fno-omit-frame-pointer -rdynamic -Wl,--build-id prof/tests/prof_test.cpp prof/prof.cpp
// Run it with the path of a symbol map made from the test binary itself (see run_tests.sh).
#include "../prof.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <mutex>
#include <thread>
#include <string>

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)

volatile double sink = 0;

static std::mutex sunk_lock;
static std::string sunk;
static void capture(const std::vector<std::string> &p_lines) {
	std::lock_guard<std::mutex> l(sunk_lock);
	for (const auto &line : p_lines) {
		sunk += line + "\n";
	}
}

__attribute__((noinline)) void burn_heavy(double sec) {
	const auto end = std::chrono::steady_clock::now() + std::chrono::duration<double>(sec);
	double x = 1;
	while (std::chrono::steady_clock::now() < end) {
		for (int i = 0; i < 2000; i++) {
			x = std::sqrt(x + i);
		}
	}
	sink = x;
}

__attribute__((noinline)) void burn_light(double sec) {
	burn_heavy(sec);
}

__attribute__((noinline)) void loader_work(double sec) {
	burn_heavy(sec);
}

int main(int argc, char **argv) {
	setenv("DROIDDECK_NATIVE_PROF_OTHER", "1", 1);
	CHECK(droiddeck_prof::start_from_env().empty() || std::getenv("DROIDDECK_NATIVE_PROF"));
	CHECK(droiddeck_prof::start(0, 1, "").find("off") != std::string::npos);
	// "--cache": no path given, the map waits in $HOME/.cache/droiddeck/native/<tag>/ as a downloaded component's.
	const bool from_cache = argc > 1 && std::strcmp(argv[1], "--cache") == 0;
	const std::string syms = argc > 1 && !from_cache ? argv[1] : "";
	const std::string started = droiddeck_prof::start(1000, 0.5, syms);
	std::printf("%s\n", started.c_str());
	CHECK(started.find("sampling the main thread at 1000 Hz") != std::string::npos);
	CHECK(droiddeck_prof::active());
	burn_light(0.3);
	CHECK(droiddeck_prof::tick().empty()); // window not over yet
	burn_light(0.3);
	const auto lines = droiddeck_prof::tick();
	for (const auto &l : lines) {
		std::printf("%s\n", l.c_str());
	}
	CHECK(lines.size() >= 4);
	std::string all;
	for (const auto &l : lines) {
		all += l + "\n";
	}
	// 0.6 s of cpu: up to 600 samples at 1000 Hz, fewer where the kernel ticks slower (250 Hz here gives ~150).
	long n = 0;
	const char *p = std::strstr(all.c_str(), "main thread ");
	CHECK(p && std::sscanf(p, "main thread %ld samples", &n) == 1 && n > 50 && n < 900);
	CHECK(all.find("prof threads") != std::string::npos);
	CHECK(all.find("main:") != std::string::npos);
	// The first tick starts the frame clock; the second closes a 0.3 s frame, a hitch with its samples.
	CHECK(all.find("prof frames 1: wall p50 3") != std::string::npos);
	CHECK(all.find("| 1 at or over 33 ms") != std::string::npos);
	CHECK(all.find("prof worst frames: 3") != std::string::npos);
	if (!syms.empty() || from_cache) {
		CHECK(started.find("engine symbols") != std::string::npos);
		CHECK(all.find("samples): 100.0% burn_heavy") != std::string::npos || all.find("samples): 9") != std::string::npos);
		CHECK(all.find("% burn_heavy") != std::string::npos); // the leaf, by name
		CHECK(all.find("burn_light") != std::string::npos); // its caller, through frame pointers
	}
	// A second report covers only the time since the first.
	burn_heavy(0.1);
	const auto again = droiddeck_prof::report_now();
	CHECK(!again.empty());
	if (!again.empty() && std::sscanf(std::strstr(again[0].c_str(), "main thread "), "main thread %ld samples", &n) == 1) {
		CHECK(n > 8 && n < 200);
	}
	// Twenty short frames and one long one: frame counts, quantiles and one hitch. A tick and a report first
	// close the frame that held the burn above and clear the window.
	droiddeck_prof::tick();
	droiddeck_prof::report_now();
	for (int i = 0; i < 20; i++) {
		burn_heavy(0.005);
		droiddeck_prof::tick();
	}
	burn_light(0.06);
	droiddeck_prof::tick();
	const auto frames = droiddeck_prof::report_now();
	std::string fr;
	for (const auto &l : frames) {
		fr += l + "\n";
	}
	std::printf("%s", fr.c_str());
	CHECK(fr.find("prof frames 21: wall p50 ") != std::string::npos);
	CHECK(fr.find("| 1 at or over 33 ms") != std::string::npos);
	double p50 = 0, mx = 0;
	const char *fp = std::strstr(fr.c_str(), "wall p50 ");
	CHECK(fp && std::sscanf(fp, "wall p50 %lf", &p50) == 1 && p50 >= 4.5 && p50 < 20);
	const char *mp = fp ? std::strstr(fp, " max ") : nullptr;
	CHECK(mp && std::sscanf(mp, " max %lf", &mx) == 1 && mx >= 59 && mx < 200);
	// A main thread that makes no frame for longer than a window (a blocking load) is reported by the side
	// thread through the sink, with how long it has gone without a frame; the next tick reports as usual.
	droiddeck_prof::tick();
	droiddeck_prof::report_now();
	droiddeck_prof::set_sink(capture);
	burn_light(2.0);
	droiddeck_prof::set_sink(nullptr);
	{
		std::lock_guard<std::mutex> l(sunk_lock);
		std::printf("%s", sunk.c_str());
		// One report per window while no frame comes (the side thread looks every 250 ms, so not exactly 4).
		int stalls = 0;
		for (size_t at = sunk.find("prof no frame for "); at != std::string::npos; at = sunk.find("prof no frame for ", at + 1)) {
			stalls++;
		}
		CHECK(stalls >= 2);
		CHECK(sunk.find("% burn_heavy") != std::string::npos || (syms.empty() && !from_cache));
		CHECK(sunk.find("prof threads") != std::string::npos);
	}
	// DROIDDECK_NATIVE_PROF_OTHER=1: a busy second thread is found in one window and sampled in the next.
	droiddeck_prof::tick();
	droiddeck_prof::report_now();
	std::thread loader(loader_work, 1.2);
	burn_heavy(0.4);
	droiddeck_prof::tick();
	droiddeck_prof::report_now(); // picks the loader
	burn_heavy(0.3);
	std::string other;
	for (const auto &l : droiddeck_prof::report_now()) {
		other += l + "\n";
	}
	loader.join();
	std::printf("%s", other.c_str());
	long on = 0;
	const char *op = std::strstr(other.c_str(), "prof other thread ");
	CHECK(op != nullptr);
	if (op) {
		const char *hash = std::strchr(op, '#');
		CHECK(hash && std::sscanf(hash, "#%*d %ld samples", &on) == 1 && on > 30);
	}
	if (!syms.empty() || from_cache) {
		CHECK(other.find("prof other self: ") != std::string::npos && other.find("burn_heavy", other.find("prof other self: ")) != std::string::npos);
	}
	if (failures) {
		std::printf("%d failure(s)\n", failures);
		return 1;
	}
	std::printf("prof tests OK\n");
	return 0;
}
