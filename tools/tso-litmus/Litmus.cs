using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.CompilerServices;
using System.Runtime.Intrinsics;
using System.Threading;

namespace TsoLitmus;

// Two shared locations per instance (0 = data/x, 1 = flag/y), each alone on a 64-byte line. The arrays are
// pinned and indexed from a 64-byte-aligned base, so a 16-byte access never straddles a line.
sealed class Cells
{
    public const int N = 1024;
    const int Line = 16;
    public readonly int[] I0, I1, V0, V1;
    public readonly float[] F0, F1;
    public readonly int BI0, BI1, BV0, BV1, BF0, BF1;
    // Zero, but read from a field so the JIT cannot fold it: the XOR keeps each SSE load a full 16-byte load.
    public Vector128<int> Key = Vector128<int>.Zero;
    public long Torn;

    public Cells()
    {
        (I0, BI0) = Aligned<int>();
        (I1, BI1) = Aligned<int>();
        (V0, BV0) = Aligned<int>();
        (V1, BV1) = Aligned<int>();
        (F0, BF0) = Aligned<float>();
        (F1, BF1) = Aligned<float>();
    }

    static unsafe (T[] Array, int Base) Aligned<T>() where T : unmanaged
    {
        var array = GC.AllocateArray<T>(N * Line + Line, pinned: true);
        long address = (long)Unsafe.AsPointer(ref array[0]);
        int skip = (int)((64 - address % 64) % 64) / sizeof(T);
        return (array, skip);
    }
}

interface IKind
{
    static abstract void Store(Cells c, int loc, int i, int v);
    // Returns -1 when the lanes of a vector load disagree (a torn 16-byte access).
    static abstract int Load(Cells c, int loc, int i);
}

struct IntKind : IKind
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static void Store(Cells c, int loc, int i, int v) =>
        Volatile.Write(ref (loc == 0 ? c.I0 : c.I1)[(loc == 0 ? c.BI0 : c.BI1) + (i << 4)], v);

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static int Load(Cells c, int loc, int i) =>
        Volatile.Read(ref (loc == 0 ? c.I0 : c.I1)[(loc == 0 ? c.BI0 : c.BI1) + (i << 4)]);
}

// Volatile.Read/Write(ref float) compile to movss: a vector-register access in FEX's eyes.
struct FloatKind : IKind
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static void Store(Cells c, int loc, int i, int v) =>
        Volatile.Write(ref (loc == 0 ? c.F0 : c.F1)[(loc == 0 ? c.BF0 : c.BF1) + (i << 4)], v);

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static int Load(Cells c, int loc, int i) =>
        (int)Volatile.Read(ref (loc == 0 ? c.F0 : c.F1)[(loc == 0 ? c.BF0 : c.BF1) + (i << 4)]);
}

struct SseKind : IKind
{
    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static void Store(Cells c, int loc, int i, int v) =>
        Vector128.Create(v).StoreUnsafe(ref (loc == 0 ? c.V0 : c.V1)[0], (nuint)((loc == 0 ? c.BV0 : c.BV1) + (i << 4)));

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static int Load(Cells c, int loc, int i)
    {
        var x = Vector128.Xor(Vector128.LoadUnsafe(ref (loc == 0 ? c.V0 : c.V1)[0], (nuint)((loc == 0 ? c.BV0 : c.BV1) + (i << 4))), c.Key);
        int s = x.ToScalar();
        return Vector128.EqualsAll(x, Vector128.Create(s)) ? s : -1;
    }
}

// A reusable spin barrier; the last thread to arrive decides whether the next round runs.
sealed class Gate
{
    readonly int parties;
    readonly long deadline;
    int arrived;
    int generation;
    volatile bool stopping;

    public Gate(int parties, double seconds)
    {
        this.parties = parties;
        deadline = Stopwatch.GetTimestamp() + (long)(seconds * Stopwatch.Frequency);
    }

    public bool Pass()
    {
        int gen = Volatile.Read(ref generation);
        if (Interlocked.Increment(ref arrived) == parties)
        {
            arrived = 0;
            stopping = Stopwatch.GetTimestamp() >= deadline || Workers.Abort;
            Interlocked.Increment(ref generation);
        }
        else
        {
            int spins = 0;
            while (Volatile.Read(ref generation) == gen)
            {
                if ((++spins & 0x3FF) == 0)
                {
                    if (Workers.Abort) return false;
                    Thread.Yield();
                }
            }
        }
        return !stopping;
    }
}

