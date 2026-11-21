const days: [string, number][] = [
  ['Mon', 7.2], ['Tue', 6.8], ['Wed', 6.1], ['Thu', 7.9], ['Fri', 5.4], ['Sat', 8.6], ['Sun', 8.1],
];
const target: number = 8;
const avg: number = days.reduce((s, d) => s + d[1], 0) / days.length;
let sel: number = -1;

root.innerHTML = `
<style>
.slp .hdr { align-items: flex-end; }
.slp .avg { text-align: right; }
.slp .det { min-height: 20px; }
.slp [data-bar] { cursor: pointer; }
</style>
<div class="stack slp">
  <div class="spread hdr">
    <div>
      <div class="title">Sleep this week</div>
      <div class="sub">Target ${target}h a night</div>
    </div>
    <div class="avg">
      <div class="big num">${avg.toFixed(1)}h</div>
      <div class="muted">weekly average</div>
    </div>
  </div>
  <div id="chart"></div>
  <div class="card sub det" data-testid="detail">Tap a bar to see that night</div>
</div>`;

const chart = root.querySelector<HTMLDivElement>('#chart')!;
const detail = root.querySelector<HTMLDivElement>('[data-testid=detail]')!;

const draw = (): void => {
  const data = days.map(([label, value]) => ({
    label,
    value,
    color: value >= target ? 'var(--success)' : undefined,
  }));
  chart.innerHTML = sel >= 0
    ? G.bar(data, { target, unit: 'h', values: true, selected: sel })
    : G.bar(data, { target, unit: 'h', values: true });
};

chart.onclick = (e: MouseEvent): void => {
  const g = (e.target as Element).closest('[data-bar]');
  if (!g) return;
  const i = Number(g.getAttribute('data-bar'));
  const d = days[i];
  if (!d) return;
  sel = i;
  detail.textContent = `${d[0]} · ${d[1].toFixed(1)}h`;
  detail.classList.remove('sub');
  draw();
};

draw();
