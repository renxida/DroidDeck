## Theme (theme.css is preloaded; colors switch light/dark automatically)
Vars: --bg --surface --surface2 --onSurface --onSurface2 --onSurface3 --outline --accent --accentSoft --success --danger --amber --rose --onAccent (text on accent).
Text: .title (17px semibold) .sub (13px secondary) .muted (12px tertiary) .big (30px number) .num (tabular digits)
Layout: .stack (column, gap 12) .row (flex row, gap 8) .spread (row, space-between) .grow .grid2 .grid3 .divider .list (children separated by lines)
Bits: .card (surface2 panel, radius 16, padding 14) .chip / .chip.on .btn / .btn.ghost / .btn.small .badge .dot .progress>i (set the i width %)
Inputs: input[type=range|number|text] and select are pre-styled; input.toggle = switch (checkbox); input.check = checkbox.
The root is a 16px-padded surface: 355px content width in the overlay card, 379px in the full-screen canvas. No fixed widths over 355px. Touch targets at least 32px. System font. Don't hardcode colors; use the vars.
Tests find elements by `data-testid`; put the testids the task names on the right elements.
