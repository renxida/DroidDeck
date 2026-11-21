const kmPerMile: number = 1.609344;
let fromKm: boolean = true;

root.innerHTML = `
<style>
.cnv .in { display: flex; align-items: center; gap: 8px; }
.cnv .in input { flex: 1; min-width: 0; min-height: 40px; font-size: 20px; }
.cnv .unit { min-width: 28px; font-weight: 600; }
.cnv .mid { display: flex; justify-content: center; }
.cnv .swap { min-height: 40px; }
.cnv .res { text-align: center; }
</style>
<div class="stack cnv">
  <div>
    <div class="title">Length converter</div>
    <div class="sub" id="dir"></div>
  </div>
  <div class="card stack">
    <div class="in">
      <input type="number" inputmode="decimal" value="10" step="any" data-testid="value" aria-label="Value">
      <span class="unit" id="from"></span>
    </div>
    <div class="mid"><button class="btn ghost swap" data-testid="swap" aria-label="Swap direction">⇅ Swap</button></div>
    <div class="res">
      <div class="big num" data-testid="result"></div>
    </div>
  </div>
  <div class="muted" id="hint"></div>
</div>`;

const input = root.querySelector<HTMLInputElement>('[data-testid=value]')!;
const swap = root.querySelector<HTMLButtonElement>('[data-testid=swap]')!;
const result = root.querySelector<HTMLDivElement>('[data-testid=result]')!;
const fromEl = root.querySelector<HTMLSpanElement>('#from')!;
const dirEl = root.querySelector<HTMLDivElement>('#dir')!;
const hint = root.querySelector<HTMLDivElement>('#hint')!;

const update = (): void => {
  const fromU = fromKm ? 'km' : 'mi';
  const toU = fromKm ? 'mi' : 'km';
  fromEl.textContent = fromU;
  dirEl.textContent = fromKm ? 'Kilometers → miles' : 'Miles → kilometers';
  hint.textContent = fromKm
    ? `1 km = ${(1 / kmPerMile).toFixed(6)} mi  (1 mi = ${kmPerMile} km)`
    : `1 mi = ${kmPerMile} km`;
  const v = parseFloat(input.value);
  if (!Number.isFinite(v)) {
    result.textContent = `— ${toU}`;
    return;
  }
  const out = fromKm ? v / kmPerMile : v * kmPerMile;
  result.textContent = `${out.toFixed(2)} ${toU}`;
};

input.oninput = (): void => update();
input.onchange = (): void => update();
swap.onclick = (): void => { fromKm = !fromKm; update(); };

update();
