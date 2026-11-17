// Builds alone (no engine): c++ -std=c++17 -O1 -fno-omit-frame-pointer -rdynamic -Wl,--build-id prof/tests/prof_test.cpp prof/prof.cpp
// Run it with the path of a symbol map made from the test binary itself (see run_tests.sh).
#include "../prof.h"

#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

static int failures = 0;
#define CHECK(c) do { if (!(c)) { std::printf("FAIL %s:%d %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)

volatile double sink = 0;

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

int main(int argc, char **argv) {
	CHECK(droiddeck_prof::start_from_env().empty() || std::getenv("DROIDDECK_NATIVE_PROF"));
	CHECK(droiddeck_prof::start(0, 1, "").find("off") != std::string::npos);
	const std::string syms = argc > 1 ? argv[1] : "";
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
	if (!syms.empty()) {
		CHECK(started.find("engine symbols") != std::string::npos);
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
	if (failures) {
		std::printf("%d failure(s)\n", failures);
		return 1;
	}
	std::printf("prof tests OK\n");
	return 0;
}
