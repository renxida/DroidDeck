const EVENTS = [
  ["09:00", "09:30", "Standup", "Zoom"],
  ["10:00", "11:30", "Design review", "Room 4B"],
  ["12:30", "13:15", "Lunch with Sam", "Tartine"],
  ["14:00", "15:00", "1:1 with Priya", "Room 2A"],
  ["16:30", "17:00", "Dentist", "24th St Dental"],
  ["19:00", "21:00", "Climbing", "Mission Cliffs"]
];
const START = 8, END = 21, PX = 64, NOW = '13:20';
const toMin = (t) => { const [h, m] = t.split(':').map(Number); return h * 60 + m; };
const yOf = (t) => (toMin(t) - START * 60) / 60 * PX;
const pad = (h) => String(h).padStart(2, '0');

function App() {
  const [sel, setSel] = useState(null);
  const nowMin = toMin(NOW);
  const nextIdx = EVENTS.findIndex((e) => toMin(e[0]) > nowMin);
  const next = nextIdx >= 0 ? EVENTS[nextIdx] : null;
  const hours = [];
  for (let h = START; h <= END; h++) hours.push(h);
  const H = (END - START) * PX;
  const s = sel != null ? EVENTS[sel] : null;
  return html`<div class="stack">
    <div class="spread">
      <div>
        <div class="title">Today</div>
        <div class="sub">${EVENTS.length} events · now ${NOW}</div>
      </div>
    </div>
    <div class="card" data-testid="next">
      ${next
        ? html`<div class="muted">Next up · in ${toMin(next[0]) - nowMin} min</div>
            <div class="title">${next[2]}</div>
            <div class="sub num">${next[0]}–${next[1]} · ${next[3]}</div>`
        : html`<div class="sub">Nothing else today</div>`}
    </div>
    <div class="card" data-testid="detail" style=${{ background: s ? 'var(--accentSoft)' : 'var(--surface2)' }}>
      ${s
        ? html`<div class="title">${s[2]}</div><div class="sub num">${s[0]}–${s[1]} · ${s[3]}</div>`
        : html`<div class="sub">Tap an event for details</div>`}
    </div>
    <div style=${{ position: 'relative', height: H, margin: '8px 0' }}>
      ${hours.map((h) => html`<div key=${'h' + h}>
        <div class="muted num" style=${{ position: 'absolute', top: (h - START) * PX - 8, left: 0, width: 40, textAlign: 'right', lineHeight: '16px' }}>${pad(h)}:00</div>
        <div style=${{ position: 'absolute', top: (h - START) * PX, left: 48, right: 0, borderTop: '1px solid var(--outline)' }}></div>
      </div>`)}
      ${EVENTS.map((e, i) => {
        const top = yOf(e[0]);
        const height = yOf(e[1]) - top - 2;
        const on = sel === i;
        const past = toMin(e[1]) <= nowMin;
        return html`<div key=${'e' + i} role="button" tabindex="0" data-testid=${'event-' + i}
          onClick=${() => setSel(i)}
          onKeyDown=${(ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); setSel(i); } }}
          style=${{
            position: 'absolute', top: top + 1, height, left: 54, right: 0,
            background: on ? 'var(--accent)' : 'var(--accentSoft)',
            color: on ? 'var(--onAccent)' : 'var(--onSurface)',
            borderLeft: '3px solid var(--accent)', borderRadius: 8,
            padding: '3px 8px', boxSizing: 'border-box', overflow: 'hidden',
            cursor: 'pointer', opacity: past && !on ? 0.6 : 1, zIndex: 1, fontSize: 13, lineHeight: 1.25
          }}>
          <div style=${{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>${e[2]}${height < 40 ? html`<span style=${{ fontWeight: 400, opacity: 0.8 }}> · ${e[0]}</span>` : null}</div>
          ${height >= 40 ? html`<div style=${{ opacity: 0.8 }} class="num">${e[0]}–${e[1]} · ${e[3]}</div>` : null}
        </div>`;
      })}
      <div style=${{ position: 'absolute', top: yOf(NOW) - 1, left: 44, right: 0, height: 2, background: 'var(--danger)', zIndex: 2, pointerEvents: 'none' }}>
        <div style=${{ position: 'absolute', left: -4, top: -4, width: 10, height: 10, borderRadius: 5, background: 'var(--danger)' }}></div>
      </div>
    </div>
  </div>`;
}
return App;