static class Litmus
{
    // The float flag of group c must stay exact.
    const int MaxRound = 1 << 23;

    // Message passing: the writer stores data then flag, both = round; a reader that sees the flag
    // must see data at least as new. Seeing older data is forbidden under x86-TSO.
    public static Result MessagePassing<TData, TFlag>(string name, string group, string needs, int pairs, double seconds)
        where TData : struct, IKind where TFlag : struct, IKind
    {
        var cells = new List<Cells>();
        var bodies = new List<Action>();
        var samples = new long[pairs];
        var bad = new long[pairs];
        for (int p = 0; p < pairs; p++)
        {
            var c = new Cells();
            var gate = new Gate(2, seconds);
            int lane = p;
            cells.Add(c);
            bodies.Add(() => MpWriter<TData, TFlag>(c, gate));
            bodies.Add(() => MpReader<TData, TFlag>(c, gate, ref samples[lane], ref bad[lane]));
        }
        var clock = Stopwatch.StartNew();
        bool finished = Workers.Run(bodies, seconds);
        var r = new Result { Name = name, Group = group, Needs = needs, Seconds = clock.Elapsed.TotalSeconds };
        for (int p = 0; p < pairs; p++)
        {
            r.Samples += samples[p];
            r.Observed += bad[p];
        }
        Finish(r, cells, finished);
        return r;
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void MpWriter<TData, TFlag>(Cells c, Gate gate) where TData : struct, IKind where TFlag : struct, IKind
    {
        for (int round = 1; round < MaxRound && gate.Pass(); round++)
        {
            for (int i = 0; i < Cells.N; i++)
            {
                TData.Store(c, 0, i, round);
                TFlag.Store(c, 1, i, round);
            }
            if (!gate.Pass()) break;
        }
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void MpReader<TData, TFlag>(Cells c, Gate gate, ref long samples, ref long bad) where TData : struct, IKind where TFlag : struct, IKind
    {
        long n = 0, b = 0, torn = 0;
        int spins = 0;
        for (int round = 1; round < MaxRound && gate.Pass(); round++)
        {
            for (int i = 0; i < Cells.N; i++)
            {
                int f;
                while ((f = TFlag.Load(c, 1, i)) < round)
                {
                    if (f < 0) torn++;
                    if ((++spins & 0xFFFF) == 0 && Workers.Abort) goto done;
                }
                int d = TData.Load(c, 0, i);
                n++;
                if (d < 0) torn++;
                else if (d < f) b++;
            }
            if (!gate.Pass()) break;
        }
    done:
        samples = n;
        bad = b;
        Interlocked.Add(ref c.Torn, torn);
    }

    // Store buffering: each thread stores its location then loads the other's. Both loads seeing the old
    // value is allowed under x86-TSO, so this only shows how much reordering the hardware does.
    public static Result StoreBuffering<T>(string name, int pairs, double seconds) where T : struct, IKind
    {
        var cells = new List<Cells>();
        var bodies = new List<Action>();
        var samples = new long[pairs];
        var both = new long[pairs];
        for (int p = 0; p < pairs; p++)
        {
            var c = new Cells();
            var gate = new Gate(2, seconds);
            var ra = new int[Cells.N];
            var rb = new int[Cells.N];
            int lane = p;
            cells.Add(c);
            bodies.Add(() => SbThread<T>(c, gate, 0, ra, rb, ref samples[lane], ref both[lane]));
            bodies.Add(() =>
            {
                long unused = 0, unusedBoth = 0;
                SbThread<T>(c, gate, 1, rb, ra, ref unused, ref unusedBoth);
            });
        }
        var clock = Stopwatch.StartNew();
        bool finished = Workers.Run(bodies, seconds);
        var r = new Result { Name = name, Group = "d", Expect = Expect.Allowed, Seconds = clock.Elapsed.TotalSeconds };
        for (int p = 0; p < pairs; p++)
        {
            r.Samples += samples[p];
            r.Observed += both[p];
        }
        Finish(r, cells, finished);
        return r;
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void SbThread<T>(Cells c, Gate gate, int mine, int[] own, int[] other, ref long samples, ref long both) where T : struct, IKind
    {
        long n = 0, b = 0;
        int theirs = 1 - mine;
        for (int round = 1; round < MaxRound && gate.Pass(); round++)
        {
            for (int i = 0; i < Cells.N; i++)
            {
                T.Store(c, mine, i, round);
                own[i] = T.Load(c, theirs, i);
            }
            if (!gate.Pass()) break;
            if (mine == 0)
            {
                for (int i = 0; i < Cells.N; i++)
                    if (own[i] >= 0 && other[i] >= 0 && own[i] < round && other[i] < round) b++;
                n += Cells.N;
            }
        }
        samples = n;
        both = b;
    }

    // IRIW: two writers store x and y; one reader waits for x then reads y, the other waits for y then
    // reads x. Both readers seeing the other write missing is forbidden under x86-TSO (stores are seen in
    // one order by everyone).
    public static Result Iriw<T>(string name, string needs, double seconds) where T : struct, IKind
    {
        if (Environment.ProcessorCount < 4)
            return new Result { Name = name, Group = "d", Needs = needs, Note = "skipped: needs 4 CPUs" };
        int groups = Environment.ProcessorCount >= 8 ? 2 : 1;
        var cells = new List<Cells>();
        var bodies = new List<Action>();
        var samples = new long[groups];
        var bad = new long[groups];
        for (int g = 0; g < groups; g++)
        {
            var c = new Cells();
            var gate = new Gate(4, seconds);
            var saw0 = new bool[Cells.N];
            var saw1 = new bool[Cells.N];
            int lane = g;
            cells.Add(c);
            bodies.Add(() => IriwWriter<T>(c, gate, 0));
            bodies.Add(() => IriwWriter<T>(c, gate, 1));
            bodies.Add(() => IriwReader<T>(c, gate, 0, saw0, saw1, ref samples[lane], ref bad[lane]));
            bodies.Add(() =>
            {
                long unused = 0, unusedBad = 0;
                IriwReader<T>(c, gate, 1, saw1, saw0, ref unused, ref unusedBad);
            });
        }
        var clock = Stopwatch.StartNew();
        bool finished = Workers.Run(bodies, seconds);
        var r = new Result { Name = name, Group = "d", Needs = needs, Seconds = clock.Elapsed.TotalSeconds };
        for (int g = 0; g < groups; g++)
        {
            r.Samples += samples[g];
            r.Observed += bad[g];
        }
        Finish(r, cells, finished);
        return r;
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void IriwWriter<T>(Cells c, Gate gate, int loc) where T : struct, IKind
    {
        for (int round = 1; round < MaxRound && gate.Pass(); round++)
        {
            for (int i = 0; i < Cells.N; i++) T.Store(c, loc, i, round);
            if (!gate.Pass()) break;
        }
    }

    // Reader `first` waits for its location, then records whether the other location was still old.
    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void IriwReader<T>(Cells c, Gate gate, int first, bool[] stale, bool[] otherStale, ref long samples, ref long bad) where T : struct, IKind
    {
        long n = 0, b = 0;
        int second = 1 - first;
        int spins = 0;
        for (int round = 1; round < MaxRound && gate.Pass(); round++)
        {
            for (int i = 0; i < Cells.N; i++)
            {
                while (T.Load(c, first, i) < round)
                {
                    if ((++spins & 0xFFFF) == 0 && Workers.Abort) goto done;
                }
                int v = T.Load(c, second, i);
                stale[i] = v >= 0 && v < round;
            }
            if (!gate.Pass()) break;
            if (first == 0)
            {
                for (int i = 0; i < Cells.N; i++)
                    if (stale[i] && otherStale[i]) b++;
                n += Cells.N;
            }
        }
    done:
        samples = n;
        bad = b;
    }

    static void Finish(Result r, List<Cells> cells, bool finished)
    {
        long torn = 0;
        foreach (var c in cells) torn += Interlocked.Read(ref c.Torn);
        if (torn > 0) r.Detail["torn_vector_loads"] = torn;
        if (!finished) r.Note = "workers did not stop in time";
    }
}
