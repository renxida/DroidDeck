const phones: [string, number, number, number, number][] = [
  ['Sony WH-1000XM6', 449, 30, 254, 5],
  ['Bose QC Ultra', 429, 24, 250, 5],
  ['Sennheiser Momentum 4', 379, 60, 293, 4],
];

interface Metric { label: string; idx: 1 | 2 | 3 | 4; lowerBetter: boolean; fmt: (v: number) => string }
const metrics: Metric[] = [
  { label: 'Price', idx: 1, lowerBetter: true, fmt: (v) => `$${v}` },
  { label: 'Battery', idx: 2, lowerBetter: false, fmt: (v) => `${v} h` },
  { label: 'Weight', idx: 3, lowerBetter: true, fmt: (v) => `${v} g` },
  { label: 'ANC', idx: 4, lowerBetter: false, fmt: (v) => `${v}/5` },
];
let picked: number = -1;

root.innerHTML = `
<style>
.cmp .tbl { display: grid; grid-template-columns: 58px repeat(3, minmax(0, 1fr)); gap: 0; }
.cmp .c { padding: 8px 4px; font-size: 13px; text-align: center; border-bottom: 1px solid var(--outline); }
.cmp .lb { text-align: left; color: var(--onSurface2); font-size: 12px; display: flex; align-items: center; }
.cmp .hd { font-weight: 600; font-size: 13px; line-height: 1.2; display: flex; align-items: flex-end; justify-content: center; }
.cmp .best { color: var(--success); font-weight: 700; }
.cmp .best::after { content: ' ★'; font-size: 10px; }
.cmp .pk { background: var(--accentSoft); }
.cmp .ft { border-bottom: 0; }
.cmp .ft .btn { width: 100%; min-height: 34px; padding-left: 4px; padding-right: 4px; }
</style>
<div class="stack cmp">
  <div>
    <div class="title">Headphones compared</div>
    <div class="sub">★ best in each row</div>
  </div>
  <div class="card"><div class="tbl" id="tbl"></div></div>
  <div class="sub" data-testid="choice">Nothing picked yet</div>
</div>`;

const tbl = root.querySelector<HTMLDivElement>('#tbl')!;
const choice = root.querySelector<HTMLDivElement>('[data-testid=choice]')!;

const render = (): void => {
  const pk = (i: number): string => (i === picked ? ' pk' : '');
  let html = '<div class="c lb"></div>' + phones.map((p, i) => `<div class="c hd${pk(i)}">${G.esc(p[0])}</div>`).join('');
  for (const m of metrics) {
    const vals = phones.map((p) => p[m.idx]);
    const best = m.lowerBetter ? Math.min(...vals) : Math.max(...vals);
    html += `<div class="c lb">${m.label}</div>`;
    html += phones.map((p, i) => {
      const v = p[m.idx];
      return `<div class="c num${v === best ? ' best' : ''}${pk(i)}">${m.fmt(v)}</div>`;
    }).join('');
  }
  html += '<div class="c lb ft"></div>' + phones.map((p, i) =>
    `<div class="c ft${pk(i)}"><button class="btn small${i === picked ? '' : ' ghost'}" data-pick="${i}" data-testid="pick-${i}"
      aria-pressed="${i === picked}">${i === picked ? '✓ Picked' : 'Pick'}</button></div>`).join('');
  tbl.innerHTML = html;
  if (picked >= 0) {
    const p = phones[picked];
    if (p) {
      choice.textContent = `Picked: ${p[0]}`;
      choice.classList.remove('sub');
      choice.classList.add('title');
    }
  }
};

tbl.onclick = (e: MouseEvent): void => {
  const b = (e.target as Element).closest('[data-pick]');
  if (!b) return;
  picked = Number(b.getAttribute('data-pick'));
  render();
};

render();
