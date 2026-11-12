using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime;
using System.Runtime.CompilerServices;
using System.Threading;

namespace TsoLitmus;

sealed class Payload
{
    public const int Magic = 0x7E5A11ED;
    public readonly int[] Data;
    public readonly long Seq;
    public readonly int Check;
    public readonly int Mark;

    public Payload(long seq)
    {
        Seq = seq;
        Check = Hash(seq);
        Data = new int[] { Check, 1, 2, 3 };
        Mark = Magic;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    public static int Hash(long seq) => (int)((ulong)seq * 0x9E3779B97F4A7C15UL >> 33) | 1;
}

// Six references: 48 bytes, so the runtime's GC-reference copy always does at least one 16-byte SSE store.
struct Hexa
{
    public object? A, B, C, D, E, F;
}

static class RefTests
{
    public enum Mode { ArrayCopy, Struct6, ListAdd, PlainStore }

    const int Batch = 6;
    const int Slots = 12;
    const int TypeBit = 1, MagicBit = 2, CheckBit = 4, DataBit = 8;

    sealed class Lane
    {
        public volatile bool Stop;
        public readonly object?[] Shared = new object?[Slots];
        public readonly Hexa[] SharedHexa = new Hexa[Slots / Batch];
        public List<object>? SharedList;
        public long Published, Samples, Fresh, Exceptions;
        public readonly long[] Bad = new long[16];
        public readonly long SeqBase;
        public Lane(long seqBase) { SeqBase = seqBase; }
    }

    [MethodImpl(MethodImplOptions.NoInlining)]
    static int Verify(object? o)
    {
        if (o is not Payload p) return TypeBit;
        int bad = 0;
        if (p.Mark != Payload.Magic) bad |= MagicBit;
        if (p.Check != Payload.Hash(p.Seq)) bad |= CheckBit;
        int[]? d = p.Data;
        if (d == null || d.Length != 4 || d[0] != p.Check || d[3] != 3) bad |= DataBit;
        return bad;
    }

    static string Name(Mode mode) => mode switch
    {
        Mode.ArrayCopy => "refs-array-copy",
        Mode.Struct6 => "refs-struct-copy",
        Mode.ListAdd => "refs-list-add",
        _ => "refs-plain-store",
    };

    public static Result Publish(Mode mode, int pairs, double seconds)
    {
        var lanes = new List<Lane>();
        var bodies = new List<Action>();
        for (int i = 0; i < pairs; i++)
        {
            var lane = new Lane((long)i << 40);
            lanes.Add(lane);
            bodies.Add(() => Produce(mode, lane));
            bodies.Add(() => Consume(mode, lane));
        }
        var clock = Stopwatch.StartNew();
        bool finished = Workers.Run(bodies, seconds, () => { foreach (var l in lanes) l.Stop = true; });
        return Collect(Name(mode), "a", mode == Mode.ArrayCopy || mode == Mode.Struct6 ? "vector-tso" : "none", lanes,
            clock.Elapsed.TotalSeconds, finished);
    }

