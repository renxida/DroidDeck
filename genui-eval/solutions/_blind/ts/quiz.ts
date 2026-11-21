const options: string[] = ['Mercury', 'Venus', 'Jupiter', 'Mars'];
const correct: number = 2;
const explanation: string = 'Jupiter spins once every 9h 56m, the shortest day of any planet.';
let answered: number = -1;

root.innerHTML = `
<style>
.qz .opts { display: flex; flex-direction: column; gap: 8px; }
.qz .opt { min-height: 44px; padding: 10px 14px; border-radius: 12px; border: 1px solid var(--outline);
  background: var(--surface2); color: var(--onSurface); font: inherit; font-size: 15px; text-align: left; cursor: pointer; }
.qz .opt:disabled { opacity: 1; cursor: default; }
.qz .opt.right { background: var(--success); border-color: var(--success); color: var(--onAccent); }
.qz .opt.wrong { background: var(--danger); border-color: var(--danger); color: var(--onAccent); }
.qz .opt.dim { opacity: 0.5; }
.qz .fb { min-height: 20px; }
.qz .fb .h { font-weight: 600; }
.qz .fb.ok .h { color: var(--success); }
.qz .fb.no .h { color: var(--danger); }
</style>
<div class="stack qz">
  <div>
    <div class="muted">Astronomy quiz</div>
    <div class="title">Which planet has the shortest day?</div>
  </div>
  <div class="opts" id="opts">
    ${options.map((o, i) => `<button class="opt" data-opt="${i}" data-testid="opt-${i}">${G.esc(o)}</button>`).join('')}
  </div>
  <div class="card fb" data-testid="feedback" aria-live="polite"><span class="sub">Pick an answer</span></div>
</div>`;

const opts = root.querySelector<HTMLDivElement>('#opts')!;
const fb = root.querySelector<HTMLDivElement>('[data-testid=feedback]')!;
const buttons = Array.from(opts.querySelectorAll<HTMLButtonElement>('[data-opt]'));

opts.onclick = (e: MouseEvent): void => {
  if (answered >= 0) return;
  const b = (e.target as Element).closest<HTMLButtonElement>('[data-opt]');
  if (!b) return;
  answered = Number(b.getAttribute('data-opt'));
  const ok = answered === correct;
  buttons.forEach((btn, i) => {
    btn.disabled = true;
    if (i === answered) btn.classList.add(ok ? 'right' : 'wrong');
    else if (i === correct) btn.classList.add('right');
    else btn.classList.add('dim');
  });
  fb.className = `card fb ${ok ? 'ok' : 'no'}`;
  fb.innerHTML = `<div class="h">${ok ? 'Correct!' : 'Not quite, it\'s Jupiter'}</div><div class="sub">${G.esc(explanation)}</div>`;
};
