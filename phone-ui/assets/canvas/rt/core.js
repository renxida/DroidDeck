/* GenUI core: formatting + SVG chart helpers shared by html, ts and preact runtimes. Global: GCore */
(function () {
  'use strict';
  const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const PAL = ['var(--accent)', 'var(--amber)', 'var(--success)', 'var(--rose)', 'var(--onSurface2)', 'var(--danger)', 'var(--onSurface3)'];
  const color = (i) => PAL[i % PAL.length];
  const r1 = (n) => Math.round(n * 10) / 10;
  const NF = {}; // Intl.NumberFormat construction is slow (~ms on a phone): cache per precision
  const nf = (d) => NF[d] || (NF[d] = new Intl.NumberFormat('en-US', { minimumFractionDigits: d, maximumFractionDigits: d }));
  const fmt = {
    money: (n, cur = '$') => (n < 0 ? '-' : '') + cur + nf(2).format(Math.abs(n)),
    num: (n, d = 0) => nf(d).format(Number(n)),
    pct: (n, d = 0) => (n * 100).toFixed(d) + '%',
    dur: (sec) => { sec = Math.max(0, Math.round(sec)); const m = Math.floor(sec / 60), s = sec % 60; return m + ':' + String(s).padStart(2, '0'); },
  };
  const niceMax = (v) => { if (v <= 0) return 1; const p = Math.pow(10, Math.floor(Math.log10(v))); const n = v / p; return (n <= 1 ? 1 : n <= 2 ? 2 : n <= 2.5 ? 2.5 : n <= 5 ? 5 : 10) * p; };
  const HALO = 'stroke="var(--surface)" stroke-width="3" paint-order="stroke"';
  const T = (x, y, s, o = '') => `<text x="${r1(x)}" y="${r1(y)}" ${o}>${esc(s)}</text>`;
  const svgOpen = (W, H, label) => `<svg viewBox="0 0 ${W} ${H}" width="100%" role="img" aria-label="${esc(label || 'chart')}" style="font:11px system-ui,sans-serif;overflow:visible">`;

  // bar(data:[{label,value}], {target, unit, height, color, selected, values})
  function bar(data, o = {}) {
    const W = o.width || 355, H = o.height || 170, L = 30, B = 22, Tp = 16, R = 6;
    const vals = data.map((d) => +d.value);
    const max = niceMax(Math.max(...vals, o.target || 0) * 1.05);
    const y = (v) => Tp + (H - Tp - B) * (1 - v / max);
    const bw = (W - L - R) / data.length;
    let s = svgOpen(W, H, o.label);
    for (let k = 0; k <= 4; k++) { const v = (max * k) / 4, yy = y(v); s += `<line x1="${L}" x2="${W - R}" y1="${r1(yy)}" y2="${r1(yy)}" stroke="var(--outline)" stroke-width="1"/>` + T(L - 6, yy + 4, fmt.num(v, max < 5 ? 1 : 0), 'text-anchor="end" fill="var(--onSurface3)"'); }
    data.forEach((d, i) => {
      const x = L + i * bw + bw * 0.18, w = bw * 0.64, yy = y(d.value), h = Math.max(1, y(0) - yy);
      const dim = o.selected != null && o.selected !== i;
      const fill = d.color || o.color || 'var(--accent)';
      s += `<g data-bar="${i}" style="cursor:pointer"><rect x="${r1(L + i * bw)}" y="${Tp}" width="${r1(bw)}" height="${H - Tp - B}" fill="transparent"/>`;
      s += `<rect x="${r1(x)}" y="${r1(yy)}" width="${r1(w)}" height="${r1(h)}" rx="4" fill="${fill}" opacity="${dim ? 0.35 : 1}"/>`;
      if (o.values) s += T(x + w / 2, yy - 4, fmt.num(d.value, 1), 'text-anchor="middle" fill="var(--onSurface2)"');
      s += T(x + w / 2, H - 6, d.label, `text-anchor="middle" fill="${o.selected === i ? 'var(--onSurface)' : 'var(--onSurface3)'}"`) + '</g>';
    });
    if (o.target != null) { const ty = y(o.target); s += `<line x1="${L}" x2="${W - R}" y1="${r1(ty)}" y2="${r1(ty)}" stroke="var(--onSurface2)" stroke-width="1.5" stroke-dasharray="4 4"/>` + T(W - R, ty - 5, o.target + (o.unit || '') + ' target', 'text-anchor="end" fill="var(--onSurface2)" font-weight="600" ' + HALO); }
    return s + '</svg>';
  }

  // line(points:[{label,value}] | number[], {height, unit, annotate:'minmax', avg:boolean, color, every})
  function line(points, o = {}) {
    const pts = points.map((p, i) => (typeof p === 'number' ? { label: String(i), value: p } : p));
    const W = o.width || 355, H = o.height || 180, L = 30, B = 22, Tp = 20, R = 10;
    const vals = pts.map((p) => +p.value), mn = Math.min(...vals), mx = Math.max(...vals), pad = (mx - mn) * 0.15 || 1;
    const lo = Math.floor(mn - pad), hi = Math.ceil(mx + pad);
    const x = (i) => L + ((W - L - R) * i) / Math.max(1, pts.length - 1), y = (v) => Tp + (H - Tp - B) * (1 - (v - lo) / (hi - lo));
    const c = o.color || 'var(--accent)';
    let s = svgOpen(W, H, o.label);
    for (let k = 0; k <= 3; k++) { const v = lo + ((hi - lo) * k) / 3, yy = y(v); s += `<line x1="${L}" x2="${W - R}" y1="${r1(yy)}" y2="${r1(yy)}" stroke="var(--outline)"/>` + T(L - 6, yy + 4, Math.round(v), 'text-anchor="end" fill="var(--onSurface3)"'); }
    const d = pts.map((p, i) => (i ? 'L' : 'M') + r1(x(i)) + ' ' + r1(y(p.value))).join(' ');
    s += `<path d="${d} L${r1(x(pts.length - 1))} ${H - B} L${L} ${H - B} Z" fill="${c}" opacity="0.12"/><path d="${d}" fill="none" stroke="${c}" stroke-width="2.2" stroke-linejoin="round" stroke-linecap="round"/>`;
    const every = o.every || Math.ceil(pts.length / 6);
    pts.forEach((p, i) => { if (i % every === 0) s += T(x(i), H - 6, p.label, 'text-anchor="middle" fill="var(--onSurface3)"'); });
    if (o.avg) { const a = vals.reduce((p, q) => p + q, 0) / vals.length, ay = y(a); s += `<line x1="${L}" x2="${W - R}" y1="${r1(ay)}" y2="${r1(ay)}" stroke="var(--onSurface2)" stroke-dasharray="4 4"/>` + T(W - R, ay - 5, 'avg ' + Math.round(a) + (o.unit || ''), 'text-anchor="end" fill="var(--onSurface2)" ' + HALO); }
    if (o.annotate === 'minmax') {
      const iMin = vals.indexOf(mn), iMax = vals.indexOf(mx);
      [[iMax, mx, 'max', -8], [iMin, mn, 'min', 16]].forEach(([i, v, lab, dy]) => {
        const anchor = i < pts.length * 0.15 ? 'start' : i > pts.length * 0.85 ? 'end' : 'middle';
        s += `<circle cx="${r1(x(i))}" cy="${r1(y(v))}" r="4" fill="var(--surface)" stroke="${c}" stroke-width="2"/>` + T(x(i), y(v) + dy, `${lab} ${v}${o.unit || ''}`, `text-anchor="${anchor}" fill="var(--onSurface)" font-weight="600" ${HALO}`);
      });
    }
    return s + '</svg>';
  }

  // donut(items:[{label,value,color}], {size, thickness, center, sub, selected})
  function donut(items, o = {}) {
    const S = o.size || 168, th = o.thickness || 22, r = (S - th) / 2, C = 2 * Math.PI * r, tot = items.reduce((a, b) => a + +b.value, 0) || 1;
    let s = `<svg viewBox="0 0 ${S} ${S}" width="${S}" height="${S}" role="img" aria-label="${esc(o.label || 'donut chart')}" style="font-family:system-ui,sans-serif">`;
    s += `<circle cx="${S / 2}" cy="${S / 2}" r="${r}" fill="none" stroke="var(--outline)" stroke-width="${th}"/>`;
    let off = 0;
    items.forEach((it, i) => {
      const len = (C * it.value) / tot, gap = items.length > 1 ? Math.min(2, len / 2) : 0;
      const dim = o.selected != null && o.selected !== i;
      s += `<circle data-slice="${i}" cx="${S / 2}" cy="${S / 2}" r="${r}" fill="none" stroke="${it.color || color(i)}" stroke-width="${th}" stroke-dasharray="${r1(Math.max(0, len - gap))} ${r1(C)}" stroke-dashoffset="${r1(-off)}" transform="rotate(-90 ${S / 2} ${S / 2})" opacity="${dim ? 0.3 : 1}" style="cursor:pointer"/>`;
      off += len;
    });
    if (o.center != null) s += T(S / 2, S / 2 + (o.sub ? 2 : 7), o.center, 'text-anchor="middle" font-size="20" font-weight="650" fill="var(--onSurface)"');
    if (o.sub) s += T(S / 2, S / 2 + 20, o.sub, 'text-anchor="middle" font-size="11" fill="var(--onSurface2)"');
    return s + '</svg>';
  }

  // spark(values:number[], {width, height, color})
  function spark(values, o = {}) {
    const W = o.width || 80, H = o.height || 24, mn = Math.min(...values), mx = Math.max(...values), rg = mx - mn || 1;
    const p = values.map((v, i) => r1((W * i) / Math.max(1, values.length - 1)) + ',' + r1(H - 2 - ((H - 4) * (v - mn)) / rg)).join(' ');
    return `<svg viewBox="0 0 ${W} ${H}" width="${W}" height="${H}"><polyline points="${p}" fill="none" stroke="${o.color || 'var(--accent)'}" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round"/></svg>`;
  }

  window.GCore = { esc, fmt, color, PAL, bar, line, donut, spark };
})();
