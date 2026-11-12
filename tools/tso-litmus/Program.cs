using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime;
using System.Runtime.InteropServices;
using System.Runtime.Intrinsics;
using System.Runtime.Intrinsics.X86;
using System.Text;
using System.Threading;

namespace TsoLitmus;

// Expect.Forbidden: an outcome x86-TSO never produces, so every observation is a failure.
// Expect.Allowed: an outcome x86-TSO produces too (store buffering); counted for contrast only.
enum Expect { Forbidden, Allowed }

sealed class Result
{
    public string Name = "";
    public string Group = "";
    public Expect Expect = Expect.Forbidden;
    // "vector-tso" when the static analysis says only FEX's vector/memcpy TSO keeps this ordered,
    // "none" when base TSO (ordered integer accesses) is enough.
    public string Needs = "none";
    public long Samples;
    public long Observed;
    public long Fresh = -1;
    public double Seconds;
    public string? Note;
    public readonly SortedDictionary<string, long> Detail = new(StringComparer.Ordinal);

    public long Failures => Expect == Expect.Forbidden ? Observed : 0;
}

sealed class Options
{
    public double Seconds = 60;
    public string Config = "unnamed";
    public string? Out;
    public HashSet<string>? Only;

    public static Options? Parse(string[] args)
    {
        var o = new Options();
        for (int i = 0; i < args.Length; i++)
        {
            string a = args[i];
            string? next = i + 1 < args.Length ? args[i + 1] : null;
            switch (a)
            {
                case "--seconds" when next != null && double.TryParse(next, NumberStyles.Float, CultureInfo.InvariantCulture, out var s) && s > 0:
                    o.Seconds = s; i++; break;
                case "--config" when next != null:
                    o.Config = next; i++; break;
                case "--out" when next != null:
                    o.Out = next; i++; break;
                case "--only" when next != null:
                    o.Only = new HashSet<string>(next.Split(',', StringSplitOptions.RemoveEmptyEntries), StringComparer.Ordinal); i++; break;
                case "--help":
                    return null;
                default:
                    // A launcher may append arguments meant for a game; they are ignored.
                    break;
            }
        }
        return o;
    }

    public bool Wants(string group) => Only == null || Only.Contains(group);
}

static class Json
{
    public static string Str(string s)
    {
        var b = new StringBuilder(s.Length + 2).Append('"');
        foreach (char c in s)
        {
            switch (c)
            {
                case '"': b.Append("\\\""); break;
                case '\\': b.Append("\\\\"); break;
                case '\n': b.Append("\\n"); break;
                case '\r': b.Append("\\r"); break;
                case '\t': b.Append("\\t"); break;
                default:
                    if (c < 0x20) b.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                    else b.Append(c);
                    break;
            }
        }
        return b.Append('"').ToString();
    }

    public static string Value(object? v) => v switch
    {
        null => "null",
        string s => Str(s),
        bool b => b ? "true" : "false",
        double d => double.IsFinite(d) ? d.ToString("R", CultureInfo.InvariantCulture) : "null",
        int i => i.ToString(CultureInfo.InvariantCulture),
        long l => l.ToString(CultureInfo.InvariantCulture),
        IDictionary<string, long> m => "{" + string.Join(",", m.Select(kv => Str(kv.Key) + ":" + Value(kv.Value))) + "}",
        IDictionary<string, string> m => "{" + string.Join(",", m.Select(kv => Str(kv.Key) + ":" + Str(kv.Value))) + "}",
        _ => Str(v.ToString() ?? ""),
    };

    public static string Obj(params (string Key, object? Value)[] fields) =>
        "{" + string.Join(",", fields.Select(f => Str(f.Key) + ":" + Value(f.Value))) + "}";
}

sealed class Report : IDisposable
{
    readonly Options options;
    readonly StreamWriter? file;
    readonly Stopwatch clock = Stopwatch.StartNew();
    long failures;
    readonly List<string> failed = new();
    readonly List<double> rates = new();

