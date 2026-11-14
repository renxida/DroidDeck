// A .NET program shaped like a game's runtime load, to look for hangs under Wine + ARM64EC FEX off-device:
// allocating threads (small and large-object heap), lock contention, Monitor.Wait/Pulse, async tasks on the
// thread pool, Parallel.For, thread churn and forced GCs (CoreCLR stops every managed thread for those).
// One heartbeat line a second; the harness calls it hung when the heartbeats stop.
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Threading;
using System.Threading.Tasks;

static class Program
{
    static volatile bool Stop;
    static long Ops, Churned, Pulses, TaskRuns;
    static readonly object Gate = new object();
    static readonly object Mon = new object();
    static readonly ConcurrentDictionary<int, string> Dict = new ConcurrentDictionary<int, string>();

    static void Worker(object arg)
    {
        int id = (int)arg;
        var rng = new Random(id * 7919 + 1);
        var keep = new object[256];
        while (!Stop)
        {
            int k = rng.Next(keep.Length);
            int r = rng.Next(1000);
            keep[k] = r < 3 ? new byte[100_000 + rng.Next(200_000)]   // large object heap
                    : r < 500 ? new string('x', rng.Next(1, 200))
                    : (object)new List<int>(rng.Next(1, 64)) { id, r };
            if (r % 5 == 0) { lock (Gate) { Ops++; } }
            if (r % 50 == 0) Dict[rng.Next(4096)] = r.ToString();
            if (r == 999) Thread.Sleep(1);
            Interlocked.Increment(ref Ops);
        }
    }

    static async Task AsyncLoop(int id)
    {
        var rng = new Random(id);
        while (!Stop)
        {
            await Task.Delay(rng.Next(1, 5));
            await Task.Run(() => { var a = new int[rng.Next(16, 4096)]; a[0] = id; Interlocked.Increment(ref TaskRuns); });
        }
    }

    static int Main(string[] args)
    {
        int seconds = args.Length > 0 ? int.Parse(args[0]) : 120;
        var threads = new List<Thread>();
        for (int i = 0; i < 10; i++)
        {
            var t = new Thread(Worker) { IsBackground = true, Name = "worker" + i };
            t.Start(i);
            threads.Add(t);
        }
        var waiter = new Thread(() =>
        {
            while (!Stop) { lock (Mon) { Monitor.Wait(Mon, 1000); Interlocked.Increment(ref Pulses); } }
        }) { IsBackground = true };
        waiter.Start();
        var pulser = new Thread(() =>
        {
            while (!Stop) { lock (Mon) Monitor.Pulse(Mon); Thread.Sleep(1); }
        }) { IsBackground = true };
        pulser.Start();
        var churner = new Thread(() =>
        {
            while (!Stop)
            {
                var t = new Thread(() => { var x = new byte[4096]; x[1] = 1; });
                t.Start();
                t.Join();
                Interlocked.Increment(ref Churned);
                Thread.Sleep(5);
            }
        }) { IsBackground = true };
        churner.Start();
        var tasks = new List<Task>();
        for (int i = 0; i < 8; i++) tasks.Add(AsyncLoop(i));

        long last = -1;
        var sw = Stopwatch.StartNew();
        for (int t = 1; t <= seconds; t++)
        {
            while (sw.ElapsedMilliseconds < t * 1000L) Thread.Sleep(50);
            if (t % 5 == 0) GC.Collect(2, GCCollectionMode.Forced, blocking: true);
            if (t % 7 == 0) Parallel.For(0, 64, i => { var a = new double[1000]; for (int j = 0; j < a.Length; j++) a[j] = Math.Sin(i + j); });
            long ops = Interlocked.Read(ref Ops);
            Console.WriteLine($"hb t={t} ops={ops} gcs={GC.CollectionCount(0)}/{GC.CollectionCount(2)} churned={Churned} pulses={Pulses} tasks={TaskRuns}{(ops == last ? " STALLED" : "")}");
            last = ops;
        }
        Stop = true;
        foreach (var th in threads) th.Join(30000);
        Task.WaitAll(tasks.ToArray(), 30000);
        Console.WriteLine("done");
        return 0;
    }
}
