#include "prof.h"

#if defined(__linux__) && (defined(__aarch64__) || defined(__x86_64__))
#define DROIDDECK_PROF_SUPPORTED 1
#endif

#ifdef DROIDDECK_PROF_SUPPORTED

#include <algorithm>
#include <atomic>
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
#include <pthread.h>
#include <signal.h>
#include <sys/syscall.h>
#include <ucontext.h>
#include <unistd.h>
#include <unordered_map>

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

double now_sec() {
	timespec ts;
	clock_gettime(CLOCK_MONOTONIC, &ts);
	return double(ts.tv_sec) + double(ts.tv_nsec) * 1e-9;
}

bool on_stack(uintptr_t p) {
	return p >= stack_lo && p + 16 <= stack_hi && (p & 7) == 0;
}

void on_prof(int, siginfo_t *, void *p_uc) {
	const int saved = errno;
	const ucontext_t *uc = static_cast<const ucontext_t *>(p_uc);
	Sample s = {};
#if defined(__aarch64__)
	s.pc = uintptr_t(uc->uc_mcontext.pc);
	s.lr = uintptr_t(uc->uc_mcontext.regs[30]);
	uintptr_t fp = uintptr_t(uc->uc_mcontext.regs[29]);
#else
	s.pc = uintptr_t(uc->uc_mcontext.gregs[REG_RIP]);
	uintptr_t fp = uintptr_t(uc->uc_mcontext.gregs[REG_RBP]);
#endif
	// Only frames that sit on the sampled thread's stack, each above the last, are read.
	for (int i = 0; i < 3 && on_stack(fp); i++) {
		const uintptr_t *frame = reinterpret_cast<const uintptr_t *>(fp);
		s.ret[i] = frame[1];
		const uintptr_t next = frame[0];
		if (next <= fp) {
			break;
		}
		fp = next;
	}
	const uint32_t at = head.load(std::memory_order_relaxed);
	ring[at % RING] = s;
	head.store(at + 1, std::memory_order_release);
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
void load_syms(const std::string &p_path) {
	const std::string path = p_path.empty() ? exe_dir() + "/godot.syms" : p_path;
	const std::string own = build_id_of_exe();
	FILE *f = fopen(path.c_str(), "r");
	if (!f) {
		syms_note = "no symbol map at " + path;
		return;
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
					return;
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
		if (path.empty() || path[0] == '[') {
			m.name = path.empty() || path.rfind("[anon", 0) == 0 ? "[anon exec]" : path;
		} else {
			const size_t slash = path.rfind('/');
			m.name = slash == std::string::npos ? path : path.substr(slash + 1);
		}
		maps.push_back(m);
	}
	fclose(f);
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
	if (m->name != "[anon exec]") {
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
	running = true;
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
	return start(atoi(hz), w ? atof(w) : 10.0, syms ? syms : "");
}

bool active() {
	return running;
}

std::vector<std::string> report_now() {
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
	lines.push_back(std::string(head_line) + " | modules: " + top(modules, n, 10));
	lines.push_back("native_compat: prof self: " + top(self, n, 30));
	lines.push_back("native_compat: prof inclusive: " + top(incl, n, 30));
	if (!lib_callers.empty()) {
		lines.push_back("native_compat: prof library time by engine caller: " + top(lib_callers, n, 15));
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
	threads_before.swap(threads_now);
	process_ticks_before = proc_now;
	return lines;
}

std::vector<std::string> tick() {
	if (!running || pid_t(syscall(SYS_gettid)) != sampled_tid || now_sec() - window_start < window_sec) {
		return {};
	}
	return report_now();
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
} // namespace droiddeck_prof

#endif
