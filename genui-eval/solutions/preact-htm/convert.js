const K = 1.609344;
function App() {
  const [val, setVal] = useState('10');
  const [kmToMi, setDir] = useState(true);
  const v = parseFloat(val);
  return html`<div class="stack">
    <div class="title">Length converter</div>
    <div class="card stack">
      <label class="sub">${kmToMi ? 'Kilometers' : 'Miles'}</label>
      <input type="number" inputmode="decimal" value=${val} data-testid="value" onInput=${e => setVal(e.target.value)}/>
      <div class="row" style="justify-content:center"><button class="btn small ghost" data-testid="swap" onClick=${() => setDir(!kmToMi)}>⇅ Swap</button></div>
      <div class="sub">${kmToMi ? 'Miles' : 'Kilometers'}</div>
      <div class="big" data-testid="result">${isNaN(v) ? '—' : (kmToMi ? v / K : v * K).toFixed(2) + (kmToMi ? ' mi' : ' km')}</div>
    </div>
    <div class="muted">${kmToMi ? `1 km = ${(1 / K).toFixed(4)} mi` : `1 mi = ${K} km`}</div>
  </div>`;
}
return App;
