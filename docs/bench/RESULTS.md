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

Notes on the rows: `fight-native-bridge-1` used the first input method (a `content call` per press, which
costs the game a slow frame each time) and is the native write test, not a frame-time reference.
`fight-native-bridge-cap120` ran with the game's own cap at 120 instead of 60. `fight-native-bridge-prof` had
the engine's sampling profiler on. Aborted runs write no row: plain PanVK on the native engine failed twice in
the fight before the one row here (`docs/research/mali-10a-fight-1.md`, section 3.3).

| Date (UTC) | Build | Path | Scenario | fps | p99 ms | Fight-animation fps | Fight-animation p99 ms | Over 33 / 50 ms per min (animation) | Press-to-screen p50/p99 | Free memory at measure | GPU memory | Note |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 2026-11-28 | 21a1157 | Native, bridge | fight | 59.4 | 29.8 | 59.2 | 30.2 | 30.2 / 10.1 | 85.6 / 254.9 ms (n=31) | 1644 MB | 1306 MB + 868 MB dma-buf | `fight-native-bridge-1` |
| 2026-11-28 | 21a1157 | Native, bridge | fight | 58.9 | 35.5 | 58.7 | 36.1 | 49.4 / 10.7 | 91.3 / 187.6 ms (n=36) | 1633 MB | 1278 MB + 883 MB dma-buf | `fight-native-bridge-2` |
| 2026-11-28 | 21a1157 | Proton, bridge | fight | 52.4 | 110.1 | 51.5 | 119.3 | 147.3 / 76.3 | 94.0 / 123.3 ms (n=60) | 745 MB | 1262 MB + 881 MB dma-buf | `fight-proton-bridge-1` |
| 2026-11-28 | 21a1157 | Native, panvk | fight | 35.4 | 46.2 | 35.2 | 49.1 | 214.3 / 17.4 | 114.3 / 134.9 ms (n=60) | 728 MB | 2670 MB + 415 MB dma-buf | `fight-native-panvk-2` |
| 2026-11-28 | 21a1157 | Proton, panvk | fight | 37.7 | 65.4 | 37.0 | 68.0 | 198.1 / 64.2 | 111.2 / 625.6 ms (n=69) | 208 MB | 2532 MB + 429 MB dma-buf | `fight-proton-panvk-1` |
| 2026-11-28 | 21a1157 | Native, bridge | fight | 58.8 | 36.6 | 58.6 | 40.0 | 53.5 / 17.4 | 88.4 / 448.8 ms (n=61) | 1644 MB | 1287 MB + 907 MB dma-buf | `fight-native-bridge-3` |
| 2026-11-28 | 21a1157 | Native, panvk, `vk-count` | fight | 37.0 | 43.8 | 37.0 | 45.9 | 211.4 / 17.4 | 112.5 / 585.2 ms (n=61) | 523 MB | 2922 MB + 423 MB dma-buf | `fight-native-panvk-vkcount` |
| 2026-11-28 | 21a1157 | Native, panvk, `vk-count`, `present-dmabuf` | fight | 59.8 | 29.8 | 59.7 | 30.5 | 30.7 / 5.3 | 81.4 / 97.2 ms (n=60) | 755 MB | 2624 MB + 446 MB dma-buf | `fight-native-panvk-dmabuf` |
| 2026-11-28 | 21a1157 | Native, bridge | menu | 60.0 | 20.8 | n/a | n/a | n/a | 82.6 / 108.0 ms (n=50) | 1616 MB | 1239 MB + 1176 MB dma-buf | `menu-native-bridge-1` |
| 2026-11-28 | 21a1157 | Proton, bridge | menu | 60.0 | 20.9 | n/a | n/a | n/a | 80.3 / 902.1 ms (n=54) | 741 MB | 1192 MB + 936 MB dma-buf | `menu-proton-bridge-1` |
| 2026-11-28 | 21a1157 | Native, panvk, `present-dmabuf` | fight | 59.7 | 29.8 | 59.7 | 30.5 | 26.8 / 9.4 | 83.4 / 203.1 ms (n=60) | 383 MB | 3125 MB + 457 MB dma-buf | `fight-native-panvk-dmabuf-2` |
| 2026-11-28 | 21a1157 | Native, venus | fight | 51.5 | 30.1 | 50.7 | 30.1 | 22.0 / 6.6 | 81.0 / 107.8 ms (n=60) | 1149 MB | 226 MB + 2437 MB dma-buf | `fight-native-venus-1` |
| 2026-11-28 | 21a1157 | Native, panvk, `present-dmabuf` | fight | 59.7 | 28.7 | 59.6 | 29.6 | 29.5 / 6.7 | 84.5 / 100.5 ms (n=60) | 522 MB | 2988 MB + 391 MB dma-buf | `fight-native-panvk-dmabuf-3` |
| 2026-11-28 | 21a1157 | Native, bridge | fight | 85.9 | 26.4 | 85.7 | 28.2 | 28.0 / 9.3 | 74.7 / 95.2 ms (n=67) | 1584 MB | 1301 MB + 866 MB dma-buf | `fight-native-bridge-cap120` |
| 2026-11-28 | 21a1157 | Native, bridge, `vk-blocks-64`, `DROIDDECK_NATIVE_PROF=1000`, `DROIDDECK_NATIVE_PROF_HITCH_MS=33` | fight | 59.1 | 33.6 | 59.0 | 35.6 | 40.1 / 13.4 | 90.3 / 112.9 ms (n=60) | 1777 MB | 1178 MB + 856 MB dma-buf | `fight-native-bridge-prof` |
| 2026-11-28 | c56cc02 | Native, bridge | fight | 58.8 | 34.6 | 58.5 | 35.7 | 49.4 / 8.0 | 85.8 / 134.3 ms (n=50) | 1319 MB | 1171 MB + 1234 MB dma-buf | `lab2-fight-native-bridge-1` |
| 2026-11-28 | c56cc02 | Native, bridge | fight | 58.6 | 38.1 | 58.4 | 40.1 | 54.9 / 12.0 | 89.8 / 116.6 ms (n=50) | 1627 MB | 1126 MB + 983 MB dma-buf | `lab2-fight-native-bridge-2` |
| 2026-11-28 | c56cc02 | Proton, bridge | fight | 52.5 | 114.2 | 51.2 | 122.2 | 161.7 / 85.6 | 94.8 / 149.4 ms (n=50) | 475 MB | 1072 MB + 1002 MB dma-buf | `lab2-fight-proton-bridge-1` |
| 2026-11-28 | c56cc02 | Native, panvk, `present-dmabuf` | fight | 59.7 | 29.0 | 59.7 | 30.5 | 32.1 / 8.0 | 83.3 / 101.5 ms (n=50) | 520 MB | 2810 MB + 448 MB dma-buf | `lab2-fight-native-panvk-dmabuf-1` |
| 2026-11-28 | c56cc02 | Native, bridge | menu | 60.0 | 21.3 | n/a | n/a | n/a | 81.1 / 132.7 ms (n=50) | 1795 MB | 1155 MB + 999 MB dma-buf | `lab2-menu-native-bridge-1` |
