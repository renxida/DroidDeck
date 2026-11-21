# Runtime: preact-htm
Write a JS function body that defines a component and ends with `return App;`. In scope: `html` (htm tagged templates bound to Preact's h), `h`, `useState`, `useEffect`, `useRef`, `useMemo`, `G`. Use `class=` (not className), `style=${{...}}` or a `<style>` element inside the template. Components: `<${Comp} prop=${x}/>`. No imports, no external URLs.
## Theme (theme.css is preloaded; colors switch light/dark automatically)
Vars: --bg --surface --surface2 --onSurface --onSurface2 --onSurface3 --outline --accent --accentSoft --success --danger --amber --rose --onAccent (text on accent).
Text: .title (17px semibold) .sub (13px secondary) .muted (12px tertiary) .big (30px number) .num (tabular digits)
Layout: .stack (column, gap 12) .row (flex row, gap 8) .spread (row, space-between) .grow .grid2 .grid3 .divider .list (children separated by lines)
Bits: .card (surface2 panel, radius 16, padding 14) .chip / .chip.on .btn / .btn.ghost / .btn.small .badge .dot .progress>i (set the i width %)
Inputs: input[type=range|number|text] and select are pre-styled; input.toggle = switch (checkbox); input.check = checkbox.
The root is a 16px-padded surface: 355px content width in the overlay card, 379px in the full-screen canvas. No fixed widths over 355px. Touch targets at least 32px. System font. Don't hardcode colors; use the vars.
Tests find elements by `data-testid`; put the testids the task names on the right elements.
## Helpers (G)
- `<${G.Bar} data=${[{label,value,color?}]} target=${8} unit="h" height=${170} selected=${i} values=${true} onBar=${(i) => ...}/>`
- `<${G.Line} data=${[{label,value}] or number[]} height=${180} unit="" annotate="minmax" avg=${true} color="var(--rose)" every=${N}/>`
- `<${G.Donut} data=${[{label,value,color?}]} size=${168} thickness=${22} center="$1,240" sub="total" selected=${i} onSlice=${(i) => ...}/>`
- `<${G.Spark} data=${number[]} width=${80} height=${24}/>`
- G.color(i) -> series color var (the order Donut uses). G.fmt.money(84.5) "$84.50", G.fmt.num(n, decimals), G.fmt.pct(0.25) "25%", G.fmt.dur(sec) "m:ss".
## Example
```js
function App() {
  const [d, setD] = useState([{ label: 'Mon', value: 6 }, { label: 'Tue', value: 8 }]);
  return html`<div class="stack"><div class="spread"><div class="title">Steps</div>
    <button class="btn small" data-testid="add" onClick=${() => setD(d.map((x, i) => i === 1 ? { ...x, value: x.value + 1 } : x))}>+1k</button></div>
    <${G.Bar} data=${d} target=${7} unit="k"/></div>`;
}
return App;
```
