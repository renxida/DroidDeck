const stages = [["mic","Mic",0,"Speech captured at 16 kHz"],["asr","On-device ASR",120,"Streaming speech-to-text on the phone"],["claude","Claude",450,"Time to first token"],["tts","TTS",180,"Text-to-speech, first audio chunk"],["speaker","Speaker",40,"Audio output buffer"]];
const total = stages.reduce((a, s) => a + s[2], 0), maxMs = Math.max(...stages.map(s => s[2]));
function App() {
  const [sel, setSel] = useState(null);
  const s = stages.find(x => x[0] === sel);
  return html`<div class="stack">
    <div class="spread"><div><div class="title">Voice pipeline</div><div class="sub">Speech in to audio out</div></div><div style="text-align:right"><div class="big" data-testid="total">${total} ms</div><div class="muted">end to end</div></div></div>
    <div>${stages.map(([k, n, ms], i) => html`
      ${i > 0 && html`<div style="height:22px;line-height:22px;text-align:center;color:var(--onSurface3)">↓</div>`}
      <div data-testid=${'node-' + k} onClick=${() => setSel(k)} class="row" style=${{ padding: '10px 12px', borderRadius: '14px', cursor: 'pointer', gap: '10px', border: '1px solid ' + (sel === k ? 'var(--accent)' : 'var(--outline)'), background: sel === k ? 'var(--accentSoft)' : 'var(--surface2)' }}>
        <span class="grow" style="font-weight:600">${n}</span>
        <div style="width:80px"><div style=${{ height: '6px', borderRadius: '3px', background: 'var(--accent)', width: ms / maxMs * 100 + '%' }}></div></div>
        <span class="num sub" style="width:56px;text-align:right">${ms ? ms + ' ms' : '—'}</span>
      </div>`)}</div>
    <div class="card" data-testid="detail">${s
      ? html`<div class="spread"><span class="title">${s[1]}</span><span class="badge">${Math.round(s[2] / total * 100)}% of total</span></div><div class="sub">${s[3]} · ${s[2]} ms</div>`
      : html`<span class="sub">Tap a stage for details</span>`}</div>
  </div>`;
}
return App;
