# Bench results (Pixel 10a)

Two tables: the earlier campaigns' rows, then the rows `tools/bench/run.py` appends (one per completed run;
columns and method in `tools/bench/README.md`, raw artifacts under the bench output root in a folder named as
the Note column says).

Earlier campaigns (menu only), from the linked notes:

| Date (UTC) | Build | Path | Scenario | fps | p99 ms | Press-to-screen p50/p99 | Free memory at measure | GPU memory | Note |
|---|---|---|---|---|---|---|---|---|---|
| 2026-11-28 | ae32618 | Proton, PanVK | menu | 46.3-47.4 | 30.9-31.6 | not measured | 255-412 MB | ~3.0 GB | `docs/research/mali-10a-baseline-1.md` |
| 2026-11-28 | ae32618 | Proton, PanVK, `pe-align` | menu | 46.5-47.4 | 30.9-32.1 | not measured | 362-550 MB | ~3.0 GB | `docs/research/mali-10a-memory-1.md` |
| 2026-11-28 | ae32618 | Native 134, PanVK | menu | 40.9-44.8 | 29.9-34.4 | not measured | 690-803 MB | 2.9-3.0 GB | `docs/research/mali-10a-native-1.md` |

## Standard bench rows

"fps" and "p99" are over the whole measured window; the fight-animation columns are over the animation
windows only. Free memory is the mean MemAvailable over the window; GPU memory is `Gpu:` plus the dma-buf heap.

| Date (UTC) | Build | Path | Scenario | fps | p99 ms | Fight-animation fps | Fight-animation p99 ms | Over 33 / 50 ms per min (animation) | Press-to-screen p50/p99 | Free memory at measure | GPU memory | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-28 | 21a1157 | Native, bridge | fight | 59.4 | 29.8 | 59.2 | 30.2 | 30.2 / 10.1 | 85.6 / 254.9 ms (n=31) | 1644 MB | 1306 MB + 868 MB dma-buf | `fight-native-bridge-1` |
| 2026-11-28 | 21a1157 | Native, bridge | fight | 58.9 | 35.5 | 58.7 | 36.1 | 49.4 / 10.7 | 91.3 / 187.6 ms (n=36) | 1633 MB | 1278 MB + 883 MB dma-buf | `fight-native-bridge-2` |
