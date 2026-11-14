// What x87 instructions cost under FEX: ns per loop for the patterns x64 Windows code actually uses.
// The x64 MSVC CRT's fmod/fmodf (which .NET calls for % on double/float) is an x87 fprem loop; with FEX's full
// x87 precision every one of those ops is a softfloat call (FEX's float_fallback counter).
// Freestanding x86-64 Linux binary: clang --target=x86_64-linux-gnu -O2 -ffreestanding -nostdlib -static.
typedef unsigned long u64;
static long sys(long n, long a, long b, long c) {
  long r;
  __asm__ volatile("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory");
  return r;
}
static u64 now_ns(void) {
  struct { long s, ns; } t;
  sys(228, 1, (long)&t, 0); // clock_gettime(CLOCK_MONOTONIC)
  return (u64)t.s * 1000000000ul + (u64)t.ns;
}
static void out(const char* s) { long n = 0; while (s[n]) n++; sys(1, 1, (long)s, n); }
static char* fmtu(char* p, u64 v) { char b[24]; int i = 0; do { b[i++] = '0' + v % 10; v /= 10; } while (v); while (i) *p++ = b[--i]; return p; }
static void row(const char* name, u64 iters, u64 ns, u64 ops) {
  char buf[160], *p = buf;
  for (const char* s = name; *s; ) *p++ = *s++;
  *p++ = ',';
  p = fmtu(p, ns * 10 / iters / 10); *p++ = '.'; p = fmtu(p, ns * 10 / iters % 10); *p++ = ',';
  p = fmtu(p, ops); *p++ = ',';
  p = fmtu(p, ns * 10 / (iters * ops) / 10); *p++ = '.'; p = fmtu(p, ns * 10 / (iters * ops) % 10);
  *p++ = '\n'; *p = 0; out(buf);
}

// MSVC x64 CRT fmod: fld y; fld x; 1: fprem; fnstsw ax; test ah,4; jnz 1b; fstp st1; fstp result
static double xfmod(double x, double y) {
  double r;
  __asm__ volatile(
    "fldl %2\n\tfldl %1\n"
    "1:\n\tfprem\n\tfnstsw %%ax\n\ttestb $4, %%ah\n\tjnz 1b\n"
    "fstp %%st(1)\n\tfstpl %0\n"
    : "=m"(r) : "m"(x), "m"(y) : "ax", "st", "st(1)", "cc");
  return r;
}

void _start(void) {
  volatile double sink = 0, a = 12345.678, b = 3.14159, big = 1e30, three = 3.0, h = 0.5;
  const u64 N = 2000000;
  out("op,ns_per_iter,x87_ops_per_iter,ns_per_op\n");
  u64 t = now_ns();
  for (u64 i = 0; i < N; i++) sink += xfmod(a, b); // fld,fld,fprem,fnstsw,fstp,fstp
  row("fmod_small_ratio", N, now_ns() - t, 6);
  t = now_ns();
  for (u64 i = 0; i < N / 10; i++) sink += xfmod(big, three); // fprem reduces 63 exponent bits per pass
  row("fmod_1e30_by_3", N / 10, now_ns() - t, 6);
  t = now_ns();
  for (u64 i = 0; i < N; i++) {
    double r;
    __asm__ volatile("fldl %1\n\tfaddl %2\n\tfmull %2\n\tfstpl %0" : "=m"(r) : "m"(a), "m"(b) : "st", "cc");
    sink += r;
  }
  row("fld_fadd_fmul_fstp", N, now_ns() - t, 4);
  t = now_ns();
  for (u64 i = 0; i < N / 4; i++) {
    double r;
    __asm__ volatile("fldl %1\n\tfsin\n\tfstpl %0" : "=m"(r) : "m"(h) : "st", "cc");
    sink += r;
  }
  row("fsin", N / 4, now_ns() - t, 3);
  t = now_ns();
  for (u64 i = 0; i < N; i++) sink += a * b + h; // SSE2 reference
  row("sse2_mul_add", N, now_ns() - t, 1);
  sys(60, sink == 42.0 ? 1 : 0, 0, 0);
}
