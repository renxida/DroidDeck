const BPM = [58, 56, 55, 54, 55, 57, 63, 78, 92, 85, 76, 74, 81, 79, 73, 72, 88, 124, 131, 96, 82, 74, 66, 61];

function App() {
  const [showAvg, setShowAvg] = useState(false);
  const min = Math.min(...BPM);
  const max = Math.max(...BPM);
  const avg = Math.round(BPM.reduce((s, v) => s + v, 0) / BPM.length);
  const data = BPM.map((value, i) => ({ label: String(i).padStart(2, '0') + ':00', value }));
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Heart rate</div>
        <div class="sub">Today, hourly</div>
      </div>
      <div class="row" style=${{ gap: 16 }}>
        <div style=${{ textAlign: 'right' }}>
          <div class="title num" data-testid="resting">${min} bpm</div>
          <div class="muted">resting</div>
        </div>
        <div style=${{ textAlign: 'right' }}>
          <div class="title num" data-testid="peak" style=${{ color: 'var(--rose)' }}>${max} bpm</div>
          <div class="muted">peak</div>
        </div>
      </div>
    </div>
    <${G.Line} data=${data} height=${180} unit="" annotate="minmax" avg=${showAvg} color="var(--rose)" every=${6}/>
    <div class="row" style=${{ alignItems: 'center' }}>
      <button class=${showAvg ? 'chip on' : 'chip'} data-testid="toggle-avg" aria-pressed=${showAvg ? 'true' : 'false'}
        style=${{ minHeight: 32 }} onClick=${() => setShowAvg((v) => !v)}>${showAvg ? 'Hide average' : 'Show average'}</button>
      ${showAvg ? html`<span class="sub num" data-testid="avg">avg ${avg} bpm</span>` : null}
    </div>
  </div>`;
}
return App;
