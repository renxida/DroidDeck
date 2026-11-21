#!/usr/bin/env node
// GenUI eval: rerun every check + perf on every solution, print a table, write results/<set>.json and screenshots.
// Usage: node run.mjs [--set final|first] [--only html,json] [--tasks tip,quiz] [--no-shots] [--no-cold] [--rate 4]
// Chromium comes from CHROME_DIR (default ../../chrome relative to this file) with @sparticuz/chromium + playwright-core.
import fs from 'node:fs'; import path from 'node:path'; import zlib from 'node:zlib';
import { fileURLToPath, pathToFileURL } from 'node:url'; import { createRequire } from 'node:module';

const D = path.dirname(fileURLToPath(import.meta.url));
const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i < 0 ? d : (process.argv[i + 1] && !process.argv[i + 1].startsWith('--') ? process.argv[i + 1] : true); };
const SET = arg('set', 'final'), RATE = +arg('rate', 4), SHOTS = !arg('no-shots', false), COLD = !arg('no-cold', false);
const CANDS = ['html', 'preact-htm', 'ts', 'json'].filter((c) => !arg('only') || arg('only').split(',').includes(c));
const EXT = { html: 'html', 'preact-htm': 'js', ts: 'ts', json: 'json' };
const TASKS = JSON.parse(fs.readFileSync(path.join(D, 'tasks/tasks.json'), 'utf8')).filter((t) => !arg('tasks') || arg('tasks').split(',').includes(t.id));
const SOL = SET === 'final' ? path.join(D, 'solutions') : path.join(D, 'solutions', '_' + SET);
const SHOTDIR = path.join(D, 'shots', SET === 'final' ? '' : '_' + SET);
const CHROME_DIR = process.env.CHROME_DIR || path.resolve(D, '../../chrome');
const req = createRequire(path.join(CHROME_DIR, 'package.json'));
const chromium = (await import(pathToFileURL(req.resolve('@sparticuz/chromium')).href)).default;
const pwm = await import(pathToFileURL(req.resolve('playwright-core')).href); const pw = pwm.chromium || pwm.default.chromium;
const ts = createRequire(import.meta.url)('typescript');

// Warm-up payloads (the phone keeps the runtime loaded and JIT-warm between UIs).
const WARM = {
  html: '<div class="card"><div class="title">hi</div></div><script>root.querySelector(".title").textContent+="!"</script>',
  ts: 'const t: HTMLElement = root; t.innerHTML = `<div class="card"><div class="title">hi</div></div>`;',
  'preact-htm': 'function App(){const [n]=useState(1);return html`<div class="card"><div class="title">hi ${n}</div></div>`}\nreturn App;',
  json: '{"state":{"n":1},"view":{"type":"card","children":[{"type":"text","style":"title","value":"hi {{n}}"}]}}',
};

function runtimeSize(c) {
  const dir = path.join(D, 'runtimes', c), host = fs.readFileSync(path.join(dir, 'host.html'), 'utf8');
  const files = ['host.html', ...[...host.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1])];
  let raw = 0, gz = 0;
  for (const f of files) { const b = fs.readFileSync(path.join(dir, f)); raw += b.length; gz += zlib.gzipSync(b, { level: 9 }).length; }
  return { raw, gz };
}

// Strict type check of all TS solutions in one program (each file is its own module).
function typecheck(files) {
  if (!files.length) return {};
  const prog = ts.createProgram([path.join(D, 'runtimes/ts/g.d.ts'), ...files], { strict: true, noEmit: true, target: ts.ScriptTarget.ES2022, lib: ['lib.es2022.d.ts', 'lib.dom.d.ts', 'lib.dom.iterable.d.ts'], moduleDetection: ts.ModuleDetectionKind.Force, skipLibCheck: true });
  const out = {};
  for (const f of files) out[f] = ts.getPreEmitDiagnostics(prog, prog.getSourceFile(f)).map((d) => { const p = d.file && d.start != null ? d.file.getLineAndCharacterOfPosition(d.start) : null; return (p ? `L${p.line + 1}: ` : '') + ts.flattenDiagnosticMessageText(d.messageText, ' '); });
  return out;
}

