using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.Intrinsics;

namespace TsoLitmus;

// Group e: single-threaded throughput of the operations FEX's memcpy/memset and vector TSO slow down.
static unsafe class Bench
{
    public static void Run(Report report, double seconds)
    {
        var benches = new List<(string Name, Func<double, double> Run)>
        {
            ("memcpy-256", s => MemCopy(256, s)),
            ("memcpy-16k", s => MemCopy(16 << 10, s)),
            ("memcpy-1m", s => MemCopy(1 << 20, s)),
            ("memcpy-16m", s => MemCopy(16 << 20, s)),
            ("memset-16k", s => MemSet(16 << 10, s)),
            ("memset-1m", s => MemSet(1 << 20, s)),
            ("refcopy-8k", s => RefCopy(1024, s)),
            ("simd128-saxpy-64k", s => Saxpy128(16 << 10, s)),
            ("scalar-sum-64k", s => ScalarSum(16 << 10, s)),
        };
        if (Vector256.IsHardwareAccelerated) benches.Add(("simd256-saxpy-64k", s => Saxpy256(16 << 10, s)));
        double each = seconds / benches.Count;
        foreach (var (name, run) in benches)
        {
            var clock = Stopwatch.StartNew();
            double rate;
            try
            {
                rate = run(each);
            }
            catch (Exception e)
            {
                Report.Say("bench " + name + " threw " + e.GetType().Name);
                rate = 0;
            }
            report.Bench(name, rate, clock.Elapsed.TotalSeconds);
        }
    }

    // Runs `step` (which processes `bytes` bytes) repeatedly for the time given, after a short warm-up;
    // returns the best MB/s of several measurement windows.
    static double Measure(long bytes, double seconds, Action step)
    {
        long ticks = (long)(Math.Max(seconds, 0.05) * Stopwatch.Frequency);
        long warm = Stopwatch.GetTimestamp() + ticks / 10;
        while (Stopwatch.GetTimestamp() < warm) step();
        double best = 0;
        long end = Stopwatch.GetTimestamp() + ticks * 9 / 10;
        long window = Math.Max(ticks / 20, 1);
        while (Stopwatch.GetTimestamp() < end)
        {
            long start = Stopwatch.GetTimestamp();
            long stop = start + window;
            long done = 0;
            long now;
            do
            {
                step();
                done += bytes;
                now = Stopwatch.GetTimestamp();
            } while (now < stop);
            double rate = done / ((double)(now - start) / Stopwatch.Frequency) / 1e6;
            if (rate > best) best = rate;
        }
        return best;
    }

    static double MemCopy(int size, double seconds)
    {
        var src = GC.AllocateArray<byte>(size, pinned: true);
        var dst = GC.AllocateArray<byte>(size, pinned: true);
        new Random(1).NextBytes(src);
        fixed (byte* s = src, d = dst)
        {
            byte* ps = s, pd = d;
            return Measure(size, seconds, () => Buffer.MemoryCopy(ps, pd, size, size));
        }
    }

    static double MemSet(int size, double seconds)
    {
        var dst = GC.AllocateArray<byte>(size, pinned: true);
        byte value = 0;
        return Measure(size, seconds, () => dst.AsSpan().Fill(value++));
    }

    static double RefCopy(int count, double seconds)
    {
        var src = new object[count];
        for (int i = 0; i < count; i++) src[i] = new object();
        var dst = new object[count];
        return Measure((long)count * IntPtr.Size, seconds, () => Array.Copy(src, dst, count));
    }

    static double Saxpy128(int count, double seconds)
    {
        var x = new float[count];
        var y = new float[count];
        for (int i = 0; i < count; i++) { x[i] = i * 0.5f; y[i] = 1; }
        return Measure((long)count * 3 * sizeof(float), seconds, () => Saxpy128Step(x, y, 0.999f));
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization | MethodImplOptions.NoInlining)]
    static void Saxpy128Step(float[] x, float[] y, float a)
    {
        var va = Vector128.Create(a);
        ref float rx = ref x[0];
        ref float ry = ref y[0];
        for (nuint i = 0; i + 4 <= (nuint)x.Length; i += 4)
        {
            var v = Vector128.LoadUnsafe(ref rx, i) * va + Vector128.LoadUnsafe(ref ry, i);
            v.StoreUnsafe(ref ry, i);
        }
    }

    static double Saxpy256(int count, double seconds)
    {
        var x = new float[count];
        var y = new float[count];
        for (int i = 0; i < count; i++) { x[i] = i * 0.5f; y[i] = 1; }
        return Measure((long)count * 3 * sizeof(float), seconds, () => Saxpy256Step(x, y, 0.999f));
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization | MethodImplOptions.NoInlining)]
    static void Saxpy256Step(float[] x, float[] y, float a)
    {
        var va = Vector256.Create(a);
        ref float rx = ref x[0];
        ref float ry = ref y[0];
        for (nuint i = 0; i + 8 <= (nuint)x.Length; i += 8)
        {
            var v = Vector256.LoadUnsafe(ref rx, i) * va + Vector256.LoadUnsafe(ref ry, i);
            v.StoreUnsafe(ref ry, i);
        }
    }

    static long sink;

    static double ScalarSum(int count, double seconds)
    {
        var x = new int[count];
        for (int i = 0; i < count; i++) x[i] = i;
        return Measure((long)count * sizeof(int), seconds, () => sink += ScalarSumStep(x));
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization | MethodImplOptions.NoInlining)]
    static long ScalarSumStep(int[] x)
    {
        long sum = 0;
        for (int i = 0; i < x.Length; i++) sum += x[i] ^ i;
        return sum;
    }
}
