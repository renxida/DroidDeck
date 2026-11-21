const STAGES = [
  ["mic", "Mic", 0, "Speech captured at 16 kHz"],
  ["asr", "On-device ASR", 120, "Streaming speech-to-text on the phone"],
  ["claude", "Claude", 450, "Time to first token"],
  ["tts", "TTS", 180, "Text-to-speech, first audio chunk"],
  ["speaker", "Speaker", 40, "Audio output buffer"]
];
const TOTAL = STAGES.reduce((s, x) => s + x[2], 0);

function Arrow() {
  return html`<div style=${{ display: 'flex', justifyContent: 'center', height: 22 }}>
    <svg width="16" height="22" viewBox="0 0 16 22" aria-hidden="true">
      <path d="M8 1 V19 M3 14 L8 20 L13 14" fill="none" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style=${{ stroke: 'var(--onSurface3)' }}/>
    </svg>
  </div>`;
}

function App() {
  const [sel, setSel] = useState(null);
  const s = sel != null ? STAGES[sel] : null;
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Voice pipeline</div>
        <div class="sub">Mic to speaker latency</div>
      </div>
      <div style=${{ textAlign: 'right' }}>
        <div class="big num" data-testid="total">${TOTAL} ms</div>
        <div class="muted">end-to-end</div>
      </div>
    </div>
    <div style=${{ display: 'flex', height: 8, borderRadius: 4, overflow: 'hidden', background: 'var(--outline)' }}>
      ${STAGES.filter((x) => x[2] > 0).map((x) => {
        const i = STAGES.indexOf(x);
        return html`<div key=${x[0]} style=${{ width: (x[2] / TOTAL * 100) + '%', background: G.color(i), opacity: sel == null || sel === i ? 1 : 0.35 }}></div>`;
      })}
    </div>
    <div>
      ${STAGES.map((x, i) => {
        const on = sel === i;
        return html`<div key=${x[0]}>
          ${i > 0 ? html`<${Arrow}/>` : null}
          <button data-testid=${'node-' + x[0]} onClick=${() => setSel(i)} aria-pressed=${on ? 'true' : 'false'}
            style=${{
              display: 'flex', alignItems: 'center', gap: 10, width: '100%', minHeight: 48,
              padding: '10px 14px', borderRadius: 12, cursor: 'pointer', textAlign: 'left', font: 'inherit',
              border: '1px solid ' + (on ? 'var(--accent)' : 'var(--outline)'),
              background: on ? 'var(--accentSoft)' : 'var(--surface2)', color: 'var(--onSurface)'
            }}>
            <span class="dot" style=${{ background: G.color(i) }}></span>
            <span class="grow" style=${{ fontWeight: 600 }}>${x[1]}</span>
            <span class="badge num">${x[2]} ms</span>
          </button>
        </div>`;
      })}
    </div>
    <div class="card" data-testid="detail">
      ${s
        ? html`<div class="spread"><span class="title">${s[1]}</span><span class="title num">${Math.round(s[2] / TOTAL * 100)}%</span></div>
            <div class="sub">${s[3]} · ${s[2]} ms, ${Math.round(s[2] / TOTAL * 100)}% of total</div>`
        : html`<div class="sub">Tap a stage to see what it does and its share of latency</div>`}
    </div>
  </div>`;
}
return App;
