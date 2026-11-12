# Focus outline

Kestrel has no animations, so focus is a static outline drawn on the focused control.
Custom controls use `glideBorder` with their focus interaction state. Material controls use
`Modifier.focusGlide(shape)` before their focus target. Match the control's shape, and keep
selection borders separate from the focus outline. Filled primary buttons use
`hotFillAlpha = 0f` to keep label contrast.

`GlideDialog`, `GlideAlertDialog`, `GlidePopup` and `GlideDropdownMenu` remain as thin wrappers
for app windows; `FocusGlideHost` is a plain container kept so existing call sites compile.
Selected chips and rail items use `SelectionMark`, a static tint behind the selected item.

Touch dismiss backdrops and controller shortcut hints should have `canFocus = false`. Web
content, Android system UI and native game input surfaces keep their own focus behaviour.
