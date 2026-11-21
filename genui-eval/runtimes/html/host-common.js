/* Shared host glue. Each runtime sets window.__renderImpl(src, root) before this file runs render(). */
(function () {
  'use strict';
  const q = new URLSearchParams(location.search);
  document.body.className = q.get('mode') === 'canvas' ? 'canvas' : 'card';
  const root = document.getElementById('root');
  // Timer tracking so a new render() cancels the previous UI's timers.
  const timers = new Set(), si = window.setInterval, st = window.setTimeout, ci = window.clearInterval, ct = window.clearTimeout;
  window.setInterval = function (f, ms, ...a) { const id = si(f, ms, ...a); timers.add(id); return id; };
  window.setTimeout = function (f, ms, ...a) { const id = st(function () { timers.delete(id); f(...a); }, ms); timers.add(id); return id; };
  window.clearInterval = (id) => { timers.delete(id); ci(id); };
  window.clearTimeout = (id) => { timers.delete(id); ct(id); };
  // Height reporting for the native overlay.
  let lastH = -1;
  new ResizeObserver(() => { const h = Math.ceil(document.body.scrollHeight); if (h !== lastH) { lastH = h; if (window.onCanvasHeight) try { window.onCanvasHeight(h); } catch (e) {} if (window.Android && Android.onCanvasHeight) Android.onCanvasHeight(h); } }).observe(document.body);
  // Instrumentation (cheap): input-to-next-frame latency and long tasks.
  window.__lat = []; window.__longtasks = [];
  const mc = new MessageChannel(); let pending = [];
  mc.port1.onmessage = () => { const now = performance.now(); pending.forEach((t) => window.__lat.push(now - t)); pending = []; };
  ['click', 'input', 'change'].forEach((ev) => window.addEventListener(ev, (e) => { pending.push(e.timeStamp); requestAnimationFrame(() => mc.port2.postMessage(0)); }, true));
  try { new PerformanceObserver((l) => l.getEntries().forEach((e) => window.__longtasks.push(e.duration))).observe({ type: 'longtask', buffered: true }); } catch (e) {}
  function showErr(e) { console.error('render error: ' + (e && e.stack || e)); const d = document.createElement('div'); d.className = 'g-err'; d.textContent = String(e && e.message || e); root.appendChild(d); }
  window.render = function (src) {
    performance.mark('render-start');
    timers.forEach((id) => { ci(id); ct(id); }); timers.clear();
    try { window.__renderImpl(src, root); } catch (e) { showErr(e); }
    performance.mark('render-end');
  };
  performance.mark('runtime-ready');
  if (window.__payload != null) {
    window.render(window.__payload);
    requestAnimationFrame(() => requestAnimationFrame(() => { window.__coldPaint = performance.now(); }));
  }
})();
