// Kestrel native-engine compatibility module. Clean-room: no Esoteric or FMOD code.
#include "register_types.h"

#include "fmod_compat.h"
#include "native_skeleton_2d.h"
#include "native_sound_bank.h"
#include "spine_compat.h"
#include "prof/prof.h"
#include "render_census.h"

#include "core/config/engine.h"
#include "core/config/project_settings.h"
#include "core/io/json.h"
#include "core/io/logger.h"
#include "core/io/resource_loader.h"

#include "core/object/class_db.h"
#include "core/os/thread.h"
#include "scene/main/scene_tree.h"
#include "core/os/os.h"
#include "core/object/message_queue.h"
#include "core/string/print_string.h"
#include "core/variant/variant.h"

#include <atomic>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include "servers/audio_server.h"
#include "servers/display_server.h"
#include "servers/rendering_server.h"

#include <map>
#include <set>
#include <string>
#include <vector>

#ifdef __linux__
#include <dirent.h>
#include <errno.h>
#include <sched.h>
#include <stdlib.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

static Ref<SpineResourceLoader> spine_loader;

#ifdef __linux__
// Opt-in scheduling hints for the main thread, which runs the game and the renderer. On the phone the CPU
// governor kept the big cores near their lowest clock while combat ran CPU-bound, so the wrapper can ask for:
//   DROIDDECK_NATIVE_MAIN_CPUS=5-7      pin the main thread (threads it starts later inherit the set)
//   DROIDDECK_NATIVE_UCLAMP_MIN=512     a utilization floor (0..1024) so the governor clocks its core up
// Each is applied once at startup and logged; unset means untouched.
static bool parse_cpu_list(const char *p_list, cpu_set_t &r_set) {
	CPU_ZERO(&r_set);
	const char *p = p_list;
	bool any = false;
	while (*p) {
		char *end = nullptr;
		long a = strtol(p, &end, 10);
		if (end == p || a < 0 || a >= CPU_SETSIZE) {
			return false;
		}
		long b = a;
		p = end;
		if (*p == '-') {
			p++;
			b = strtol(p, &end, 10);
			if (end == p || b < a || b >= CPU_SETSIZE) {
				return false;
			}
			p = end;
		}
		for (long i = a; i <= b; i++) {
			CPU_SET(int(i), &r_set);
			any = true;
		}
		if (*p == ',') {
			p++;
		} else if (*p) {
			return false;
		}
	}
	return any;
}

struct SchedAttrUclamp { // the kernel's struct sched_attr, through sched_util_max
	uint32_t size;
	uint32_t sched_policy;
	uint64_t sched_flags;
	int32_t sched_nice;
	uint32_t sched_priority;
	uint64_t sched_runtime, sched_deadline, sched_period;
	uint32_t sched_util_min, sched_util_max;
};

static void apply_main_thread_hints() {
	if (const char *cpus = getenv("DROIDDECK_NATIVE_MAIN_CPUS")) {
		cpu_set_t set;
		if (!parse_cpu_list(cpus, set)) {
			print_line(vformat("native_compat: sched main thread cpus '%s' not understood; left as is", cpus));
		} else if (sched_setaffinity(0, sizeof(set), &set) != 0) {
			print_line(vformat("native_compat: sched main thread cpus %s refused (errno %d)", cpus, errno));
		} else {
			print_line(vformat("native_compat: sched main thread pinned to cpus %s", cpus));
		}
	}
	if (const char *floor = getenv("DROIDDECK_NATIVE_UCLAMP_MIN")) {
		const long v = strtol(floor, nullptr, 10);
		if (v < 0 || v > 1024) {
			print_line(vformat("native_compat: sched uclamp min '%s' out of range 0..1024; left as is", floor));
		} else {
#ifdef SYS_sched_setattr
			SchedAttrUclamp attr = {};
			attr.size = sizeof(attr);
			// SCHED_FLAG_KEEP_POLICY | SCHED_FLAG_KEEP_PARAMS | SCHED_FLAG_UTIL_CLAMP_MIN
			attr.sched_flags = 0x08 | 0x10 | 0x20;
			attr.sched_util_min = uint32_t(v);
			if (syscall(SYS_sched_setattr, 0, &attr, 0) != 0) {
				print_line(vformat("native_compat: sched uclamp min %d refused (errno %d)", int(v), errno));
			} else {
				print_line(vformat("native_compat: sched main thread uclamp min %d", int(v)));
			}
#else
			print_line("native_compat: sched uclamp min not available in this build");
#endif
		}
	}
}
#endif
// Engine-CPU switches (branch perf/engine-cpu). Each is off unless its variable is set, and logs what it did.
//   DROIDDECK_NATIVE_THREAD_CPUS="main=7;rest=4-6;.NET BGC=0-3"
//       per-thread CPU placement: "main" is the thread that runs frames, a name is a /proc comm prefix, "rest" is
//       every other thread. Applied at the first frame and to new threads every 10 s; threads are moved once.
//   DROIDDECK_NATIVE_SWAPCHAIN_IMAGES=2   rendering/rendering_device/vsync/swapchain_image_count (Godot's default 3)
//   DROIDDECK_NATIVE_PHYSICS_TPS=<n>      physics ticks per second (default 60)
//   DROIDDECK_NATIVE_PHYSICS_MAX_STEPS=<n> physics steps one frame may catch up on (default 8)
//   DROIDDECK_NATIVE_RUNTIME_CENSUS=1     the census below even when no switch is set (for control arms)
// When any of these, DROIDDECK_NATIVE_DOTNET, DROIDDECK_NATIVE_AUDIO_DRIVER or DROIDDECK_NATIVE_MAIN_CPUS is set,
// a "native_compat: runtime" line at 20 s and 120 s reports what the engine really runs with: render thread
// model, audio driver, vsync mode, swapchain images, max fps, physics, and threads by name with their CPUs.
static bool env_set(const char *p_name) {
	const char *v = getenv(p_name);
	return v && *v;
}

