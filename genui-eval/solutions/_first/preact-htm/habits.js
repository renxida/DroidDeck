const init = { "Meditate": [1,1,0,1,1,0,0], "Stretch": [1,0,0,1,0,1,0], "Read 30m": [1,1,1,1,0,1,1], "No phone after 11": [0,0,1,0,0,1,0] };
const days = ['M','T','W','T','F','S','S'];
function App() {
  const [rows, setRows] = useState(Object.entries(init));
  const toggle = (r, c) => setRows(rows.map(([n, v], i) => i !== r ? [n, v] : [n, v.map((x, j) => j === c ? (x ? 0 : 1) : x)]));
  const done = v => v.filter(Boolean).length;
  const cell = { width: '30px', height: '30px', borderRadius: '8px', border: 0, padding: 0, cursor: 'pointer', color: 'var(--onAccent)', fontWeight: 700 };
  return html`<div class="stack">
    <div class="spread"><div class="title">Habits this week</div><span class="badge num" data-testid="count">${rows.reduce((a, [, v]) => a + done(v), 0)} / 28</span></div>
    <div style="display:grid;grid-template-columns:96px repeat(7,minmax(0,1fr));gap:6px 2px;align-items:center;justify-items:center">
      <span></span>${days.map(d => html`<span class="muted">${d}</span>`)}
      ${rows.map(([n, v], r) => html`
        <div style="justify-self:start;font-size:13px;line-height:1.2">${n}<div class="muted">${done(v)}/7</div></div>
        ${v.map((x, c) => html`<button data-testid=${`cell-${r}-${c}`} aria-label=${n + ' ' + days[c]} onClick=${() => toggle(r, c)} style=${{ ...cell, background: x ? 'var(--accent)' : 'var(--surface2)' }}>${x ? '✓' : ''}</button>`)}`)}
    </div>
  </div>`;
}
return App;