    public Report(Options options)
    {
        this.options = options;
        if (options.Out != null)
        {
            try
            {
                Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(options.Out)) ?? ".");
                file = new StreamWriter(new FileStream(options.Out, FileMode.Create, FileAccess.Write, FileShare.ReadWrite)) { AutoFlush = true };
            }
            catch (Exception e)
            {
                Say("results file " + options.Out + " not writable (" + e.GetType().Name + ": " + e.Message + "); stdout only");
            }
        }
    }

    public static void Say(string line)
    {
        Console.Out.WriteLine("tso-litmus: " + line);
        Console.Out.Flush();
    }

    void Record(string json)
    {
        Console.Out.WriteLine("tso-litmus-json: " + json);
        Console.Out.Flush();
        try { file?.WriteLine(json); } catch (IOException) { }
    }

    public void Environment()
    {
        var env = new SortedDictionary<string, string>(StringComparer.Ordinal);
        foreach (DictionaryEntry e in System.Environment.GetEnvironmentVariables())
        {
            string key = e.Key?.ToString() ?? "";
            if (key.StartsWith("FEX_", StringComparison.OrdinalIgnoreCase) || key.StartsWith("DOTNET_", StringComparison.OrdinalIgnoreCase)
                || key.StartsWith("COMPlus_", StringComparison.OrdinalIgnoreCase))
                env[key] = e.Value?.ToString() ?? "";
        }
        var (erms, fsrm) = CpuFeatures();
        string wine = WineVersion();
        bool concurrent = GCSettings.LatencyMode != GCLatencyMode.Batch;
        Record(Json.Obj(
            ("kind", "env"), ("config", options.Config), ("seconds", options.Seconds),
            ("runtime", RuntimeInformation.FrameworkDescription), ("version", System.Environment.Version.ToString()),
            ("os", RuntimeInformation.OSDescription), ("arch", RuntimeInformation.ProcessArchitecture.ToString()),
            ("wine", wine), ("cpus", System.Environment.ProcessorCount),
            ("gc_concurrent", concurrent), ("gc_latency", GCSettings.LatencyMode.ToString()), ("server_gc", GCSettings.IsServerGC),
            ("erms", erms), ("fsrm", fsrm), ("avx", Avx.IsSupported), ("avx2", Avx2.IsSupported),
            ("vector256", Vector256.IsHardwareAccelerated), ("env", env)));
        Say(string.Format(CultureInfo.InvariantCulture,
            "env config={0} runtime={1} wine={2} cpus={3} gc={4} erms={5} avx2={6} {7}",
            options.Config, System.Environment.Version, wine, System.Environment.ProcessorCount,
            concurrent ? "concurrent" : "blocking", erms ? "yes" : "no", Avx2.IsSupported ? "yes" : "no",
            string.Join(" ", env.Where(kv => kv.Key.StartsWith("FEX_", StringComparison.OrdinalIgnoreCase) && kv.Key.Contains("TSO", StringComparison.OrdinalIgnoreCase)
                || kv.Key.Equals("DOTNET_gcConcurrent", StringComparison.OrdinalIgnoreCase)).Select(kv => kv.Key + "=" + kv.Value))));
    }

    public void Test(Result r)
    {
        failures += r.Failures;
        if (r.Failures > 0) failed.Add(r.Name);
        Record(Json.Obj(
            ("kind", "test"), ("name", r.Name), ("group", r.Group),
            ("expect", r.Expect == Expect.Forbidden ? "forbidden" : "allowed"), ("needs", r.Needs),
            ("samples", r.Samples), ("observed", r.Observed), ("failures", r.Failures), ("fresh", r.Fresh),
            ("seconds", Math.Round(r.Seconds, 3)), ("note", r.Note), ("detail", r.Detail)));
        var line = new StringBuilder();
        line.Append("test=").Append(r.Name).Append(" group=").Append(r.Group)
            .Append(r.Expect == Expect.Forbidden ? " failures=" : " observed=").Append(r.Observed)
            .Append(" samples=").Append(r.Samples);
        if (r.Fresh >= 0) line.Append(" fresh=").Append(r.Fresh);
        line.Append(" seconds=").Append(r.Seconds.ToString("0.0", CultureInfo.InvariantCulture));
        foreach (var kv in r.Detail) line.Append(' ').Append(kv.Key).Append('=').Append(kv.Value);
        if (r.Note != null) line.Append(" note=\"").Append(r.Note).Append('"');
        Say(line.ToString());
    }

    public void Bench(string name, double mbps, double seconds)
    {
        if (mbps > 0) rates.Add(mbps);
        Record(Json.Obj(("kind", "bench"), ("name", name), ("unit", "MB/s"), ("value", Math.Round(mbps, 2)),
            ("seconds", Math.Round(seconds, 3))));
        Say("bench=" + name + " MBps=" + mbps.ToString("0.0", CultureInfo.InvariantCulture));
    }

    public void Done()
    {
        double geomean = rates.Count == 0 ? 0 : Math.Exp(rates.Sum(Math.Log) / rates.Count);
        Record(Json.Obj(("kind", "done"), ("config", options.Config), ("failures", failures),
            ("failed", string.Join(",", failed)), ("throughput", Math.Round(geomean, 2)),
            ("seconds", Math.Round(clock.Elapsed.TotalSeconds, 3))));
        Say(string.Format(CultureInfo.InvariantCulture, "done config={0} failures={1}{2} throughput={3:0.0}MB/s seconds={4:0}",
            options.Config, failures, failed.Count > 0 ? " (" + string.Join(",", failed) + ")" : "", geomean, clock.Elapsed.TotalSeconds));
    }

    public void Dispose() => file?.Dispose();

    static (bool Erms, bool Fsrm) CpuFeatures()
    {
        if (!X86Base.IsSupported) return (false, false);
        if (X86Base.CpuId(0, 0).Eax < 7) return (false, false);
        var leaf7 = X86Base.CpuId(7, 0);
        return ((leaf7.Ebx & (1 << 9)) != 0, (leaf7.Edx & (1 << 4)) != 0);
    }

    static unsafe string WineVersion()
    {
        if (!OperatingSystem.IsWindows()) return "none";
        try
        {
            if (NativeLibrary.TryLoad("ntdll.dll", out var ntdll) && NativeLibrary.TryGetExport(ntdll, "wine_get_version", out var export))
                return Marshal.PtrToStringAnsi((IntPtr)((delegate* unmanaged<sbyte*>)export)()) ?? "unknown";
        }
        catch (Exception) { }
        return "none";
    }
}