async function nodeTransformMs(src) {
  const { transformSync } = await import('esbuild'); const { transform } = createRequire(import.meta.url)('sucrase');
  const time = (f) => { for (let i = 0; i < 5; i++) f(); const t = performance.now(); for (let i = 0; i < 20; i++) f(); return (performance.now() - t) / 20; };
  return { esbuild: time(() => transformSync(src, { loader: 'ts' })), sucrase: time(() => transform(src, { transforms: ['typescript'], disableESTransforms: true })) };
}

// ---------- in-page probes ----------
const probe = () => {
  const root = document.getElementById('root'), rr = root.getBoundingClientRect();
  const nonEmpty = (root.innerText.trim().length > 0 || !!root.querySelector('svg')) && rr.height > 40;
  // overflow: page-level horizontal scroll, or elements poking out past the root's right edge (unless clipped/scrolled by an ancestor inside root)
  const clipped = (el) => { for (let p = el.parentElement; p && p !== root; p = p.parentElement) { const ox = getComputedStyle(p).overflowX; if (ox !== 'visible') return true; } return false; };
  const off = [];
  root.querySelectorAll('*').forEach((el) => { const r = el.getBoundingClientRect(); if (r.width && r.height && (r.right > rr.right + 1 || r.left < rr.left - 1) && !clipped(el)) off.push(el.tagName.toLowerCase() + (el.className && typeof el.className === 'string' ? '.' + el.className.split(' ')[0] : '') + ` ${Math.round(r.left)}..${Math.round(r.right)}`); });
  const pageScroll = document.documentElement.scrollWidth > innerWidth + 1;
  // contrast: text fg vs nearest opaque background (approximate; ignores images/gradients)
  const parse = (s) => { const m = s && s.match(/rgba?\(([^)]+)\)/); if (!m) return null; const p = m[1].split(/[ ,/]+/).filter(Boolean).map(Number); return { r: p[0], g: p[1], b: p[2], a: p[3] == null ? 1 : p[3] }; };
  const lum = (c) => { const f = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }; return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b); };
  const ratio = (a, b) => { const x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
  const bgOf = (el) => { for (let p = el; p; p = p.parentElement) { const cs = getComputedStyle(p); const c = parse(cs.backgroundColor); if (c && c.a > 0.5) return c; if (p.tagName === 'rect' || p.tagName === 'circle') { const f = parse(cs.fill); if (f && f.a > 0.5) return f; } } return parse(getComputedStyle(document.body).backgroundColor); };
  const lowContrast = []; let minRatio = 99;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  const seen = new Set();
  for (let n = walker.nextNode(); n; n = walker.nextNode()) {
    const el = n.parentElement; if (!n.textContent.trim() || seen.has(el)) continue; seen.add(el);
    const cs = getComputedStyle(el), r = el.getBoundingClientRect();
    if (!r.width || cs.visibility === 'hidden' || +cs.opacity === 0 || el.closest('[hidden],option,style,script')) continue;
    let op = 1; for (let p = el; p && p !== root; p = p.parentElement) op *= +getComputedStyle(p).opacity;
    if (op < 0.6) continue; // intentionally de-emphasised content
    const fg = parse(el instanceof SVGElement ? cs.fill : cs.color); if (!fg) continue;
    const rt = ratio(fg, bgOf(el.parentElement && el instanceof SVGElement ? el : el)); minRatio = Math.min(minRatio, rt);
    if (rt < 2.5) lowContrast.push(n.textContent.trim().slice(0, 24) + ' ' + rt.toFixed(2));
  }
  return { nonEmpty, overflow: off.slice(0, 5), pageScroll, lowContrast: lowContrast.slice(0, 5), minRatio: +minRatio.toFixed(2), height: Math.round(rr.height) };
};
const renderTimed = (src) => new Promise((res) => {
  const t0 = performance.now(); window.render(src); const t1 = performance.now();
  requestAnimationFrame(() => { const mc = new MessageChannel(); mc.port1.onmessage = () => res({ js: t1 - t0, paint: performance.now() - t0 }); mc.port2.postMessage(0); });
});
const nextPaint = () => new Promise((r) => requestAnimationFrame(() => { const mc = new MessageChannel(); mc.port1.onmessage = () => r(); mc.port2.postMessage(0); }));

