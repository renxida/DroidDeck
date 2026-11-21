const TOTAL: number = 180;
let remaining: number = TOTAL;
let timerId: number | undefined;

const R = 80;
const CIRC = 2 * Math.PI * R;

root.innerHTML = `
<style>
.tea { align-items: center; }
.tea .ring { position: relative; width: 200px; height: 200px; margin: 0 auto; }
.tea .ring svg { transform: rotate(-90deg); }
.tea .ring .trk { stroke: var(--outline); }
.tea .ring .fg { stroke: var(--accent); transition: stroke-dashoffset 0.3s linear; }
.tea .ring.done .fg { stroke: var(--success); }
.tea .ctr { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; }
.tea .tm { font-size: 44px; font-weight: 600; }
.tea .ctl { justify-content: center; }
.tea .ctl .btn { min-width: 110px; min-height: 40px; }
</style>
<div class="stack tea">
  <div style="align-self:stretch">
    <div class="title">Tea timer</div>
    <div class="sub">3 minute steep</div>
  </div>
  <div class="ring" id="ring">
    <svg width="200" height="200" viewBox="0 0 200 200" aria-hidden="true">
      <circle class="trk" cx="100" cy="100" r="${R}" fill="none" stroke-width="12"/>
      <circle class="fg" id="fg" cx="100" cy="100" r="${R}" fill="none" stroke-width="12" stroke-linecap="round"
        stroke-dasharray="${CIRC.toFixed(2)}" stroke-dashoffset="0"/>
    </svg>
    <div class="ctr">
      <div class="tm num" data-testid="time"></div>
      <div class="muted" id="state"></div>
    </div>
  </div>
  <div class="row ctl">
    <button class="btn" data-testid="start">Start</button>
    <button class="btn ghost" data-testid="reset">Reset</button>
  </div>
</div>`;

const timeEl = root.querySelector<HTMLDivElement>('[data-testid=time]')!;
const stateEl = root.querySelector<HTMLDivElement>('#state')!;
const startBtn = root.querySelector<HTMLButtonElement>('[data-testid=start]')!;
const resetBtn = root.querySelector<HTMLButtonElement>('[data-testid=reset]')!;
const fg = root.querySelector<SVGCircleElement>('#fg')!;
const ring = root.querySelector<HTMLDivElement>('#ring')!;

const fmt = (s: number): string => {
  const m = Math.floor(s / 60);
  const ss = s % 60;
  return `${m}:${ss < 10 ? '0' : ''}${ss}`;
};

const running = (): boolean => timerId !== undefined;

const render = (): void => {
  timeEl.textContent = fmt(remaining);
  const frac = remaining / TOTAL;
  fg.setAttribute('stroke-dashoffset', (CIRC * (1 - frac)).toFixed(2));
  ring.classList.toggle('done', remaining === 0);
  startBtn.textContent = running() ? 'Pause' : (remaining < TOTAL && remaining > 0 ? 'Resume' : 'Start');
  stateEl.textContent = remaining === 0 ? 'Tea is ready' : running() ? 'Steeping…' : (remaining < TOTAL ? 'Paused' : 'Ready');
};

const stop = (): void => {
  if (timerId !== undefined) {
    window.clearInterval(timerId);
    timerId = undefined;
  }
};

const tick = (): void => {
  if (remaining > 0) remaining--;
  if (remaining <= 0) {
    remaining = 0;
    stop();
  }
  render();
};

startBtn.onclick = (): void => {
  if (running()) {
    stop();
  } else {
    if (remaining === 0) remaining = TOTAL;
    timerId = window.setInterval(tick, 1000);
  }
  render();
};

resetBtn.onclick = (): void => {
  stop();
  remaining = TOTAL;
  render();
};

render();
