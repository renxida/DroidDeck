// Stand-in for Slay the Spire 2's CPU work under FEX: the kinds of .NET code a Godot C# game runs each
// frame, plus the JIT churn that makes FEX re-translate code. Prints one CSV line per phase:
// phase,best_ms,median_ms. Same IL runs natively (arm64 runtime) and under FEX (x64 runtime).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Linq.Expressions;
using System.Numerics;
using System.Runtime.CompilerServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

static class Bench
{
    static int sink;

    static void Main(string[] args)
    {
        int reps = args.Length > 0 ? int.Parse(args[0]) : 5;
        string only = args.Length > 1 ? args[1] : "";
        if (only == "frames") { Frames(reps); return; }
        var phases = new (string, Action)[]
        {
            ("first_jit", FirstJit),
            ("alloc_gc", AllocGc),
            ("collections", Collections),
            ("dispatch", Dispatch),
            ("vector_math", VectorMath),
            ("memmove_refs", MemmoveRefs),
            ("memmove_bytes", MemmoveBytes),
            ("strings", Strings),
            ("linq", LinqPhase),
            ("threads_contended", ThreadsContended),
            ("tiering_churn", TieringChurn),
        };
        Console.WriteLine("phase,best_ms,median_ms");
        foreach (var (name, fn) in phases)
        {
            if (only != "" && !only.Split(',').Contains(name)) continue;
            var t = new List<double>();
            int n = name == "first_jit" || name == "tiering_churn" ? 1 : reps;
            for (int i = 0; i < n; i++)
            {
                var sw = Stopwatch.StartNew();
                fn();
                t.Add(sw.Elapsed.TotalMilliseconds);
            }
            t.Sort();
            Console.WriteLine($"{name},{t[0]:F1},{t[t.Count / 2]:F1}");
        }
        Console.WriteLine($"gc_count,{GC.CollectionCount(0)},{GC.CollectionCount(2)}");
        // Same IL, same inputs: every config must print the same checksum, or the translator ran stale or wrong code.
        Console.WriteLine($"checksum,{sink},0");
    }

