using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;

// A stand-in for a Godot C# game's managed side: a large long-lived object graph (cards, nodes, caches),
// a frame loop that allocates short-lived garbage and replaces a small part of the graph each frame, and two
// worker threads. Prints one JSON line: frame-time percentiles and the runtime's own GC counts.
sealed class Node { public Node Next; public object Payload; public int[] Data; }

static class Program
{
    static volatile bool stop;

    static int Main(string[] args)
    {
        double seconds = Arg(args, "--seconds", 20);
        int liveMb = (int)Arg(args, "--live-mb", 300);
        int frameKb = (int)Arg(args, "--frame-kb", 512);
        double churn = Arg(args, "--churn", 0.002);
        var rnd = new Random(1);

        var graph = new Node[liveMb * 1024 * 1024 / 160];
        for (int i = 0; i < graph.Length; i++)
            graph[i] = new Node { Data = new int[16], Payload = i % 7 == 0 ? new string('c', 24) : null };
        for (int i = 0; i < graph.Length; i++) graph[i].Next = graph[rnd.Next(graph.Length)];

        var workers = new List<Thread>();
        for (int t = 0; t < 2; t++)
        {
            var thread = new Thread(() =>
            {
                var r = new Random(Environment.CurrentManagedThreadId);
                while (!stop) { var x = new byte[r.Next(64, 2048)]; x[0] = 1; Thread.Sleep(0); }
            }) { IsBackground = true };
            thread.Start();
            workers.Add(thread);
        }

        int gen0 = GC.CollectionCount(0), gen1 = GC.CollectionCount(1), gen2 = GC.CollectionCount(2);
        var pause0 = GC.GetTotalPauseDuration();
        var frames = new List<double>(100000);
        var clock = Stopwatch.StartNew();
        long checksum = 0;
        while (clock.Elapsed.TotalSeconds < seconds)
        {
            long begin = clock.ElapsedTicks;
            int bytes = 0;
            var garbage = new List<object>();
            while (bytes < frameKb * 1024)
            {
                var s = new string('x', rnd.Next(8, 120));
                garbage.Add(s);
                bytes += s.Length * 2 + 32;
            }
            int replace = (int)(graph.Length * churn);
            for (int i = 0; i < replace; i++)
            {
                int k = rnd.Next(graph.Length);
                graph[k] = new Node { Data = new int[16], Next = graph[rnd.Next(graph.Length)] };
            }
            for (int i = 0; i < 2000; i++) checksum += graph[rnd.Next(graph.Length)].Data.Length;
            frames.Add((clock.ElapsedTicks - begin) * 1000.0 / Stopwatch.Frequency);
        }
        stop = true;
        frames.Sort();
        double P(double q) => frames[Math.Min(frames.Count - 1, (int)(q * frames.Count))];
        Console.WriteLine("{" +
            $"\"frames\":{frames.Count},\"fps\":{frames.Count / seconds:F1},\"p50_ms\":{P(0.5):F2},\"p95_ms\":{P(0.95):F2}," +
            $"\"p99_ms\":{P(0.99):F2},\"max_ms\":{frames[^1]:F1},\"gen0\":{GC.CollectionCount(0) - gen0}," +
            $"\"gen1\":{GC.CollectionCount(1) - gen1},\"gen2\":{GC.CollectionCount(2) - gen2}," +
            $"\"gc_total\":{GC.CollectionCount(0)},\"pause_ms\":{(GC.GetTotalPauseDuration() - pause0).TotalMilliseconds:F0}," +
            $"\"heap_mb\":{GC.GetGCMemoryInfo().HeapSizeBytes >> 20},\"checksum\":{checksum % 1000}" + "}");
        return 0;
    }

    static double Arg(string[] args, string name, double fallback)
    {
        int i = Array.IndexOf(args, name);
        return i >= 0 && i + 1 < args.Length ? double.Parse(args[i + 1], System.Globalization.CultureInfo.InvariantCulture) : fallback;
    }
}
