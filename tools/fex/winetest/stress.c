// A Windows x86-64 program that leans on the things FEX's ARM64EC options touch, to look for hangs
// off-device: heaps (process heap and a private one, small and large blocks), critical sections, event
// ping-pong, VirtualAlloc/VirtualFree, thread churn (FEX ThreadInit/ThreadTerm), x87 math (softfloat
// fallbacks), and a GC-like suspender that suspends threads and reads their context the way CoreCLR does.
// Prints one heartbeat line a second; the harness calls it hung when the heartbeats stop.
//
//   x86_64-w64-mingw32-gcc -O2 -static -o stress.exe stress.c
//   wine stress.exe <seconds>
#include <windows.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define WORKERS 12

static volatile LONG Stop;
static volatile LONG64 Progress[WORKERS];
static HANDLE WorkerHandles[WORKERS];
static volatile LONG64 Churned, Suspends, PingPongs, Shared;
static CRITICAL_SECTION Lock;
static HANDLE PrivateHeap;
static HANDLE Ping, Pong;
static volatile long double Sink;

static unsigned Next(unsigned* S) {
  *S = *S * 1103515245u + 12345u;
  return *S >> 8;
}

static DWORD WINAPI Worker(void* Arg) {
  const int Id = (int)(INT_PTR)Arg;
  unsigned S = 0x9e3779b9u * (unsigned)(Id + 1);
  struct { void* P; HANDLE H; } Keep[96] = {0};
  long double Acc = 1.0L;
  while (!Stop) {
    const unsigned R = Next(&S);
    const int K = R % 96;
    if (Keep[K].P) {
      HeapFree(Keep[K].H, 0, Keep[K].P);
      Keep[K].P = NULL;
    }
    const HANDLE H = (R & 1) ? GetProcessHeap() : PrivateHeap;
    // Mostly small blocks; now and then one past the large-block threshold (its own mapping).
    const SIZE_T Size = (R % 211 == 0) ? (SIZE_T)(1u << 20) + (R % 65536) : 16 + (R % 4096);
    Keep[K].P = HeapAlloc(H, 0, Size);
    Keep[K].H = H;
    if (Keep[K].P) {
      memset(Keep[K].P, Id, Size < 256 ? Size : 256);
    }
    if (R % 8 == 0) {
      EnterCriticalSection(&Lock);
      Shared++;
      LeaveCriticalSection(&Lock);
    }
    if (R % 701 == 0) {
      void* V = VirtualAlloc(NULL, 4u << 20, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
      if (V) {
        memset(V, Id, 1u << 16);
        VirtualFree(V, 0, MEM_RELEASE);
      }
    }
    if (R % 97 == 0) {
      // x87: mingw's long double is the 80-bit x87 format, so these need FEX's softfloat paths.
      Acc = Acc * 1.0000001L + sinl((long double)(R & 1023)) / 3.0L;
    }
    if (R % 4096 == 0) {
      Sleep(1);
    }
    InterlockedIncrement64(&Progress[Id]);
  }
  for (int i = 0; i < 96; ++i) {
    if (Keep[i].P) {
      HeapFree(Keep[i].H, 0, Keep[i].P);
    }
  }
  Sink += Acc;
  return 0;
}

static DWORD WINAPI Pinger(void* Arg) {
  (void)Arg;
  while (!Stop) {
    SetEvent(Ping);
    if (WaitForSingleObject(Pong, 1000) == WAIT_OBJECT_0) {
      InterlockedIncrement64(&PingPongs);
    }
  }
  SetEvent(Ping);
  return 0;
}

static DWORD WINAPI Ponger(void* Arg) {
  (void)Arg;
  while (!Stop) {
    if (WaitForSingleObject(Ping, 1000) == WAIT_OBJECT_0) {
      SetEvent(Pong);
    }
  }
  SetEvent(Pong);
  return 0;
}

static DWORD WINAPI ShortLived(void* Arg) {
  volatile unsigned X = (unsigned)(INT_PTR)Arg;
  for (int i = 0; i < 20000; ++i) {
    X = X * 31 + i;
  }
  void* P = HeapAlloc(GetProcessHeap(), 0, 512);
  HeapFree(GetProcessHeap(), 0, P);
  return X & 1;
}

static DWORD WINAPI Churner(void* Arg) {
  (void)Arg;
  while (!Stop) {
    HANDLE T = CreateThread(NULL, 0, ShortLived, (void*)(INT_PTR)Churned, 0, NULL);
    if (T) {
      WaitForSingleObject(T, INFINITE);
      CloseHandle(T);
      InterlockedIncrement64(&Churned);
    }
    Sleep(5);
  }
  return 0;
}

// What CoreCLR's GC does to stop the world: suspend a running thread and read its registers.
static DWORD WINAPI Suspender(void* Arg) {
  (void)Arg;
  unsigned S = 12345;
  while (!Stop) {
    const HANDLE T = WorkerHandles[Next(&S) % WORKERS];
    if (SuspendThread(T) != (DWORD)-1) {
      CONTEXT C;
      memset(&C, 0, sizeof(C));
      C.ContextFlags = CONTEXT_FULL;
      GetThreadContext(T, &C);
      ResumeThread(T);
      InterlockedIncrement64(&Suspends);
    }
    Sleep(2);
  }
  return 0;
}

int main(int argc, char** argv) {
  const int Seconds = argc > 1 ? atoi(argv[1]) : 120;
  setvbuf(stdout, NULL, _IONBF, 0);
  InitializeCriticalSection(&Lock);
  PrivateHeap = HeapCreate(0, 0, 0);
  Ping = CreateEventW(NULL, FALSE, FALSE, NULL);
  Pong = CreateEventW(NULL, FALSE, FALSE, NULL);
  for (int i = 0; i < WORKERS; ++i) {
    WorkerHandles[i] = CreateThread(NULL, 0, Worker, (void*)(INT_PTR)i, 0, NULL);
  }
  HANDLE Extra[4] = {
    CreateThread(NULL, 0, Pinger, NULL, 0, NULL),
    CreateThread(NULL, 0, Ponger, NULL, 0, NULL),
    CreateThread(NULL, 0, Churner, NULL, 0, NULL),
    CreateThread(NULL, 0, Suspender, NULL, 0, NULL),
  };
  LONG64 Last = -1;
  for (int t = 1; t <= Seconds; ++t) {
    Sleep(1000);
    LONG64 Total = 0;
    int Idle = 0;
    static LONG64 Before[WORKERS];
    for (int i = 0; i < WORKERS; ++i) {
      const LONG64 P = Progress[i];
      Idle += P == Before[i];
      Before[i] = P;
      Total += P;
    }
    printf("hb t=%d ops=%lld idle_workers=%d churned=%lld suspends=%lld pingpong=%lld%s\n", t, (long long)Total, Idle,
           (long long)Churned, (long long)Suspends, (long long)PingPongs, Total == Last ? " STALLED" : "");
    Last = Total;
  }
  InterlockedExchange(&Stop, 1);
  WaitForMultipleObjects(WORKERS, WorkerHandles, TRUE, 30000);
  WaitForMultipleObjects(4, Extra, TRUE, 30000);
  printf("done shared=%lld\n", (long long)Shared);
  return 0;
}