    // A game-like frame loop: the same small mix of work every frame, and every 50th frame new content loads
    // (40 game methods called for the first time, and every 100th frame 10 freshly built expression lambdas), which
    // is when code is new to the .NET JIT and to FEX. Prints startup_ms (process start to Main), first_frame_ms,
    // frame_p50_ms, frame_p99_ms, frame_max_ms, total_ms (Main to the last frame), and the checksum.
    static void Frames(int frames)
    {
        double startup = (DateTime.Now - System.Diagnostics.Process.GetCurrentProcess().StartTime).TotalMilliseconds;
        var total = Stopwatch.StartNew();
        IEffect[] e = { new A(), new B(), new C() };
        Card[] c = { new Strike(), new Defend() };
        var pos = new Vector3[2048];
        var vel = new Vector3[2048];
        for (int i = 0; i < pos.Length; i++) vel[i] = new Vector3(i, -i, 0.5f * i) * 0.001f;
        var m = Matrix4x4.CreateRotationZ(0.01f);
        var d = new Dictionary<string, int>();
        var keys = Enumerable.Range(0, 512).Select(i => "card_" + i).ToArray();
        var items = Enumerable.Range(0, 2_000).Select(i => new Item(i, i % 7, "x" + (i % 50))).ToList();
        var sb = new StringBuilder();
        var times = new double[frames];
        int nextCard = 0, x = 0;
        for (int f = 0; f < frames; f++)
        {
            var sw = Stopwatch.StartNew();
            if (f % 50 == 0)
                for (int k = 0; k < 40; k++, nextCard++) sink += Cards.All[nextCard % Cards.Count](f + k);
            if (f % 100 == 0)
                for (int k = 0; k < 10; k++)
                {
                    var p = Expression.Parameter(typeof(int));
                    Expression body = Expression.Add(Expression.Multiply(p, Expression.Constant(f + k * 3 + 1)), Expression.Constant(k));
                    sink += Expression.Lambda<Func<int, int>>(body, p).Compile()(f);
                }
            for (int i = 0; i < 50_000; i++) x = c[i & 1].Play(e[i % 3].Apply(x));
            for (int i = 0; i < pos.Length; i++) { vel[i] = Vector3.Transform(vel[i], m) * 0.999f; pos[i] += vel[i]; }
            Node? head = null;
            for (int i = 0; i < 5_000; i++) { var n = new Node { V = i, Next = (i & 3) == 0 ? head : null }; if ((i & 3) == 0) head = n; }
            d.Clear();
            foreach (var k in keys) d[k] = k.Length + f;
            foreach (var k in keys) x += d[k];
            for (int i = 0; i < 100; i++) { sb.Clear(); sb.Append("Deal ").Append((i + f) % 50).Append(" damage"); x += sb.ToString().Length; }
            if (f % 10 == 0) x += items.Where(it => it.Cost < 4).GroupBy(it => it.Name).Select(g => g.Sum(y => y.Cost)).Max();
            sink += x + head!.V + (int)pos[f & 2047].X;
            times[f] = sw.Elapsed.TotalMilliseconds;
        }
        double all = total.Elapsed.TotalMilliseconds;
        var rest = times.Skip(1).OrderBy(t => t).ToArray();
        double Pct(double q) => rest[Math.Min(rest.Length - 1, (int)Math.Ceiling(q * rest.Length) - 1)];
        Console.WriteLine("metric,value,unit");
        Console.WriteLine($"startup_ms,{startup:F1},ms");
        Console.WriteLine($"first_frame_ms,{times[0]:F2},ms");
        Console.WriteLine($"frame_p50_ms,{Pct(0.50):F2},ms");
        Console.WriteLine($"frame_p99_ms,{Pct(0.99):F2},ms");
        Console.WriteLine($"frame_max_ms,{rest[^1]:F2},ms");
        Console.WriteLine($"total_ms,{all:F1},ms");
        Console.WriteLine($"checksum,{sink},0");
    }

    // Many small distinct methods compiled once: what loading a game's assemblies does.
    static void FirstJit()
    {
        for (int i = 0; i < 400; i++)
        {
            var p = Expression.Parameter(typeof(int));
            Expression body = p;
            for (int k = 0; k < 8; k++) body = Expression.Add(Expression.Multiply(body, Expression.Constant(i + k)), Expression.Constant(k));
            var f = Expression.Lambda<Func<int, int>>(body, p).Compile();
            sink += f(i);
        }
    }

    // Methods called often enough to tier up (tier0 -> tier1 with OSR): every tier-up is new code FEX must translate.
    static void TieringChurn()
    {
        var fns = new Func<int, int>[300];
        for (int i = 0; i < fns.Length; i++)
        {
            var p = Expression.Parameter(typeof(int));
            Expression body = Expression.Add(Expression.Multiply(p, Expression.Constant(i | 1)), Expression.Constant(i));
            fns[i] = Expression.Lambda<Func<int, int>>(body, p).Compile();
        }
        for (int r = 0; r < 200; r++)
            for (int i = 0; i < fns.Length; i++)
                sink += fns[i](r);
        Thread.Sleep(150); // let the tiering thread finish its background compiles
        for (int r = 0; r < 200; r++)
            for (int i = 0; i < fns.Length; i++)
                sink += fns[i](r);
    }

    sealed class Node { public Node? Next; public int V; public double[]? Data; }

    static Node? survivors;
    static void AllocGc()
    {
        Node? head = null;
        for (int i = 0; i < 2_000_000; i++)
        {
            var n = new Node { V = i, Next = (i & 7) == 0 ? head : null, Data = (i & 63) == 0 ? new double[16] : null };
            if ((i & 7) == 0) head = n;
            if ((i & 4095) == 0) { n.Next = survivors; survivors = n; }
        }
        sink += head!.V;
    }