static int env_int(const char *p_name, int p_min, int p_max) {
	const char *v = getenv(p_name);
	if (!v || !*v) {
		return -1;
	}
	char *end = nullptr;
	const long n = strtol(v, &end, 10);
	if (*end || n < p_min || n > p_max) {
		print_line(vformat("native_compat: %s=%s ignored (%d to %d)", p_name, v, p_min, p_max));
		return -1;
	}
	return int(n);
}

static void engine_cpu_servers() {
	const int images = env_int("DROIDDECK_NATIVE_SWAPCHAIN_IMAGES", 2, 4);
	if (images > 0) {
		ProjectSettings::get_singleton()->set_setting("rendering/rendering_device/vsync/swapchain_image_count", images);
		print_line(vformat("native_compat: swapchain images %d (DROIDDECK_NATIVE_SWAPCHAIN_IMAGES)", images));
	}
}

static void engine_cpu_scene() {
	Engine *e = Engine::get_singleton();
	const int tps = env_int("DROIDDECK_NATIVE_PHYSICS_TPS", 10, 240);
	if (tps > 0) {
		e->set_physics_ticks_per_second(tps);
		print_line(vformat("native_compat: physics ticks per second %d (DROIDDECK_NATIVE_PHYSICS_TPS)", tps));
	}
	const int steps = env_int("DROIDDECK_NATIVE_PHYSICS_MAX_STEPS", 1, 8);
	if (steps > 0) {
		e->set_max_physics_steps_per_frame(steps);
		print_line(vformat("native_compat: max physics steps per frame %d (DROIDDECK_NATIVE_PHYSICS_MAX_STEPS)", steps));
	}
}

static bool engine_cpu_census_on() {
	static const char *names[] = { "DROIDDECK_NATIVE_RUNTIME_CENSUS", "DROIDDECK_NATIVE_THREAD_CPUS",
		"DROIDDECK_NATIVE_SWAPCHAIN_IMAGES", "DROIDDECK_NATIVE_PHYSICS_TPS", "DROIDDECK_NATIVE_PHYSICS_MAX_STEPS",
		"DROIDDECK_NATIVE_DOTNET", "DROIDDECK_NATIVE_AUDIO_DRIVER", "DROIDDECK_NATIVE_MAIN_CPUS", "DROIDDECK_NATIVE_UCLAMP_MIN" };
	for (const char *n : names) {
		if (env_set(n)) {
			return true;
		}
	}
	return false;
}

#ifdef __linux__
struct ThreadRule {
	std::string name; // "main", "rest" or a comm prefix
	std::string cpus;
	cpu_set_t set;
};
static std::vector<ThreadRule> thread_rules;
static std::set<long> threads_placed;
static uint64_t threads_next_ms = 0;

