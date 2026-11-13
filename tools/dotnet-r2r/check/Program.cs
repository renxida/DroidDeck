// Deterministic card-battle simulation: many small methods, generics, LINQ and lambdas, so that its own
// assembly has a few hundred methods to JIT. Prints a checksum that must not change with precompiled code.
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;

namespace R2RCheck
{
    enum Kind { Attack, Skill, Power }

    record Card(string Name, Kind Kind, int Cost, int Damage, int Block, int Draw);

    sealed class Rng
    {
        ulong state;
        public Rng(ulong seed) { state = seed * 0x9E3779B97F4A7C15UL + 1; }
        public int Next(int max) { state ^= state << 13; state ^= state >> 7; state ^= state << 17; return (int)(state % (ulong)max); }
        public void Shuffle<T>(IList<T> list) { for (int i = list.Count - 1; i > 0; i--) { int j = Next(i + 1); (list[i], list[j]) = (list[j], list[i]); } }
    }

    interface IEffect { void Apply(Fighter self, Fighter other); }
    sealed class Poison : IEffect { public int Stacks; public void Apply(Fighter self, Fighter other) { if (Stacks > 0) { self.Hp -= Stacks; Stacks--; } } }
    sealed class Strength : IEffect { public int Amount; public void Apply(Fighter self, Fighter other) { self.Bonus = Amount; } }
    sealed class Regen : IEffect { public int Amount; public void Apply(Fighter self, Fighter other) { self.Hp = Math.Min(self.MaxHp, self.Hp + Amount); } }

    sealed class Fighter
    {
        public string Name; public int Hp, MaxHp, Block, Bonus, Energy;
        public readonly List<IEffect> Effects = new();
        public Fighter(string name, int hp) { Name = name; Hp = MaxHp = hp; }
        public bool Alive => Hp > 0;
        public void TakeDamage(int amount) { int through = Math.Max(0, amount - Block); Block = Math.Max(0, Block - amount); Hp -= through; }
        public void StartTurn(Fighter other) { Block = 0; Energy = 3; foreach (var e in Effects) e.Apply(this, other); }
    }

    sealed class Deck
    {
        readonly List<Card> draw = new(), discard = new(), hand = new();
        readonly Rng rng;
        public Deck(IEnumerable<Card> cards, Rng rng) { draw.AddRange(cards); this.rng = rng; rng.Shuffle(draw); }
        public IReadOnlyList<Card> Hand => hand;
        public void Draw(int n)
        {
            for (int i = 0; i < n; i++)
            {
                if (draw.Count == 0) { draw.AddRange(discard); discard.Clear(); rng.Shuffle(draw); }
                if (draw.Count == 0) return;
                hand.Add(draw[^1]); draw.RemoveAt(draw.Count - 1);
            }
        }
        public void Play(Card card) { hand.Remove(card); discard.Add(card); }
        public void EndTurn() { discard.AddRange(hand); hand.Clear(); }
    }

    static class Cards
    {
        public static IEnumerable<Card> Starter() =>
            Enumerable.Range(0, 5).Select(i => new Card("Strike", Kind.Attack, 1, 6, 0, 0))
            .Concat(Enumerable.Range(0, 4).Select(i => new Card("Defend", Kind.Skill, 1, 0, 5, 0)))
            .Append(new Card("Bash", Kind.Attack, 2, 8, 0, 0));

        public static IEnumerable<Card> Pool() => new[]
        {
            new Card("Cleave", Kind.Attack, 1, 8, 0, 0), new Card("Pommel", Kind.Attack, 1, 9, 0, 1),
            new Card("Shrug", Kind.Skill, 1, 0, 8, 1), new Card("Inflame", Kind.Power, 1, 0, 0, 0),
            new Card("Bludgeon", Kind.Attack, 3, 32, 0, 0), new Card("Impervious", Kind.Skill, 2, 0, 30, 0),
            new Card("Offering", Kind.Skill, 0, 0, 0, 3), new Card("Catalyst", Kind.Skill, 1, 0, 0, 0),
        };
    }

