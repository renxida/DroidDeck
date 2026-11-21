const ITEMS = [
  ["Sony WH-1000XM6", 449, 30, 254, 5],
  ["Bose QC Ultra", 429, 24, 250, 5],
  ["Sennheiser Momentum 4", 379, 60, 293, 4]
];
const ROWS = [
  { label: 'Price', idx: 1, fmt: (v) => '$' + v, best: 'min' },
  { label: 'Battery', idx: 2, fmt: (v) => v + ' h', best: 'max' },
  { label: 'Weight', idx: 3, fmt: (v) => v + ' g', best: 'min' },
  { label: 'ANC', idx: 4, fmt: (v) => v + '/5', best: 'max' }
];
const COLS = '62px repeat(3, minmax(0,1fr))';

function App() {
  const [pick, setPick] = useState(null);
  const colBg = (i) => (pick === i ? 'var(--accentSoft)' : 'transparent');
  const cell = { padding: '8px 6px', fontSize: 13, display: 'flex', alignItems: 'center', justifyContent: 'center', textAlign: 'center', minWidth: 0 };
  return html`<div class="stack">
    <div>
      <div class="title">Headphones compared</div>
      <div class="sub">Best in each row is highlighted</div>
    </div>
    <div style=${{ display: 'grid', gridTemplateColumns: COLS }}>
      <div style=${cell}></div>
      ${ITEMS.map((it, i) => html`<div key=${'h' + i} style=${{ ...cell, fontWeight: 600, background: colBg(i), borderRadius: '10px 10px 0 0', flexDirection: 'column', overflowWrap: 'anywhere' }}>
        ${pick === i ? html`<span class="badge" style=${{ marginBottom: 4 }}>✓ Picked</span>` : null}
        ${it[0]}
      </div>`)}
      ${ROWS.map((row) => {
        const vals = ITEMS.map((it) => it[row.idx]);
        const target = row.best === 'min' ? Math.min(...vals) : Math.max(...vals);
        return [
          html`<div key=${row.label} class="sub" style=${{ ...cell, justifyContent: 'flex-start', borderTop: '1px solid var(--outline)' }}>${row.label}</div>`,
          ...ITEMS.map((it, i) => {
            const best = it[row.idx] === target;
            return html`<div key=${row.label + i} class="num" data-best=${best ? 'true' : 'false'} style=${{
              ...cell, borderTop: '1px solid var(--outline)', background: colBg(i),
              color: best ? 'var(--success)' : 'var(--onSurface)', fontWeight: best ? 700 : 400
            }}>${best ? '★ ' : ''}${row.fmt(it[row.idx])}</div>`;
          })
        ];
      })}
      <div style=${cell}></div>
      ${ITEMS.map((it, i) => html`<div key=${'p' + i} style=${{ ...cell, background: colBg(i), borderRadius: '0 0 10px 10px' }}>
        <button class=${pick === i ? 'btn small' : 'btn small ghost'} data-testid=${'pick-' + i}
          aria-pressed=${pick === i ? 'true' : 'false'} style=${{ width: '100%', minHeight: 34 }}
          onClick=${() => setPick(i)}>${pick === i ? '✓ Picked' : 'Pick'}</button>
      </div>`)}
    </div>
    <div class="card" data-testid="choice">${pick == null ? 'No pick yet' : 'Picked: ' + ITEMS[pick][0]}</div>
  </div>`;
}
return App;
