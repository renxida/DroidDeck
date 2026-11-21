const data: Record<string, number> = { Rent: 1850, Groceries: 420, Dining: 310, Transport: 140, Subscriptions: 65, Other: 215 };
type Cat = { label: string; value: number; color: string; on: boolean };
const cats: Cat[] = Object.entries(data).map(([label, value], i) => ({ label, value, color: G.color(i), on: true }));
root.innerHTML = `<div class="stack sp">
  <div><div class="title">Spending · November</div><div class="sub">Tap a category to include or exclude it</div></div>
  <div id="donut" style="display:flex;justify-content:center"></div>
  <div style="text-align:center"><span class="sub">Included total </span><span class="num" style="font-weight:600" data-testid="total"></span></div>
  <div class="list" id="legend"></div></div>
  <style>.sp .lg{display:flex;align-items:center;gap:10px;cursor:pointer;min-height:40px}.sp .lg.off{opacity:.45}.sp .lg.off .nm{text-decoration:line-through}</style>`;
const legend = root.querySelector<HTMLDivElement>('#legend')!;
function draw(): void {
  const inc = cats.filter((c) => c.on);
  const tot = inc.reduce((a, c) => a + c.value, 0);
  root.querySelector<HTMLDivElement>('#donut')!.innerHTML = G.donut(inc, { center: G.fmt.money(tot).replace('.00', ''), sub: 'spent' });
  root.querySelector<HTMLSpanElement>('[data-testid=total]')!.textContent = G.fmt.money(tot);
  legend.innerHTML = cats.map((c) => `<div class="lg ${c.on ? '' : 'off'}" data-testid="cat-${c.label}" data-cat="${c.label}"><span class="dot" style="background:${c.color}"></span><span class="nm grow">${c.label}</span><span class="num">${G.fmt.money(c.value)}</span><span class="muted num" style="width:40px;text-align:right">${c.on && tot ? G.fmt.pct(c.value / tot) : '—'}</span></div>`).join('');
}
legend.addEventListener('click', (e) => {
  const r = (e.target as Element).closest<HTMLElement>('[data-cat]');
  if (!r) return;
  const c = cats.find((x) => x.label === r.dataset.cat);
  if (c) { c.on = !c.on; draw(); }
});
draw();
