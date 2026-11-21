const data = { Rent: 1850, Groceries: 420, Dining: 310, Transport: 140, Subscriptions: 65, Other: 215 };
const cats = Object.entries(data).map(([label, value], i) => ({ label, value, color: G.color(i) }));
function App() {
  const [off, setOff] = useState({});
  const inc = cats.filter(c => !off[c.label]);
  const tot = inc.reduce((a, c) => a + c.value, 0);
  return html`<div class="stack">
    <div><div class="title">Spending · November</div><div class="sub">Tap a category to include or exclude it</div></div>
    <${G.Donut} data=${inc} center=${G.fmt.money(tot).replace('.00', '')} sub="spent" style=${{ display: 'flex', justifyContent: 'center' }}/>
    <div style="text-align:center"><span class="sub">Included total </span><span class="num" style="font-weight:600" data-testid="total">${G.fmt.money(tot)}</span></div>
    <div class="list">${cats.map(c => html`
      <div class="row" data-testid=${'cat-' + c.label} style=${{ minHeight: '40px', cursor: 'pointer', opacity: off[c.label] ? 0.45 : 1, gap: '10px' }} onClick=${() => setOff({ ...off, [c.label]: !off[c.label] })}>
        <span class="dot" style=${{ background: c.color }}></span>
        <span class="grow" style=${{ textDecoration: off[c.label] ? 'line-through' : 'none' }}>${c.label}</span>
        <span class="num">${G.fmt.money(c.value)}</span>
        <span class="muted num" style="width:40px;text-align:right">${!off[c.label] && tot ? G.fmt.pct(c.value / tot) : '—'}</span>
      </div>`)}</div>
  </div>`;
}
return App;
