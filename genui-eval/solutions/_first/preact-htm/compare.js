const opts = [["Sony WH-1000XM6",449,30,254,5],["Bose QC Ultra",429,24,250,5],["Sennheiser Momentum 4",379,60,293,4]];
const rows = [["Price",1,'min',v => '$' + v],["Battery",2,'max',v => v + 'h'],["Weight",3,'min',v => v + 'g'],["ANC",4,'max',v => '●'.repeat(v)]];
function App() {
  const [pick, setPick] = useState(null);
  const best = { background: 'var(--accentSoft)', color: 'var(--accent)', fontWeight: 600 };
  return html`<div class="stack">
    <div><div class="title">Headphones compared</div><div class="sub">Best in each row highlighted</div></div>
    <div style="display:grid;grid-template-columns:64px repeat(3,minmax(0,1fr));gap:4px;font-size:13px;align-items:center;text-align:center">
      <span></span>${opts.map((o, i) => html`<div style=${{ fontWeight: 600, fontSize: '12px', lineHeight: 1.25, padding: '8px 4px', borderRadius: '10px', outline: pick === i ? '2px solid var(--accent)' : 'none' }}>${o[0]}</div>`)}
      ${rows.map(([n, k, m, f]) => { const vals = opts.map(o => o[k]), b = m === 'min' ? Math.min(...vals) : Math.max(...vals);
        return html`<span class="sub" style="text-align:left;font-size:12px">${n}</span>${opts.map(o => html`<div class="num" style=${{ padding: '6px 2px', borderRadius: '8px', ...(o[k] === b ? best : {}) }}>${f(o[k])}</div>`)}`; })}
      <span></span>${opts.map((o, i) => html`<button class=${'btn small' + (pick === i ? '' : ' ghost')} style="width:100%;padding:0 4px" data-testid=${'pick-' + i} onClick=${() => setPick(i)}>${pick === i ? 'Picked' : 'Pick'}</button>`)}
    </div>
    <div class="card" data-testid="choice">${pick == null ? html`<span class="sub">Pick one to decide</span>` : html`<span style="font-weight:600">Picked: ${opts[pick][0]}</span> <span class="sub">· $${opts[pick][1]}</span>`}</div>
  </div>`;
}
return App;
