const HABITS = [
  ["Meditate", [1, 1, 0, 1, 1, 0, 0]],
  ["Stretch", [1, 0, 0, 1, 0, 1, 0]],
  ["Read 30m", [1, 1, 1, 1, 0, 1, 1]],
  ["No phone after 11", [0, 0, 1, 0, 0, 1, 0]]
];
const DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const COLS = 'minmax(0,1fr) repeat(7, 32px) 28px';

function App() {
  const [grid, setGrid] = useState(() => HABITS.map((h) => h[1].slice()));
  const toggle = (r, c) => setGrid((g) => g.map((row, i) => i !== r ? row : row.map((v, j) => j === c ? (v ? 0 : 1) : v)));
  const done = grid.reduce((s, row) => s + row.reduce((a, b) => a + b, 0), 0);
  const max = HABITS.length * 7;
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Habits this week</div>
        <div class="sub">Tap a cell to toggle</div>
      </div>
      <div style=${{ textAlign: 'right' }}>
        <div class="title num" data-testid="count">${done} / ${max}</div>
        <div class="muted">done</div>
      </div>
    </div>
    <div class="progress"><i style=${{ width: (done / max * 100) + '%' }}></i></div>
    <div style=${{ display: 'grid', gridTemplateColumns: COLS, gap: 3, alignItems: 'center' }}>
      <span></span>
      ${DAYS.map((d) => html`<span key=${d} class="muted" style=${{ textAlign: 'center' }} title=${d}>${d.slice(0, 2)}</span>`)}
      <span class="muted" style=${{ textAlign: 'center' }}>#</span>
      ${grid.map((row, r) => {
        const n = row.reduce((a, b) => a + b, 0);
        return [
          html`<span key=${'n' + r} style=${{ fontSize: 13, lineHeight: 1.2, overflowWrap: 'anywhere' }}>${HABITS[r][0]}</span>`,
          ...row.map((v, c) => html`<button key=${r + '-' + c} data-testid=${'cell-' + r + '-' + c}
            aria-pressed=${v ? 'true' : 'false'} aria-label=${HABITS[r][0] + ' ' + DAYS[c]}
            onClick=${() => toggle(r, c)}
            style=${{
              width: 32, height: 32, padding: 0, borderRadius: 8, cursor: 'pointer', font: 'inherit', fontSize: 14,
              border: '1px solid ' + (v ? 'var(--accent)' : 'var(--outline)'),
              background: v ? 'var(--accent)' : 'transparent', color: 'var(--onAccent)'
            }}>${v ? '✓' : ''}</button>`),
          html`<span key=${'c' + r} class="num sub" data-testid=${'habit-count-' + r} style=${{ textAlign: 'center' }}>${n}</span>`
        ];
      })}
    </div>
  </div>`;
}
return App;
