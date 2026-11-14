# Kestrel FEX under Wine: startup hang and sampler stall (Nov 14, 2026)

The first phone run of Kestrel's FEX (`Kestrel-FEXCore-20261114.22-…-d705c8e8.wcp`) hung at game start.
Everything below was reproduced and fixed off-device with the ARM64EC harness
(`.github/workflows/fex-wine-test.yml` on `emu/winetest*`, `tools/fex/winetest/`): Hangover 11.16 on an
`ubuntu-24.04-arm` runner, running an x86-64 `stress.exe` that churns heap, VirtualAlloc, threads, an
event ping-pong, x87 math and a SuspendThread/GetThreadContext loop (what CoreCLR's GC does), with a
heartbeat each second. Stock FEX does 200-240M ops in 150 s and exits on time. The harness builds FEX with
patches up to N (0000, 0006, 0007, …) so a bad patch shows up as a bisect.

## Startup hang: three bugs, all in 0008/0009 (fixed in `.23`, `01b0ec79`)

1. **Kernelbase imports.** 0008's heap walk and 0009's OpenThread/GetThreadTimes/Suspend/Resume came from
   kernel32 import libs. `libarm64ecfex.dll` loads before kernelbase, so any non-ntdll import makes the
   loader map and initialise kernelbase while the emulator is mid-load: a null-execute crash at start, an
   SEH invalid-frame error, or a hang. Now ntdll only (RtlGetProcessHeaps/RtlLockHeap/RtlUnlockHeap,
   NtOpenThread, NtQueryInformationThread(ThreadTimes), NtSuspendThread/NtResumeThread).
   `tools/fex/arm64ec/build.sh` now fails any build where `libarm64ecfex.dll` imports anything but
   ntdll.dll, or `libwow64fex.dll` anything but ntdll.dll and wow64.dll.
2. **Re-entrant recorder in VirtualName.** 0008 recorded FEX's own mappings in a `std::map` behind a
   `std::mutex`. FEX's allocator calls VirtualName for every mmap it makes, so an insert that allocated
   re-entered VirtualName and self-deadlocked on the (non-recursive, SRW) mutex. This hung every process
   even with NameWindowsMemory=0, so it is the likeliest cause of the phone hang. Now a fixed array of
   4096 sorted ranges under an SRW lock that never allocates; touching ranges merge, and when it is full the
   closest pair merges.
3. **Workers created in ProcessInit.** The memory-name and idle-trim threads were created while the
   process could not yet run threads. They now start from the second thread's ThreadInit, and their first
   pass waits at least 30 s.

Run 43616705366: every row with these options off, NameWindowsMemory=5 and TrimIdleThreads=5 finishes at
stock speed.

## Sampler stall (fixed in 0007, `f69c306`)

With SampleHz on, the same stress program ran 2-30x slower, with all twelve workers stalled for seconds at a
time, and took ~5 min to exit. It did not scale with the rate (25 Hz stalled too), and the handler and drain
thread used a few ms of CPU per minute.

Knockouts (patch 0010, `FEX_SAMPLER_DIAG`, diagnostic only, never published), run 43619922767:

| Knocked out | Result |
|---|---|
| timers created but never armed | stock speed |
| no timers or handler | stock speed |
| no x87 hook | stalls |
| no drain thread, no hook (timers only) | stalls |

So delivering SIGPROF was enough. Cause: Wine's SIGUSR1 (thread suspend) could land inside the SIGPROF
handler, on the signal stack. Wine's `usr1_handler` (`dlls/ntdll/unix/signal_arm64.c`) decides how to
suspend from `ChpeV2CpuAreaInfo->InSimulation` and whether the stack pointer is inside the syscall frame;
on the signal stack it is neither, so it takes the "plain user code" path and suspends and resumes against
the handler's context. The suspended workers are exactly the ones that stalled.

Fix: the handler's `sa_mask` blocks Wine's asynchronous signals (SIGINT, SIGHUP, SIGQUIT, SIGTERM,
SIGUSR1, SIGUSR2, SIGALRM, SIGIO), so they arrive after it returns, against the real context. Run
43621205549, same build: masked rows at 25/100/250 Hz, alone and with names and trim, 195-235M ops and
on-time exit; `FEX_SAMPLER_DIAG=16` (mask knocked out) 25-85M ops and ~310 s exits.

## Notes for later

- Any unix-side signal handler that runs inside a Wine process needs the same mask.
- The `clr.exe` row exits after 2 s even on stock FEX (a winedbg crash in this environment); ignore it.
- Run winedbg with `WINEDEBUG=-all`, or the parent's trace channels bury the stacks.
