// Merges results/final.json, results/first.json, gen-log.json, visual-scores.json into the per-candidate table (markdown).
import fs from 'node:fs'; import path from 'node:path'; import zlib from 'node:zlib'; import { fileURLToPath } from 'node:url';
const D = path.dirname(fileURLToPath(import.meta.url)), J = (p) => JSON.parse(fs.readFileSync(path.join(D, p), 'utf8'));
const fin = J('results/final.json'), first = J('results/first.json'), log = J('gen-log.json'), vis = J('visual-scores.json');
const EXT = { html: 'html', 'preact-htm': 'js', ts: 'ts', json: 'json' };
const mean = (a) => { a = a.filter((x) => x != null); return a.reduce((p, q) => p + q, 0) / a.length; };
const added = (a, b) => { const s = new Set(a.split('\n')); return b.split('\n').filter((l) => !s.has(l)).join('\n').length; };
const rt = (c) => { const dir = path.join(D, 'runtimes', c), h = fs.readFileSync(path.join(dir, 'host.html'), 'utf8'); let raw = 0, gz = 0; for (const f of ['host.html', ...[...h.matchAll(/(?:src|href)="([^"]+)"/g)].map((m) => m[1])]) { const b = fs.readFileSync(path.join(dir, f)); raw += b.length; gz += zlib.gzipSync(b, { level: 9 }).length; } return [raw, gz]; };
console.log('| candidate | first-try pass | est. tokens to first working (first try + fixes) | fix rounds (total) | warm render→paint ms @4x (median / worst) | input→next frame ms @4x (worst step, mean over tasks) | cold load+render ms @4x | runtime KB raw (gz) | doc tokens | visual (1-5) |');
console.log('|---|---|---|---|---|---|---|---|---|---|');
for (const c of ['html', 'preact-htm', 'ts', 'json']) {
  const F = fin.filter((r) => r.cand === c), S = first.filter((r) => r.cand === c);
  const toks = F.map((r) => { const a = fs.readFileSync(path.join(D, 'solutions/_first', c, `${r.task}.${EXT[c]}`), 'utf8'), b = fs.readFileSync(path.join(D, 'solutions', c, `${r.task}.${EXT[c]}`), 'utf8'); return (a.length + (a === b ? 0 : added(a, b))) / 3.5; });
  const rounds = Object.values(log.fixRounds[c] || {}).reduce((a, b) => a + b, 0);
  const [raw, gz] = rt(c), doc = fs.statSync(path.join(D, 'runtimes', c, 'DOC.md')).size;
  console.log(`| ${c} | ${S.filter((r) => r.pass).length}/${S.length} | ${Math.round(mean(toks))} | ${rounds} | ${mean(F.map((r) => r.warmMs)).toFixed(1)} / ${Math.max(...F.map((r) => r.warmMaxMs)).toFixed(0)} | ${mean(F.map((r) => r.inputLatMs)).toFixed(1)} | ${mean(F.map((r) => r.coldPaintMs)).toFixed(0)} | ${(raw / 1024).toFixed(1)} (${(gz / 1024).toFixed(1)}) | ${Math.round(doc / 3.5)} | ${mean(Object.values(vis[c])).toFixed(2)} |`);
}
const T = fin.filter((r) => r.cand === 'ts');
console.log(`\nts transform: in-page Sucrase at 4x ${mean(T.map((r) => r.tsTransformPhoneMs)).toFixed(1)} ms/solution; this machine esbuild ${mean(T.map((r) => r.nodeTransform.esbuild)).toFixed(2)} ms, Sucrase ${mean(T.map((r) => r.nodeTransform.sucrase)).toFixed(2)} ms (x4 phone est. ${(4 * mean(T.map((r) => r.nodeTransform.esbuild))).toFixed(1)} / ${(4 * mean(T.map((r) => r.nodeTransform.sucrase))).toFixed(1)} ms). runtime init cold @4x: ${['html', 'preact-htm', 'ts', 'json'].map((c) => c + ' ' + mean(fin.filter((r) => r.cand === c).map((r) => r.runtimeInitMs)).toFixed(0) + 'ms').join(', ')}. long tasks (>50ms) across all renders+interactions: ${['html', 'preact-htm', 'ts', 'json'].map((c) => c + ' ' + fin.filter((r) => r.cand === c).reduce((a, r) => a + r.longTasks, 0)).join(', ')}.`);
