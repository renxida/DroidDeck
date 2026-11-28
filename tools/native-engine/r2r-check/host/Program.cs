// Loads Windows x64 assemblies, as droiddeck-native-godot's portable_image leaves them, into their own load
// contexts on linux-arm64 .NET and runs code from them. Arguments are pairs: "ok:<path>" must load and run,
// "refused:<path>" must be refused (the untouched x64 image), so a pass means the patch did it.
using System;
using System.Collections;
using System.Runtime.Loader;

static class R2rCheck
{
    static string Run(string path)
    {
        var context = new AssemblyLoadContext("r2r-check-" + Guid.NewGuid(), isCollectible: true);
        var asm = context.LoadFromAssemblyPath(System.IO.Path.GetFullPath(path));
        var linq = asm.GetType("System.Linq.Enumerable");
        if (linq != null)
        {
            int sum = 0;
            foreach (int n in (IEnumerable)linq.GetMethod("Range", new[] { typeof(int), typeof(int) }).Invoke(null, new object[] { 1, 10 }))
                sum += n;
            return sum == 55 ? "Range(1,10) sums to 55" : throw new Exception("wrong sum " + sum);
        }
        var answer = (int)asm.GetType("ProbeLib.Probe", true).GetMethod("Answer").Invoke(null, new object[] { 7 });
        return answer == 42 ? "Answer(7) is 42" : throw new Exception("wrong answer " + answer);
    }

    static int Main(string[] args)
    {
        int bad = 0;
        foreach (var arg in args)
        {
            var expect = arg.Substring(0, arg.IndexOf(':'));
            var path = arg.Substring(arg.IndexOf(':') + 1);
            string outcome;
            bool loaded;
            try { outcome = Run(path); loaded = true; }
            catch (Exception e) { outcome = (e.InnerException ?? e).GetType().Name + ": " + (e.InnerException ?? e).Message; loaded = false; }
            bool pass = (expect == "ok") == loaded;
            if (!pass) bad++;
            Console.WriteLine($"R2R_CHECK {(pass ? "pass" : "FAIL")} expect {expect}: {path}: {outcome}");
        }
        return bad == 0 ? 0 : 1;
    }
}
