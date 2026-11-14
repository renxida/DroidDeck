// Loads a Windows x64 ReadyToRun framework assembly into its own load context on linux-arm64 and runs code
// from it, after droiddeck-native-godot's portable_image made it portable. Also checks that the untouched
// file is refused, so the pass means the patch did it. Usage: R2rCheck <patched System.Linq.dll> <original>
using System;
using System.Collections;
using System.Runtime.Loader;

static class R2rCheck
{
    static int Sum(string path)
    {
        var context = new AssemblyLoadContext("r2r-check-" + Guid.NewGuid(), isCollectible: true);
        var linq = context.LoadFromAssemblyPath(System.IO.Path.GetFullPath(path));
        var range = linq.GetType("System.Linq.Enumerable", true).GetMethod("Range", new[] { typeof(int), typeof(int) });
        int sum = 0;
        foreach (int n in (IEnumerable)range.Invoke(null, new object[] { 1, 10 }))
            sum += n;
        return sum;
    }

    static int Main(string[] args)
    {
        int patched = Sum(args[0]);
        Console.WriteLine($"R2R_CHECK patched {args[0]}: sum {patched}");
        if (patched != 55)
            return 1;
        try
        {
            Sum(args[1]);
            Console.WriteLine("R2R_CHECK the untouched x64 image loaded too; the check proves nothing");
            return 2;
        }
        catch (BadImageFormatException e)
        {
            Console.WriteLine($"R2R_CHECK untouched image refused as expected: {e.Message}");
        }
        return 0;
    }
}