    static void Collections()
    {
        var d = new Dictionary<string, int>();
        var keys = Enumerable.Range(0, 50_000).Select(i => "card_" + i).ToArray();
        for (int r = 0; r < 4; r++)
        {
            d.Clear();
            foreach (var k in keys) d[k] = k.Length;
            int s = 0;
            foreach (var k in keys) s += d[k];
            var l = new List<int>(keys.Length);
            foreach (var k in keys) l.Add(d[k] * 7919 ^ k[k.Length - 1]); // deterministic, unlike string.GetHashCode
            l.Sort();
            sink += s + l[0];
        }
    }

    interface IEffect { int Apply(int x); }
    sealed class A : IEffect { public int Apply(int x) => x + 1; }
    sealed class B : IEffect { public int Apply(int x) => x * 3; }
    sealed class C : IEffect { public int Apply(int x) => x ^ 0x55; }
    abstract class Card { public abstract int Play(int x); }
    sealed class Strike : Card { public override int Play(int x) => x - 6; }
    sealed class Defend : Card { public override int Play(int x) => x + 5; }

    static void Dispatch()
    {
        IEffect[] e = { new A(), new B(), new C() };
        Card[] c = { new Strike(), new Defend() };
        int x = 0;
        for (int i = 0; i < 20_000_000; i++) x = c[i & 1].Play(e[i % 3].Apply(x));
        sink += x;
    }

    static void VectorMath()
    {
        var pos = new Vector3[4096];
        var vel = new Vector3[4096];
        for (int i = 0; i < pos.Length; i++) vel[i] = new Vector3(i, -i, 0.5f * i) * 0.001f;
        var m = Matrix4x4.CreateRotationZ(0.01f);
        for (int f = 0; f < 600; f++)
            for (int i = 0; i < pos.Length; i++)
            {
                vel[i] = Vector3.Transform(vel[i], m) * 0.999f;
                pos[i] += vel[i];
            }
        sink += (int)pos[17].X;
    }

    // Copies of arrays of references go through coreclr's GC-ref memmove helper (SSE loads/stores + card marking).
    static void MemmoveRefs()
    {
        var src = new object[65536];
        for (int i = 0; i < src.Length; i++) src[i] = i;
        var dst = new object[65536];
        for (int r = 0; r < 600; r++) { Array.Copy(src, 0, dst, r & 15, src.Length - 16); }
        sink += dst.Length;
    }

    static void MemmoveBytes()
    {
        var src = new byte[1 << 20];
        var dst = new byte[1 << 20];
        new Random(1).NextBytes(src);
        for (int r = 0; r < 600; r++) src.AsSpan(r & 63).CopyTo(dst);
        sink += dst[5];
    }

    static void Strings()
    {
        var sb = new StringBuilder();
        for (int i = 0; i < 300_000; i++)
        {
            sb.Clear();
            sb.Append("Deal ").Append(i % 50).Append(" damage to ").Append("enemy_").Append(i & 7);
            var s = sb.ToString();
            sink += s.Length + (s.Contains("damage") ? 1 : 0) + int.Parse((i % 1000).ToString());
        }
    }

    record struct Item(int Id, int Cost, string Name);
    static void LinqPhase()
    {
        var items = Enumerable.Range(0, 20_000).Select(i => new Item(i, i % 7, "x" + (i % 100))).ToList();
        for (int r = 0; r < 20; r++)
        {
            var q = items.Where(i => i.Cost < 4).GroupBy(i => i.Name).Select(g => g.Sum(x => x.Cost)).OrderBy(x => x).ToArray();
            sink += q.Length;
        }
    }

    // Lock, Interlocked and a volatile handoff across threads: where x86 ordering really matters.
    static void ThreadsContended()
    {
        object gate = new();
        long total = 0;
        int counter = 0;
        Parallel.For(0, 4, new ParallelOptions { MaxDegreeOfParallelism = 4 }, w =>
        {
            for (int i = 0; i < 200_000; i++)
            {
                Interlocked.Increment(ref counter);
                if ((i & 15) == 0) lock (gate) total += i;
            }
        });
        sink += counter + (int)total;
    }
}