async function runSteps(page, steps) {
  const tid = (t) => `[data-testid="${t}"]`, lats = [], vars = {};
  const textOf = async (t) => (await page.locator(tid(t)).first().innerText({ timeout: 1500 })).trim();
  for (const s of steps) {
    try {
      if (s.click || s.clickSel || s.fill || s.setRange != null) {
        await page.evaluate(() => { window.__lat = []; });
        if (s.click) await page.locator(tid(s.click)).first().click({ timeout: 1500, force: !!s.force });
        else if (s.clickSel) await page.locator(s.clickSel).first().click({ timeout: 1500 });
        else if (s.fill) await page.locator(tid(s.fill)).first().fill(String(s.value), { timeout: 1500 });
        else await page.locator(tid(s.setRange)).first().evaluate((el, v) => { el.value = v; el.dispatchEvent(new Event('input', { bubbles: true })); el.dispatchEvent(new Event('change', { bubbles: true })); }, String(s.value), { timeout: 1500 });
        await page.evaluate(nextPaint); await page.evaluate(nextPaint);
        const l = await page.evaluate(() => window.__lat); if (l.length) lats.push(Math.max(...l));
      } else if (s.wait) await page.waitForTimeout(s.wait);
      else if (s.save) vars[s.as] = await textOf(s.save);
      else if (s.expectSaved) { const t = await textOf(s.expectSaved); if (t !== vars[s.as]) throw new Error(`${s.expectSaved}: "${t}" != saved "${vars[s.as]}"`); }
      else if (s.expect) {
        const want = [].concat(s.contains || []), re = s.match ? new RegExp(s.match) : null; let t = '', ok = false;
        for (let i = 0; i < 15 && !ok; i++) {
          t = await textOf(s.expect).catch(() => '');
          ok = want.every((w) => t.includes(w)) && (!re || re.test(t)) && (!s.visible || await page.locator(tid(s.expect)).first().isVisible());
          if (!ok) await page.waitForTimeout(100);
        }
        if (!ok) throw new Error(`${s.expect}: got "${t.slice(0, 60)}" want ${JSON.stringify(want)}${re ? ' ~' + s.match : ''}`);
      }
    } catch (e) { return { ok: false, err: String(e.message || e).split('\n')[0].slice(0, 160), lats }; }
  }
  return { ok: true, lats };
}

const median = (a) => { const b = [...a].sort((x, y) => x - y); return b.length ? b[Math.floor((b.length - 1) / 2)] : null; };
const host = (c, mode) => pathToFileURL(path.join(D, 'runtimes', c, 'host.html')).href + '?mode=' + mode;

const browser = await pw.launch({ executablePath: await chromium.executablePath(), args: chromium.args.filter((a) => a !== '--single-process'), headless: true });
const ctxOpts = (scheme) => ({ viewport: { width: 411, height: 914 }, deviceScaleFactor: 2.625, isMobile: true, hasTouch: true, colorScheme: scheme });
async function newPage(scheme) {
  const ctx = await browser.newContext(ctxOpts(scheme)); const page = await ctx.newPage(); const errs = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 160)); }); page.on('pageerror', (e) => errs.push(String(e.message).slice(0, 160)));
  const cdp = await ctx.newCDPSession(page); return { ctx, page, errs, cdp };
}

// Browser-process warm-up (fonts, ICU, GPU raster) so the first measured solution isn't penalised.
{ const { ctx, page } = await newPage('light'); await page.goto(host('html', 'card'));
  await page.evaluate(renderTimed, '<div class="card"><div class="title">warm</div><div id="d"></div></div><script>root.querySelector("#d").innerHTML=G.donut([{label:"a",value:1},{label:"b",value:2}],{center:G.fmt.money(3),sub:"x"})+G.bar([{label:"a",value:1}],{target:1})+G.fmt.num(1,1)</script>');
  await ctx.close(); }
const results = [];
const tsFiles = TASKS.map((t) => path.join(SOL, 'ts', `${t.id}.ts`)).filter((f) => CANDS.includes('ts') && fs.existsSync(f));
const tc = typecheck(tsFiles);

