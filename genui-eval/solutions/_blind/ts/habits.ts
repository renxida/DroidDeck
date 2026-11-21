const habitData: Record<string, number[]> = {
  'Meditate': [1, 1, 0, 1, 1, 0, 0],
  'Stretch': [1, 0, 0, 1, 0, 1, 0],
  'Read 30m': [1, 1, 1, 1, 0, 1, 1],
  'No phone after 11': [0, 0, 1, 0, 0, 1, 0],
};
const habits: { name: string; days: boolean[] }[] = Object.entries(habitData).map(([name, arr]) => ({
  name,
  days: arr.map((v) => v === 1),
}));
const dayNames: string[] = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
const possible: number = habits.length * 7;

root.innerHTML = `
<style>
.hab .grid { display: grid; grid-template-columns: minmax(0, 1fr) repeat(7, 32px) 30px; gap: 4px 3px; align-items: center; }
.hab .dh { font-size: 11px; color: var(--onSurface3); text-align: center; }
.hab .hn { font-size: 13px; line-height: 1.2; overflow-wrap: anywhere; padding-right: 4px; }
.hab .cell { width: 32px; height: 32px; border-radius: 8px; border: 1px solid var(--outline); background: var(--surface);
  padding: 0; cursor: pointer; color: var(--onAccent); font-size: 14px; }
.hab .cell.on { background: var(--accent); border-color: var(--accent); }
.hab .hc { text-align: right; font-size: 13px; color: var(--onSurface2); }
</style>
<div class="stack hab">
  <div class="spread">
    <div>
      <div class="title">Habits this week</div>
      <div class="sub">Tap a day to toggle it</div>
    </div>
    <div class="title num" data-testid="count"></div>
  </div>
  <div class="progress"><i id="pbar"></i></div>
  <div class="card"><div class="grid" id="grid"></div></div>
</div>`;

const grid = root.querySelector<HTMLDivElement>('#grid')!;
const countEl = root.querySelector<HTMLDivElement>('[data-testid=count]')!;
const pbar = root.querySelector<HTMLElement>('#pbar')!;

const render = (): void => {
  let html = '<div></div>' + dayNames.map((d) => `<div class="dh">${d.slice(0, 2)}</div>`).join('') + '<div class="dh">#</div>';
  let done = 0;
  habits.forEach((h, r) => {
    const n = h.days.filter(Boolean).length;
    done += n;
    html += `<div class="hn">${G.esc(h.name)}</div>`;
    html += h.days.map((on, c) =>
      `<button class="cell${on ? ' on' : ''}" data-r="${r}" data-c="${c}" data-testid="cell-${r}-${c}"
        aria-pressed="${on}" aria-label="${G.esc(h.name)} ${dayNames[c]}">${on ? '✓' : ''}</button>`).join('');
    html += `<div class="hc num">${n}/7</div>`;
  });
  grid.innerHTML = html;
  countEl.textContent = `${done} / ${possible}`;
  pbar.style.width = `${(done / possible) * 100}%`;
};

grid.onclick = (e: MouseEvent): void => {
  const b = (e.target as Element).closest('[data-r]');
  if (!b) return;
  const h = habits[Number(b.getAttribute('data-r'))];
  const c = Number(b.getAttribute('data-c'));
  if (!h || c < 0 || c > 6) return;
  h.days[c] = !h.days[c];
  render();
};

render();
