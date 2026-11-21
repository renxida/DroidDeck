const TOTAL = 180, C = 2 * Math.PI * 88;
function App() {
  const [left, setLeft] = useState(TOTAL);
  const [running, setRunning] = useState(false);
  const endAt = useRef(0);
  useEffect(() => {
    if (!running) return;
    endAt.current = Date.now() + left * 1000;
    const id = setInterval(() => {
      const l = Math.max(0, Math.ceil((endAt.current - Date.now()) / 1000));
      setLeft(l); if (l === 0) setRunning(false);
    }, 250);
    return () => clearInterval(id);
  }, [running]);
  return html`<div class="stack" style="align-items:center">
    <div class="title" style="align-self:flex-start">Tea timer</div>
    <div style="position:relative;width:200px;height:200px">
      <svg viewBox="0 0 200 200" width="200" height="200"><circle cx="100" cy="100" r="88" fill="none" stroke="var(--outline)" stroke-width="12"/>
        <circle cx="100" cy="100" r="88" fill="none" stroke="var(--accent)" stroke-width="12" stroke-linecap="round" transform="rotate(-90 100 100)" stroke-dasharray=${C} stroke-dashoffset=${C * (1 - left / TOTAL)}/></svg>
      <div style="position:absolute;inset:0;display:grid;place-items:center"><div class="big" style="font-size:44px" data-testid="time">${G.fmt.dur(left)}</div></div>
    </div>
    <div class="row" style="width:100%">
      <button class="btn grow" data-testid="start" disabled=${left === 0} onClick=${() => setRunning(!running)}>${running ? 'Pause' : left === 0 ? 'Done' : left < TOTAL ? 'Resume' : 'Start'}</button>
      <button class="btn ghost grow" data-testid="reset" onClick=${() => { setRunning(false); setLeft(TOTAL); }}>Reset</button>
    </div>
  </div>`;
}
return App;
