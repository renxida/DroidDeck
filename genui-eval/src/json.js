/* json runtime: payload = JSON {"state":{...},"view":node}. A stand-in for a native (Compose) schema renderer. */
(function () {
  'use strict';
  const C = GCore;
  const H = {
    fmt: C.fmt, Math, round: (n, d = 0) => Math.round(n * 10 ** d) / 10 ** d, min: Math.min, max: Math.max, abs: Math.abs, floor: Math.floor,
    sum: (a, f) => a.reduce((s, x, i) => s + (+(f ? f(x, i) : x) || 0), 0), count: (a, f) => a.filter(f || Boolean).length, len: (a) => a.length,
  };
  const cache = new Map();
  let state = {}, ups = [];
  const compile = (code) => { let f = cache.get(code); if (!f) { f = new Function('$h', '$s', '$l', 'with($h) with($s) with($l) { return (' + code + '); }'); cache.set(code, f); } return f; };
  const evalExpr = (code, l) => compile(code)(H, state, l || {});
  // value: "=expr" -> expression; "a {{expr}} b" -> interpolated string; anything else literal (arrays/objects walked).
  function val(v, l) {
    if (typeof v === 'string') {
      if (v[0] === '=') return evalExpr(v.slice(1), l);
      if (v.includes('{{')) return v.replace(/\{\{(.+?)\}\}/g, (_, e) => { const r = evalExpr(e, l); return r == null ? '' : r; });
      return v;
    }
    if (Array.isArray(v)) return v.map((x) => val(x, l));
    if (v && typeof v === 'object') { const o = {}; for (const k in v) o[k] = k === 'onSelect' ? v[k] : val(v[k], l); return o; }
    return v;
  }
  const isDyn = (v) => typeof v === 'string' ? v[0] === '=' || v.includes('{{') : Array.isArray(v) ? v.some(isDyn) : v && typeof v === 'object' ? Object.values(v).some(isDyn) : false;
  const pathOf = (p, l) => String(p).split('.').map((k) => (k === '$index' ? l.$index : k[0] === '$' ? val('=' + k, l) : k));
  function setPath(p, v, l) { const ks = pathOf(p, l); let o = state; for (let i = 0; i < ks.length - 1; i++) o = o[ks[i]]; o[ks[ks.length - 1]] = v; }
  const getPath = (p, l) => pathOf(p, l).reduce((o, k) => (o == null ? o : o[k]), state);
  function run(action, l) {
    if (!action) return;
    if (Array.isArray(action)) { action.forEach((a) => run(a, l)); return; }
    if (action.set) for (const k in action.set) setPath(k, val(action.set[k], l), l);
    if (action.toggle) setPath(action.toggle, !getPath(action.toggle, l), l);
    if (!action._noUpdate) update();
  }
  const update = () => ups.forEach((u) => u());
  function el(tag, cls, kids) { const e = document.createElement(tag); if (cls) e.className = cls; (kids || []).forEach((k) => k && e.appendChild(k)); return e; }
  // bind a dynamic property: f(value) is called now and on every update
  function dyn(u, v, l, f) { f(val(v, l)); if (isDyn(v)) u.push(() => f(val(v, l))); }
  const gapStyle = (e, g) => { e.style.gap = (g == null ? 12 : g) + 'px'; };

  function node(n, l, u) {
    if (n == null) return null;
    const t = n.type, kids = () => (n.children || []).map((c) => node(c, l, u));
    let e;
    switch (t) {
      case 'column': case 'stack': e = el('div', 'stack', kids()); gapStyle(e, n.gap); break;
      case 'card': e = el('div', 'card stack', kids()); gapStyle(e, n.gap); break;
      case 'row': e = el('div', n.justify === 'between' ? 'spread' : 'row', kids()); gapStyle(e, n.gap == null ? 8 : n.gap); if (n.wrap) e.style.flexWrap = 'wrap'; if (n.align) e.style.alignItems = n.align; break;
      case 'grid': e = el('div', '', kids()); e.style.display = 'grid'; e.style.gridTemplateColumns = `repeat(${n.columns || 2},minmax(0,1fr))`; gapStyle(e, n.gap == null ? 8 : n.gap); break;
      case 'text': {
        e = el('div', { title: 'title', sub: 'sub', muted: 'muted', big: 'big', label: 'sub' }[n.style] || '');
        if (n.style === 'label') { e.style.fontWeight = '600'; e.style.color = 'var(--onSurface)'; }
        if (n.color) e.style.color = `var(--${n.color})`;
        if (n.align) e.style.textAlign = n.align;
        if (n.weight) e.style.fontWeight = n.weight;
        dyn(u, n.value, l, (v) => { e.textContent = v == null ? '' : v; });
        if (n.color && isDyn(n.color)) dyn(u, n.color, l, (v) => { e.style.color = `var(--${v})`; });
        break;
      }
      case 'badge': e = el('span', 'badge'); dyn(u, n.value, l, (v) => { e.textContent = v; }); break;
      case 'chart': {
        e = el('div', 'chart');
        const fn = { bar: C.bar, line: C.line, donut: C.donut, spark: C.spark }[n.kind];
        const opts = Object.assign({}, n); delete opts.type; delete opts.kind; delete opts.data; delete opts.id;
        dyn(u, [n.data, opts], l, ([d, o]) => { e.innerHTML = fn(d, o); });
        if (n.kind === 'donut') e.style.display = 'flex', e.style.justifyContent = 'center';
        if (n.onSelect) e.addEventListener('click', (ev) => { const h = ev.target.closest('[data-bar],[data-slice]'); if (h) run(n.onSelect, Object.assign({}, l, { $index: +(h.dataset.bar || h.dataset.slice) })); });
        break;
      }
      case 'button': {
        e = el('button', 'btn' + (n.variant === 'ghost' ? ' ghost' : n.variant === 'small' ? ' small' : n.variant === 'small-ghost' ? ' small ghost' : ''));
        dyn(u, n.label, l, (v) => { e.textContent = v; });
        if (n.disabled != null) dyn(u, n.disabled, l, (v) => { e.disabled = !!v; });
        if (n.full) e.style.width = '100%';
        e.onclick = () => run(n.action, l);
        break;
      }
      case 'chip': e = el('button', 'chip'); dyn(u, n.label, l, (v) => { e.textContent = v; }); if (n.on != null) dyn(u, n.on, l, (v) => e.classList.toggle('on', !!v)); e.onclick = () => run(n.action, l); break;
      case 'toggle': case 'check': {
        const inp = el('input', t === 'toggle' ? 'toggle' : 'check'); inp.type = 'checkbox';
        u.push(() => { inp.checked = !!getPath(n.bind, l); }); inp.checked = !!getPath(n.bind, l);
        inp.onchange = () => { setPath(n.bind, inp.checked, l); run(n.action, l); if (!n.action) update(); };
        if (n.label != null) { e = el('label', 'row'); const s = el('span', 'grow'); dyn(u, n.label, l, (v) => { s.textContent = v; }); if (t === 'check') e.append(inp, s); else e.append(s, inp); inp.dataset.testid = n.id ? n.id + '-input' : ''; } else e = inp;
        break;
      }
      case 'slider': {
        e = el('input'); e.type = 'range'; e.min = n.min ?? 0; e.max = n.max ?? 100; e.step = n.step ?? 1;
        e.value = getPath(n.bind, l); u.push(() => { if (document.activeElement !== e) e.value = getPath(n.bind, l); });
        e.oninput = () => { setPath(n.bind, +e.value, l); update(); };
        break;
      }
      case 'stepper': {
        const minus = el('button', 'btn small ghost'), plus = el('button', 'btn small ghost'), v = el('span', 'num');
        minus.textContent = '−'; plus.textContent = '+'; v.style.minWidth = '32px'; v.style.textAlign = 'center'; v.style.fontWeight = '600';
        if (n.id) { minus.dataset.testid = n.id + '-minus'; plus.dataset.testid = n.id + '-plus'; }
        const step = (d) => { const x = Math.min(n.max ?? Infinity, Math.max(n.min ?? -Infinity, (+getPath(n.bind, l) || 0) + d * (n.step || 1))); setPath(n.bind, x, l); update(); };
        minus.onclick = () => step(-1); plus.onclick = () => step(1);
        u.push(() => { v.textContent = getPath(n.bind, l); }); v.textContent = getPath(n.bind, l);
        e = el('div', 'row', [minus, v, plus]);
        break;
      }
      case 'input': {
        e = el('input'); e.type = n.inputType || 'text'; if (n.placeholder) e.placeholder = n.placeholder; if (e.type === 'number') e.inputMode = 'decimal';
        e.value = getPath(n.bind, l) ?? ''; u.push(() => { if (document.activeElement !== e) e.value = getPath(n.bind, l) ?? ''; });
        e.oninput = () => { setPath(n.bind, e.type === 'number' ? (e.value === '' ? '' : +e.value) : e.value, l); update(); };
        break;
      }
      case 'select': {
        e = el('select');
        (n.options || []).forEach((o) => { const op = el('option'); op.value = o.value ?? o; op.textContent = o.label ?? o; e.appendChild(op); });
        e.value = getPath(n.bind, l); u.push(() => { e.value = getPath(n.bind, l); });
        e.onchange = () => { setPath(n.bind, e.value, l); update(); };
        break;
      }
      case 'progress': { const i = el('i'); e = el('div', 'progress', [i]); dyn(u, n.value, l, (v) => { i.style.width = Math.max(0, Math.min(1, +v || 0)) * 100 + '%'; }); if (n.color) i.style.background = `var(--${n.color})`; break; }
      case 'divider': e = el('div', 'divider'); break;
      case 'spacer': e = el('div'); e.style.height = (n.size || 8) + 'px'; break;
      case 'list': {
        e = el('div', n.divided ? 'list' : 'stack'); if (!n.divided) gapStyle(e, n.gap == null ? 8 : n.gap);
        let inner = [], prev = null;
        const sync = () => {
          const items = val(n.items, l) || [];
          const same = prev && prev.length === items.length && items.every((x, i) => x === prev[i]);
          if (same) { inner.forEach((f) => f()); return; }
          inner = []; prev = items.slice();
          e.replaceChildren(...items.map((it, i) => node(n.template, Object.assign({}, l, { $item: it, $index: i }), inner)));
        };
        sync(); u.push(sync);
        break;
      }
      case 'timer': {
        e = el('span'); e.hidden = true; let id = null;
        const sync = (on) => { if (on && id == null) id = setInterval(() => run(n.do, l), n.every || 1000); else if (!on && id != null) { clearInterval(id); id = null; } };
        dyn(u, n.running, l, sync);
        break;
      }
      default: throw new Error('unknown node type: ' + t);
    }
    if (n.id) { const tid = String(val(n.id, l)); e.dataset.testid = tid; }
    if (n.style && typeof n.style === 'object') Object.assign(e.style, n.style);
    if (n.visible != null) dyn(u, n.visible, l, (v) => { e.style.display = v ? '' : 'none'; });
    if (n.onTap) { e.style.cursor = 'pointer'; e.addEventListener('click', () => run(n.onTap, l)); }
    return e;
  }

  window.G = C;
  window.__renderImpl = function (src, root) {
    const spec = typeof src === 'string' ? JSON.parse(src) : src;
    state = JSON.parse(JSON.stringify(spec.state || {}));
    ups = [];
    root.replaceChildren(node(spec.view, {}, ups));
  };
})();
