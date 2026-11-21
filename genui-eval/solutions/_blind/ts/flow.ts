const stages: [string, string, number, string][] = [
  ['mic', 'Mic', 0, 'Speech captured at 16 kHz'],
  ['asr', 'On-device ASR', 120, 'Streaming speech-to-text on the phone'],
  ['claude', 'Claude', 450, 'Time to first token'],
  ['tts', 'TTS', 180, 'Text-to-speech, first audio chunk'],
  ['speaker', 'Speaker', 40, 'Audio output buffer'],
];
const total: number = stages.reduce((s, st) => s + st[2], 0);

const arrow = `<div class="arr" aria-hidden="true"><svg width="20" height="22" viewBox="0 0 20 22">
  <line x1="10" y1="1" x2="10" y2="18" class="ln"/><polyline points="4,13 10,20 16,13" class="ln" fill="none"/>
</svg></div>`;

const nodes = stages.map((st, i) => {
  const [key, name, ms] = st;
  const share = total > 0 ? ms / total : 0;
  const node = `<button class="nd" data-key="${key}" data-testid="node-${key}">
    <span class="nm">${G.esc(name)}</span>
    <span class="badge num">${ms} ms</span>
    <span class="bar"><i style="width:${(share * 100).toFixed(1)}%"></i></span>
  </button>`;
  return i < stages.length - 1 ? node + arrow : node;
}).join('');

root.innerHTML = `
<style>
.flw .nd { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 6px 8px; width: 100%;
  min-height: 48px; padding: 10px 12px; border-radius: 14px; border: 1px solid var(--outline);
  background: var(--surface2); color: var(--onSurface); font: inherit; text-align: left; cursor: pointer; }
.flw .nd.sel { border-color: var(--accent); background: var(--accentSoft); }
.flw .nd .nm { font-weight: 600; }
.flw .nd .bar { grid-column: 1 / -1; height: 4px; border-radius: 2px; background: var(--outline); overflow: hidden; }
.flw .nd .bar i { display: block; height: 100%; background: var(--accent); }
.flw .arr { display: flex; justify-content: center; height: 22px; }
.flw .arr .ln { stroke: var(--onSurface3); stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }
.flw .pipe { display: flex; flex-direction: column; gap: 4px; }
.flw .det { min-height: 20px; }
.flw .tot { text-align: right; }
</style>
<div class="stack flw">
  <div class="spread">
    <div>
      <div class="title">Voice pipeline</div>
      <div class="sub">Latency per stage</div>
    </div>
    <div class="tot">
      <div class="big num" data-testid="total">${total} ms</div>
      <div class="muted">end to end</div>
    </div>
  </div>
  <div class="pipe" id="pipe">${nodes}</div>
  <div class="card sub det" data-testid="detail">Tap a stage for details</div>
</div>`;

const pipe = root.querySelector<HTMLDivElement>('#pipe')!;
const detail = root.querySelector<HTMLDivElement>('[data-testid=detail]')!;

pipe.onclick = (e: MouseEvent): void => {
  const b = (e.target as Element).closest('[data-key]');
  if (!b) return;
  const st = stages.find((s) => s[0] === b.getAttribute('data-key'));
  if (!st) return;
  pipe.querySelectorAll('.nd.sel').forEach((x) => x.classList.remove('sel'));
  b.classList.add('sel');
  const pct = total > 0 ? Math.round(st[2] / total * 100) : 0;
  detail.classList.remove('sub');
  detail.innerHTML = `<div class="title">${G.esc(st[1])}</div><div class="sub">${G.esc(st[3])} · ${st[2]} ms · ${pct}% of total</div>`;
};