    static class Ai
    {
        public static Card Choose(IReadOnlyList<Card> hand, Fighter self, Fighter enemy) =>
            hand.Where(c => c.Cost <= self.Energy)
                .OrderByDescending(c => Score(c, self, enemy))
                .ThenBy(c => c.Name, StringComparer.Ordinal)
                .FirstOrDefault();

        static int Score(Card c, Fighter self, Fighter enemy) => c.Kind switch
        {
            Kind.Attack => (c.Damage + self.Bonus) * 10 / Math.Max(1, c.Cost) + (enemy.Hp <= c.Damage + self.Bonus ? 1000 : 0),
            Kind.Skill => c.Block * 8 / Math.Max(1, c.Cost) + c.Draw * 15,
            _ => self.Effects.OfType<Strength>().Any() ? 0 : 40,
        };
    }

    static class Battle
    {
        public static (bool won, int turns, int hp) Fight(List<Card> cards, int enemyHp, Rng rng)
        {
            var player = new Fighter("Player", 80);
            var enemy = new Fighter("Enemy", enemyHp);
            enemy.Effects.Add(new Regen { Amount = 2 });
            var deck = new Deck(cards, rng);
            int turn = 0;
            while (player.Alive && enemy.Alive && turn < 60)
            {
                turn++;
                player.StartTurn(enemy);
                deck.Draw(5);
                for (Card c; (c = Ai.Choose(deck.Hand, player, enemy)) != null;)
                {
                    player.Energy -= c.Cost;
                    deck.Play(c);
                    Resolve(c, player, enemy, deck);
                    if (!enemy.Alive) break;
                }
                deck.EndTurn();
                if (!enemy.Alive) break;
                enemy.StartTurn(player);
                player.TakeDamage(6 + turn / 3 + rng.Next(5));
            }
            return (!enemy.Alive && player.Alive, turn, player.Hp);
        }

        static void Resolve(Card c, Fighter p, Fighter e, Deck deck)
        {
            switch (c.Name)
            {
                case "Inflame": p.Effects.Add(new Strength { Amount = 2 }); p.Bonus = 2; break;
                case "Catalyst": foreach (var poison in e.Effects.OfType<Poison>()) poison.Stacks *= 2; e.Effects.Add(new Poison { Stacks = 3 }); break;
                default:
                    if (c.Damage > 0) e.TakeDamage(c.Damage + p.Bonus);
                    p.Block += c.Block;
                    if (c.Draw > 0) deck.Draw(c.Draw);
                    break;
            }
        }
    }

    static class Program
    {
        static int Main(string[] args)
        {
            int runs = args.Length > 0 ? int.Parse(args[0]) : 2000;
            var rng = new Rng(42);
            var pool = Cards.Pool().ToList();
            var stats = new Dictionary<string, (int wins, long turns)>();
            ulong checksum = 1469598103934665603UL;
            for (int i = 0; i < runs; i++)
            {
                var cards = Cards.Starter().ToList();
                for (int k = 0; k < 1 + i % 6; k++) cards.Add(pool[rng.Next(pool.Count)]);
                var (won, turns, hp) = Battle.Fight(cards, 40 + i % 50, rng);
                string key = string.Join(",", cards.GroupBy(c => c.Name).OrderBy(g => g.Key, StringComparer.Ordinal).Select(g => g.Key + g.Count()));
                var old = stats.TryGetValue(key, out var s) ? s : (0, 0L);
                stats[key] = (old.Item1 + (won ? 1 : 0), old.Item2 + turns);
                checksum = (checksum ^ (ulong)(turns * 131 + hp + (won ? 7 : 0))) * 1099511628211UL;
            }
            var best = stats.OrderByDescending(kv => kv.Value.wins).ThenBy(kv => kv.Key, StringComparer.Ordinal).First();
            var text = new StringBuilder().Append("decks=").Append(stats.Count).Append(" best=").Append(best.Key)
                .Append(" wins=").Append(best.Value.wins).Append(" checksum=").Append(checksum.ToString("x16"));
            Console.WriteLine(text.ToString());
            return 0;
        }
    }
}