static void thread_rules_parse() {
	const char *v = getenv("DROIDDECK_NATIVE_THREAD_CPUS");
	if (!v || !*v) {
		return;
	}
	std::string text(v);
	size_t at = 0;
	while (at <= text.size()) {
		size_t semi = text.find(';', at);
		std::string rule = text.substr(at, semi == std::string::npos ? std::string::npos : semi - at);
		at = semi == std::string::npos ? text.size() + 1 : semi + 1;
		if (rule.empty()) {
			continue;
		}
		const size_t eq = rule.rfind('=');
		ThreadRule r;
		if (eq == std::string::npos || eq == 0 || !parse_cpu_list(rule.c_str() + eq + 1, r.set)) {
			print_line(vformat("native_compat: DROIDDECK_NATIVE_THREAD_CPUS rule '%s' not understood; skipped", rule.c_str()));
			continue;
		}
		r.name = rule.substr(0, eq);
		r.cpus = rule.substr(eq + 1);
		thread_rules.push_back(r);
	}
	print_line(vformat("native_compat: thread placement from DROIDDECK_NATIVE_THREAD_CPUS, %d rules", int(thread_rules.size())));
}

static std::string thread_comm(long p_tid) {
	char path[64];
	snprintf(path, sizeof(path), "/proc/self/task/%ld/comm", p_tid);
	FILE *f = fopen(path, "r");
	if (!f) {
		return std::string();
	}
	char buf[64] = {};
	if (!fgets(buf, sizeof(buf), f)) {
		buf[0] = 0;
	}
	fclose(f);
	std::string s(buf);
	while (!s.empty() && (s.back() == '\n' || s.back() == ' ')) {
		s.pop_back();
	}
	return s;
}

static std::vector<long> thread_ids() {
	std::vector<long> ids;
	if (DIR *d = opendir("/proc/self/task")) {
		while (struct dirent *e = readdir(d)) {
			if (e->d_name[0] >= '0' && e->d_name[0] <= '9') {
				ids.push_back(strtol(e->d_name, nullptr, 10));
			}
		}
		closedir(d);
	}
	return ids;
}

static void threads_place(uint64_t p_now) {
	if (thread_rules.empty() || p_now < threads_next_ms) {
		return;
	}
	threads_next_ms = p_now + 10000;
	const long main_tid = long(syscall(SYS_gettid));
	std::map<std::string, int> moved;
	int refused = 0;
	for (long tid : thread_ids()) {
		if (threads_placed.count(tid)) {
			continue;
		}
		threads_placed.insert(tid);
		const std::string comm = thread_comm(tid);
		const ThreadRule *pick = nullptr, *rest = nullptr;
		for (const ThreadRule &r : thread_rules) {
			if (r.name == "rest") {
				rest = &r;
			} else if (r.name == "main" ? tid == main_tid : comm.compare(0, r.name.size(), r.name) == 0) {
				pick = pick ? pick : &r;
			}
		}
		pick = pick ? pick : rest;
		if (!pick) {
			continue;
		}
		if (sched_setaffinity(pid_t(tid), sizeof(pick->set), &pick->set) != 0) {
			refused++;
			continue;
		}
		moved[(pick->name == "main" || pick->name == "rest" ? pick->name : "'" + pick->name + "'") + "->" + pick->cpus]++;
	}
	if (moved.empty() && !refused) {
		return;
	}
	String line = vformat("native_compat: threads placed at %.1f s:", p_now / 1000.0);
	for (const auto &m : moved) {
		line += vformat(" %d %s", m.second, m.first.c_str());
	}
	if (refused) {
		line += vformat("; %d refused (errno %d)", refused, errno);
	}
	print_line(line);
}

static std::string thread_cpus_allowed(long p_tid) {
	char path[64];
	snprintf(path, sizeof(path), "/proc/self/task/%ld/status", p_tid);
	FILE *f = fopen(path, "r");
	if (!f) {
		return "?";
	}
	char buf[256];
	std::string out = "?";
	while (fgets(buf, sizeof(buf), f)) {
		if (strncmp(buf, "Cpus_allowed_list:", 18) == 0) {
			out = buf + 18;
			while (!out.empty() && (out.front() == '\t' || out.front() == ' ')) {
				out.erase(0, 1);
			}
			while (!out.empty() && out.back() == '\n') {
				out.pop_back();
			}
			break;
		}
	}
	fclose(f);
	return out;
}
#endif

