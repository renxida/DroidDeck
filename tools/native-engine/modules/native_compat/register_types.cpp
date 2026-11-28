// Kestrel native-engine compatibility module. Clean-room: no Esoteric or FMOD code.
#include "register_types.h"

#include "fmod_compat.h"
#include "native_skeleton_2d.h"
#include "native_sound_bank.h"
#include "spine_compat.h"
#include "prof/prof.h"
#include "render_census.h"

#include "core/config/engine.h"
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
#include "servers/rendering_server.h"

#ifdef __linux__
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
	if (!droiddeck_prof::active() && !droiddeck_census::active() && !boot_on.load()) {
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
	if (p_level != MODULE_INITIALIZATION_LEVEL_SCENE) {
		return;
	}
#ifdef __linux__
	apply_main_thread_hints();
#endif
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
