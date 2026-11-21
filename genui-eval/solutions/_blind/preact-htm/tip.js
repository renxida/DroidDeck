const BILL = 84.5;

function App() {
  const [tip, setTip] = useState(18);
  const [n, setN] = useState(2);
  const tipAmt = BILL * tip / 100;
  const total = BILL + tipAmt;
  const per = total / n;
  const onTip = (e) => setTip(Number(e.target.value));
  return html`<div class="stack">
    <div class="spread">
      <div class="title">Tip splitter</div>
      <div class="sub num">Bill ${G.fmt.money(BILL)}</div>
    </div>
    <div class="card stack">
      <div class="spread"><span>Tip</span><span class="title num">${tip}%</span></div>
      <input type="range" min="10" max="30" step="1" value=${tip} data-testid="tip" onInput=${onTip} onChange=${onTip} aria-label="Tip percent"/>
      <div class="spread muted"><span>10%</span><span>30%</span></div>
    </div>
    <div class="card spread">
      <span>Party size</span>
      <div class="row" style=${{ alignItems: 'center' }}>
        <button class="btn small ghost" data-testid="minus" aria-label="Fewer people" style=${{ minWidth: 40, minHeight: 36 }}
          onClick=${() => setN((v) => Math.max(1, v - 1))}>−</button>
        <span class="title num" data-testid="party" style=${{ minWidth: 28, textAlign: 'center' }}>${n}</span>
        <button class="btn small ghost" data-testid="plus" aria-label="More people" style=${{ minWidth: 40, minHeight: 36 }}
          onClick=${() => setN((v) => v + 1)}>+</button>
      </div>
    </div>
    <div class="card stack">
      <div class="spread"><span class="sub">Tip amount</span><span class="num" data-testid="tip-amount">${G.fmt.money(tipAmt)}</span></div>
      <div class="spread"><span class="sub">Total</span><span class="num" data-testid="total">${G.fmt.money(total)}</span></div>
      <div class="divider"></div>
      <div class="spread">
        <span class="sub">Per person${n > 1 ? ' (' + n + ' people)' : ''}</span>
        <span class="big num" data-testid="per-person">${G.fmt.money(per)}</span>
      </div>
    </div>
  </div>`;
}
return App;
