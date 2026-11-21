# GenUI format eval: what should Claude write on-the-fly phone UIs in?

12 realistic asks × 4 formats = 48 solutions. Each one was checked in light and dark mode for console errors, empty renders, horizontal overflow (387 px card and 411 px canvas), text contrast (sanity floor 2.5:1) and a scripted interaction. Perf was measured in headless Chromium at 411×914 @2.625 with CDP CPU throttling at 4× to approximate a phone.

## Results

| candidate | first-try pass | est. tokens to first working | fix rounds | warm render→paint ms (median / worst) | input→next frame ms | cold load+render ms | runtime KB raw (gz) | doc tokens | visual (1-5) |
|---|---|---|---|---|---|---|---|---|---|
| **preact-htm** | 12/12 | **424** | 0 | 14.7 / 117 | 16.0 | 123 | 32.3 (13.1) | 720 | 3.67 |
| html | 12/12 | 461 | 0 | 13.5 / 118 | 18.4 | 138 | **15.2 (6.0)** | 734 | 3.67 |
| ts (strict, Sucrase on device) | 12/12 | 531 | 0 | 14.8 / 135 | 15.0 | 171 | 216.5 (52.3) | 806 | 3.67 |
| json (schema renderer) | **7/12** | 508 | 5 | 14.0 / 104 | 17.5 | 108 | 24.6 (9.0) | 724 | 3.50 |

How to read the columns:

- **Tokens** is the solution size in characters ÷ 3.5, plus any characters added by fixes.
- **Warm** means the runtime was already loaded and JIT-warmed, which is how the phone will run it. It's the median of 7 renders. "Worst" is the slowest of all renders and shows up in every format; it comes from occasional GC/raster spikes, not from the format.
- **Input→next frame** is the slowest step of each task's interaction, averaged over the 12 tasks.
- **Cold** is a fresh page load with the payload rendered at load time.

More measurements:

- **Runtime init cost (cold, 4×):** html 17 ms, preact 18 ms, json 15 ms, ts 46 ms. The ts number is the 200 KB Sucrase bundle.
- **TS transform per solution:** 5.1 ms in the page at 4×. On this machine, esbuild takes 1.25 ms and Sucrase 0.38 ms, so roughly 5 ms and 1.5 ms on a phone.
- **Long tasks (over 50 ms):** 1 in total, in ts.

## Recommendation: **preact-htm**

1. **It's the cheapest for Claude to write and lost nothing on correctness.** It was shortest in 11 of 12 tasks, 8% fewer tokens than html and 20% fewer than ts, and passed 12/12 first try. The view is a function of state, so there is no querySelector plumbing and no "forgot to update that label" bugs. That class of bug grows with UI size, which these small tasks don't stress.
2. **The user feels no difference between the web formats.** Warm render is about 14 ms and tap-to-frame is about 15–18 ms at 4× for every format, which is one frame. The 17 KB extra over html is nothing once the runtime is preloaded. So user speed doesn't decide this; Claude's speed and reliability do.

**Runner-up: html.** It's effectively tied: 12/12, the same perf, and the smallest runtime. It costs about 9% more tokens and leaves state syncing to the author.

**Why not the other two:**

- **json** failed 5 of 12 on the first try. Four were the same syntax slip (`"={{…}}"`) and one was a low-contrast glyph. It can't express layout it has no primitive for: the calendar needed hand-computed pixel offsets (3.8k chars, the largest solution) and the timer had no ring. It is the most compact format for simple form-like cards (convert, timer, checklist and quiz were 40–50% smaller), so it is worth keeping as a later native fast path, not as the main format.
- **ts** caught 0 bugs with `tsc --strict` across 12 solutions. It costs 25% more tokens than preact, and on-device transpiling adds 200 KB and about 30 ms of cold init. If types are wanted, strip them server-side (esbuild, about 1 ms) and ship html.

## Blind rerun (Nov 21): four fresh authors, one per format, DOC.md and the task prompts only, one write per file, nothing run

