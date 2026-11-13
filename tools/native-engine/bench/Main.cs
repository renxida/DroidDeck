// Stand-in for StS2's CPU work: card-game logic in C# (small objects, LINQ, strings,
// dictionaries, virtual calls, GC pressure) plus Godot interop (node churn, property sets,
// signals). Headless, uncapped: per-frame cost is pure CPU. Prints one JSON line and quits.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using Godot;

public partial class Main : Node2D
{
    [Signal] public delegate void CardPlayedEventHandler(int id, string name);

    abstract class Power { public abstract int Modify(int dmg); }
    sealed class Strength : Power { public int N; public override int Modify(int d) => d + N; }
    sealed class Weak : Power { public override int Modify(int d) => d * 3 / 4; }
    sealed class Vuln : Power { public override int Modify(int d) => d * 3 / 2; }
    sealed class Card { public int Id; public string Name; public int Cost; public int Dmg; public List<string> Tags = new(); }
    sealed class Creature { public int Hp = 999999; public List<Power> Powers = new(); public Dictionary<string, int> Stacks = new(); }

    const int Warmup = 240, Frames = 1200, Nodes = 1500;
    readonly Random _rng = new(42);
    readonly List<Card> _deck = new();
    readonly List<Creature> _enemies = new();
    readonly List<Node2D> _sprites = new();
    readonly List<double> _times = new();
    readonly Stopwatch _sw = new();
    int _frame, _played;
    long _sink;
    double _startMs;

    public override void _Ready()
    {
        _startMs = Time.GetTicksMsec();
        for (int i = 0; i < 400; i++)
        {
            var c = new Card { Id = i, Name = $"Card_{i % 75}+{i / 75}", Cost = i % 4, Dmg = 3 + i % 11 };
            for (int t = 0; t < 1 + i % 4; t++) c.Tags.Add(t switch { 0 => "Attack", 1 => "Skill", 2 => "Exhaust", _ => "Ethereal" });
            _deck.Add(c);
        }
        for (int e = 0; e < 5; e++)
        {
            var cr = new Creature();
            cr.Powers.Add(new Strength { N = e });
            if (e % 2 == 0) cr.Powers.Add(new Weak());
            if (e % 3 == 0) cr.Powers.Add(new Vuln());
            _enemies.Add(cr);
        }
        for (int i = 0; i < Nodes; i++)
        {
            var n = new Node2D { Name = $"S{i}" };
            AddChild(n);
            _sprites.Add(n);
        }
        CardPlayed += OnCardPlayed;
    }

    void OnCardPlayed(int id, string name) { _played++; _sink += name.Length + id; }

    public override void _Process(double delta)
    {
        _sw.Restart();
        // Interop: move every node (StS2 animates UI/cards from C# each frame).
        float t = _frame * 0.016f;
        for (int i = 0; i < _sprites.Count; i++)
        {
            var s = _sprites[i];
            s.Position = new Vector2(Mathf.Sin(t + i) * 100f, Mathf.Cos(t * 0.5f + i) * 50f);
            s.Rotation = t + i * 0.01f;
        }
        // Node churn: tooltips/VFX spawned and freed.
        for (int i = 0; i < 40; i++)
        {
            var tmp = new Control { Name = "Tip" };
            AddChild(tmp);
            tmp.Size = new Vector2(100 + i, 40);
            tmp.QueueFree();
        }
        // Game logic: draw a hand, filter, compute damage through powers, log strings.
        var hand = _deck.OrderBy(_ => _rng.Next()).Take(10).ToList();
        var attacks = hand.Where(c => c.Tags.Contains("Attack") && c.Cost <= 3).GroupBy(c => c.Cost).ToDictionary(g => g.Key, g => g.ToList());
        foreach (var (cost, cards) in attacks)
            foreach (var c in cards)
                foreach (var en in _enemies)
                {
                    int d = c.Dmg;
                    foreach (var p in en.Powers) d = p.Modify(d);
                    en.Hp -= d;
                    en.Stacks[c.Name] = en.Stacks.TryGetValue(c.Name, out var v) ? v + 1 : 1;
                    var log = $"{c.Name} hits for {d} (cost {cost})";
                    _sink += log.Length;
                    EmitSignal(SignalName.CardPlayed, c.Id, c.Name);
                }
        foreach (var en in _enemies) if (en.Hp < 0) { en.Hp = 999999; en.Stacks.Clear(); }
        // Short-lived garbage like UI strings and event args.
        var junk = new List<int[]>(64);
        for (int i = 0; i < 64; i++) junk.Add(new int[32 + i]);
        _sink += junk.Count;

        _sw.Stop();
        if (_frame >= Warmup) _times.Add(_sw.Elapsed.TotalMilliseconds);
        _frame++;
        if (_frame == 1) GD.Print($"FIRST_FRAME_MS {Time.GetTicksMsec()}");
        if (_frame == Warmup + Frames) Finish();
    }

    void Finish()
    {
        var s = _times.OrderBy(x => x).ToArray();
        double P(double q) => s[Math.Min(s.Length - 1, (int)(q * s.Length))];
        GD.Print("BENCH_JSON {" +
            $"\"arch\":\"{System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture}\"," +
            $"\"frames\":{s.Length},\"mean_ms\":{s.Average():F4},\"p50_ms\":{P(0.5):F4},\"p95_ms\":{P(0.95):F4},\"p99_ms\":{P(0.99):F4}," +
            $"\"gc0\":{GC.CollectionCount(0)},\"gc1\":{GC.CollectionCount(1)},\"gc2\":{GC.CollectionCount(2)}," +
            $"\"played\":{_played},\"wall_ms\":{Time.GetTicksMsec()},\"sink\":{_sink % 1000}" + "}");
        GetTree().Quit();
    }
}
