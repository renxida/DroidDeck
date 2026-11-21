const days = [["Mon",7.2],["Tue",6.8],["Wed",6.1],["Thu",7.9],["Fri",5.4],["Sat",8.6],["Sun",8.1]].map(([label,value]) => ({ label, value }));
function App() {
  const [sel, setSel] = useState(null);
  const avg = days.reduce((a, d) => a + d.value, 0) / days.length;
  const d = sel != null ? days[sel] : null;
  return html`<div class="stack">
    <div class="spread"><div><div class="title">Sleep this week</div><div class="sub">Target 8h a night</div></div>
      <div style="text-align:right"><div class="big">${avg.toFixed(1)}h</div><div class="muted">avg / night</div></div></div>
    <${G.Bar} data=${days.map(x => ({ ...x, color: x.value >= 8 ? 'var(--success)' : 'var(--accent)' }))} target=${8} unit="h" selected=${sel} onBar=${setSel}/>
    <div class="card row" data-testid="detail">${d
      ? html`<span class="title">${d.label} · ${d.value}h</span><span class="grow"></span><span class="badge">${d.value >= 8 ? '+' : ''}${(d.value - 8).toFixed(1)}h vs target</span>`
      : html`<span class="sub">Tap a bar to see the day</span>`}</div>
  </div>`;
}
return App;
