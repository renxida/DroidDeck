const evs = [["09:00","09:30","Standup","Zoom"],["10:00","11:30","Design review","Room 4B"],["12:30","13:15","Lunch with Sam","Tartine"],["14:00","15:00","1:1 with Priya","Room 2A"],["16:30","17:00","Dentist","24th St Dental"],["19:00","21:00","Climbing","Mission Cliffs"]];
const HOUR = 44, START = 8, END = 21, NOW = '13:20';
const mins = t => { const [h, m] = t.split(':').map(Number); return h * 60 + m; };
const y = t => (mins(t) - START * 60) / 60 * HOUR;
const css = `.cal .tl{position:relative;height:${(END - START) * HOUR}px;margin-left:44px}
.cal .hr{position:absolute;left:-44px;right:0;border-top:1px solid var(--outline)}
.cal .hr span{position:absolute;left:0;top:-8px;font-size:11px;color:var(--onSurface3);background:var(--surface);padding-right:4px}
.cal .ev{position:absolute;left:4px;right:0;border-radius:10px;background:var(--accentSoft);border-left:3px solid var(--accent);padding:4px 8px;overflow:hidden;cursor:pointer;font-size:13px}
.cal .ev b{display:block;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.cal .ev small{color:var(--onSurface2)}
.cal .now{position:absolute;left:-6px;right:0;border-top:2px solid var(--danger)}`;
function App() {
  const [sel, setSel] = useState(null);
  const next = evs.find(e => mins(e[0]) > mins(NOW));
  const hours = []; for (let h = START; h <= END; h++) hours.push(h);
  const s = sel != null ? evs[sel] : null;
  return html`<div class="cal stack"><style>${css}</style>
    <div><div class="title">Today</div><div class="sub">${next ? `Next: ${next[2]} at ${next[0]}` : 'Nothing else today'}</div></div>
    <div class="tl">
      ${hours.map(h => html`<div class="hr" style=${{ top: (h - START) * HOUR + 'px' }}><span>${String(h).padStart(2, '0')}:00</span></div>`)}
      ${evs.map(([st, en, t], i) => { const hgt = y(en) - y(st); return html`<div class="ev" data-testid=${'event-' + i} onClick=${() => setSel(i)} style=${{ top: y(st) + 1 + 'px', height: hgt - 2 + 'px', opacity: mins(en) <= mins(NOW) ? 0.6 : 1 }}><b>${t}</b>${hgt >= 40 && html`<small>${st}–${en}</small>`}</div>`; })}
      <div class="now" style=${{ top: y(NOW) + 'px' }}></div>
    </div>
    <div class="card" data-testid="detail">${s ? html`<div class="title">${s[2]}</div><div class="sub">${s[0]}–${s[1]} · ${s[3]}</div>` : html`<span class="sub">Tap an event for details</span>`}</div>
  </div>`;
}
return App;