static bool census_on = false;
static int census_done = 0;
static const uint64_t census_at_ms[] = { 20000, 120000 };

static void runtime_census(uint64_t p_now) {
	if (!census_on || census_done >= 2 || p_now < census_at_ms[census_done]) {
		return;
	}
	census_done++;
	Engine *e = Engine::get_singleton();
	String vsync = "?";
	if (DisplayServer *ds = DisplayServer::get_singleton()) {
		static const char *modes[] = { "disabled", "enabled", "adaptive", "mailbox" };
		const int m = int(ds->window_get_vsync_mode(DisplayServer::MAIN_WINDOW_ID));
		vsync = m >= 0 && m < 4 ? String(modes[m]) : itos(m);
	}
	String line = vformat("native_compat: runtime at %.0f s: render thread %s, audio %s, vsync %s, swapchain images %d, max fps %d, physics %d tps max %d steps",
			p_now / 1000.0, OS::get_singleton()->is_separate_thread_rendering_enabled() ? "separate" : "main",
			AudioServer::get_singleton() ? AudioServer::get_singleton()->get_driver_name() : String("?"), vsync,
			int(GLOBAL_GET("rendering/rendering_device/vsync/swapchain_image_count")), e->get_max_fps(),
			e->get_physics_ticks_per_second(), e->get_max_physics_steps_per_frame());
#ifdef __linux__
	// Threads by name (digits dropped, so pools count as one), with the CPU sets they may run on.
	std::map<std::string, std::map<std::string, int>> by_name;
	int total = 0;
	for (long tid : thread_ids()) {
		std::string comm = thread_comm(tid);
		std::string key;
		for (char c : comm) {
			if (c < '0' || c > '9') {
				key += c;
			}
		}
		by_name[key.empty() ? "?" : key][thread_cpus_allowed(tid)]++;
		total++;
	}
	line += vformat("; %d threads:", total);
	for (const auto &n : by_name) {
		for (const auto &c : n.second) {
			line += vformat(" '%s' x%d [%s]", n.first.c_str(), c.second, c.first.c_str());
		}
	}
#endif
	print_line(line);
}

static void engine_cpu_frame() {
	if (!census_on) {
		return;
	}
	const uint64_t now = OS::get_singleton()->get_ticks_msec();
#ifdef __linux__
	threads_place(now);
#endif
	runtime_census(now);
}

static FmodServer *fmod_server = nullptr;

// The sampling profiler (prof/prof.h), when DROIDDECK_NATIVE_PROF asks for it: reports go to the log each window.
static void prof_print(const std::vector<std::string> &p_lines) {
	for (const std::string &line : p_lines) {
		print_line(String::utf8(line.c_str()));
	}
}

// The render census (render_census.h), when DROIDDECK_NATIVE_RENDER_CENSUS asks for it. It walks the scene tree,
// so it only runs on the thread that started the engine (the scene tree's thread; under a .NET host it need not
// carry Godot's main-thread id).
static Thread::ID census_thread = 0;

static void census_frame() {
	if (!droiddeck_census::active() || Thread::get_caller_id() != census_thread) {
		return;
	}
	const std::string line = droiddeck_census::tick();
	if (!line.empty()) {
		print_line(String::utf8(line.c_str()));
	}
}

// The boot window: from engine start until the game logs that its main menu is up ("[Startup] Time to main
// menu"), or DROIDDECK_NATIVE_BOOT_MAX_S (default 30) seconds after start, whichever is first. Unset, nothing
// changes. Within it:
//   DROIDDECK_NATIVE_BOOT_TIME_SCALE=<x>  game time runs x times faster (timed intros, tweens and timers finish
//                                         x times sooner; loading that blocks doesn't move)
//   DROIDDECK_NATIVE_BOOT_MAX_FPS=<n>     frames are capped at n, so the splash leaves the CPU to the loader
// At its end each goes back to what it was, unless the game has set its own meanwhile (then it stays).
static double boot_time_scale = 0;
static int boot_max_fps = 0, boot_max_fps_before = 0;
static uint64_t boot_until_ms = 0;
static std::atomic<bool> boot_on{ false };

