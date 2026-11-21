const events: [string, string, string, string][] = [
  ['09:00', '09:30', 'Standup', 'Zoom'],
  ['10:00', '11:30', 'Design review', 'Room 4B'],
  ['12:30', '13:15', 'Lunch with Sam', 'Tartine'],
  ['14:00', '15:00', '1:1 with Priya', 'Room 2A'],
  ['16:30', '17:00', 'Dentist', '24th St Dental'],
  ['19:00', '21:00', 'Climbing', 'Mission Cliffs'],
];
const START_H = 8;
const END_H = 21;
const PX_PER_H = 60;
const NOW = '13:20';

const toMin = (t: string): number => {
  const [h, m] = t.split(':').map(Number);
  return (h ?? 0) * 60 + (m ?? 0);
};
const yOf = (t: string): number => (toMin(t) - START_H * 60) * PX_PER_H / 60;
const pad = (n: number): string => (n < 10 ? '0' : '') + n;

const nowMin = toMin(NOW);
const nextIdx = events.findIndex((e) => toMin(e[0]) > nowMin);
const next = nextIdx >= 0 ? events[nextIdx] : undefined;
const nextLine = next
  ? `Next: <b>${G.esc(next[2])}</b> at ${next[0]} · ${G.esc(next[3])}`
  : 'No more events today';

let hours = '';
for (let h = START_H; h <= END_H; h++) {
  const y = (h - START_H) * PX_PER_H;
  hours += `<div class="hl" style="top:${y}px"><span class="muted num">${pad(h)}:00</span></div>`;
}

const blocks = events.map((e, i) => {
  const top = yOf(e[0]);
  const h = Math.max(32, yOf(e[1]) - top - 2);
  const past = toMin(e[1]) <= nowMin;
  return `<button class="ev${past ? ' past' : ''}${i === nextIdx ? ' nx' : ''}" data-ev="${i}" data-testid="event-${i}"
    style="top:${top + 1}px;height:${h}px">
    <span class="et">${G.esc(e[2])}</span><span class="ed">${e[0]}–${e[1]}</span>
  </button>`;
}).join('');

const totalH = (END_H - START_H) * PX_PER_H;
const nowY = yOf(NOW);

root.innerHTML = `
<style>
.cal .tl { position: relative; height: ${totalH}px; margin: 8px 0 12px; }
.cal .hl { position: absolute; left: 0; right: 0; height: 0; border-top: 1px solid var(--outline); }
.cal .hl span { position: absolute; left: 0; top: -8px; background: var(--bg); padding-right: 6px; font-size: 11px; }
.cal .ev { position: absolute; left: 52px; right: 0; display: flex; flex-direction: column; align-items: flex-start;
  justify-content: flex-start; gap: 1px; overflow: hidden; padding: 4px 8px; border: 0; border-left: 3px solid var(--accent);
  border-radius: 8px; background: var(--accentSoft); color: var(--onSurface); font: inherit; text-align: left; cursor: pointer; }
.cal .ev.past { opacity: 0.55; }
.cal .ev.sel { outline: 2px solid var(--accent); }
.cal .ev .et { font-size: 13px; font-weight: 600; line-height: 1.2; }
.cal .ev .ed { font-size: 11px; color: var(--onSurface2); line-height: 1.2; }
.cal .now { position: absolute; left: 44px; right: 0; height: 0; border-top: 2px solid var(--danger); z-index: 2; pointer-events: none; }
.cal .now::before { content: ''; position: absolute; left: -5px; top: -6px; width: 10px; height: 10px; border-radius: 50%; background: var(--danger); }
.cal .nowl { position: absolute; left: 0; top: -9px; font-size: 10px; color: var(--danger); font-weight: 600; }
.cal .det { min-height: 20px; }
</style>
<div class="stack cal">
  <div>
    <div class="title">Today</div>
    <div class="sub">${nextLine}</div>
  </div>
  <div class="card sub det" data-testid="detail">Tap an event for details</div>
  <div class="tl" id="tl">
    ${hours}
    ${blocks}
    <div class="now" style="top:${nowY}px"></div>
    <div class="nowl num" style="position:absolute;top:${nowY - 7}px;left:0">${NOW}</div>
  </div>
</div>`;

const tl = root.querySelector<HTMLDivElement>('#tl')!;
const detail = root.querySelector<HTMLDivElement>('[data-testid=detail]')!;

tl.onclick = (e: MouseEvent): void => {
  const b = (e.target as Element).closest('[data-ev]');
  if (!b) return;
  const i = Number(b.getAttribute('data-ev'));
  const ev = events[i];
  if (!ev) return;
  tl.querySelectorAll('.ev.sel').forEach((x) => x.classList.remove('sel'));
  b.classList.add('sel');
  detail.classList.remove('sub');
  detail.innerHTML = `<div class="title">${G.esc(ev[2])}</div><div class="sub">${ev[0]}–${ev[1]} · ${G.esc(ev[3])}</div>`;
};
