## Helpers (G)
- G.bar(data:[{label,value,color?}], {target?, unit?, height?=170, color?, selected?:index, values?:bool}) -> SVG string. Each bar is a `<g data-bar="i">`; delegate clicks with e.target.closest('[data-bar]').
- G.line(points:[{label,value}] | number[], {height?=180, unit?, annotate?:'minmax', avg?:bool, color?, every?:N label step}) -> SVG string.
- G.donut(items:[{label,value,color?}], {size?=168, thickness?=22, center?:string, sub?:string, selected?:index}) -> SVG string; arcs carry data-slice="i".
- G.spark(values:number[], {width?=80, height?=24, color?}) -> SVG string.
- G.color(i) -> series color var (the order donut uses). G.fmt.money(84.5) "$84.50", G.fmt.num(n, decimals), G.fmt.pct(0.25) "25%", G.fmt.dur(sec) "m:ss", G.esc(s) HTML-escape.
