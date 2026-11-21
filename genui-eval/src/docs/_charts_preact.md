## Helpers (G)
- `<${G.Bar} data=${[{label,value,color?}]} target=${8} unit="h" height=${170} selected=${i} values=${true} onBar=${(i) => ...}/>`
- `<${G.Line} data=${[{label,value}] or number[]} height=${180} unit="" annotate="minmax" avg=${true} color="var(--rose)" every=${N}/>`
- `<${G.Donut} data=${[{label,value,color?}]} size=${168} thickness=${22} center="$1,240" sub="total" selected=${i} onSlice=${(i) => ...}/>`
- `<${G.Spark} data=${number[]} width=${80} height=${24}/>`
- G.color(i) -> series color var (the order Donut uses). G.fmt.money(84.5) "$84.50", G.fmt.num(n, decimals), G.fmt.pct(0.25) "25%", G.fmt.dur(sec) "m:ss".
