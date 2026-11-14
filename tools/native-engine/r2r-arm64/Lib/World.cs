// A small deterministic dungeon simulation: many small methods, interfaces, generics, LINQ, records and
// lambdas, so this assembly has a few hundred methods for the JIT (or ReadyToRun code) to provide.
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace KestrelLib
{
    public enum Element { Fire, Frost, Storm, Stone }

    public readonly record struct Point(int X, int Y)
    {
        public Point Step(int dx, int dy) => new(X + dx, Y + dy);
        public int Manhattan(Point other) => Math.Abs(X - other.X) + Math.Abs(Y - other.Y);
    }

    public record Item(string Name, Element Element, int Power, int Weight);

    public sealed class Random64
    {
        ulong state;
        public Random64(ulong seed) { state = seed * 0xD1342543DE82EF95UL + 7; }
        public ulong NextRaw() { state ^= state >> 12; state ^= state << 25; state ^= state >> 27; return state * 0x2545F4914F6CDD1DUL; }
        public int Next(int max) => (int)(NextRaw() % (ulong)Math.Max(1, max));
        public T Pick<T>(IReadOnlyList<T> list) => list[Next(list.Count)];
        public void Shuffle<T>(IList<T> list) { for (int i = list.Count - 1; i > 0; i--) { int j = Next(i + 1); (list[i], list[j]) = (list[j], list[i]); } }
    }

    public interface IStatus { string Name { get; } int Tick(Creature owner); bool Expired { get; } }

    public sealed class Burn : IStatus
    {
        int turns;
        public Burn(int turns) { this.turns = turns; }
        public string Name => "burn";
        public bool Expired => turns <= 0;
        public int Tick(Creature owner) { turns--; return owner.Hurt(2 + turns); }
    }

    public sealed class Chill : IStatus
    {
        int turns;
        public Chill(int turns) { this.turns = turns; }
        public string Name => "chill";
        public bool Expired => turns <= 0;
        public int Tick(Creature owner) { turns--; owner.Speed = Math.Max(1, owner.Speed - 1); return 0; }
    }

    public sealed class Shield : IStatus
    {
        int amount;
        public Shield(int amount) { this.amount = amount; }
        public string Name => "shield";
        public bool Expired => amount <= 0;
        public int Tick(Creature owner) { owner.Armor += amount / 2; amount /= 2; return 0; }
    }

    public class Creature
    {
        public string Name { get; }
        public int Hp { get; private set; }
        public int MaxHp { get; }
        public int Armor { get; set; }
        public int Speed { get; set; }
        public Point Pos { get; set; }
        public Element Weakness { get; }
        public List<IStatus> Statuses { get; } = new();
        public List<Item> Bag { get; } = new();

        public Creature(string name, int hp, int speed, Element weakness, Point pos)
        {
            Name = name; Hp = MaxHp = hp; Speed = speed; Weakness = weakness; Pos = pos;
        }

        public bool Alive => Hp > 0;
        public int Hurt(int amount) { int through = Math.Max(0, amount - Armor); Armor = Math.Max(0, Armor - amount); Hp -= through; return through; }
        public void Heal(int amount) => Hp = Math.Min(MaxHp, Hp + amount);
        public int Load => Bag.Sum(i => i.Weight);
        public Item Best(Element against) => Bag.OrderByDescending(i => Score(i, against)).ThenBy(i => i.Name, StringComparer.Ordinal).FirstOrDefault();
        static int Score(Item item, Element against) => item.Power * (item.Element == against ? 3 : 1) - item.Weight;

        public int TickStatuses()
        {
            int total = 0;
            foreach (var s in Statuses) total += s.Tick(this);
            Statuses.RemoveAll(s => s.Expired);
            return total;
        }
    }

    public static class Loot
    {
        static readonly string[] Prefix = { "Old", "Bright", "Grim", "Swift", "Heavy" };
        static readonly string[] Kind = { "Blade", "Staff", "Charm", "Bow", "Tome" };

        public static Item Roll(Random64 rng) =>
            new(rng.Pick(Prefix) + " " + rng.Pick(Kind), (Element)rng.Next(4), 3 + rng.Next(12), 1 + rng.Next(6));

        public static IEnumerable<Item> Chest(Random64 rng, int size) => Enumerable.Range(0, size).Select(_ => Roll(rng));

        public static Dictionary<Element, int> PowerByElement(IEnumerable<Item> items) =>
            items.GroupBy(i => i.Element).ToDictionary(g => g.Key, g => g.Sum(i => i.Power));
    }

    public sealed class Map
    {
        readonly bool[,] walls;
        public int Width { get; }
        public int Height { get; }

        public Map(int width, int height, Random64 rng)
        {
            Width = width; Height = height; walls = new bool[width, height];
            for (int x = 0; x < width; x++)
                for (int y = 0; y < height; y++)
                    walls[x, y] = (x == 0 || y == 0 || x == width - 1 || y == height - 1) || rng.Next(100) < 18;
            walls[1, 1] = false;
        }

        public bool Open(Point p) => p.X >= 0 && p.Y >= 0 && p.X < Width && p.Y < Height && !walls[p.X, p.Y];

        public IEnumerable<Point> Neighbours(Point p)
        {
            foreach (var (dx, dy) in new[] { (1, 0), (-1, 0), (0, 1), (0, -1) })
            {
                var q = p.Step(dx, dy);
                if (Open(q)) yield return q;
            }
        }

        public List<Point> Path(Point from, Point to)
        {
            var came = new Dictionary<Point, Point> { [from] = from };
            var queue = new Queue<Point>();
            queue.Enqueue(from);
            while (queue.Count > 0)
            {
                var p = queue.Dequeue();
                if (p == to) break;
                foreach (var q in Neighbours(p).Where(q => !came.ContainsKey(q)))
                {
                    came[q] = p;
                    queue.Enqueue(q);
                }
            }
            if (!came.ContainsKey(to)) return new List<Point>();
            var path = new List<Point>();
            for (var p = to; p != from; p = came[p]) path.Add(p);
            path.Reverse();
            return path;
        }
    }

    public sealed class Dungeon
    {
        readonly Random64 rng;
        readonly Map map;
        public Creature Hero { get; }
        public List<Creature> Monsters { get; } = new();
        public StringBuilder Journal { get; } = new();
        public int Turn { get; private set; }

        public Dungeon(ulong seed, int depth)
        {
            rng = new Random64(seed);
            map = new Map(24 + depth % 8, 16 + depth % 5, rng);
            Hero = new Creature("Hero", 60 + depth, 4, Element.Stone, new Point(1, 1));
            Hero.Bag.AddRange(Loot.Chest(rng, 3));
            for (int i = 0; i < 3 + depth % 4; i++)
                Monsters.Add(new Creature("M" + i, 12 + rng.Next(10 + depth), 2 + rng.Next(3), (Element)rng.Next(4), RandomOpen()));
        }

        Point RandomOpen()
        {
            for (int tries = 0; tries < 1000; tries++)
            {
                var p = new Point(rng.Next(map.Width), rng.Next(map.Height));
                if (map.Open(p)) return p;
            }
            return new Point(1, 1);
        }

        public Creature Nearest() => Monsters.Where(m => m.Alive).OrderBy(m => m.Pos.Manhattan(Hero.Pos)).ThenBy(m => m.Name, StringComparer.Ordinal).FirstOrDefault();

        public bool Step()
        {
            Turn++;
            Hero.TickStatuses();
            foreach (var m in Monsters.Where(m => m.Alive)) m.TickStatuses();
            var target = Nearest();
            if (target == null || !Hero.Alive || Turn > 400) return false;
            if (Hero.Pos.Manhattan(target.Pos) > 1)
            {
                var path = map.Path(Hero.Pos, target.Pos);
                if (path.Count == 0) { target.Hurt(target.Hp); Journal.Append('x'); return true; }
                Hero.Pos = path[0];
            }
            else
            {
                Attack(Hero, target);
            }
            foreach (var m in Monsters.Where(m => m.Alive && m.Pos.Manhattan(Hero.Pos) <= 1)) Attack(m, Hero);
            if (Turn % 7 == 0) Hero.Bag.Add(Loot.Roll(rng));
            if (Hero.Load > 30) Hero.Bag.Remove(Hero.Bag.OrderBy(i => i.Power).First());
            return true;
        }

        void Attack(Creature from, Creature to)
        {
            var item = from.Best(to.Weakness);
            int power = (item?.Power ?? 3) * (item != null && item.Element == to.Weakness ? 2 : 1);
            int dealt = to.Hurt(power + rng.Next(3));
            switch (item?.Element)
            {
                case Element.Fire: to.Statuses.Add(new Burn(2)); break;
                case Element.Frost: to.Statuses.Add(new Chill(2)); break;
                case Element.Stone: from.Statuses.Add(new Shield(4)); break;
                case Element.Storm: if (rng.Next(3) == 0) to.Hurt(power / 2); break;
            }
            Journal.Append(dealt > 9 ? 'X' : dealt > 0 ? '+' : '.');
        }

        public Summary Run()
        {
            while (Step()) { }
            return new Summary(Hero.Alive, Turn, Hero.Hp, Monsters.Count(m => !m.Alive),
                Loot.PowerByElement(Hero.Bag).OrderBy(kv => kv.Key).Select(kv => kv.Key + ":" + kv.Value).ToArray(),
                Journal.ToString());
        }
    }

    public record Summary(bool Won, int Turns, int Hp, int Kills, string[] Bag, string Journal)
    {
        public ulong Hash()
        {
            ulong h = 1469598103934665603UL;
            void Mix(int v) { h = (h ^ (uint)v) * 1099511628211UL; }
            Mix(Won ? 1 : 0); Mix(Turns); Mix(Hp); Mix(Kills);
            foreach (var s in Bag) foreach (char c in s) Mix(c);
            foreach (char c in Journal) Mix(c);
            return h;
        }
    }

    public static class Campaign
    {
        public static (int wins, ulong hash, Dictionary<string, int> best) Play(int dungeons)
        {
            ulong hash = 0;
            int wins = 0;
            var best = new Dictionary<string, int>();
            for (int d = 0; d < dungeons; d++)
            {
                var summary = new Dungeon((ulong)d + 1, d).Run();
                wins += summary.Won ? 1 : 0;
                hash = hash * 31 + summary.Hash();
                foreach (var entry in summary.Bag)
                {
                    var parts = entry.Split(':');
                    best[parts[0]] = Math.Max(best.TryGetValue(parts[0], out var v) ? v : 0, int.Parse(parts[1]));
                }
            }
            return (wins, hash, best);
        }
    }
}
