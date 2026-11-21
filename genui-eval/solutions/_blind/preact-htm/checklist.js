const ITEMS = [["Passport", 1], ["Charger", 1], ["Toothbrush", 0], ["Rain jacket", 0], ["Headphones", 0], ["Snacks", 0]];

function App() {
  const [done, setDone] = useState(() => ITEMS.map((x) => !!x[1]));
  const toggle = (i) => setDone((d) => d.map((v, j) => (j === i ? !v : v)));
  const n = done.filter(Boolean).length;
  const pct = n / ITEMS.length * 100;
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Weekend trip packing</div>
        <div class="sub">${n === ITEMS.length ? 'All packed, have a great trip!' : (ITEMS.length - n) + ' left to pack'}</div>
      </div>
      <span class="title num" data-testid="progress-label">${n}/${ITEMS.length} packed</span>
    </div>
    <div class="progress"><i style=${{ width: pct + '%' }}></i></div>
    <div class="list">
      ${ITEMS.map((it, i) => {
        const on = done[i];
        return html`<div key=${it[0]} role="checkbox" aria-checked=${on ? 'true' : 'false'} tabindex="0"
          data-testid=${'item-' + i} class="row"
          onClick=${() => toggle(i)}
          onKeyDown=${(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(i); } }}
          style=${{ alignItems: 'center', minHeight: 44, cursor: 'pointer', userSelect: 'none' }}>
          <span style=${{
            width: 24, height: 24, borderRadius: 12, flex: 'none', display: 'flex', alignItems: 'center', justifyContent: 'center',
            border: '2px solid ' + (on ? 'var(--accent)' : 'var(--outline)'),
            background: on ? 'var(--accent)' : 'transparent', color: 'var(--onAccent)', fontSize: 14, boxSizing: 'border-box'
          }}>${on ? '✓' : ''}</span>
          <span class="grow" style=${{ textDecoration: on ? 'line-through' : 'none', color: on ? 'var(--onSurface3)' : 'var(--onSurface)' }}>${it[0]}</span>
        </div>`;
      })}
    </div>
  </div>`;
}
return App;
