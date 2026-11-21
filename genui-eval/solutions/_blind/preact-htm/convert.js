const KM_PER_MILE = 1.609344;

function App() {
  const [val, setVal] = useState('10');
  const [from, setFrom] = useState('km');
  const to = from === 'km' ? 'mi' : 'km';
  const x = parseFloat(val);
  const ok = !isNaN(x);
  const r = ok ? (from === 'km' ? x / KM_PER_MILE : x * KM_PER_MILE) : null;
  const onVal = (e) => setVal(e.target.value);
  const name = (u) => (u === 'km' ? 'Kilometers' : 'Miles');
  const hint = from === 'km'
    ? '1 km = ' + (1 / KM_PER_MILE).toFixed(6) + ' mi'
    : '1 mi = ' + KM_PER_MILE + ' km';
  return html`<div class="stack">
    <div class="spread">
      <div class="title">Length converter</div>
      <span class="badge">${from} → ${to}</span>
    </div>
    <div class="card stack">
      <label class="sub" for="cv-in">${name(from)}</label>
      <div class="row" style=${{ alignItems: 'center' }}>
        <input id="cv-in" class="grow" type="number" inputmode="decimal" step="any" data-testid="value"
          value=${val} onInput=${onVal} onChange=${onVal} style=${{ minWidth: 0 }}/>
        <span class="title" style=${{ width: 28 }}>${from}</span>
      </div>
    </div>
    <div style=${{ display: 'flex', justifyContent: 'center' }}>
      <button class="btn ghost" data-testid="swap" aria-label="Swap direction" style=${{ minHeight: 40 }}
        onClick=${() => setFrom((f) => (f === 'km' ? 'mi' : 'km'))}>⇅ Swap</button>
    </div>
    <div class="card stack">
      <span class="sub">${name(to)}</span>
      <div class="big num" data-testid="result">${ok ? r.toFixed(2) + ' ' + to : '—'}</div>
    </div>
    <div class="muted num">${hint}</div>
  </div>`;
}
return App;
