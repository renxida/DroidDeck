const items: { name: string; done: boolean }[] = ([
  ['Passport', 1],
  ['Charger', 1],
  ['Toothbrush', 0],
  ['Rain jacket', 0],
  ['Headphones', 0],
  ['Snacks', 0],
] as [string, number][]).map(([name, d]) => ({ name, done: d === 1 }));

root.innerHTML = `
<style>
.pck .it { display: flex; align-items: center; gap: 12px; width: 100%; min-height: 44px; padding: 8px 4px;
  background: none; border: 0; color: var(--onSurface); font: inherit; font-size: 15px; text-align: left; cursor: pointer; }
.pck .bx { width: 22px; height: 22px; flex: none; border-radius: 6px; border: 2px solid var(--outline);
  display: flex; align-items: center; justify-content: center; color: var(--onAccent); font-size: 14px; line-height: 1; }
.pck .it.on .bx { background: var(--accent); border-color: var(--accent); }
.pck .it.on .nm { text-decoration: line-through; color: var(--onSurface3); }
</style>
<div class="stack pck">
  <div class="spread">
    <div>
      <div class="title">Weekend trip packing</div>
      <div class="sub">Tap an item to check it off</div>
    </div>
    <div class="sub num" data-testid="progress-label"></div>
  </div>
  <div class="progress"><i id="bar"></i></div>
  <div class="card list" id="list"></div>
</div>`;

const list = root.querySelector<HTMLDivElement>('#list')!;
const label = root.querySelector<HTMLDivElement>('[data-testid=progress-label]')!;
const bar = root.querySelector<HTMLElement>('#bar')!;

const render = (): void => {
  list.innerHTML = items.map((it, i) =>
    `<button class="it${it.done ? ' on' : ''}" data-i="${i}" data-testid="item-${i}" role="checkbox" aria-checked="${it.done}">
      <span class="bx">${it.done ? '✓' : ''}</span><span class="nm">${G.esc(it.name)}</span>
    </button>`).join('');
  const n = items.filter((it) => it.done).length;
  label.textContent = `${n}/${items.length} packed`;
  bar.style.width = `${(n / items.length) * 100}%`;
};

list.onclick = (e: MouseEvent): void => {
  const b = (e.target as Element).closest('[data-i]');
  if (!b) return;
  const it = items[Number(b.getAttribute('data-i'))];
  if (!it) return;
  it.done = !it.done;
  render();
};

render();
