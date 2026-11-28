# Bench results (Pixel 10a)

One row per run. Details and raw numbers are in the linked notes until the bench writes rows itself.

| Date (UTC) | Build | Path | Scenario | fps | p99 ms | Press-to-screen p50/p99 | Free memory at measure | GPU memory | Note |
|---|---|---|---|---|---|---|---|---|---|
| 2026-11-28 | ae32618 | Proton, PanVK | menu | 46.3-47.4 | 30.9-31.6 | not measured | 255-412 MB | ~3.0 GB | `docs/research/mali-10a-baseline-1.md` |
| 2026-11-28 | ae32618 | Proton, PanVK, `pe-align` | menu | 46.5-47.4 | 30.9-32.1 | not measured | 362-550 MB | ~3.0 GB | `docs/research/mali-10a-memory-1.md` |
| 2026-11-28 | ae32618 | Native 134, PanVK | menu | 40.9-44.8 | 29.9-34.4 | not measured | 690-803 MB | 2.9-3.0 GB | `docs/research/mali-10a-native-1.md` |
