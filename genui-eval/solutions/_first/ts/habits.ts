const habits: [string, number[]][] = Object.entries({ "Meditate": [1, 1, 0, 1, 1, 0, 0], "Stretch": [1, 0, 0, 1, 0, 1, 0], "Read 30m": [1, 1, 1, 1, 0, 1, 1], "No phone after 11": [0, 0, 1, 0, 0, 1, 0] });
const days = ['M', 'T', 'W', 'T', 'F', 'S', 'S'];
root.innerHTML = `<div class="hb stack"><div class="spread"><div class="title">Habits this week</div><span class="badge num" data-testid="count"></span></div><div id="grid"></div></div>
<style>
.hb table{width:100%;border-collapse:collapse;table-layout:fixed}
.hb th{font-size:11px;font-weight:500;color:var(--onSurface3);padding-bottom:6px}
.hb th:first-child{width:96px;text-align:left}
.hb td{padding:3px 0;text-align:center}
.hb td.n{text-align:left;font-size:13px;line-height:1.2}
.hb td.n small{display:block;color:var(--onSurface3);font-size:11px}
.hb .c{width:30px;height:30px;border-radius:8px;border:0;background:var(--surface2);cursor:pointer;padding:0}
.hb .c.on{background:var(--accent)}
.hb .c.on::after{content:"✓";color:var(--onAccent);font-weight:700}
</style>`;
const grid = root.querySelector<HTMLDivElement>('#grid')!;
const done = (v: number[]): number => v.filter(Boolean).length;
function draw(): void {
  grid.innerHTML = `<table><tr><th>Habit</th>${days.map((d) => `<th>${d}</th>`).join('')}</tr>${habits.map(([n, v], r) => `<tr><td class="n">${n}<small>${done(v)}/7</small></td>${v.map((x, c) => `<td><button class="c ${x ? 'on' : ''}" data-testid="cell-${r}-${c}" data-r="${r}" data-c="${c}"></button></td>`).join('')}</tr>`).join('')}</table>`;
  root.querySelector<HTMLElement>('[data-testid=count]')!.textContent = `${habits.reduce((a, [, v]) => a + done(v), 0)} / 28`;
}
grid.addEventListener('click', (e) => {
  const b = (e.target as Element).closest<HTMLButtonElement>('[data-r]');
  if (!b) return;
  const v = habits[Number(b.dataset.r)][1], c = Number(b.dataset.c);
  v[c] = v[c] ? 0 : 1;
  draw();
});
draw();