static class Program
{
    sealed record Step(string Group, double Weight, Func<double, Result?> Run);

    static int Main(string[] args)
    {
        var options = Options.Parse(args);
        if (options == null)
        {
            Console.Error.WriteLine("usage: tso-litmus [--seconds N] [--config NAME] [--out FILE] [--only a,b,c,d,e]");
            return 2;
        }
        using var report = new Report(options);
        report.Environment();
        int pairs = Math.Clamp(Environment.ProcessorCount / 2, 1, 3);
        var steps = new List<Step>
        {
            new("a", 0.05, s => RefTests.Publish(RefTests.Mode.ArrayCopy, pairs, s)),
            new("a", 0.05, s => RefTests.Publish(RefTests.Mode.Struct6, pairs, s)),
            new("a", 0.03, s => RefTests.Publish(RefTests.Mode.ListAdd, pairs, s)),
            new("a", 0.03, s => RefTests.Publish(RefTests.Mode.PlainStore, pairs, s)),
            new("b", 0.10, s => RefTests.Churn(GCLatencyMode.Interactive, s)),
            new("b", 0.10, s => RefTests.Churn(GCLatencyMode.Batch, s)),
            new("c", 0.05, s => Litmus.MessagePassing<IntKind, FloatKind>("volatile-float-flag", "c", "vector-tso", pairs, s)),
            new("c", 0.04, s => Litmus.MessagePassing<FloatKind, IntKind>("volatile-float-data", "c", "none", pairs, s)),
            new("d", 0.04, s => Litmus.MessagePassing<IntKind, IntKind>("mp-int", "d", "none", pairs, s)),
            new("d", 0.05, s => Litmus.MessagePassing<SseKind, SseKind>("mp-sse", "d", "vector-tso", pairs, s)),
            new("d", 0.05, s => Litmus.MessagePassing<IntKind, SseKind>("mp-int-data-sse-flag", "d", "vector-tso", pairs, s)),
            new("d", 0.04, s => Litmus.MessagePassing<SseKind, IntKind>("mp-sse-data-int-flag", "d", "none", pairs, s)),
            new("d", 0.02, s => Litmus.StoreBuffering<IntKind>("sb-int", pairs, s)),
            new("d", 0.02, s => Litmus.StoreBuffering<SseKind>("sb-sse", pairs, s)),
            new("d", 0.04, s => Litmus.Iriw<IntKind>("iriw-int", "none", s)),
            new("d", 0.04, s => Litmus.Iriw<SseKind>("iriw-sse", "vector-tso", s)),
        };
        double benchWeight = 0.25;
        double total = steps.Where(s => options.Wants(s.Group)).Sum(s => s.Weight) + (options.Wants("e") ? benchWeight : 0);
        double scale = total > 0 ? options.Seconds / total : 0;
        foreach (var step in steps)
        {
            if (!options.Wants(step.Group)) continue;
            Result? r;
            try
            {
                r = step.Run(step.Weight * scale);
            }
            catch (Exception e)
            {
                Report.Say("test in group " + step.Group + " threw " + e.GetType().Name + ": " + e.Message);
                r = new Result { Name = "exception-" + step.Group, Group = step.Group, Observed = 1, Note = e.GetType().Name };
            }
            if (r != null) report.Test(r);
        }
        if (options.Wants("e")) Bench.Run(report, benchWeight * scale);
        report.Done();
        return 0;
    }
}

// Runs worker threads for a fixed time; a worker that does not finish is reported, not waited on forever.
static class Workers
{
    public static volatile bool Abort;

    public static bool Run(IReadOnlyList<Action> bodies, double seconds, Action? stop = null)
    {
        Abort = false;
        var threads = bodies.Select((body, i) => new Thread(() => body()) { IsBackground = true, Name = "litmus-" + i }).ToList();
        foreach (var t in threads) t.Start();
        if (stop != null)
        {
            Thread.Sleep(TimeSpan.FromSeconds(seconds));
            stop();
        }
        var deadline = Stopwatch.StartNew();
        double grace = seconds * 2 + 10;
        bool finished = true;
        foreach (var t in threads)
        {
            var left = TimeSpan.FromSeconds(Math.Max(0.1, grace - deadline.Elapsed.TotalSeconds));
            if (!t.Join(left)) finished = false;
        }
        if (!finished)
        {
            Abort = true;
            foreach (var t in threads) t.Join(TimeSpan.FromSeconds(5));
        }
        return finished;
    }
}
