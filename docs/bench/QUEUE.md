# Phone queue

Newest decisions at the top. The test director edits this file; tracks append tickets at the bottom.

## Order

1. (running) GPU memory and present A/B at the menu: Steam UI software compositing; PanVK vs Venus vs Mali bridge on native.
2. Build the standard bench (`fight` scenario on a fixed seed, latency cross-check, Perfetto capture) and take the
   first fight baseline: Proton vs native. Includes the native save-write test (fresh backup first).
3. Restore the game's `.pck` (truncate the appended texture build, check against the depot hash) and remove the
   leftover aligned exe copy.
4. Tickets from the tracks, batched into one test build.

## Tickets

(none yet)
