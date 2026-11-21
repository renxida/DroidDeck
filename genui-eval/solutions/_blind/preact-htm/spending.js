const SPEND = { Rent: 1850, Groceries: 420, Dining: 310, Transport: 140, Subscriptions: 65, Other: 215 };
const CATS = Object.entries(SPEND).map(([name, value], i) => ({ name, value, color: G.color(i) }));
const money = (v) => '$' + Math.round(v).toLocaleString('en-US');

function App() {
  const [off, setOff] = useState({});
  const incl = CATS.filter((c) => !off[c.name]);
  const total = incl.reduce((s, c) => s + c.value, 0);
  const toggle = (name) => setOff((o) => ({ ...o, [name]: !o[name] }));
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Spending this month</div>
        <div class="sub">${incl.length} of ${CATS.length} categories shown</div>
      </div>
    </div>
    <div style=${{ display: 'flex', justifyContent: 'center' }}>
      ${incl.length
        ? html`<${G.Donut} data=${incl.map((c) => ({ label: c.name, value: c.value, color: c.color }))} size=${168} thickness=${22} center=${money(total)} sub="total"/>`
        : html`<div class="sub" style=${{ height: 168, display: 'flex', alignItems: 'center' }}>All categories hidden</div>`}
    </div>
    <div class="spread">
      <span class="sub">Included total</span>
      <span class="title num" data-testid="total">${money(total)}</span>
    </div>
    <div class="list">
      ${CATS.map((c) => {
        const on = !off[c.name];
        return html`<div key=${c.name} role="button" tabindex="0" aria-pressed=${on ? 'true' : 'false'}
          data-testid=${'cat-' + c.name}
          onClick=${() => toggle(c.name)}
          onKeyDown=${(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(c.name); } }}
          class="row"
          style=${{ alignItems: 'center', minHeight: 40, cursor: 'pointer', opacity: on ? 1 : 0.45, userSelect: 'none' }}>
          <span class="dot" style=${{ background: c.color }}></span>
          <span class="grow" style=${{ textDecoration: on ? 'none' : 'line-through' }}>${c.name}</span>
          <span class="num">${money(c.value)}</span>
          <span class="muted num" style=${{ width: 40, textAlign: 'right' }}>${on && total ? G.fmt.pct(c.value / total) : '—'}</span>
        </div>`;
      })}
    </div>
    <div class="muted">Tap a category to include or exclude it.</div>
  </div>`;
}
return App;
