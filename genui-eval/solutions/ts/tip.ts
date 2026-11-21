const bill = 84.5;
let tip = 18, people = 2;
root.innerHTML = `<div class="stack">
  <div class="spread"><div class="title">Split the bill</div><span class="badge">Bill ${G.fmt.money(bill)}</span></div>
  <div class="card stack">
    <div class="spread"><span class="sub">Tip</span><span class="num" style="font-weight:600" id="tipv"></span></div>
    <input type="range" min="10" max="30" step="1" value="18" data-testid="tip">
    <div class="spread"><span class="sub">People</span><div class="row"><button class="btn small ghost" data-testid="minus">−</button><span class="num" id="ppl" style="min-width:28px;text-align:center;font-weight:600"></span><button class="btn small ghost" data-testid="plus">+</button></div></div>
  </div>
  <div class="grid2"><div class="card"><div class="muted">Tip</div><div class="num" style="font-weight:600" id="tipamt"></div></div><div class="card"><div class="muted">Total</div><div class="num" style="font-weight:600" id="total"></div></div></div>
  <div class="card" style="background:var(--accentSoft);text-align:center"><div class="sub">Each person pays</div><div class="big" style="color:var(--accent)" data-testid="per-person"></div></div></div>`;
const q = <T extends Element>(s: string): T => root.querySelector<T>(s)!;
const slider = q<HTMLInputElement>('[data-testid=tip]'), minus = q<HTMLButtonElement>('[data-testid=minus]'), plus = q<HTMLButtonElement>('[data-testid=plus]');
function upd(): void {
  const t = (bill * tip) / 100, tot = bill + t;
  q<HTMLElement>('#tipv').textContent = tip + '%';
  q<HTMLElement>('#ppl').textContent = String(people);
  q<HTMLElement>('#tipamt').textContent = G.fmt.money(t);
  q<HTMLElement>('#total').textContent = G.fmt.money(tot);
  q<HTMLElement>('[data-testid=per-person]').textContent = G.fmt.money(tot / people);
  minus.disabled = people <= 1;
}
slider.addEventListener('input', () => { tip = Number(slider.value); upd(); });
plus.onclick = () => { people++; upd(); };
minus.onclick = () => { if (people > 1) people--; upd(); };
upd();
