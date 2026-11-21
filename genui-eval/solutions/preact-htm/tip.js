const bill = 84.5;
function App() {
  const [tip, setTip] = useState(18);
  const [people, setPeople] = useState(2);
  const t = bill * tip / 100, tot = bill + t;
  return html`<div class="stack">
    <div class="spread"><div class="title">Split the bill</div><span class="badge">Bill ${G.fmt.money(bill)}</span></div>
    <div class="card stack">
      <div class="spread"><span class="sub">Tip</span><span class="num" style="font-weight:600">${tip}%</span></div>
      <input type="range" min="10" max="30" step="1" value=${tip} data-testid="tip" onInput=${e => setTip(+e.target.value)}/>
      <div class="spread"><span class="sub">People</span><div class="row">
        <button class="btn small ghost" data-testid="minus" disabled=${people <= 1} onClick=${() => setPeople(Math.max(1, people - 1))}>−</button>
        <span class="num" style="min-width:28px;text-align:center;font-weight:600">${people}</span>
        <button class="btn small ghost" data-testid="plus" onClick=${() => setPeople(people + 1)}>+</button></div></div>
    </div>
    <div class="grid2"><div class="card"><div class="muted">Tip</div><div class="num" style="font-weight:600">${G.fmt.money(t)}</div></div><div class="card"><div class="muted">Total</div><div class="num" style="font-weight:600">${G.fmt.money(tot)}</div></div></div>
    <div class="card" style="background:var(--accentSoft);text-align:center"><div class="sub">Each person pays</div><div class="big" style="color:var(--accent)" data-testid="per-person">${G.fmt.money(tot / people)}</div></div>
  </div>`;
}
return App;
