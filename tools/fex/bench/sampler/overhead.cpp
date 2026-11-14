// What the fex-sample profiler costs the threads it samples. Builds FEX's own sampler (Source/Windows/UnixLib/Sampler.h,
// patch 0007) natively, starts it at the given rate on T busy threads with a fake CPU frame, drains the ring the way
// the game-side reader does, and prints how long the fixed work took and what the signal handler spent.
//
//   overhead <hz> <threads> <work-iterations>   (hz 0 = sampler off)
// Output: one CSV line  hz,threads,work_ms,samples,lost,handler_us,handler_ns_per_sample,drain_us
#include "Sampler.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>

static uint64_t TickHz() {
#if defined(__aarch64__)
  uint64_t F;
  asm volatile("mrs %0, cntfrq_el0" : "=r"(F));
  return F;
#else
  return 1000000000ull;
#endif
}

static uint64_t NowNs(clockid_t C) {
  timespec T;
  clock_gettime(C, &T);
  return uint64_t(T.tv_sec) * 1000000000ull + T.tv_nsec;
}

struct FakeFrame {
  uint64_t Header = 0x1000;
  uint64_t RIP = 0x140001000;
};

static volatile uint64_t Sink;

static void Work(uint64_t Iterations, FakeFrame* Frame, bool Sample) {
  FEXSampler::ThreadSlot* Slot = Sample ? FEXSampler::StartThread(Frame) : nullptr;
  uint64_t X = 88172645463325252ull ^ uint64_t(gettid());
  for (uint64_t i = 0; i < Iterations; ++i) {
    X ^= X << 13;
    X ^= X >> 7;
    X ^= X << 17;
    if ((i & 1023) == 0) {
      Frame->RIP = 0x140001000 + (X & 0xfff); // As a JIT block entry would.
    }
  }
  Sink = X;
  if (Slot) {
    FEXSampler::StopThread(Slot);
  }
}

int main(int argc, char** argv) {
  if (argc != 4) {
    fprintf(stderr, "usage: overhead <hz> <threads> <work-iterations>\n");
    return 2;
  }
  const uint32_t Hz = uint32_t(atoi(argv[1]));
  const int Threads = atoi(argv[2]);
  const uint64_t Iterations = strtoull(argv[3], nullptr, 10);

  FEXUnixLib_SamplerStart Args {.Hz = Hz, .FrameOffsetBlockHeader = 0, .FrameOffsetRIP = 8};
  const bool Sample = Hz && FEXSampler::Start(&Args);
  if (Hz && !Sample) {
    fprintf(stderr, "sampler did not start\n");
    return 1;
  }

  std::atomic<bool> Done {false};
  uint64_t Drained = 0, Lost = 0, DrainNs = 0;
  std::thread Reader([&] {
    uint64_t Tail = 0;
    while (!Done.load()) {
      usleep(100000);
      const uint64_t Start = NowNs(CLOCK_THREAD_CPUTIME_ID);
      const uint64_t H = FEXSampler::Head.load(std::memory_order_acquire);
      if (H - Tail > Args.Capacity) {
        Lost += H - Tail - Args.Capacity;
        Tail = H - Args.Capacity;
      }
      for (; Tail < H; ++Tail) {
        const auto& R = Args.Ring ? Args.Ring[Tail & (Args.Capacity - 1)] : FEXSampleRecord {};
        if (__atomic_load_n(&R.Seq, __ATOMIC_ACQUIRE) != Tail + 1) {
          break;
        }
        ++Drained;
      }
      DrainNs += NowNs(CLOCK_THREAD_CPUTIME_ID) - Start;
    }
  });

  std::vector<FakeFrame> Frames(Threads);
  const uint64_t Start = NowNs(CLOCK_MONOTONIC);
  std::vector<std::thread> Workers;
  for (int t = 0; t < Threads; ++t) {
    Workers.emplace_back(Work, Iterations, &Frames[t], Sample);
  }
  for (auto& W : Workers) {
    W.join();
  }
  const uint64_t WorkNs = NowNs(CLOCK_MONOTONIC) - Start;
  usleep(150000);
  Done = true;
  Reader.join();

  const uint64_t Samples = FEXSampler::Head.load();
  const double HandlerUs = double(FEXSampler::HandlerTicks.load()) * 1e6 / double(TickHz());
  printf("%u,%d,%.1f,%llu,%llu,%.0f,%.0f,%.0f\n", Hz, Threads, WorkNs / 1e6, (unsigned long long)Samples, (unsigned long long)Lost,
         HandlerUs, Samples ? HandlerUs * 1000.0 / double(Samples) : 0.0, DrainNs / 1e3);
  return 0;
}
