const raw: Record<string, number> = {
  Rent: 1850,
  Groceries: 420,
  Dining: 310,
  Transport: 140,
  Subscriptions: 65,
  Other: 215,
};

interface Cat { name: string; value: number; color: string; on: boolean }
const cats: Cat[] = Object.entries(raw).map(([name, value], i): Cat => ({
  name,
  value,
  color: G.color(i),
  on: true,
}));

root.innerHTML = `
<style>
.spd .chart { display: flex; justify-content: center; }
.spd .lg { display: flex; flex-direction: column; }
.spd .lgr { display: flex; align-items: center; gap: 10px; min-height: 40px; padding: 6px 4px;
  background: none; border: 0; border-bottom: 1px solid var(--outline); color: var(--onSurface);
  font: inherit; text-align: left; cursor: pointer; width: 100%; }
.spd .lgr:last-child { border-bottom: 0; }
.spd .lgr .sw { width: 12px; height: 12px; border-radius: 4px; flex: none; }
.spd .lgr .nm { flex: 1; }
.spd .lgr .amt { min-width: 76px; text-align: right; }
.spd .lgr .sh { min-width: 40px; text-align: right; color: var(--onSurface2); font-size: 13px; }
.spd .lgr.off { opacity: 0.45; }
.spd .lgr.off .nm { text-decoration: line-through; }
.spd .lgr.off .sw { background: var(--outline) !important; }
</style>
<div class="stack spd">
  <div class="spread">
    <div>
      <div class="title">Spending this month</div>
      <div class="sub">Tap a category to include or exclude it</div>
    </div>
    <div class="title num" data-testid="total"></div>
  </div>
  <div class="chart" id="chart"></div>
  <div class="card lg" id="legend"></div>
</div>`;

const chart = root.querySelector<HTMLDivElement>('#chart')!;
const legend = root.querySelector<HTMLDivElement>('#legend')!;
const totalEl = root.querySelector<HTMLDivElement>('[data-testid=total]')!;

const render = (): void => {
  const inc = cats.filter((c) => c.on);
  const total = inc.reduce((s, c) => s + c.value, 0);
  const items = inc.length
    ? inc.map((c) => ({ label: c.name, value: c.value, color: c.color }))
    : [{ label: 'None', value: 1, color: 'var(--outline)' }];
  chart.innerHTML = G.donut(items, { center: G.fmt.money(total), sub: `${inc.length} of ${cats.length} categories` });
  totalEl.textContent = G.fmt.money(total);
  legend.innerHTML = cats.map((c, i) => {
    const share = c.on && total > 0 ? G.fmt.pct(c.value / total) : 'off';
    return `<button class="lgr${c.on ? '' : ' off'}" data-cat="${i}" data-testid="cat-${G.esc(c.name)}" aria-pressed="${c.on}">
      <span class="sw" style="background:${c.color}"></span>
      <span class="nm">${G.esc(c.name)}</span>
      <span class="amt num">${G.fmt.money(c.value)}</span>
      <span class="sh num">${share}</span>
    </button>`;
  }).join('');
};

legend.onclick = (e: MouseEvent): void => {
  const b = (e.target as Element).closest('[data-cat]');
  if (!b) return;
  const c = cats[Number(b.getAttribute('data-cat'))];
  if (!c) return;
  c.on = !c.on;
  render();
};

render();
