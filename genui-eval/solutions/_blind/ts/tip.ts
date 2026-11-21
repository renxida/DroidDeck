const bill: number = 84.5;
let pct: number = 18;
let party: number = 2;

root.innerHTML = `
<style>
.tip .stp { align-items: center; }
.tip .stp .btn { min-width: 40px; min-height: 40px; }
.tip .cnt { min-width: 32px; text-align: center; font-size: 20px; font-weight: 600; }
.tip input[type=range] { width: 100%; }
.tip .pp { text-align: center; }
</style>
<div class="stack tip">
  <div class="spread">
    <div class="title">Tip splitter</div>
    <div class="sub">Bill <span class="num">${G.fmt.money(bill)}</span></div>
  </div>
  <div class="card stack">
    <div class="spread"><span class="sub">Tip</span><span class="title num" id="pctl"></span></div>
    <input type="range" min="10" max="30" step="1" value="18" data-testid="tip" aria-label="Tip percent">
    <div class="spread muted"><span>10%</span><span>30%</span></div>
  </div>
  <div class="card spread stp">
    <span class="sub">Party size</span>
    <div class="row stp">
      <button class="btn small" data-testid="minus" aria-label="Fewer people">−</button>
      <span class="cnt num" id="party"></span>
      <button class="btn small" data-testid="plus" aria-label="More people">+</button>
    </div>
  </div>
  <div class="card stack">
    <div class="spread"><span class="sub">Tip amount</span><span class="num" id="tipamt"></span></div>
    <div class="spread"><span class="sub">Total</span><span class="num" id="total"></span></div>
    <div class="divider"></div>
    <div class="pp">
      <div class="muted">Each person pays</div>
      <div class="big num" data-testid="per-person"></div>
    </div>
  </div>
</div>`;

const slider = root.querySelector<HTMLInputElement>('[data-testid=tip]')!;
const minus = root.querySelector<HTMLButtonElement>('[data-testid=minus]')!;
const plus = root.querySelector<HTMLButtonElement>('[data-testid=plus]')!;
const pctl = root.querySelector<HTMLSpanElement>('#pctl')!;
const partyEl = root.querySelector<HTMLSpanElement>('#party')!;
const tipAmt = root.querySelector<HTMLSpanElement>('#tipamt')!;
const totalEl = root.querySelector<HTMLSpanElement>('#total')!;
const per = root.querySelector<HTMLDivElement>('[data-testid=per-person]')!;

const update = (): void => {
  const tip = bill * pct / 100;
  const total = bill + tip;
  pctl.textContent = `${pct}%`;
  partyEl.textContent = String(party);
  tipAmt.textContent = G.fmt.money(tip);
  totalEl.textContent = G.fmt.money(total);
  per.textContent = G.fmt.money(total / party);
  minus.disabled = party <= 1;
};

slider.oninput = (): void => {
  const v = Number(slider.value);
  pct = Number.isFinite(v) ? v : 18;
  update();
};
slider.onchange = slider.oninput;
minus.onclick = (): void => { if (party > 1) { party--; update(); } };
plus.onclick = (): void => { party++; update(); };

update();
