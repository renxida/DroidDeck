const OPTIONS = ["Mercury", "Venus", "Jupiter", "Mars"];
const CORRECT = 2;
const WHY = 'Jupiter spins once about every 9h 56m, the shortest day of any planet.';

function App() {
  const [ans, setAns] = useState(null);
  const answered = ans != null;
  const choose = (i) => { if (!answered) setAns(i); };
  const styleFor = (i) => {
    const base = {
      width: '100%', minHeight: 44, padding: '10px 14px', borderRadius: 12, font: 'inherit', textAlign: 'left',
      cursor: answered ? 'default' : 'pointer', border: '1px solid var(--outline)',
      background: 'var(--surface2)', color: 'var(--onSurface)'
    };
    if (!answered) return base;
    if (i === ans && i === CORRECT) return { ...base, background: 'var(--success)', borderColor: 'var(--success)', color: 'var(--onAccent)' };
    if (i === ans) return { ...base, background: 'var(--danger)', borderColor: 'var(--danger)', color: 'var(--onAccent)' };
    if (i === CORRECT) return { ...base, borderColor: 'var(--success)', color: 'var(--success)' };
    return { ...base, opacity: 0.55 };
  };
  const ok = ans === CORRECT;
  return html`<div class="card stack">
    <div class="muted">Quiz · Planets</div>
    <div class="title">Which planet has the shortest day?</div>
    <div class="stack" style=${{ gap: 8 }}>
      ${OPTIONS.map((o, i) => html`<button key=${o} data-testid=${'opt-' + i} aria-disabled=${answered ? 'true' : 'false'}
        aria-pressed=${ans === i ? 'true' : 'false'} style=${styleFor(i)} onClick=${() => choose(i)}>
        ${o}${answered && i === ans ? (ok ? '  ✓' : '  ✗') : ''}
      </button>`)}
    </div>
    ${answered ? html`<div data-testid="feedback" class="stack" style=${{ gap: 4 }}>
      <div class="title" style=${{ color: ok ? 'var(--success)' : 'var(--danger)' }}>${ok ? 'Correct!' : "Not quite, it's Jupiter"}</div>
      <div class="sub">${WHY}</div>
    </div>` : html`<div class="muted">Tap an answer</div>`}
  </div>`;
}
return App;
