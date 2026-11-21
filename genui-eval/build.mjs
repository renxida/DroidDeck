// Assembles runtimes/<name>/ (self-contained WebView assets) from src/.
import fs from 'node:fs'; import path from 'node:path'; import { fileURLToPath } from 'node:url';
const D = path.dirname(fileURLToPath(import.meta.url)), S = (p) => path.join(D, 'src', p), NM = (p) => path.join(D, 'node_modules', p);
const R = {
  html: ['html.js'],
  ts: [['sucrase.min.js', S('sucrase.min.js')], 'ts.js'],
  'preact-htm': [['preact.min.js', NM('preact/dist/preact.min.umd.js')], ['hooks.min.js', NM('preact/hooks/dist/hooks.umd.js')], ['htm.min.js', NM('htm/dist/htm.umd.js')], 'preact-g.js'],
  json: ['json.js'],
};
const tmpl = fs.readFileSync(S('host.tmpl.html'), 'utf8');
for (const [name, files] of Object.entries(R)) {
  const out = path.join(D, 'runtimes', name); fs.mkdirSync(out, { recursive: true });
  const list = [['core.js', S('core.js')], ...files.map((f) => (Array.isArray(f) ? f : [f, S(f)]))];
  for (const [n, p] of [['theme.css', S('theme.css')], ...list, ['host-common.js', S('host-common.js')]]) fs.copyFileSync(p, path.join(out, n));
  fs.writeFileSync(path.join(out, 'host.html'), tmpl.replace('%SCRIPTS%', list.map(([n]) => `<script src="${n}"></script>`).join('\n')));
  fs.copyFileSync(S(`docs/${name}.md`), path.join(out, 'DOC.md'));
  if (name === 'ts') fs.copyFileSync(S('g.d.ts'), path.join(out, 'g.d.ts'));
}
console.log('built', Object.keys(R).join(', '));
