type Ev = [start: string, end: string, title: string, loc: string];
const evs: Ev[] = [["09:00", "09:30", "Standup", "Zoom"], ["10:00", "11:30", "Design review", "Room 4B"], ["12:30", "13:15", "Lunch with Sam", "Tartine"], ["14:00", "15:00", "1:1 with Priya", "Room 2A"], ["16:30", "17:00", "Dentist", "24th St Dental"], ["19:00", "21:00", "Climbing", "Mission Cliffs"]];
const HOUR = 44, START = 8, END = 21, NOW = '13:20';
const mins = (t: string): number => { const [h, m] = t.split(':').map(Number); return h * 60 + m; };
const y = (t: string): number => ((mins(t) - START * 60) / 60) * HOUR;
const next = evs.find((e) => mins(e[0]) > mins(NOW));
let lines = '';
for (let h = START; h <= END; h++) lines += `<div class="hr" style="top:${(h - START) * HOUR}px"><span>${String(h).padStart(2, '0')}:00</span></div>`;
const blocks = evs.map(([s, e, t], i) => { const h = y(e) - y(s); return `<div class="ev ${mins(e) <= mins(NOW) ? 'past' : ''}" data-testid="event-${i}" data-i="${i}" style="top:${y(s) + 1}px;height:${h - 2}px"><b>${t}</b>${h >= 40 ? `<small>${s}–${e}</small>` : ''}</div>`; }).join('');
root.innerHTML = `<div class="cal stack">
  <div><div class="title">Today</div><div class="sub">${next ? `Next: ${next[2]} at ${next[0]}` : 'Nothing else today'}</div></div>
  <div class="tl" id="tl">${lines}${blocks}<div class="now" style="top:${y(NOW)}px"></div></div>
  <div class="card" data-testid="detail"><span class="sub">Tap an event for details</span></div></div>
<style>
.cal .tl{position:relative;height:${(END - START) * HOUR}px;margin-left:44px}
.cal .hr{position:absolute;left:-44px;right:0;border-top:1px solid var(--outline)}
.cal .hr span{position:absolute;left:0;top:-8px;font-size:11px;color:var(--onSurface3);background:var(--surface);padding-right:4px}
.cal .ev{position:absolute;left:4px;right:0;border-radius:10px;background:var(--accentSoft);border-left:3px solid var(--accent);padding:4px 8px;overflow:hidden;cursor:pointer;font-size:13px}
.cal .ev b{display:block;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cal .ev small{color:var(--onSurface2)}
.cal .ev.past{opacity:.6}
.cal .now{position:absolute;left:-6px;right:0;border-top:2px solid var(--danger)}
</style>`;
root.querySelector<HTMLDivElement>('#tl')!.addEventListener('click', (e) => {
  const el = (e.target as Element).closest<HTMLElement>('[data-i]');
  if (!el) return;
  const [s, en, t, l] = evs[Number(el.dataset.i)];
  root.querySelector<HTMLDivElement>('[data-testid=detail]')!.innerHTML = `<div class="title">${t}</div><div class="sub">${s}–${en} · ${l}</div>`;
});
