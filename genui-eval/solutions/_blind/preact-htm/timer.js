const TOTAL = 180;

function App() {
  const [left, setLeft] = useState(TOTAL);
  const [run, setRun] = useState(false);
  useEffect(() => {
    if (!run) return;
    const id = setInterval(() => setLeft((l) => Math.max(0, l - 1)), 1000);
    return () => clearInterval(id);
  }, [run]);
  useEffect(() => { if (left === 0 && run) setRun(false); }, [left, run]);
  const toggle = () => {
    if (left === 0) { setLeft(TOTAL); setRun(true); }
    else setRun((r) => !r);
  };
  const reset = () => { setRun(false); setLeft(TOTAL); };
  const R = 86, C = 2 * Math.PI * R, frac = left / TOTAL;
  const status = left === 0 ? 'Done' : run ? 'Steeping' : left === TOTAL ? 'Ready' : 'Paused';
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Tea timer</div>
        <div class="sub">Steep for 3 minutes</div>
      </div>
      <span class="badge">${status}</span>
    </div>
    <div style=${{ position: 'relative', width: 200, height: 200, alignSelf: 'center' }}>
      <svg width="200" height="200" viewBox="0 0 200 200" role="progressbar" aria-valuemin="0" aria-valuemax=${TOTAL} aria-valuenow=${left} data-testid="progress">
        <circle cx="100" cy="100" r=${R} fill="none" stroke-width="12" style=${{ stroke: 'var(--outline)' }}/>
        <circle cx="100" cy="100" r=${R} fill="none" stroke-width="12" stroke-linecap="round"
          stroke-dasharray=${C} stroke-dashoffset=${C * (1 - frac)} transform="rotate(-90 100 100)"
          style=${{ stroke: left === 0 ? 'var(--success)' : 'var(--accent)', transition: 'stroke-dashoffset 1s linear' }}/>
      </svg>
      <div style=${{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
        <div class="num" data-testid="time" style=${{ fontSize: 48, fontWeight: 600, lineHeight: 1 }}>${G.fmt.dur(left)}</div>
        <div class="muted" style=${{ marginTop: 6 }}>${left === 0 ? 'Tea is ready' : 'remaining'}</div>
      </div>
    </div>
    <div class="row">
      <button class="btn grow" data-testid="start" style=${{ minHeight: 44 }} onClick=${toggle}>${run ? 'Pause' : 'Start'}</button>
      <button class="btn ghost grow" data-testid="reset" style=${{ minHeight: 44 }} onClick=${reset}>Reset</button>
    </div>
  </div>`;
}
return App;
