const K = 1.609344;
let kmToMi = true;
root.innerHTML = `<div class="stack"><div class="title">Length converter</div>
  <div class="card stack">
    <label class="sub" id="from"></label>
    <input type="number" inputmode="decimal" value="10" data-testid="value">
    <div class="row" style="justify-content:center"><button class="btn small ghost" data-testid="swap">⇅ Swap</button></div>
    <div class="sub" id="to"></div>
    <div class="big" data-testid="result"></div>
  </div>
  <div class="muted" id="hint"></div></div>`;
const input = root.querySelector<HTMLInputElement>('[data-testid=value]')!;
const set = (sel: string, text: string): void => { root.querySelector<HTMLElement>(sel)!.textContent = text; };
function upd(): void {
  const v = parseFloat(input.value);
  set('#from', kmToMi ? 'Kilometers' : 'Miles');
  set('#to', kmToMi ? 'Miles' : 'Kilometers');
  set('[data-testid=result]', isNaN(v) ? '—' : (kmToMi ? v / K : v * K).toFixed(2) + (kmToMi ? ' mi' : ' km'));
  set('#hint', kmToMi ? `1 km = ${(1 / K).toFixed(4)} mi` : `1 mi = ${K} km`);
}
input.addEventListener('input', upd);
root.querySelector<HTMLButtonElement>('[data-testid=swap]')!.onclick = () => { kmToMi = !kmToMi; upd(); };
upd();
