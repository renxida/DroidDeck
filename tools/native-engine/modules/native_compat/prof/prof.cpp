#include "prof.h"

#if defined(__linux__) && (defined(__aarch64__) || defined(__x86_64__))
#define DROIDDECK_PROF_SUPPORTED 1
#endif

#ifdef DROIDDECK_PROF_SUPPORTED

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <cxxabi.h>
#include <dirent.h>
#include <dlfcn.h>
#include <link.h>
#include <map>
#include <mutex>
#include <pthread.h>
#include <signal.h>
#include <sys/syscall.h>
#include <thread>
#include <ucontext.h>
#include <unistd.h>
#include <unordered_map>
#include <vector>

#ifndef sigev_notify_thread_id
#define sigev_notify_thread_id _sigev_un._tid
#endif

namespace droiddeck_prof {
namespace {

// What the signal handler records: the interrupted pc, the link register (the caller, even in a leaf that saved
// no frame), and up to three return addresses from the frame-pointer chain.
struct Sample {
	uintptr_t pc, lr, ret[3];
};

constexpr uint32_t RING = 1u << 15;
Sample ring[RING];
std::atomic<uint32_t> head { 0 };
uint32_t tail = 0;

uintptr_t stack_lo = 0, stack_hi = 0;
bool running = false;
timer_t timer;
pid_t sampled_tid = 0;
int rate_hz = 0;
double window_sec = 10;
double window_start = 0;

// Reports normally come from tick(), once a frame. A main thread that makes no frame for a whole window (a
// blocking load at boot) would report nothing until its next frame, so a side thread writes the report then,
// through the sink, and says how long the main thread has gone without a frame. The lock keeps tick() and
// that thread from reading the sample ring or the frame lists at once.
std::mutex report_lock;
std::atomic<double> last_tick_wall { 0 };
void (*report_sink)(const std::vector<std::string> &) = nullptr;

// Frame timing, from tick() (called once a frame): wall and main-thread CPU per frame, and the samples that fell
// in each frame at or over the hitch threshold, so a slow frame can be told apart as CPU work (and whose) or a
// wait (little CPU, few samples).
double hitch_ms = 33;
double last_frame_wall = 0, last_frame_cpu = 0;
uint32_t frame_mark = 0; // ring position at the last frame's end
std::vector<float> frame_wall_ms, frame_cpu_ms;
struct Hitch {
	float wall_ms, cpu_ms;
	std::vector<uintptr_t> pcs; // leaf pcs of the samples inside the frame
};
std::vector<Hitch> hitches;
uint32_t hitch_count = 0;

double thread_cpu_sec() {
	timespec ts;
	clock_gettime(CLOCK_THREAD_CPUTIME_ID, &ts);
	return double(ts.tv_sec) + double(ts.tv_nsec) * 1e-9;
}

double now_sec() {
	timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return double(ts.tv_sec) + double(ts.tv_nsec) * 1e-9;
}

bool on_stack(uintptr_t p) {
	return p >= stack_lo && p + 16 <= stack_hi && (p & 7) == 0;
}

// DROIDDECK_NATIVE_PROF_OTHER=1: also sample the busiest other thread of the last window (a loader, say), on its
// own CPU clock, into its own ring. Its stack bounds aren't known, so its frame chain is read only within 1 MiB
// above the interrupted stack pointer.
constexpr uint32_t OTHER_RING = 1u << 14;
Sample other_ring[OTHER_RING];
std::atomic<uint32_t> other_head { 0 };
uint32_t other_tail = 0;
bool other_on = false;
pid_t other_tid = 0;
std::string other_name;
timer_t other_timer;
bool other_timer_set = false;
pid_t side_tid = 0; // the stall-report thread, never chosen

void on_prof(int, siginfo_t *, void *p_uc) {
	const int saved = errno;
	const ucontext_t *uc = static_cast<const ucontext_t *>(p_uc);
	const bool main = pid_t(syscall(SYS_gettid)) == sampled_tid;
	Sample s = {};
#if defined(__aarch64__)
	s.pc = uintptr_t(uc->uc_mcontext.pc);
	s.lr = uintptr_t(uc->uc_mcontext.regs[30]);
	uintptr_t fp = uintptr_t(uc->uc_mcontext.regs[29]);
	const uintptr_t sp = uintptr_t(uc->uc_mcontext.sp);
#else
	s.pc = uintptr_t(uc->uc_mcontext.gregs[REG_RIP]);
	uintptr_t fp = uintptr_t(uc->uc_mcontext.gregs[REG_RBP]);
	const uintptr_t sp = uintptr_t(uc->uc_mcontext.gregs[REG_RSP]);
#endif
	auto inside = [&](uintptr_t p) {
		return main ? on_stack(p) : (p >= sp && p + 16 <= sp + (1u << 20) && (p & 7) == 0);
	};
	// Only frames that sit on the sampled thread's stack, each above the last, are read.
	for (int i = 0; i < 3 && inside(fp); i++) {
		const uintptr_t *frame = reinterpret_cast<const uintptr_t *>(fp);
		s.ret[i] = frame[1];
		const uintptr_t next = frame[0];
		if (next <= fp) {
			break;
		}
		fp = next;
	}
	if (main) {
		const uint32_t at = head.load(std::memory_order_relaxed);
		ring[at % RING] = s;
		head.store(at + 1, std::memory_order_release);
	} else {
		const uint32_t at = other_head.load(std::memory_order_relaxed);
		other_ring[at % OTHER_RING] = s;
		other_head.store(at + 1, std::memory_order_release);
	}
	errno = saved;
}

// --- Symbols --------------------------------------------------------------------------------------------

struct Sym {
	uintptr_t addr, size;
	std::string name;
};
std::vector<Sym> exe_syms;
uintptr_t exe_bias = 0;
std::string syms_note;

std::string build_id_of_exe() {
	std::string id;
	dl_iterate_phdr([](dl_phdr_info *info, size_t, void *p_out) -> int {
		// The first object is the executable.
		std::string &out = *static_cast<std::string *>(p_out);
		exe_bias = info->dlpi_addr;
		for (int i = 0; i < info->dlpi_phnum; i++) {
			const ElfW(Phdr) &ph = info->dlpi_phdr[i];
			if (ph.p_type != PT_NOTE) {
				continue;
			}
			const uint8_t *p = reinterpret_cast<const uint8_t *>(info->dlpi_addr + ph.p_vaddr);
			const uint8_t *end = p + ph.p_memsz;
			while (p + 12 <= end) {
				uint32_t namesz, descsz, type;
				memcpy(&namesz, p, 4);
				memcpy(&descsz, p + 4, 4);
				memcpy(&type, p + 8, 4);
				const uint8_t *name = p + 12, *desc = name + ((namesz + 3) & ~3u);
				if (desc + descsz > end) {
					break;
				}
				if (type == 3 && namesz == 4 && memcmp(name, "GNU", 4) == 0) {
					static const char *hex = "0123456789abcdef";
					for (uint32_t k = 0; k < descsz; k++) {
						out += hex[desc[k] >> 4];
						out += hex[desc[k] & 15];
					}
					return 1;
				}
				p = desc + ((descsz + 3) & ~3u);
			}
		}
		return 1;
	}, &id);
	return id;
}

std::string exe_dir() {
	char buf[4096];
	const ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
	if (n <= 0) {
		return ".";
	}
	buf[n] = 0;
	std::string p(buf);
	const size_t slash = p.rfind('/');
	return slash == std::string::npos ? std::string(".") : p.substr(0, slash);
}

// "nm -C -n -S --defined-only" lines: address, optional size, type, name. The first line may be
// "# buildid <hex>".
bool load_syms_from(const std::string &path, const std::string &own) {
	FILE *f = fopen(path.c_str(), "r");
	if (!f) {
		syms_note = "no symbol map at " + path;
		return false;
	}
	char line[8192];
	bool first = true;
	while (fgets(line, sizeof(line), f)) {
		size_t len = strlen(line);
		while (len && (line[len - 1] == '\n' || line[len - 1] == '\r')) {
			line[--len] = 0;
		}
		if (first) {
			first = false;
			if (strncmp(line, "# buildid ", 10) == 0) {
				if (!own.empty() && own != line + 10) {
					syms_note = "symbol map " + path + " is for build " + std::string(line + 10) + ", not " + own;
					fclose(f);
					return false;
				}
				continue;
			}
		}
		char *p = line, *end = nullptr;
		const unsigned long long addr = strtoull(p, &end, 16);
		if (end == p || *end != ' ') {
			continue;
		}
		p = end + 1;
		unsigned long long size = 0;
		// Size is there when the next field is hex followed by a space and a one-letter type.
		char *e2 = nullptr;
		const unsigned long long maybe = strtoull(p, &e2, 16);
		if (e2 != p && e2[0] == ' ' && e2[1] && e2[2] == ' ') {
			size = maybe;
			p = e2 + 1;
		}
		if (!p[0] || p[1] != ' ') {
			continue;
		}
		const char type = p[0];
		if (type != 't' && type != 'T' && type != 'w' && type != 'W') {
			continue;
		}
		std::string name(p + 2);
		// Parameters make lines long and say little here.
		int depth = 0;
		for (size_t i = 0; i < name.size(); i++) {
			if (name[i] == '<') {
				depth++;
			} else if (name[i] == '>') {
				depth--;
			} else if (name[i] == '(' && depth == 0 && i > 0) {
				name.resize(i);
				break;
			}
		}
		if (name.size() > 100) {
			name.resize(100);
		}
		exe_syms.push_back({ uintptr_t(addr), uintptr_t(size), name });
	}
	fclose(f);
	std::sort(exe_syms.begin(), exe_syms.end(), [](const Sym &a, const Sym &b) { return a.addr < b.addr; });
	syms_note = std::to_string(exe_syms.size()) + " engine symbols";
	return true;
}

// The map named by DROIDDECK_NATIVE_PROF_SYMS, else godot.syms beside the executable, else one in a downloaded
// component (the launcher hardlinks the engine out of ~/.cache/droiddeck/native/<tag>/ under the game's name)
// whose build id is this executable's.
void load_syms(const std::string &p_path) {
	const std::string own = build_id_of_exe();
	if (!p_path.empty()) {
		load_syms_from(p_path, own);
		return;
	}
	if (load_syms_from(exe_dir() + "/godot.syms", own)) {
		return;
	}
	const std::string first_note = syms_note;
	const char *home = getenv("HOME");
	const std::string cache = std::string(home ? home : "") + "/.cache/droiddeck/native";
	if (!own.empty()) {
		if (DIR *d = opendir(cache.c_str())) {
			while (dirent *e = readdir(d)) {
				if (e->d_name[0] == '.') {
					continue;
				}
				const std::string path = cache + "/" + e->d_name + "/godot.syms";
				FILE *f = fopen(path.c_str(), "r");
				if (!f) {
					continue;
				}
				char head[128] = {};
				const bool match = fgets(head, sizeof(head), f) && strncmp(head, "# buildid ", 10) == 0 &&
						strncmp(head + 10, own.c_str(), own.size()) == 0;
				fclose(f);
				if (match && load_syms_from(path, own)) {
					closedir(d);
					return;
				}
			}
			closedir(d);
		}
	}
	syms_note = first_note + "; none for build " + (own.empty() ? std::string("?") : own) + " in " + cache;
}



struct Map {
	uintptr_t start, end, offset;
	std::string name; // basename, or [anon exec] for anonymous code (the JIT's)
	bool exe;
};
std::vector<Map> maps;
std::string exe_path;

void load_maps() {
	maps.clear();
	if (exe_path.empty()) {
		char buf[4096];
		const ssize_t n = readlink("/proc/self/exe", buf, sizeof(buf) - 1);
		exe_path = n > 0 ? std::string(buf, size_t(n)) : std::string();
	}
	FILE *f = fopen("/proc/self/maps", "r");
	if (!f) {
		return;
	}
	char line[4096];
	while (fgets(line, sizeof(line), f)) {
		unsigned long long s, e, off;
		char perm[8] = {};
		int name_at = 0;
		if (sscanf(line, "%llx-%llx %7s %llx %*s %*s %n", &s, &e, perm, &off, &name_at) < 4 || perm[2] != 'x') {
			continue;
		}
		std::string path = name_at > 0 ? std::string(line + name_at) : std::string();
		while (!path.empty() && (path.back() == '\n' || path.back() == ' ')) {
			path.pop_back();
		}
		Map m { uintptr_t(s), uintptr_t(e), uintptr_t(off), std::string(), !exe_path.empty() && path == exe_path };
		if (path.rfind("/memfd:doublemapper", 0) == 0 || path.rfind("memfd:doublemapper", 0) == 0) {
			m.name = "[managed jit]"; // the .NET runtime's W^X double mapping of JIT code
		} else if (path.empty() || path[0] == '[') {
			m.name = path.empty() || path.rfind("[anon", 0) == 0 ? "[anon exec]" : path;
		} else {
			const size_t slash = path.rfind('/');
			m.name = slash == std::string::npos ? path : path.substr(slash + 1);
		}
		maps.push_back(m);
	}
	fclose(f);
}

// Names for JIT'd .NET code, from the runtime's perf map (DOTNET_PerfMapEnabled=3 writes
// <DOTNET_PerfMapJitDumpPath or /tmp>/perf-<pid>.map: "start size name" per method). Read as it grows.
std::map<uintptr_t, std::pair<uintptr_t, std::string>> jit_syms; // start -> (size, name)
long jit_map_offset = 0;
std::string jit_map_path;

void load_jit_map() {
	if (jit_map_path.empty()) {
		const char *dir = getenv("DOTNET_PerfMapJitDumpPath");
		jit_map_path = std::string(dir && *dir ? dir : "/tmp") + "/perf-" + std::to_string(getpid()) + ".map";
	}
	FILE *f = fopen(jit_map_path.c_str(), "r");
	if (!f) {
		return;
	}
	if (fseek(f, jit_map_offset, SEEK_SET) == 0) {
		char line[1024];
		long good = jit_map_offset;
		while (fgets(line, sizeof(line), f)) {
			const size_t len = strlen(line);
			if (len == 0 || line[len - 1] != '\n') {
				break; // a line still being written: read it next time
			}
			good = ftell(f);
			unsigned long long a = 0, n = 0;
			int at = 0;
			if (sscanf(line, "%llx %llx %n", &a, &n, &at) >= 2 && at > 0) {
				std::string name(line + at, len - size_t(at) - 1);
				const size_t paren = name.find('(');
				if (paren != std::string::npos) {
					name.resize(paren);
				}
				if (name.size() > 100) {
					name.resize(100);
				}
				jit_syms[uintptr_t(a)] = std::make_pair(uintptr_t(n), name);
			}
		}
		jit_map_offset = good;
	}
	fclose(f);
}

const char *jit_name(uintptr_t pc) {
	auto it = jit_syms.upper_bound(pc);
	if (it == jit_syms.begin()) {
		return nullptr;
	}
	--it;
	return pc < it->first + it->second.first ? it->second.second.c_str() : nullptr;
}

const Map *map_of(uintptr_t pc) {
	auto it = std::upper_bound(maps.begin(), maps.end(), pc, [](uintptr_t v, const Map &m) { return v < m.start; });
	if (it == maps.begin()) {
		return nullptr;
	}
	--it;
	return pc < it->end ? &*it : nullptr;
}

// A readable name for the code at pc: an engine function, an exported library symbol, or the library alone.
std::string where(uintptr_t pc, std::string *r_module) {
	const Map *m = map_of(pc);
	if (!m) {
		if (r_module) {
			*r_module = "[unmapped]";
		}
		return "[unmapped]";
	}
	if (r_module) {
		*r_module = m->name;
	}
	if (m->exe) {
		const uintptr_t v = pc - exe_bias;
		auto it = std::upper_bound(exe_syms.begin(), exe_syms.end(), v, [](uintptr_t x, const Sym &s) { return x < s.addr; });
		if (it != exe_syms.begin()) {
			--it;
			if (it->size == 0 || v < it->addr + it->size) {
				return it->name;
			}
		}
		return m->name;
	}
	if (m->name == "[anon exec]" || m->name == "[managed jit]") {
		if (const char *jn = jit_name(pc)) {
			return std::string("jit:") + jn;
		}
	}
	if (m->name != "[anon exec]" && m->name != "[managed jit]") {
		Dl_info info;
		if (dladdr(reinterpret_cast<void *>(pc), &info) && info.dli_sname) {
			int status = 0;
			char *d = abi::__cxa_demangle(info.dli_sname, nullptr, nullptr, &status);
			std::string n = m->name + ":" + (status == 0 && d ? d : info.dli_sname);
			free(d);
			const size_t paren = n.find('(');
			if (paren != std::string::npos) {
				n.resize(paren);
			}
			return n.size() > 100 ? n.substr(0, 100) : n;
		}
	}
	return m->name;
}

// --- Threads --------------------------------------------------------------------------------------------

struct ThreadCpu {
	std::string comm;
	uint64_t ticks = 0;
};
std::map<int, ThreadCpu> threads_before;
uint64_t process_ticks_before = 0;

bool read_stat(const std::string &p_path, std::string &r_comm, uint64_t &r_ticks) {
	FILE *f = fopen(p_path.c_str(), "r");
	if (!f) {
		return false;
	}
	char buf[1024];
	const size_t n = fread(buf, 1, sizeof(buf) - 1, f);
	fclose(f);
	buf[n] = 0;
	char *open = strchr(buf, '('), *close = strrchr(buf, ')');
	if (!open || !close || close < open) {
		return false;
	}
	r_comm.assign(open + 1, size_t(close - open - 1));
	// Fields after the name: state is field 3; utime and stime are fields 14 and 15.
	unsigned long long ut = 0, st = 0;
	if (sscanf(close + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &ut, &st) != 2) {
		return false;
	}
	r_ticks = ut + st;
	return true;
}

std::map<int, ThreadCpu> read_threads(uint64_t &r_process) {
	std::map<int, ThreadCpu> out;
	std::string comm;
	read_stat("/proc/self/stat", comm, r_process);
	if (DIR *d = opendir("/proc/self/task")) {
		while (dirent *e = readdir(d)) {
			const int tid = atoi(e->d_name);
			if (tid <= 0) {
				continue;
			}
			ThreadCpu t;
			if (read_stat(std::string("/proc/self/task/") + e->d_name + "/stat", t.comm, t.ticks)) {
				out[tid] = t;
			}
		}
		closedir(d);
	}
	return out;
}

std::string pct(int64_t n, int64_t total) {
	char b[32];
	snprintf(b, sizeof(b), "%.1f%%", total ? 100.0 * double(n) / double(total) : 0.0);
	return b;
}

std::string top(const std::unordered_map<std::string, int64_t> &p_counts, int64_t p_total, size_t p_n) {
	std::vector<std::pair<std::string, int64_t>> v(p_counts.begin(), p_counts.end());
	std::sort(v.begin(), v.end(), [](const auto &a, const auto &b) { return a.second != b.second ? a.second > b.second : a.first < b.first; });
	std::string s;
	for (size_t i = 0; i < v.size() && i < p_n; i++) {
		s += (i ? "; " : "") + pct(v[i].second, p_total) + " " + v[i].first;
	}
	return s;
}

} // namespace

static void watch_stalls();

std::string start(int p_hz, double p_window_sec, const std::string &p_syms) {
	if (running) {
		return "native_compat: prof already running";
	}
	if (p_hz <= 0 || p_hz > 10000) {
		return "native_compat: prof rate " + std::to_string(p_hz) + " out of range 1..10000; off";
	}
	pthread_attr_t attr;
	if (pthread_getattr_np(pthread_self(), &attr) == 0) {
		void *base = nullptr;
		size_t size = 0;
		if (pthread_attr_getstack(&attr, &base, &size) == 0) {
			stack_lo = uintptr_t(base);
			stack_hi = uintptr_t(base) + size;
		}
		pthread_attr_destroy(&attr);
	}
	load_syms(p_syms);
	const char *other = getenv("DROIDDECK_NATIVE_PROF_OTHER");
	other_on = other && other[0] == '1';
	struct sigaction sa = {};
	sa.sa_sigaction = on_prof;
	sa.sa_flags = SA_SIGINFO | SA_RESTART;
	sigemptyset(&sa.sa_mask);
	if (sigaction(SIGPROF, &sa, nullptr) != 0) {
		return "native_compat: prof sigaction refused (errno " + std::to_string(errno) + "); off";
	}
	clockid_t cid;
	if (pthread_getcpuclockid(pthread_self(), &cid) != 0) {
		return "native_compat: prof no thread cpu clock; off";
	}
	sampled_tid = pid_t(syscall(SYS_gettid));
	sigevent sev = {};
	sev.sigev_notify = SIGEV_THREAD_ID;
	sev.sigev_signo = SIGPROF;
	sev.sigev_notify_thread_id = sampled_tid;
	if (timer_create(cid, &sev, &timer) != 0) {
		return "native_compat: prof timer_create refused (errno " + std::to_string(errno) + "); off";
	}
	const long ns = 1000000000L / p_hz;
	itimerspec its = {};
	its.it_interval.tv_sec = ns / 1000000000L;
	its.it_interval.tv_nsec = ns % 1000000000L;
	its.it_value = its.it_interval;
	if (timer_settime(timer, 0, &its, nullptr) != 0) {
		timer_delete(timer);
		return "native_compat: prof timer_settime refused (errno " + std::to_string(errno) + "); off";
	}
	rate_hz = p_hz;
	window_sec = p_window_sec > 0 ? p_window_sec : 10;
	window_start = now_sec();
	threads_before = read_threads(process_ticks_before);
	last_tick_wall.store(window_start);
	running = true;
	std::thread(watch_stalls).detach();
	char w[32];
	snprintf(w, sizeof(w), "%g", window_sec);
	return "native_compat: prof sampling the main thread at " + std::to_string(p_hz) + " Hz of its cpu time, a report every " + w +
			" s (" + syms_note + ")";
}

std::string start_from_env() {
	const char *hz = getenv("DROIDDECK_NATIVE_PROF");
	if (!hz || !*hz) {
		return std::string();
	}
	const char *w = getenv("DROIDDECK_NATIVE_PROF_WINDOW");
	const char *syms = getenv("DROIDDECK_NATIVE_PROF_SYMS");
	const char *hitch = getenv("DROIDDECK_NATIVE_PROF_HITCH_MS");
	if (hitch && atof(hitch) > 0) {
		hitch_ms = atof(hitch);
	}
	return start(atoi(hz), w ? atof(w) : 10.0, syms ? syms : "");
}

bool active() {
	return running;
}

static std::vector<std::string> report_locked(double p_stall_sec) {
	std::vector<std::string> lines;
	if (!running) {
		return lines;
	}
	const double now = now_sec();
	const double span = now - window_start;
	window_start = now;
	const uint32_t h = head.load(std::memory_order_acquire);
	uint32_t dropped = 0;
	if (h - tail > RING) {
		dropped = h - tail - RING;
		tail = h - RING;
	}
	load_maps();
	load_jit_map();
	std::unordered_map<std::string, int64_t> modules, self, incl, lib_callers;
	std::unordered_map<uintptr_t, std::pair<std::string, std::string>> cache; // pc -> (name, module)
	auto lookup = [&](uintptr_t pc) -> const std::pair<std::string, std::string> & {
		auto it = cache.find(pc);
		if (it != cache.end()) {
			return it->second;
		}
		std::string mod;
		std::string name = where(pc, &mod);
		return cache.emplace(pc, std::make_pair(name, mod)).first->second;
	};
	int64_t n = 0;
	for (; tail != h; tail++) {
		const Sample s = ring[tail % RING];
		n++;
		const auto leaf = lookup(s.pc);
		modules[leaf.second]++;
		self[leaf.first]++;
		std::vector<std::string> seen { leaf.first };
		// Time in a library or in managed code (libc, the GPU driver, the runtime, the JIT's code) is also charged
		// to its nearest caller in the engine.
		const Map *leaf_map = map_of(s.pc);
		bool charged = leaf_map && leaf_map->exe;
		for (uintptr_t a : { s.lr, s.ret[0], s.ret[1], s.ret[2] }) {
			if (!a) {
				continue;
			}
			const auto c = lookup(a > 4 ? a - 4 : a); // the call, not the instruction after it
			if (!charged) {
				const Map *cm = map_of(a);
				if (cm && cm->exe) {
					lib_callers[leaf.first + " <- " + c.first]++;
					charged = true;
				}
			}
			if (std::find(seen.begin(), seen.end(), c.first) == seen.end()) {
				seen.push_back(c.first);
			}
		}
		for (const std::string &name : seen) {
			incl[name]++;
		}
	}
	char head_line[256];
	snprintf(head_line, sizeof(head_line), "native_compat: prof %.1fs main thread %lld samples (%.0f/s of its cpu at %d Hz, %u dropped)",
			span, (long long)n, span > 0 ? double(n) / span : 0.0, rate_hz, dropped);
	if (p_stall_sec > 0) {
		char stall[128];
		snprintf(stall, sizeof(stall), "native_compat: prof no frame for %.1f s (report from a side thread)", p_stall_sec);
		lines.push_back(stall);
	}
	lines.push_back(std::string(head_line) + " | modules: " + top(modules, n, 10));
	lines.push_back("native_compat: prof self: " + top(self, n, 30));
	lines.push_back("native_compat: prof inclusive: " + top(incl, n, 30));
	if (!lib_callers.empty()) {
		lines.push_back("native_compat: prof library time by engine caller: " + top(lib_callers, n, 15));
	}
	// Frame times over the window, and the worst frames at or over the hitch threshold.
	if (!frame_wall_ms.empty()) {
		auto q = [](std::vector<float> v, double p) {
			std::sort(v.begin(), v.end());
			return double(v[std::min(v.size() - 1, size_t(p * double(v.size())))]);
		};
		char fl[320];
		snprintf(fl, sizeof(fl),
				"native_compat: prof frames %zu: wall p50 %.1f p95 %.1f p99 %.1f max %.1f ms | main cpu p50 %.1f p95 %.1f p99 %.1f max %.1f ms | %u at or over %g ms",
				frame_wall_ms.size(), q(frame_wall_ms, 0.5), q(frame_wall_ms, 0.95), q(frame_wall_ms, 0.99), q(frame_wall_ms, 1.0),
				q(frame_cpu_ms, 0.5), q(frame_cpu_ms, 0.95), q(frame_cpu_ms, 0.99), q(frame_cpu_ms, 1.0), hitch_count, hitch_ms);
		lines.push_back(fl);
		std::sort(hitches.begin(), hitches.end(), [](const Hitch &a, const Hitch &b) { return a.wall_ms > b.wall_ms; });
		std::string hl;
		for (size_t i = 0; i < hitches.size() && i < 8; i++) {
			std::unordered_map<std::string, int64_t> leafs;
			for (uintptr_t pc : hitches[i].pcs) {
				leafs[lookup(pc).first]++;
			}
			char hb[96];
			snprintf(hb, sizeof(hb), "%s%.0f ms (cpu %.0f, %zu samples): ", i ? " || " : "", hitches[i].wall_ms, hitches[i].cpu_ms,
					hitches[i].pcs.size());
			hl += hb + (leafs.empty() ? std::string("no samples") : top(leafs, int64_t(hitches[i].pcs.size()), 4));
		}
		if (!hl.empty()) {
			lines.push_back("native_compat: prof worst frames: " + hl);
		}
		frame_wall_ms.clear();
		frame_cpu_ms.clear();
		hitches.clear();
		hitch_count = 0;
	}
	// Every thread's CPU over the window: what the battery pays for.
	uint64_t proc_now = 0;
	std::map<int, ThreadCpu> threads_now = read_threads(proc_now);
	const double tick_ms = 1000.0 / double(sysconf(_SC_CLK_TCK));
	std::unordered_map<std::string, int64_t> by_thread; // per-thread ms over the window, named comm#tid
	for (const auto &t : threads_now) {
		auto b = threads_before.find(t.first);
		const uint64_t before = b != threads_before.end() ? b->second.ticks : 0;
		if (t.second.ticks > before) {
			const std::string tag = (t.first == sampled_tid ? std::string("main:") : std::string()) + t.second.comm;
			by_thread[tag] += int64_t(double(t.second.ticks - before) * tick_ms);
		}
	}
	std::vector<std::pair<std::string, int64_t>> v(by_thread.begin(), by_thread.end());
	std::sort(v.begin(), v.end(), [](const auto &a, const auto &b) { return a.second > b.second; });
	char b[160];
	snprintf(b, sizeof(b), "native_compat: prof threads %.1fs: process %.0f ms cpu per s (%zu threads)", span,
			span > 0 ? double(proc_now - process_ticks_before) * tick_ms / span : 0.0, threads_now.size());
	std::string t = b;
	for (size_t i = 0; i < v.size() && i < 12; i++) {
		snprintf(b, sizeof(b), "%s %s %.0f", i ? "," : " |", v[i].first.c_str(), span > 0 ? double(v[i].second) / span : 0.0);
		t += b;
	}
	lines.push_back(t);
	if (other_on) {
		// The busiest other thread's samples since the last report, then aim at this window's busiest.
		const uint32_t oh = other_head.load(std::memory_order_acquire);
		if (oh - other_tail > OTHER_RING) {
			other_tail = oh - OTHER_RING;
		}
		std::unordered_map<std::string, int64_t> omod, oself, oincl;
		int64_t on = 0;
		for (; other_tail != oh; other_tail++) {
			const Sample os = other_ring[other_tail % OTHER_RING];
			on++;
			const auto leaf = lookup(os.pc);
			omod[leaf.second]++;
			oself[leaf.first]++;
			std::vector<std::string> seen { leaf.first };
			for (uintptr_t a : { os.lr, os.ret[0], os.ret[1], os.ret[2] }) {
				if (a) {
					const auto c = lookup(a > 4 ? a - 4 : a);
					if (std::find(seen.begin(), seen.end(), c.first) == seen.end()) {
						seen.push_back(c.first);
					}
				}
			}
			for (const std::string &name : seen) {
				oincl[name]++;
			}
		}
		if (other_tid) {
			char ob[200];
			snprintf(ob, sizeof(ob), "native_compat: prof other thread %s#%d %lld samples (%.0f/s of its cpu)", other_name.c_str(), int(other_tid),
					(long long)on, span > 0 ? double(on) / span : 0.0);
			lines.push_back(std::string(ob) + " | modules: " + top(omod, on, 8));
			if (on) {
				lines.push_back("native_compat: prof other self: " + top(oself, on, 25));
				lines.push_back("native_compat: prof other inclusive: " + top(oincl, on, 25));
			}
		}
		pid_t busiest = 0;
		uint64_t most = 0;
		for (const auto &th : threads_now) {
			auto bt = threads_before.find(th.first);
			const uint64_t d = th.second.ticks - (bt != threads_before.end() ? std::min(bt->second.ticks, th.second.ticks) : 0);
			if (th.first != sampled_tid && th.first != side_tid && d > most) {
				most = d;
				busiest = th.first;
			}
		}
		if (busiest && busiest != other_tid) {
			if (other_timer_set) {
				timer_delete(other_timer);
				other_timer_set = false;
			}
			// The thread's own CPU clock (the kernel's per-thread scheduler clock id).
			const clockid_t cid = clockid_t((~unsigned(busiest)) << 3) | 6;
			sigevent sev = {};
			sev.sigev_notify = SIGEV_THREAD_ID;
			sev.sigev_signo = SIGPROF;
			sev.sigev_notify_thread_id = busiest;
			if (timer_create(cid, &sev, &other_timer) == 0) {
				const long ns = 1000000000L / rate_hz;
				itimerspec its = {};
				its.it_interval.tv_sec = ns / 1000000000L;
				its.it_interval.tv_nsec = ns % 1000000000L;
				its.it_value = its.it_interval;
				if (timer_settime(other_timer, 0, &its, nullptr) == 0) {
					other_timer_set = true;
					other_tid = busiest;
					other_name = threads_now[busiest].comm;
				} else {
					timer_delete(other_timer);
				}
			}
		}
	}
	threads_before.swap(threads_now);
	process_ticks_before = proc_now;
	return lines;
}

std::vector<std::string> report_now() {
	std::lock_guard<std::mutex> l(report_lock);
	return report_locked(0);
}

void set_sink(void (*p_sink)(const std::vector<std::string> &)) {
	report_sink = p_sink;
}

static void watch_stalls() {
	side_tid = pid_t(syscall(SYS_gettid));
	pthread_setname_np(pthread_self(), "dd-prof");
	for (;;) {
		std::this_thread::sleep_for(std::chrono::milliseconds(250));
		void (*sink)(const std::vector<std::string> &) = report_sink;
		if (!sink) {
			continue;
		}
		std::vector<std::string> lines;
		{
			std::lock_guard<std::mutex> l(report_lock);
			const double now = now_sec();
			const double stall = now - last_tick_wall.load();
			if (stall >= window_sec && now - window_start >= window_sec) {
				lines = report_locked(stall);
			}
		}
		if (!lines.empty()) {
			sink(lines);
		}
	}
}

std::vector<std::string> tick() {
	if (!running || pid_t(syscall(SYS_gettid)) != sampled_tid) {
		return {};
	}
	std::lock_guard<std::mutex> l(report_lock);
	const double wall = now_sec(), cpu = thread_cpu_sec();
	last_tick_wall.store(wall);
	const uint32_t h = head.load(std::memory_order_acquire);
	if (last_frame_wall > 0) {
		const float wall_ms = float((wall - last_frame_wall) * 1000.0), cpu_ms = float((cpu - last_frame_cpu) * 1000.0);
		frame_wall_ms.push_back(wall_ms);
		frame_cpu_ms.push_back(cpu_ms);
		if (wall_ms >= hitch_ms) {
			hitch_count++;
			Hitch hi { wall_ms, cpu_ms, {} };
			for (uint32_t i = (h - frame_mark > RING ? h - RING : frame_mark); i != h; i++) {
				hi.pcs.push_back(ring[i % RING].pc);
			}
			// Keep the 32 worst for the report.
			if (hitches.size() < 32) {
				hitches.push_back(std::move(hi));
			} else {
				auto least = std::min_element(hitches.begin(), hitches.end(), [](const Hitch &a, const Hitch &b) { return a.wall_ms < b.wall_ms; });
				if (least->wall_ms < hi.wall_ms) {
					*least = std::move(hi);
				}
			}
		}
	}
	last_frame_wall = wall;
	last_frame_cpu = cpu;
	frame_mark = h;
	if (wall - window_start < window_sec) {
		return {};
	}
	return report_locked(0);
}

} // namespace droiddeck_prof

#else // not supported

namespace droiddeck_prof {
std::string start_from_env() {
	return std::string();
}
std::string start(int, double, const std::string &) {
	return "native_compat: prof not supported on this platform";
}
bool active() {
	return false;
}
std::vector<std::string> tick() {
	return {};
}
std::vector<std::string> report_now() {
	return {};
}
void set_sink(void (*)(const std::vector<std::string> &)) {
}
} // namespace droiddeck_prof

#endif