static void boot_window_end(const char *p_why) {
	if (!boot_on.exchange(false)) {
		return;
	}
	Engine *e = Engine::get_singleton();
	String kept;
	if (boot_time_scale > 0) {
		if (e->get_time_scale() == boot_time_scale) {
			e->set_time_scale(1.0);
		} else {
			kept += " time scale";
		}
	}
	if (boot_max_fps > 0) {
		if (e->get_max_fps() == boot_max_fps) {
			e->set_max_fps(boot_max_fps_before);
		} else {
			kept += " max fps";
		}
	}
	print_line(vformat("native_compat: boot window over at %.1f s (%s)%s", OS::get_singleton()->get_ticks_msec() / 1000.0, p_why,
			kept.is_empty() ? String() : "; the game had set its own" + kept + ", left as is"));
}

// Sees every log line (the game's GD.Print included) and ends the boot window at the main-menu line.
class BootWindowLogger : public Logger {
public:
	virtual void logv(const char *p_format, va_list p_list, bool p_err) override {
		if (p_err || !boot_on.load(std::memory_order_relaxed)) {
			return;
		}
		char buf[256];
		va_list copy;
		va_copy(copy, p_list);
		vsnprintf(buf, sizeof(buf), p_format, copy);
		va_end(copy);
		if (strstr(buf, "[Startup] Time to main menu")) {
			boot_window_end("main menu");
		}
	}
};

static void boot_window_start() {
	const char *ts = getenv("DROIDDECK_NATIVE_BOOT_TIME_SCALE");
	const char *fps = getenv("DROIDDECK_NATIVE_BOOT_MAX_FPS");
	if (ts && *ts) {
		const double x = atof(ts);
		if (x > 1.0 && x <= 100.0) {
			boot_time_scale = x;
		} else {
			print_line(vformat("native_compat: DROIDDECK_NATIVE_BOOT_TIME_SCALE=%s ignored (more than 1, at most 100)", ts));
		}
	}
	if (fps && *fps) {
		const int n = atoi(fps);
		if (n >= 5 && n <= 1000) {
			boot_max_fps = n;
		} else {
			print_line(vformat("native_compat: DROIDDECK_NATIVE_BOOT_MAX_FPS=%s ignored (5 to 1000)", fps));
		}
	}
	if (boot_time_scale <= 0 && boot_max_fps <= 0) {
		return;
	}
	const char *m = getenv("DROIDDECK_NATIVE_BOOT_MAX_S");
	const double max_s = m && atof(m) > 0 ? atof(m) : 30.0;
	boot_until_ms = OS::get_singleton()->get_ticks_msec() + uint64_t(max_s * 1000.0);
	Engine *e = Engine::get_singleton();
	if (boot_time_scale > 0) {
		e->set_time_scale(boot_time_scale);
	}
	if (boot_max_fps > 0) {
		boot_max_fps_before = e->get_max_fps();
		e->set_max_fps(boot_max_fps);
	}
	boot_on.store(true);
	OS::get_singleton()->add_logger(memnew(BootWindowLogger));
	print_line(vformat("native_compat: boot window until the main menu (at most %.1f s): time scale %.1f, max fps %d", max_s,
			boot_time_scale > 0 ? boot_time_scale : 1.0, boot_max_fps));
}

static void boot_window_frame() {
	if (boot_on.load(std::memory_order_relaxed)) {
		if (OS::get_singleton()->get_ticks_msec() >= boot_until_ms) {
			boot_window_end("time cap");
		}
	}
}

// Once a frame for both: the scene tree's process_frame once the tree exists (it runs headless too, where the
// rendering server sends no frame signals), the rendering server's frame_pre_draw until then.
static bool frame_from_tree = false;

static void each_frame() {
	boot_window_frame();
	engine_cpu_frame();
	prof_print(droiddeck_prof::tick());
	census_frame();
}

static void each_frame_from_rs() {
	if (!frame_from_tree) {
		each_frame();
	}
}

static void hook_tree() {
	if (SceneTree *st = Object::cast_to<SceneTree>(OS::get_singleton()->get_main_loop())) {
		st->connect(SNAME("process_frame"), callable_mp_static(&each_frame));
		frame_from_tree = true;
	}
}

