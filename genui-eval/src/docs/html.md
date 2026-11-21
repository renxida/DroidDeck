# Runtime: html
Write one HTML fragment: optional `<style>`, markup, then `<script>`. Scripts run in order as `function(root, G)`: `root` is the container element (query with root.querySelector), `G` the helper object below. No imports, no external URLs. Scope CSS under a class on your top element.
## Theme (theme.css is preloaded; colors switch light/dark automatically)
Vars: --bg --surface --surface2 --onSurface --onSurface2 --onSurface3 --outline --accent --accentSoft --success --danger --amber --rose --onAccent (text on accent).
Text: .title (17px semibold) .sub (13px secondary) .muted (12px tertiary) .big (30px number) .num (tabular digits)
Layout: .stack (column, gap 12) .row (flex row, gap 8) .spread (row, space-between) .grow .grid2 .grid3 .divider .list (children separated by lines)
Bits: .card (surface2 panel, radius 16, padding 14) .chip / .chip.on .btn / .btn.ghost / .btn.small .badge .dot .progress>i (set the i width %)
Inputs: input[type=range|number|text] and select are pre-styled; input.toggle = switch (checkbox); input.check = checkbox.
The root is a 16px-padded surface: 355px content width in the overlay card, 379px in the full-screen canvas. No fixed widths over 355px. Touch targets at least 32px. System font. Don't hardcode colors; use the vars.
Tests find elements by `data-testid`; put the testids the task names on the right elements.
## Helpers (G)
- G.bar(data:[{label,value,color?}], {target?, unit?, height?=170, color?, selected?:index, values?:bool}) -> SVG string. Each bar is a `<g data-bar="i">`; delegate clicks with e.target.closest('[data-bar]').
- G.line(points:[{label,value}] | number[], {height?=180, unit?, annotate?:'minmax', avg?:bool, color?, every?:N label step}) -> SVG string.
- G.donut(items:[{label,value,color?}], {size?=168, thickness?=22, center?:string, sub?:string, selected?:index}) -> SVG string; arcs carry data-slice="i".
- G.spark(values:number[], {width?=80, height?=24, color?}) -> SVG string.
- G.color(i) -> series color var (the order donut uses). G.fmt.money(84.5) "$84.50", G.fmt.num(n, decimals), G.fmt.pct(0.25) "25%", G.fmt.dur(sec) "m:ss", G.esc(s) HTML-escape.
## Example
```html
<div class="x stack"><div class="spread"><div class="title">Steps</div><button class="btn small" data-testid="add">+1k</button></div><div id="c"></div></div>
<script>
const d=[{label:'Mon',value:6},{label:'Tue',value:8}];
const draw=()=>root.querySelector('#c').innerHTML=G.bar(d,{target:7,unit:'k'});
root.querySelector('[data-testid=add]').onclick=()=>{d[1].value++;draw()};draw();
</script>
```
