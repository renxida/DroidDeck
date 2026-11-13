// SPDX-License-Identifier: MIT
// fex-stats: sample FEX's live per-thread statistics (FEX_PROFILESTATS=1) from /dev/shm/fex-<pid>-stats.
// Works for Linux FEX (FEXInterpreter) and for the FEX inside Wine (ARM64EC/WOW64, written by its unixlib).
//
//   fex-stats [-i MS] [-s] [-p PID]...      default: every /dev/shm/fex-*-stats, 1000 ms, CSV per sample
//     -s   summary only: wait until every watched process is gone, then print one total line per pid
//
// CSV: t_s,pid,threads,jit_ms,signal_ms,sigbus,smc,float_fallback,cache_miss,cache_rlock_ms,cache_wlock_ms,
//      jit_blocks,disk_hit,disk_miss,disk_lookup_ms
// Counts are cumulative since the process started; threads that exited keep counting in their pid's total.
// Layout mirrors FEXCore/include/FEXCore/Utils/SHMStats.h, STATS_VERSION 2.
#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

struct Header {
  uint8_t Version, AppType;
  uint16_t ThreadStatsSize;
  char FexVersion[48];
  uint32_t Head, Size, Pad;
};
enum { NFIELDS = 12 };
struct Stats {
  uint32_t Next, TID;
  uint64_t f[NFIELDS + 1]; // JITTime, SignalTime, SIGBUS, SMC, FloatFallback, CacheMiss, CacheReadLock, CacheWriteLock,
                           // JITCount, DiskHit, DiskMiss, DiskLookupTime, Padding
};
static const int IS_TIME[NFIELDS] = {1, 1, 0, 0, 0, 0, 1, 1, 0, 0, 0, 1};

#define MAXT 4096
struct Thread { uint32_t tid; uint64_t f[NFIELDS]; int seen; };
struct Proc {
  int pid, alive;
  uint64_t dead[NFIELDS];
  struct Thread t[MAXT];
  int nt, live;
  char version[49];
};
#define MAXP 64
static struct Proc procs[MAXP];
static int nprocs, fixed;

static double freq(void) {
#ifdef __aarch64__
  uint64_t v;
  __asm volatile("mrs %0, cntfrq_el0" : "=r"(v));
  return (double)v;
#else
  return 1e9; // x86: TSC ticks, roughly ns on modern parts; only for local testing
#endif
}

static struct Proc* proc(int pid) {
  for (int i = 0; i < nprocs; i++) if (procs[i].pid == pid) return &procs[i];
  if (nprocs == MAXP) return NULL;
  struct Proc* p = &procs[nprocs++];
  memset(p, 0, sizeof(*p));
  p->pid = pid;
  return p;
}

static int sample(struct Proc* p) {
  char path[64];
  snprintf(path, sizeof path, "/dev/shm/fex-%d-stats", p->pid);
  if (kill(p->pid, 0) && errno == ESRCH) return 0; // stale file of a process that died without cleaning up
  int fd = open(path, O_RDONLY);
  if (fd < 0) return 0;
  struct stat st;
  if (fstat(fd, &st) || st.st_size < (off_t)sizeof(struct Header)) { close(fd); return 0; }
  uint8_t* base = mmap(NULL, st.st_size, PROT_READ, MAP_SHARED, fd, 0);
  close(fd);
  if (base == MAP_FAILED) return 0;
  const struct Header* h = (const void*)base;
  memcpy(p->version, h->FexVersion, 48);
  uint32_t stride = h->ThreadStatsSize ? h->ThreadStatsSize : sizeof(struct Stats);
  for (int i = 0; i < p->nt; i++) p->t[i].seen = 0;
  uint32_t off = __atomic_load_n(&h->Head, __ATOMIC_RELAXED);
  int guard = 0;
  while (off && off + sizeof(struct Stats) <= (uint64_t)st.st_size && guard++ < MAXT) {
    const struct Stats* s = (const void*)(base + off);
    struct Thread* t = NULL;
    for (int i = 0; i < p->nt; i++) if (p->t[i].tid == s->TID) { t = &p->t[i]; break; }
    if (!t && p->nt < MAXT) { t = &p->t[p->nt++]; memset(t, 0, sizeof *t); t->tid = s->TID; }
    if (t) {
      uint64_t f[NFIELDS];
      memcpy(f, s->f, sizeof f);
      // A recycled slot shows smaller counters: bank the old thread first.
      if (f[0] < t->f[0]) for (int k = 0; k < NFIELDS; k++) { p->dead[k] += t->f[k]; t->f[k] = 0; }
      memcpy(t->f, f, sizeof f);
      t->seen = 1;
    }
    off = __atomic_load_n(&s->Next, __ATOMIC_RELAXED);
    (void)stride;
  }
  munmap(base, st.st_size);
  int live = 0;
  for (int i = 0; i < p->nt;) {
    if (!p->t[i].seen) {
      for (int k = 0; k < NFIELDS; k++) p->dead[k] += p->t[i].f[k];
      p->t[i] = p->t[--p->nt];
    } else { live++; i++; }
  }
  p->live = live;
  return 1;
}

