type Day = { label: string; value: number };
const days: Day[] = ([["Mon", 7.2], ["Tue", 6.8], ["Wed", 6.1], ["Thu", 7.9], ["Fri", 5.4], ["Sat", 8.6], ["Sun", 8.1]] as [string, number][]).map(([label, value]) => ({ label, value }));
const avg = days.reduce((a, d) => a + d.value, 0) / days.length;
root.innerHTML = `<div class="stack">
  <div class="spread"><div><div class="title">Sleep this week</div><div class="sub">Target 8h a night</div></div>
  <div style="text-align:right"><div class="big">${avg.toFixed(1)}h</div><div class="muted">avg / night</div></div></div>
  <div id="chart"></div>
  <div class="card row" data-testid="detail"><span class="sub">Tap a bar to see the day</span></div></div>`;
const chart = root.querySelector<HTMLDivElement>('#chart')!;
const detail = root.querySelector<HTMLDivElement>('[data-testid=detail]')!;
let sel: number | null = null;
function draw(): void {
  chart.innerHTML = G.bar(days.map((d) => ({ ...d, color: d.value >= 8 ? 'var(--success)' : 'var(--accent)' })), { target: 8, unit: 'h', selected: sel });
  if (sel !== null) {
    const d = days[sel], diff = d.value - 8;
    detail.innerHTML = `<span class="title">${d.label} · ${d.value}h</span><span class="grow"></span><span class="badge">${diff >= 0 ? '+' : ''}${diff.toFixed(1)}h vs target</span>`;
  }
}
chart.addEventListener('click', (e) => {
  const b = (e.target as Element).closest<SVGGElement>('[data-bar]');
  if (b) { sel = Number(b.dataset.bar); draw(); }
});
draw();