static void frame_hooks_start() {
	const std::string prof = droiddeck_prof::start_from_env();
	if (!prof.empty()) {
		print_line(String::utf8(prof.c_str()));
	}
	droiddeck_prof::set_sink(prof_print);
	const std::string census = droiddeck_census::start_from_env();
	if (!census.empty()) {
		print_line(String::utf8(census.c_str()));
		census_thread = Thread::get_caller_id();
	}
	boot_window_start();
	census_on = engine_cpu_census_on();
#ifdef __linux__
	thread_rules_parse();
#endif
	if (!droiddeck_prof::active() && !droiddeck_census::active() && !boot_on.load() && !census_on) {
		return;
	}
	if (RenderingServer *rs = RenderingServer::get_singleton()) {
		rs->connect(SNAME("frame_pre_draw"), callable_mp_static(&each_frame_from_rs));
	}
	// The first message-queue flush comes in the main loop's first iteration, after the tree is made.
	if (MessageQueue::get_singleton()) {
		MessageQueue::get_singleton()->push_callable(callable_mp_static(&hook_tree));
	}
}

void initialize_native_compat_module(ModuleInitializationLevel p_level) {
	if (p_level == MODULE_INITIALIZATION_LEVEL_SERVERS) {
		engine_cpu_servers(); // before the display server makes the swapchain
	}
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}
#ifdef __linux__
	apply_main_thread_hints();
#endif
	engine_cpu_scene();
	frame_hooks_start();
	GDREGISTER_CLASS(NativeSkeleton2D);
	GDREGISTER_CLASS(NativeSoundBank);

	// Under the names the game's scenes and code use (see spine_compat.h).
	GDREGISTER_CLASS(SpineAtlasResource);
	GDREGISTER_CLASS(SpineSkeletonFileResource);
	GDREGISTER_CLASS(SpineAnimationMix);
	GDREGISTER_CLASS(SpineSkeletonDataResource);
	GDREGISTER_CLASS(SpineAnimation);
	GDREGISTER_CLASS(SpineBoneData);
	GDREGISTER_CLASS(SpineSlotData);
	GDREGISTER_CLASS(SpineSkin);
	GDREGISTER_CLASS(SpineEventData);
	GDREGISTER_CLASS(SpineEvent);
	GDREGISTER_CLASS(SpineTrackEntry);
	GDREGISTER_CLASS(SpineAnimationState);
	GDREGISTER_CLASS(SpineBone);
	GDREGISTER_CLASS(SpineSlot);
	GDREGISTER_CLASS(SpineSkeleton);
	GDREGISTER_CLASS(SpineSprite);
	GDREGISTER_CLASS(SpineBoneNode);
	GDREGISTER_CLASS(SpineSlotNode);
	GDREGISTER_CLASS(SpineMesh2D);
	GDREGISTER_CLASS(SpineResourceLoader);
	// Under the names of the game's FMOD plugin (see fmod_compat.h).
	GDREGISTER_CLASS(FmodParameterDescription);
	GDREGISTER_CLASS(FmodEvent);
	GDREGISTER_CLASS(FmodEventDescription);
	GDREGISTER_CLASS(FmodBus);
	GDREGISTER_CLASS(FmodVCA);
	GDREGISTER_CLASS(FmodBank);
	GDREGISTER_CLASS(FmodPerformanceData);
	GDREGISTER_CLASS(FmodServer);
	GDREGISTER_CLASS(FmodEventEmitter2D);
	GDREGISTER_CLASS(FmodEventEmitter3D);
	GDREGISTER_CLASS(FmodListener2D);
	GDREGISTER_CLASS(FmodListener3D);
	GDREGISTER_CLASS(FmodBankLoader);
	fmod_server = memnew(FmodServer);
	Engine::get_singleton()->add_singleton(Engine::Singleton("FmodServer", fmod_server));
	spine_loader.instantiate();
	ResourceLoader::add_resource_format_loader(spine_loader);
	print_verbose("native_compat: module loaded (NativeSkeleton2D, NativeSoundBank, Spine- and FMOD-compatible classes)");
}

void uninitialize_native_compat_module(ModuleInitializationLevel p_level) {
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}
	if (spine_loader.is_valid()) {
		ResourceLoader::remove_resource_format_loader(spine_loader);
		spine_loader.unref();
	}
	prof_print(droiddeck_prof::report_now());
	// One line each for the session log: how the game's skeletons and sound calls fared.
	print_line(vformat("native_compat: spine skeletons loaded=%d failed=%d sprites=%d", spine_compat_stats.loaded,
			spine_compat_stats.failed, spine_compat_stats.sprites));
	if (fmod_server) {
		print_line("native_compat: fmod " + JSON::stringify(fmod_server->get_compat_stats()));
		Engine::get_singleton()->remove_singleton("FmodServer");
		memdelete(fmod_server);
		fmod_server = nullptr;
	}
}
