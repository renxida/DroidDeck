const bpm: number[] = [58, 56, 55, 54, 55, 57, 63, 78, 92, 85, 76, 74, 81, 79, 73, 72, 88, 124, 131, 96, 82, 74, 66, 61];
const points: { label: string; value: number }[] = bpm.map((value, h) => ({ label: `${h < 10 ? '0' : ''}${h}:00`, value }));
const minV: number = Math.min(...bpm);
const maxV: number = Math.max(...bpm);
const avgV: number = Math.round(bpm.reduce((s, v) => s + v, 0) / bpm.length);
let showAvg: boolean = false;

root.innerHTML = `
<style>
.hr .stats { gap: 16px; }
.hr .st .big { line-height: 1.1; }
.hr .chip { min-height: 32px; cursor: pointer; }
</style>
<div class="stack hr">
  <div class="title">Heart rate today</div>
  <div class="row stats">
    <div class="st"><div class="big num">${minV}</div><div class="muted">resting bpm</div></div>
    <div class="st"><div class="big num" style="color:var(--rose)">${maxV}</div><div class="muted">peak bpm</div></div>
  </div>
  <div id="chart"></div>
  <div class="spread">
    <button class="chip" data-testid="toggle-avg" aria-pressed="false">Show average</button>
    <span class="sub num" data-testid="avg" hidden>avg ${avgV} bpm</span>
  </div>
</div>`;

const chart = root.querySelector<HTMLDivElement>('#chart')!;
const chip = root.querySelector<HTMLButtonElement>('[data-testid=toggle-avg]')!;
const avgEl = root.querySelector<HTMLSpanElement>('[data-testid=avg]')!;

const draw = (): void => {
  chart.innerHTML = G.line(points, { annotate: 'minmax', avg: showAvg, unit: ' bpm', every: 6, color: 'var(--rose)' });
  chip.classList.toggle('on', showAvg);
  chip.setAttribute('aria-pressed', String(showAvg));
  chip.textContent = showAvg ? 'Hide average' : 'Show average';
  avgEl.hidden = !showAvg;
};

chip.onclick = (): void => { showAvg = !showAvg; draw(); };

draw();
