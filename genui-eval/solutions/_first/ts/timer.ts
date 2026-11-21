const TOTAL = 180, C = 2 * Math.PI * 88;
let left = TOTAL, endAt = 0;
let id: number | null = null;
root.innerHTML = `<div class="stack" style="align-items:center">
  <div class="title" style="align-self:flex-start">Tea timer</div>
  <div style="position:relative;width:200px;height:200px">
    <svg viewBox="0 0 200 200" width="200" height="200"><circle cx="100" cy="100" r="88" fill="none" stroke="var(--outline)" stroke-width="12"/><circle id="arc" cx="100" cy="100" r="88" fill="none" stroke="var(--accent)" stroke-width="12" stroke-linecap="round" transform="rotate(-90 100 100)" stroke-dasharray="${C}" stroke-dashoffset="0"/></svg>
    <div style="position:absolute;inset:0;display:grid;place-items:center"><div class="big" style="font-size:44px" data-testid="time">3:00</div></div>
  </div>
  <div class="row" style="width:100%"><button class="btn grow" data-testid="start">Start</button><button class="btn ghost grow" data-testid="reset">Reset</button></div></div>`;
const time = root.querySelector<HTMLDivElement>('[data-testid=time]')!;
const arc = root.querySelector<SVGCircleElement>('#arc')!;
const startBtn = root.querySelector<HTMLButtonElement>('[data-testid=start]')!;
function show(): void {
  time.textContent = G.fmt.dur(left);
  arc.setAttribute('stroke-dashoffset', String(C * (1 - left / TOTAL)));
  startBtn.textContent = id !== null ? 'Pause' : left === 0 ? 'Done' : left < TOTAL ? 'Resume' : 'Start';
}
function stop(): void { if (id !== null) { clearInterval(id); id = null; } }
function tick(): void { left = Math.max(0, Math.ceil((endAt - Date.now()) / 1000)); if (left === 0) stop(); show(); }
startBtn.onclick = () => { if (id !== null) stop(); else if (left > 0) { endAt = Date.now() + left * 1000; id = window.setInterval(tick, 250); } show(); };
root.querySelector<HTMLButtonElement>('[data-testid=reset]')!.onclick = () => { stop(); left = TOTAL; show(); };
show();
