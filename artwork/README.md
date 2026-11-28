# Lanner artwork

The Lanner mark is a falcon seen from above in a steep dive: a bone-white body and wings (`#F1EBDD`) with a rust head (`#E0703A`). It is made of two flat polygons so it stays crisp at launcher-icon sizes and needs no gradients or animation.

- `lanner-icon.svg`: the full adaptive-icon artwork on its 108-unit canvas, slate background (`#1F2A30`). The mark sits inside the 66-unit safe circle.
- `lanner-mark.svg`: the mark alone, cropped tight, on a transparent background.
- `lanner-wordmark-dark.svg` and `lanner-wordmark-light.svg`: the mark and the name in Archivo Bold (SIL Open Font License), converted to outlines. The dark version uses bone and rust for dark backgrounds; the light version uses slate and a deeper rust (`#C9541F`).

Android draws the launcher icon from vectors: `res/drawable/ic_launcher_foreground.xml`, `res/drawable/ic_launcher_monochrome.xml` (for themed icons) and the `ic_launcher_background` colour. The same polygons are in `hud/BrandMark.kt`, and the wordmark outlines are in `hud/WordmarkArt.kt` and `ui/Wordmark.kt`; change all of them together.

`lanner-boot-card.svg` is the still card behind the built-in boot movies (`app/src/main/assets/steam-startup/`, 2.5 seconds, no motion, silent). The asset file names are unchanged so Steam and existing settings keep finding them.

`boot-animation-source/` is upstream DroidDeck's original boot movie source, kept for reference; the app no longer ships that movie.