| format | first-try pass | tokens | warm render | tap-to-frame | cold | runtime gz |
|---|---|---|---|---|---|---|
| html | 12/12 | 663 | 13.6 ms | 15.4 ms | 124 ms | 6.0 KB |
| preact-htm | 12/12 | 642 | 16.9 ms | 19.9 ms | 134 ms | 13.1 KB |
| ts | 12/12 | 759 | 15.5 ms | 13.6 ms | 196 ms | 52.3 KB |
| json | 9/12 | 726 | 13.8 ms | 15.2 ms | 103 ms | 9.0 KB |

(4x CPU throttle, 411x914 @2.625.) Blind authors close the gap: html and preact-htm tie on reliability and are within 3% on tokens; html renders about 3 ms faster and its runtime is half the size, both far below a 16 ms frame budget. ts costs 18% more tokens and catches nothing that the others missed. json fails blind (3 of 12: an invalid expression, a click target that never rendered, a missing value), confirming the first result.

Decision for the phone: preact-htm stays the default (state and re-render are free, so longer-lived interactive UIs don't need hand-written DOM patching), and the phone canvas also accepts kind html, so Claude can pick either per UI. Shots: shots/_blind/contact-*.png.

## Jetpack Compose
Compose is not viable for on-the-fly generation. Each UI would need kotlinc plus the Compose compiler plugin, D8/dex and class loading. That takes seconds (warm daemon) to minutes (cold Gradle) on a server, and there is no supported on-device Kotlin compiler or interpreter. Shipping dex the device then loads at runtime is also a code-execution and security hole. Where Compose does fit is as the native renderer for a declarative schema like the json candidate here. That skips WebView cold start, so it's worth considering for a small set of high-frequency cards once the schema stabilises.

## Caveats (read before trusting the numbers)

- **One author wrote all 48 solutions.** I had no subagent tool. The candidate order was randomized as html → ts → json → preact-htm, and every first attempt was written before seeing any render (frozen in `solutions/_first/`). The html, ts and preact sets share structure, and their screenshots are near-identical. So the visual scores reflect the shared theme and helpers, not the format.
- **Ordering could flatter preact.** preact was written last, which could favour its token count. Its savings are structural, though: it skips DOM lookup and update code.
- **Perf is approximate.** CPU throttling is not a real phone (GPU and memory differ), and it does not include the cost of creating the Android WebView itself; prewarm one. Cold numbers drift down over a run because of the browser's code cache, so compare them within a column, not as absolutes.
- **Harness fixes after the first html run.** The habits fixture count was wrong in the spec (it said 16/28; the data sums to 15), and the quiz lock test now force-clicks a disabled button. No solution changed for either.

## Files and how to rerun
- `node run.mjs` reruns every check and perf measurement and takes about 2 minutes. It prints the table and writes `results/final.json`, `shots/<cand>/<task>-<light|dark>.png` and `shots/contact-<cand>.png`.
- Flags:
  - `--set first` evaluates the frozen first attempts.
  - `--only html,json` limits the run to some formats.
  - `--tasks tip,quiz` limits the run to some tasks.
  - `--no-cold` and `--no-shots` skip those steps.
  - `--rate 6` changes the CPU throttling rate.
  - `CHROME_DIR` points to the chromium/playwright-core folder.
- To add a format: add a runtime to `build.mjs`, add its extension in `run.mjs` (`EXT`/`WARM`), and put solutions in `solutions/<cand>/`.
- `node report.mjs` rebuilds the table above from `results/`, `gen-log.json` and `visual-scores.json`.
- `node build.mjs` regenerates `runtimes/<name>/` from `src/`. Each runtime folder is a self-contained WebView asset: `host.html` exposes `window.render(source)`, calls `window.onCanvasHeight(px)` (or `Android.onCanvasHeight`), cancels the previous UI's timers on every render, and includes a `DOC.md` that is the brief Claude writes against.
- Tasks, fixtures and interaction scripts are in `tasks/tasks.json`.
