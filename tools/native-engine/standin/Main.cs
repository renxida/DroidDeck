// Stand-in game for the native startup gate. Contract (other tools rely on it; keep it stable):
//   stdout "STANDIN_DEPS loaded=<n> <name> <version>, ..."  once, in _Ready: the NuGet assemblies it loaded
//   stdout "FIRST_FRAME"                                    on the first _Process
//   stdout "MAINLOOP_OK frames=<N>"                         after N frames; then it quits with exit code 0
// N comes from "--quit-after-frames N" (user args after "--", or engine args), else the environment variable
// STANDIN_QUIT_AFTER_FRAMES; with neither it runs until closed.
//   stdout "STANDIN_STEAM_INIT ok" or "STANDIN_STEAM_INIT failed <result> <message>"   in _Ready, only with
// "--steam-init" (or STANDIN_STEAM_INIT=1): Steamworks.NET's own SteamAPI.InitEx, the call Slay the Spire 2
// makes, against whatever libsteam_api the launch put beside the game. Nothing in it is faked; it keeps running. Lines go straight to stdout through .NET's
// Console (unbuffered), not Godot's print, so a reader sees each one as it happens.
using System;
using System.Collections.Generic;
using System.Linq;
using Godot;

public partial class Main : Node2D
{
    long _quitAfter = -1;
    long _frames;
    long _inputs;
    long _sink;

    static void Say(string line)
    {
        Console.Out.WriteLine(line);
        Console.Out.Flush();
    }

    static bool Flag(string name, string env)
    {
        foreach (var args in new[] { OS.GetCmdlineUserArgs(), OS.GetCmdlineArgs() })
            if (args.Contains(name))
                return true;
        return System.Environment.GetEnvironmentVariable(env) == "1";
    }

    static void SteamInit()
    {
#if STANDIN_DEPS
        try
        {
            var result = Steamworks.SteamAPI.InitEx(out string message);
            Say(result == Steamworks.ESteamAPIInitResult.k_ESteamAPIInitResult_OK
                ? "STANDIN_STEAM_INIT ok"
                : $"STANDIN_STEAM_INIT failed {result} {message}");
        }
        catch (Exception e)
        {
            Say($"STANDIN_STEAM_INIT failed {e.GetType().Name} {e.Message}");
        }
#else
        Say("STANDIN_STEAM_INIT failed this stand-in was built without Steamworks.NET");
#endif
    }

    static long QuitAfter()
    {
        foreach (var args in new[] { OS.GetCmdlineUserArgs(), OS.GetCmdlineArgs() })
        {
            for (int i = 0; i < args.Length; i++)
            {
                string a = args[i];
                if (a.StartsWith("--quit-after-frames=") && long.TryParse(a.Substring(20), out var v))
                    return v;
                if (a == "--quit-after-frames" && i + 1 < args.Length && long.TryParse(args[i + 1], out v))
                    return v;
            }
        }
        var env = System.Environment.GetEnvironmentVariable("STANDIN_QUIT_AFTER_FRAMES");
        return long.TryParse(env, out var e) ? e : -1;
    }

    public override void _Ready()
    {
        _quitAfter = QuitAfter();
        var names = Deps.Touch();
        var loaded = AppDomain.CurrentDomain.GetAssemblies()
            .Select(a => a.GetName())
            .Where(n => names.Contains(n.Name))
            .OrderBy(n => n.Name, StringComparer.Ordinal)
            .Select(n => $"{n.Name} {n.Version}")
            .ToList();
        Say($"STANDIN_DEPS loaded={loaded.Count} " + (loaded.Count > 0 ? string.Join(", ", loaded) : "none")
            + $" arch={System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture}");
        if (Flag("--steam-init", "STANDIN_STEAM_INIT"))
            SteamInit();
    }

    public override void _Input(InputEvent e)
    {
        _inputs++;
    }

    public override void _Process(double delta)
    {
        _frames++;
        if (_frames == 1)
            Say("FIRST_FRAME");
        // A little steady churn (nodes, strings, the packages' code), so a long run would show a leak.
        var tip = new Label { Text = $"frame {_frames} inputs {_inputs}" };
        AddChild(tip);
        tip.QueueFree();
        if (_frames % 30 == 0)
            _sink += Deps.Work(_frames);
        if (_quitAfter >= 0 && _frames >= _quitAfter)
        {
            Say($"MAINLOOP_OK frames={_frames}");
            _quitAfter = -1;
            GetTree().Quit(0);
        }
    }
}

static class Deps
{
    // Touch one managed type (and run a little managed code) from each package, so each assembly loads and
    // JIT-compiles from the launch folder. Nothing here P/Invokes a native Windows library, and Steam is only a
    // value type here (SteamInit above is the one opt-in call).
    public static HashSet<string> Touch()
    {
        var names = new HashSet<string>(StringComparer.Ordinal);
#if STANDIN_DEPS
        long sink = 0;
        sink += Sentry.SentrySdk.IsEnabled ? 1 : 0;
        sink += Sentry.SentryId.Empty.ToString().Length;
        names.Add(typeof(Sentry.SentrySdk).Assembly.GetName().Name);

        sink += new HarmonyLib.Harmony("standin.gate").Id.Length;
        names.Add(typeof(HarmonyLib.Harmony).Assembly.GetName().Name);

        names.Add(typeof(System.MathEx).Assembly.GetName().Name);
        names.Add(typeof(MonoMod.ILHelpers).Assembly.GetName().Name);

        sink += SmartFormat.Smart.Format("{0} of {1}", 1, 2).Length;
        names.Add(typeof(SmartFormat.Smart).Assembly.GetName().Name);
        names.Add("ZString");

        sink += new Steamworks.CSteamID(76561197960265729UL).GetAccountID().m_AccountID;
        names.Add(typeof(Steamworks.CSteamID).Assembly.GetName().Name);

        sink += new Vortice.Mathematics.Color4(1f, 0.5f, 0.25f, 1f).ToString().Length;
        names.Add(typeof(Vortice.Mathematics.Color4).Assembly.GetName().Name);
        sink += Vortice.DXGI.Format.R8G8B8A8_UNorm.ToString().Length;
        names.Add(typeof(Vortice.DXGI.Format).Assembly.GetName().Name);
        sink += SharpGen.Runtime.Result.Ok.Success ? 1 : 0;
        names.Add(typeof(SharpGen.Runtime.Result).Assembly.GetName().Name);

        names.Add(typeof(JetBrains.Annotations.UsedImplicitlyAttribute).Assembly.GetName().Name);

        sink += (long)(System.IO.Hashing.XxHash64.HashToUInt64(new byte[] { 1, 2, 3, 4 }) & 0xff);
        names.Add(typeof(System.IO.Hashing.XxHash64).Assembly.GetName().Name);

        if (sink == long.MinValue)
            Console.WriteLine(sink);
#endif
        return names;
    }

    public static long Work(long frame)
    {
        long sink = string.Join(",", Enumerable.Range(0, 16).Select(i => (i * frame).ToString())).Length;
#if STANDIN_DEPS
        sink += SmartFormat.Smart.Format("{0}:{1}", frame, sink).Length;
        sink += (long)(System.IO.Hashing.XxHash64.HashToUInt64(BitConverter.GetBytes(frame)) & 0xff);
#endif
        return sink;
    }
}