static void print(FILE* o, double t, struct Proc* p, double hz) {
  uint64_t sum[NFIELDS];
  for (int k = 0; k < NFIELDS; k++) {
    sum[k] = p->dead[k];
    for (int i = 0; i < p->nt; i++) sum[k] += p->t[i].f[k];
  }
  fprintf(o, "%.1f,%d,%d", t, p->pid, p->live);
  for (int k = 0; k < NFIELDS; k++) {
    if (IS_TIME[k]) fprintf(o, ",%.1f", sum[k] * 1000.0 / hz);
    else fprintf(o, ",%llu", (unsigned long long)sum[k]);
  }
  fputc('\n', o);
  fflush(o);
}

static void discover(void) {
  if (fixed) return;
  DIR* d = opendir("/dev/shm");
  if (!d) return;
  struct dirent* e;
  int pid;
  while ((e = readdir(d))) {
    char tail[16];
    if (sscanf(e->d_name, "fex-%d-%15s", &pid, tail) == 2 && !strcmp(tail, "stats")) proc(pid);
  }
  closedir(d);
}

int main(int argc, char** argv) {
  int ms = 1000, summary = 0, c;
  while ((c = getopt(argc, argv, "i:sp:")) != -1) {
    if (c == 'i') ms = atoi(optarg);
    else if (c == 's') summary = 1;
    else if (c == 'p') { proc(atoi(optarg)); fixed = 1; }
    else { fprintf(stderr, "usage: %s [-i MS] [-s] [-p PID]...\n", argv[0]); return 2; }
  }
  double hz = freq();
  struct timespec t0, now;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  if (!summary)
    puts("t_s,pid,threads,jit_ms,signal_ms,sigbus,smc,float_fallback,cache_miss,cache_rlock_ms,cache_wlock_ms,"
         "jit_blocks,disk_hit,disk_miss,disk_lookup_ms");
  int ever = 0;
  for (;;) {
    discover();
    clock_gettime(CLOCK_MONOTONIC, &now);
    double t = (now.tv_sec - t0.tv_sec) + (now.tv_nsec - t0.tv_nsec) / 1e9;
    int any = 0;
    for (int i = 0; i < nprocs; i++) {
      struct Proc* p = &procs[i];
      int ok = sample(p);
      if (ok) { p->alive = 1; any = ever = 1; if (!summary) print(stdout, t, p, hz); }
      else if (p->alive) {
        p->alive = 0; // gone: bank its last threads
        for (int j = 0; j < p->nt; j++) for (int k = 0; k < NFIELDS; k++) p->dead[k] += p->t[j].f[k];
        p->nt = 0; p->live = 0;
        if (summary) { printf("%s,", p->version); print(stdout, t, p, hz); }
      }
    }
    if (summary && ever && !any) {
      int alive = 0;
      for (int i = 0; i < nprocs; i++) alive |= procs[i].alive;
      if (!alive) return 0;
    }
    usleep(ms * 1000);
  }
}
