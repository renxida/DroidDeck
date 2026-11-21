const options = ["Mercury","Venus","Jupiter","Mars"], correct = 2;
function App() {
  const [ans, setAns] = useState(null);
  const style = i => {
    const base = { justifyContent: 'flex-start', width: '100%', fontWeight: 500, border: '1px solid var(--outline)', background: 'var(--surface2)', color: 'var(--onSurface)', opacity: 1 };
    if (ans == null) return base;
    if (i === correct) return { ...base, background: 'var(--success)', color: 'var(--surface)', borderColor: 'transparent' };
    if (i === ans) return { ...base, background: 'var(--danger)', color: 'var(--surface)', borderColor: 'transparent' };
    return base;
  };
  const ok = ans === correct;
  return html`<div class="stack">
    <span class="badge" style="align-self:flex-start">Space quiz</span>
    <div class="title">Which planet has the shortest day?</div>
    <div class="stack" style="gap:8px">${options.map((o, i) => html`<button class="btn" data-testid=${'opt-' + i} disabled=${ans != null} style=${style(i)} onClick=${() => ans == null && setAns(i)}>${o}</button>`)}</div>
    <div data-testid="feedback">${ans != null && html`<div class="card"><div class="title" style=${{ color: ok ? 'var(--success)' : 'var(--danger)' }}>${ok ? 'Correct!' : "Not quite, it's Jupiter"}</div><div class="sub">Jupiter spins once every ~9h 56m, the fastest rotation of any planet.</div></div>`}</div>
  </div>`;
}
return App;
