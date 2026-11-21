# Runtime: json
Write one JSON document `{"state": {...}, "view": node}` rendered by a native-style schema renderer (no code, no CSS). Colors follow the theme automatically.
Values: a string starting with `=` is an expression over state (JS syntax; helpers fmt.money/num/pct/dur, sum(arr, fn?), count(arr, fn?), len, round(n, d), min, max, abs, floor, Math). Strings with `{{expr}}` are interpolated. Inside a list template, `$item` and `$index` are in scope.
Actions: `{"set": {"path": "=expr" | literal}}`, `{"toggle": "path"}`, or an array of actions. Paths are dotted: `"items.$index.done"`, `"days.2"`.
Every node accepts: `id` (becomes data-testid, may use {{ }}), `visible` (expr), `onTap` (action), `style` (CSS object, camelCase, escape hatch).
Nodes:
- column {children, gap=12} · row {children, gap=8, justify:"between", align, wrap} · grid {columns=2, children, gap=8} · card {children} (surface2 panel)
- text {value, style: title|sub|muted|big|label, color: accent|success|danger|onSurface2|onSurface3, align, weight} · badge {value}
- chart {kind: bar|line|donut|spark, data, ...options, onSelect: action ($index = tapped bar/slice)}. bar options: target, unit, height=170, selected, values. line: height=180, unit, annotate:"minmax", avg. donut: size=168, thickness=22, center, sub, selected. Series colors: accent, amber, success, rose, onSurface2 in that order (`color: "var(--amber)"` per item).
- button {label, action, variant: primary|ghost|small|small-ghost, disabled, full} · chip {label, on, action}
- toggle {bind, label?} (switch) · check {bind, label?} (checkbox) · slider {bind, min, max, step} · stepper {bind, min, max, step} (its buttons get testids `<id>-minus`, `<id>-plus`)
- input {bind, inputType: number|text, placeholder} · select {bind, options: [{value,label}]} · progress {value: 0..1, color}
- list {items, template, divided?, gap=8} · divider · spacer {size} · timer {running: expr, every: ms, do: action} (invisible ticker)
Root content width is 355px (overlay card) or 379px (canvas). Tests find elements by data-testid: use `id` for the testids the task names.
## Example
```json
{"state":{"d":[{"label":"Mon","value":6},{"label":"Tue","value":8}]},
 "view":{"type":"column","children":[
  {"type":"row","justify":"between","children":[{"type":"text","style":"title","value":"Steps"},
    {"type":"button","id":"add","variant":"small","label":"+1k","action":{"set":{"d.1.value":"=d[1].value+1"}}}]},
  {"type":"chart","kind":"bar","data":"=d","target":7,"unit":"k"}]}}
```
