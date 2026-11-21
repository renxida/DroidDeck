type Opt = [name: string, price: number, battery: number, weight: number, anc: number];
const opts: Opt[] = [["Sony WH-1000XM6", 449, 30, 254, 5], ["Bose QC Ultra", 429, 24, 250, 5], ["Sennheiser Momentum 4", 379, 60, 293, 4]];
type Row = [label: string, idx: 1 | 2 | 3 | 4, best: 'min' | 'max', f: (v: number) => string];
const rows: Row[] = [["Price", 1, 'min', (v) => '$' + v], ["Battery", 2, 'max', (v) => v + 'h'], ["Weight", 3, 'min', (v) => v + 'g'], ["ANC", 4, 'max', (v) => '●'.repeat(v)]];
let pick: number | null = null;
root.innerHTML = `<div class="cp stack"><div><div class="title">Headphones compared</div><div class="sub">Best in each row highlighted</div></div><div id="tbl"></div><div class="card" data-testid="choice"><span class="sub">Pick one to decide</span></div></div>
<style>
.cp .g{display:grid;grid-template-columns:64px repeat(3,minmax(0,1fr));gap:4px;font-size:13px;align-items:center}
.cp .h{font-weight:600;font-size:12px;line-height:1.25;padding:8px 4px;border-radius:10px;text-align:center}
.cp .v{text-align:center;padding:6px 2px;border-radius:8px}
.cp .best{background:var(--accentSoft);color:var(--accent);font-weight:600}
.cp .l{color:var(--onSurface2);font-size:12px}
.cp .picked{outline:2px solid var(--accent)}
.cp .btn{width:100%;padding:0 4px}
</style>`;
const tbl = root.querySelector<HTMLDivElement>('#tbl')!;
function draw(): void {
  let h = '<div class="g"><div></div>' + opts.map((o, i) => `<div class="h ${pick === i ? 'picked' : ''}">${o[0]}</div>`).join('');
  for (const [n, k, m, f] of rows) {
    const vals = opts.map((o) => o[k]);
    const b = m === 'min' ? Math.min(...vals) : Math.max(...vals);
    h += `<div class="l">${n}</div>` + opts.map((o) => `<div class="v num ${o[k] === b ? 'best' : ''}">${f(o[k])}</div>`).join('');
  }
  h += '<div></div>' + opts.map((_, i) => `<button class="btn small ${pick === i ? '' : 'ghost'}" data-testid="pick-${i}" data-i="${i}">${pick === i ? 'Picked' : 'Pick'}</button>`).join('') + '</div>';
  tbl.innerHTML = h;
}
tbl.addEventListener('click', (e) => {
  const b = (e.target as Element).closest<HTMLElement>('[data-i]');
  if (!b) return;
  pick = Number(b.dataset.i);
  const o = opts[pick];
  root.querySelector<HTMLDivElement>('[data-testid=choice]')!.innerHTML = `<span style="font-weight:600">Picked: ${o[0]}</span> <span class="sub">· $${o[1]}</span>`;
  draw();
});
draw();
