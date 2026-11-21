type Item = { n: string; d: boolean };
const items: Item[] = ([["Passport", 1], ["Charger", 1], ["Toothbrush", 0], ["Rain jacket", 0], ["Headphones", 0], ["Snacks", 0]] as [string, number][]).map(([n, d]) => ({ n, d: !!d }));
root.innerHTML = `<div class="ck stack"><div class="spread"><div class="title">Weekend trip packing</div><span class="sub num" data-testid="progress-label"></span></div><div class="progress"><i id="bar"></i></div><div class="list" id="items"></div></div>
<style>.ck label{display:flex;align-items:center;gap:12px;cursor:pointer;min-height:28px}.ck label.done span{color:var(--onSurface3);text-decoration:line-through}</style>`;
const list = root.querySelector<HTMLDivElement>('#items')!;
function draw(): void {
  list.innerHTML = items.map((it, i) => `<label class="${it.d ? 'done' : ''}" data-testid="item-${i}"><input type="checkbox" class="check" data-i="${i}" ${it.d ? 'checked' : ''}><span>${it.n}</span></label>`).join('');
  const n = items.filter((i) => i.d).length;
  root.querySelector<HTMLElement>('[data-testid=progress-label]')!.textContent = `${n}/${items.length} packed`;
  root.querySelector<HTMLElement>('#bar')!.style.width = (n / items.length) * 100 + '%';
}
list.addEventListener('change', (e) => {
  const t = e.target as HTMLInputElement;
  if (t.dataset.i === undefined) return;
  items[Number(t.dataset.i)].d = t.checked;
  draw();
});
draw();
