// Runs KestrelLib's campaign and prints a checksum that must not change whether the code came from the JIT
// or from ReadyToRun images. Usage: KestrelHost [dungeons]
using System;
using System.Linq;
using System.Runtime.InteropServices;
using KestrelLib;

namespace KestrelHost
{
    static class Program
    {
        static int Main(string[] args)
        {
            int dungeons = args.Length > 0 ? int.Parse(args[0]) : 200;
            var (wins, hash, best) = Campaign.Play(dungeons);
            string bag = string.Join(",", best.OrderBy(kv => kv.Key, StringComparer.Ordinal).Select(kv => kv.Key + "=" + kv.Value));
            Console.WriteLine($"KESTREL_R2R wins={wins}/{dungeons} best={bag} hash={hash:x16}");
            Console.Error.WriteLine($"runtime {RuntimeInformation.FrameworkDescription} {RuntimeInformation.RuntimeIdentifier} {RuntimeInformation.ProcessArchitecture}");
            return 0;
        }
    }
}