    static Result Collect(string name, string group, string needs, List<Lane> lanes, double seconds, bool finished)
    {
        var r = new Result { Name = name, Group = group, Needs = needs, Seconds = seconds, Fresh = 0 };
        long published = 0, exceptions = 0, type = 0, magic = 0, check = 0, data = 0;
        foreach (var l in lanes)
        {
            published += l.Published;
            r.Samples += l.Samples;
            r.Fresh += l.Fresh;
            exceptions += l.Exceptions;
            for (int bits = 1; bits < 16; bits++)
            {
                long n = l.Bad[bits];
                r.Observed += n;
                if ((bits & TypeBit) != 0) type += n;
                if ((bits & MagicBit) != 0) magic += n;
                if ((bits & CheckBit) != 0) check += n;
                if ((bits & DataBit) != 0) data += n;
            }
        }
        r.Observed += exceptions;
        r.Detail["published"] = published;
        r.Detail["bad_type"] = type;
        r.Detail["bad_magic"] = magic;
        r.Detail["bad_check"] = check;
        r.Detail["bad_data"] = data;
        r.Detail["exceptions"] = exceptions;
        if (!finished) r.Note = "workers did not stop in time";
        return r;
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void Produce(Mode mode, Lane lane)
    {
        long seq = lane.SeqBase + 1;
        long published = 0;
        var batch = new object[Batch];
        var staging = new Hexa[1];
        var shared = lane.Shared;
        var sharedHexa = lane.SharedHexa;
        int pos = 0;
        List<object>? list = null;
        while (!lane.Stop)
        {
            switch (mode)
            {
                case Mode.ArrayCopy:
                    for (int k = 0; k < Batch; k++) batch[k] = new Payload(seq++);
                    Array.Copy(batch, 0, shared, pos, Batch);
                    pos += Batch;
                    if (pos >= Slots) pos = 0;
                    break;
                case Mode.Struct6:
                    staging[0].A = new Payload(seq++);
                    staging[0].B = new Payload(seq++);
                    staging[0].C = new Payload(seq++);
                    staging[0].D = new Payload(seq++);
                    staging[0].E = new Payload(seq++);
                    staging[0].F = new Payload(seq++);
                    sharedHexa[pos] = staging[0];
                    pos = pos + 1 == sharedHexa.Length ? 0 : pos + 1;
                    break;
                case Mode.ListAdd:
                    if (list == null || list.Count >= 4096)
                    {
                        list = new List<object>(1);
                        Volatile.Write(ref lane.SharedList, list);
                    }
                    for (int k = 0; k < Batch; k++) list.Add(new Payload(seq++));
                    break;
                default:
                    for (int k = 0; k < Batch; k++) shared[pos + k] = new Payload(seq++);
                    pos += Batch;
                    if (pos >= Slots) pos = 0;
                    break;
            }
            published += Batch;
        }
        lane.Published = published;
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void Consume(Mode mode, Lane lane)
    {
        long samples = 0, fresh = 0;
        var last = new long[Slots];
        var bad = lane.Bad;
        while (!lane.Stop)
        {
            try
            {
                while (!lane.Stop)
                {
                    switch (mode)
                    {
                        case Mode.Struct6:
                            var hexa = lane.SharedHexa;
                            for (int i = 0; i < hexa.Length; i++)
                            {
                                Check(hexa[i].A, ref last[i * Batch], ref samples, ref fresh, bad);
                                Check(hexa[i].B, ref last[i * Batch + 1], ref samples, ref fresh, bad);
                                Check(hexa[i].C, ref last[i * Batch + 2], ref samples, ref fresh, bad);
                                Check(hexa[i].D, ref last[i * Batch + 3], ref samples, ref fresh, bad);
                                Check(hexa[i].E, ref last[i * Batch + 4], ref samples, ref fresh, bad);
                                Check(hexa[i].F, ref last[i * Batch + 5], ref samples, ref fresh, bad);
                            }
                            break;
                        case Mode.ListAdd:
                            var list = Volatile.Read(ref lane.SharedList);
                            if (list == null) break;
                            int count = list.Count;
                            for (int i = Math.Max(0, count - Slots); i < count; i++)
                                Check(list[i], ref last[i % Slots], ref samples, ref fresh, bad);
                            break;
                        default:
                            var shared = lane.Shared;
                            for (int i = 0; i < shared.Length; i++)
                                Check(Volatile.Read(ref shared[i]), ref last[i], ref samples, ref fresh, bad);
                            break;
                    }
                }
            }
            catch (Exception)
            {
                lane.Exceptions++;
            }
        }
        lane.Samples = samples;
        lane.Fresh = fresh;
    }

    [MethodImpl(MethodImplOptions.AggressiveInlining)]
    static void Check(object? o, ref long last, ref long samples, ref long fresh, long[] bad)
    {
        if (o == null) return;
        samples++;
        int problems = Verify(o);
        if (problems != 0)
        {
            bad[problems]++;
            return;
        }
        long seq = ((Payload)o).Seq;
        if (seq != last)
        {
            last = seq;
            fresh++;
        }
    }

    // Group b: the same publication under a gen2 workload with frequent background (or blocking) gen2 collections.
    public static Result Churn(GCLatencyMode latency, double seconds)
    {
        var before = GCSettings.LatencyMode;
        string name = latency == GCLatencyMode.Batch ? "refs-gen2-churn-blocking" : "refs-gen2-churn-background";
        bool concurrentAvailable = before != GCLatencyMode.Batch;
        try
        {
            GCSettings.LatencyMode = latency;
        }
        catch (Exception) { }
        var mode = GCSettings.LatencyMode;
        int pairs = Math.Clamp((Environment.ProcessorCount - 1) / 4, 1, 2);
        var lanes = new List<Lane>();
        var bodies = new List<Action>();
        for (int i = 0; i < pairs; i++)
        {
            var copy = new Lane(((long)i << 40) | (1L << 50));
            var hexa = new Lane(((long)i << 40) | (1L << 51));
            lanes.Add(copy);
            lanes.Add(hexa);
            bodies.Add(() => Produce(Mode.ArrayCopy, copy));
            bodies.Add(() => Consume(Mode.ArrayCopy, copy));
            bodies.Add(() => Produce(Mode.Struct6, hexa));
            bodies.Add(() => Consume(Mode.Struct6, hexa));
        }
        var churn = new ChurnState();
        bodies.Add(() => ChurnLoop(churn));
        int gen2Before = GC.CollectionCount(2);
        var clock = Stopwatch.StartNew();
        bool finished = Workers.Run(bodies, seconds, () =>
        {
            churn.Stop = true;
            foreach (var l in lanes) l.Stop = true;
        });
        double elapsed = clock.Elapsed.TotalSeconds;
        try { GCSettings.LatencyMode = before; } catch (Exception) { }
        var r = Collect(name, "b", latency == GCLatencyMode.Batch ? "vector-tso" : "vector-tso+blocking-gc", lanes, elapsed, finished);
        r.Detail["gen2_collections"] = GC.CollectionCount(2) - gen2Before;
        r.Detail["background_gcs"] = churn.BackgroundGcs;
        r.Detail["gc_requests"] = churn.Requests;
        if (latency != GCLatencyMode.Batch && !concurrentAvailable)
            r.Note = "concurrent GC is off in this process (DOTNET_gcConcurrent=0): collections were blocking";
        else if (mode != latency)
            r.Note = "GC latency mode stayed " + mode;
        return r;
    }

    sealed class ChurnState
    {
        public volatile bool Stop;
        public long BackgroundGcs, Requests;
    }

    [MethodImpl(MethodImplOptions.AggressiveOptimization)]
    static void ChurnLoop(ChurnState state)
    {
        var keep = new object?[1 << 15];
        var random = new Random(1234);
        var clock = Stopwatch.StartNew();
        long lastRequest = 0;
        long lastBackground = GC.GetGCMemoryInfo(GCKind.Background).Index;
        long seq = -1L << 60;
        while (!state.Stop)
        {
            for (int k = 0; k < 256; k++)
            {
                int slot = random.Next(keep.Length);
                keep[slot] = (k & 3) == 0 ? new Payload(seq++) : new byte[16 + random.Next(1024)];
            }
            long now = clock.ElapsedMilliseconds;
            if (now - lastRequest >= 20)
            {
                lastRequest = now;
                GC.Collect(2, GCCollectionMode.Forced, blocking: false, compacting: false);
                state.Requests++;
                long index = GC.GetGCMemoryInfo(GCKind.Background).Index;
                if (index != lastBackground)
                {
                    lastBackground = index;
                    state.BackgroundGcs++;
                }
            }
        }
        GC.KeepAlive(keep);
    }
}
