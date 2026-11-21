const options = ["Mercury", "Venus", "Jupiter", "Mars"], correct = 2;
let ans: number | null = null;
root.innerHTML = `<div class="qz stack"><span class="badge" style="align-self:flex-start">Space quiz</span><div class="title">Which planet has the shortest day?</div><div class="stack" style="gap:8px" id="opts"></div><div data-testid="feedback"></div></div>
<style>
.qz .o{justify-content:flex-start;width:100%;background:var(--surface2);color:var(--onSurface);border:1px solid var(--outline);font-weight:500}
.qz .o.right{background:var(--success);color:var(--surface);border-color:transparent}
.qz .o.wrong{background:var(--danger);color:var(--surface);border-color:transparent}
.qz .o:disabled{opacity:1}
</style>`;
const optsEl = root.querySelector<HTMLDivElement>('#opts')!;
const fb = root.querySelector<HTMLDivElement>('[data-testid=feedback]')!;
function draw(): void {
  optsEl.innerHTML = options.map((o, i) => `<button class="btn o ${ans !== null && i === correct ? 'right' : ''} ${ans === i && i !== correct ? 'wrong' : ''}" data-testid="opt-${i}" data-i="${i}" ${ans !== null ? 'disabled' : ''}>${o}</button>`).join('');
  if (ans !== null) {
    const ok = ans === correct;
    fb.innerHTML = `<div class="card"><div class="title" style="color:${ok ? 'var(--success)' : 'var(--danger)'}">${ok ? 'Correct!' : "Not quite, it's Jupiter"}</div><div class="sub">Jupiter spins once every ~9h 56m, the fastest rotation of any planet.</div></div>`;
  }
}
optsEl.addEventListener('click', (e) => {
  const b = (e.target as Element).closest<HTMLElement>('[data-i]');
  if (!b || ans !== null) return;
  ans = Number(b.dataset.i);
  draw();
});
draw();
