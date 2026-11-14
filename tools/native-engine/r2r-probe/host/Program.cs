// Loads each given file in its own load context on this runtime and runs code from it; never fails.
using System;
using System.Collections;
using System.Linq;
using System.Runtime.Loader;

foreach (var path in args)
{
    try
    {
        var ctx = new AssemblyLoadContext("p-" + Guid.NewGuid(), true);
        var asm = ctx.LoadFromAssemblyPath(System.IO.Path.GetFullPath(path));
        string result;
        var linq = asm.GetType("System.Linq.Enumerable");
        if (linq != null)
        {
            int sum = 0;
            foreach (int n in (IEnumerable)linq.GetMethod("Range", new[] { typeof(int), typeof(int) }).Invoke(null, new object[] { 1, 10 }))
                sum += n;
            result = "Range sum " + sum;
        }
        else
        {
            result = "Answer " + asm.GetType("ProbeLib.Probe", true).GetMethod("Answer").Invoke(null, new object[] { 7 });
        }
        Console.WriteLine($"PROBE OK   {System.IO.Path.GetFileName(System.IO.Path.GetDirectoryName(path))}/{System.IO.Path.GetFileName(path)}: {result}");
    }
    catch (Exception e)
    {
        var inner = e is System.Reflection.TargetInvocationException t && t.InnerException != null ? t.InnerException : e;
        Console.WriteLine($"PROBE FAIL {System.IO.Path.GetFileName(System.IO.Path.GetDirectoryName(path))}/{System.IO.Path.GetFileName(path)}: {inner.GetType().Name}: {inner.Message}");
    }
}
