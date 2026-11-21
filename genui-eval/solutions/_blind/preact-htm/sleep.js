const DAYS = [["Mon", 7.2], ["Tue", 6.8], ["Wed", 6.1], ["Thu", 7.9], ["Fri", 5.4], ["Sat", 8.6], ["Sun", 8.1]];
const TARGET = 8;

function App() {
  const [sel, setSel] = useState(null);
  const data = DAYS.map(([label, value]) => ({ label, value }));
  const avg = DAYS.reduce((s, d) => s + d[1], 0) / DAYS.length;
  const hit = DAYS.filter((d) => d[1] >= TARGET).length;
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Sleep this week</div>
        <div class="sub">Target ${TARGET}h · ${hit} of ${DAYS.length} nights met</div>
      </div>
      <div style=${{ textAlign: 'right' }}>
        <div class="big num" data-testid="avg">${avg.toFixed(1)}h</div>
        <div class="muted">weekly avg</div>
      </div>
    </div>
    <${G.Bar} data=${data} target=${TARGET} unit="h" height=${170} selected=${sel} values=${true} onBar=${(i) => setSel(i)}/>
    <div class="card spread" data-testid="detail">
      ${sel == null
        ? html`<span class="sub">Tap a bar to see that night</span>`
        : html`<span class="title num">${DAYS[sel][0]} · ${DAYS[sel][1].toFixed(1)}h</span>`}
    </div>
    ${sel != null ? html`<div class="muted">${DAYS[sel][1] >= TARGET
      ? 'Met the ' + TARGET + 'h target'
      : (TARGET - DAYS[sel][1]).toFixed(1) + 'h short of target'}</div>` : null}
  </div>`;
}
return App;