for (const c of CANDS) {
  const size = runtimeSize(c);
  for (const t of TASKS) {
    const file = path.join(SOL, c, `${t.id}.${EXT[c]}`);
    if (!fs.existsSync(file)) { results.push({ cand: c, task: t.id, missing: true, pass: false }); continue; }
    const src = fs.readFileSync(file, 'utf8');
    const r = { cand: c, task: t.id, chars: src.length, tokens: Math.round(src.length / 3.5), errors: [], fails: [] };
    if (c === 'ts') { r.typeErrors = tc[file] || []; if (r.typeErrors.length) r.fails.push('tsc: ' + r.typeErrors[0]); r.nodeTransform = await nodeTransformMs(src); }
    for (const scheme of ['light', 'dark']) {
      const { ctx, page, errs, cdp } = await newPage(scheme);
      await page.goto(host(c, 'card'));
      const throttled = scheme === 'light';
      if (throttled) await cdp.send('Emulation.setCPUThrottlingRate', { rate: RATE });
      await page.evaluate(renderTimed, WARM[c]);
      await page.evaluate(() => { window.__longtasks = []; performance.clearMeasures(); });
      const times = []; for (let i = 0; i < 7; i++) times.push(await page.evaluate(renderTimed, src));
      const pr = await page.evaluate(probe);
      if (!pr.nonEmpty) r.fails.push(`${scheme}: empty render`);
      if (pr.overflow.length || pr.pageScroll) r.fails.push(`${scheme}/card overflow: ${pr.pageScroll ? 'page-scroll ' : ''}${pr.overflow.join(', ')}`);
      if (pr.lowContrast.length) r.fails.push(`${scheme} contrast<2.5: ${pr.lowContrast.join('; ')}`);
      r[scheme] = { minContrast: pr.minRatio, height: pr.height };
      if (SHOTS) { const dir = path.join(SHOTDIR, c); fs.mkdirSync(dir, { recursive: true }); await page.locator('#root').screenshot({ path: path.join(dir, `${t.id}-${scheme}.png`) }).catch(() => {}); }
      const st = await runSteps(page, t.steps);
      if (!st.ok) r.fails.push(`${scheme} interaction: ${st.err}`);
      if (throttled) {
        r.warmFirstMs = +times[0].paint.toFixed(1); r.warmMs = +median(times.map((x) => x.paint)).toFixed(1); r.warmJsMs = +median(times.map((x) => x.js)).toFixed(1); r.warmMaxMs = +Math.max(...times.map((x) => x.paint)).toFixed(1);
        r.inputLatMs = st.lats.length ? +Math.max(...st.lats).toFixed(1) : null; r.inputLatMeanMs = st.lats.length ? +(st.lats.reduce((a, b) => a + b, 0) / st.lats.length).toFixed(1) : null;
        const lt = await page.evaluate(() => window.__longtasks); r.longTasks = lt.length; r.longTaskMs = Math.round(lt.reduce((a, b) => a + b, 0));
        if (c === 'ts') r.tsTransformPhoneMs = +median(await page.evaluate(() => performance.getEntriesByName('ts-transform').map((e) => e.duration))).toFixed(1);
      }
      r.errors.push(...errs.map((e) => scheme + ': ' + e));
      await ctx.close();
    }
    { // full-screen canvas width
      const { ctx, page, errs } = await newPage('light');
      await page.goto(host(c, 'canvas')); await page.evaluate(renderTimed, src);
      const pr = await page.evaluate(probe);
      if (pr.overflow.length || pr.pageScroll) r.fails.push(`canvas overflow: ${pr.overflow.join(', ')}`);
      r.errors.push(...errs.map((e) => 'canvas: ' + e)); await ctx.close();
    }
    if (COLD) { // cold: fresh page, runtime not yet parsed, payload rendered at load
      const { ctx, page, cdp } = await newPage('light');
      await cdp.send('Emulation.setCPUThrottlingRate', { rate: RATE });
      await page.addInitScript((s) => { window.__payload = s; }, src);
      await page.goto(host(c, 'card'));
      await page.waitForFunction(() => window.__coldPaint, null, { timeout: 20000 }).catch(() => {});
      const m = await page.evaluate(() => { const g = (n) => performance.getEntriesByName(n)[0]?.startTime; return { paint: window.__coldPaint, init: g('runtime-ready') - g('host-start'), render: g('render-end') - g('render-start') }; });
      r.coldPaintMs = m.paint ? +m.paint.toFixed(1) : null; r.runtimeInitMs = +m.init.toFixed(1); r.coldRenderMs = +m.render.toFixed(1);
      await ctx.close();
    }
    if (r.errors.length) r.fails.unshift('console: ' + r.errors[0]);
    r.pass = r.fails.length === 0;
    results.push(r);
    process.stdout.write(`${r.pass ? 'PASS' : 'FAIL'} ${c.padEnd(10)} ${t.id.padEnd(10)} warm ${String(r.warmMs).padStart(6)}ms  lat ${String(r.inputLatMs).padStart(5)}ms  cold ${String(r.coldPaintMs).padStart(6)}ms ${r.pass ? '' : ' <- ' + r.fails.join(' | ').slice(0, 220)}\n`);
  }
}

