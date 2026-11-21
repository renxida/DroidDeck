const bpm = [58,56,55,54,55,57,63,78,92,85,76,74,81,79,73,72,88,124,131,96,82,74,66,61];
const pts = bpm.map((v, i) => ({ label: i % 6 === 0 ? `${i % 12 || 12}${i < 12 ? 'a' : 'p'}` : '', value: v }));
const avg = Math.round(bpm.reduce((a, b) => a + b, 0) / bpm.length);
function App() {
  const [showAvg, setShowAvg] = useState(false);
  return html`<div class="stack">
    <div class="spread"><div class="title">Heart rate today</div><button class=${'chip' + (showAvg ? ' on' : '')} data-testid="toggle-avg" onClick=${() => setShowAvg(!showAvg)}>Show average</button></div>
    <div class="grid2">
      <div class="card"><div class="muted">Resting</div><div class="num" style="font-size:22px;font-weight:650">${Math.min(...bpm)} bpm</div></div>
      <div class="card"><div class="muted">Peak</div><div class="num" style="font-size:22px;font-weight:650;color:var(--accent)">${Math.max(...bpm)} bpm</div></div>
    </div>
    <${G.Line} data=${pts} annotate="minmax" avg=${showAvg} every=${6} color="var(--rose)"/>
    ${showAvg && html`<div class="sub" data-testid="avg">avg ${avg} bpm</div>`}
  </div>`;
}
return App;
