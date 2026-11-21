type Stage = [key: string, name: string, ms: number, desc: string];
const stages: Stage[] = [["mic", "Mic", 0, "Speech captured at 16 kHz"], ["asr", "On-device ASR", 120, "Streaming speech-to-text on the phone"], ["claude", "Claude", 450, "Time to first token"], ["tts", "TTS", 180, "Text-to-speech, first audio chunk"], ["speaker", "Speaker", 40, "Audio output buffer"]];
const total = stages.reduce((a, s) => a + s[2], 0);
const maxMs = Math.max(...stages.map((s) => s[2]));
let sel: string | null = null;
root.innerHTML = `<div class="fl stack">
  <div class="spread"><div><div class="title">Voice pipeline</div><div class="sub">Speech in to audio out</div></div><div style="text-align:right"><div class="big" data-testid="total">${total} ms</div><div class="muted">end to end</div></div></div>
  <div id="nodes" class="stack" style="gap:0"></div>
  <div class="card" data-testid="detail"><span class="sub">Tap a stage for details</span></div></div>
<style>
.fl .nd{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:14px;border:1px solid var(--outline);background:var(--surface2);cursor:pointer}
.fl .nd.on{border-color:var(--accent);background:var(--accentSoft)}
.fl .bar{height:6px;border-radius:3px;background:var(--accent)}
.fl .ar{height:22px;text-align:center;color:var(--onSurface3);line-height:22px}
</style>`;
const nodes = root.querySelector<HTMLDivElement>('#nodes')!;
const detail = root.querySelector<HTMLDivElement>('[data-testid=detail]')!;
function draw(): void {
  nodes.innerHTML = stages.map(([k, n, ms], i) => (i ? '<div class="ar">↓</div>' : '') + `<div class="nd ${sel === k ? 'on' : ''}" data-testid="node-${k}" data-k="${k}"><span class="grow" style="font-weight:600">${n}</span><div style="width:80px"><div class="bar" style="width:${(ms / maxMs) * 100}%"></div></div><span class="num sub" style="width:56px;text-align:right">${ms ? ms + ' ms' : '—'}</span></div>`).join('');
}
nodes.addEventListener('click', (e) => {
  const el = (e.target as Element).closest<HTMLElement>('[data-k]');
  const s = el && stages.find((x) => x[0] === el.dataset.k);
  if (!s) return;
  sel = s[0];
  detail.innerHTML = `<div class="spread"><span class="title">${s[1]}</span><span class="badge">${Math.round((s[2] / total) * 100)}% of total</span></div><div class="sub">${s[3]} · ${s[2]} ms</div>`;
  draw();
});
draw();