// Contact sheets: one PNG per candidate, light|dark per task.
if (SHOTS) {
  const { ctx, page } = await newPage('light');
  for (const c of CANDS) {
    const cells = TASKS.map((t) => `<figure><figcaption>${t.id}</figcaption><div class="p">${['light', 'dark'].map((s) => `<img src="${pathToFileURL(path.join(SHOTDIR, c, `${t.id}-${s}.png`)).href}">`).join('')}</div></figure>`).join('');
    const html = `<html><body style="margin:0;background:#888;font:28px system-ui"><h1 style="margin:16px">${c}</h1><div style="display:grid;grid-template-columns:repeat(4,auto);gap:16px;padding:16px;align-items:start">${cells}</div><style>figure{margin:0}.p{display:flex;gap:6px;align-items:flex-start}img{width:290px}figcaption{color:#fff;margin:4px}</style></body></html>`;
    const f = path.join(SHOTDIR, `_sheet-${c}.html`); fs.writeFileSync(f, html);
    await page.setViewportSize({ width: 2500, height: 900 }); await page.goto(pathToFileURL(f).href); await page.waitForLoadState('load');
    await page.screenshot({ path: path.join(SHOTDIR, `contact-${c}.png`), fullPage: true, scale: 'css' }); fs.unlinkSync(f);
  }
  await ctx.close();
}
await browser.close();

// ---------- summary ----------
fs.mkdirSync(path.join(D, 'results'), { recursive: true });
fs.writeFileSync(path.join(D, 'results', `${SET}.json`), JSON.stringify(results, null, 1));
const mean = (a) => { a = a.filter((x) => x != null && !Number.isNaN(x)); return a.length ? a.reduce((p, q) => p + q, 0) / a.length : null; };
const f1 = (x) => (x == null ? '-' : x.toFixed(1));
console.log(`\nset=${SET}  CPU throttle ${RATE}x  viewport 411x914@2.625`);
console.log('cand        pass   tokens  warm_ms  warm_js  lat_max  lat_mean  longT  cold_ms  init_ms  rt_KB(gz)');
for (const c of CANDS) {
  const rs = results.filter((r) => r.cand === c && !r.missing), s = runtimeSize(c);
  console.log(`${c.padEnd(11)} ${(rs.filter((r) => r.pass).length + '/' + TASKS.length).padEnd(6)} ${String(Math.round(mean(rs.map((r) => r.tokens)) || 0)).padStart(6)}  ${f1(mean(rs.map((r) => r.warmMs))).padStart(7)}  ${f1(mean(rs.map((r) => r.warmJsMs))).padStart(7)}  ${f1(mean(rs.map((r) => r.inputLatMs))).padStart(7)}  ${f1(mean(rs.map((r) => r.inputLatMeanMs))).padStart(8)}  ${String(rs.reduce((a, r) => a + (r.longTasks || 0), 0)).padStart(5)}  ${f1(mean(rs.map((r) => r.coldPaintMs))).padStart(7)}  ${f1(mean(rs.map((r) => r.runtimeInitMs))).padStart(7)}  ${(s.raw / 1024).toFixed(1)}(${(s.gz / 1024).toFixed(1)})`);
  if (c === 'ts') console.log(`            ts transform: phone(${RATE}x, in-page sucrase) ${f1(mean(rs.map((r) => r.tsTransformPhoneMs)))}ms; this machine esbuild ${mean(rs.map((r) => r.nodeTransform?.esbuild)).toFixed(2)}ms, sucrase ${mean(rs.map((r) => r.nodeTransform?.sucrase)).toFixed(2)}ms`);
}
