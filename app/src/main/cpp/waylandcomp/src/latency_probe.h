#pragma once
#include <stdint.h>

/*
 * Input-to-screen probes for latency.csv. Each touch or pad button the app hands to the guest follows
 * one frame: the first new game frame the compositor receives after it, the composited frame that
 * carries it to Android and, when the screen swapchain reports it (VK_GOOGLE_display_timing), the time
 * that frame reached the panel. The first game frame after an input is a lower bound on the game's
 * reaction: it may have been started before the input arrived, or be a cursor-only repaint.
 *
 * Everything but lp_drain runs on the compositor thread.
 */

enum { LP_TOUCH_DOWN, LP_TOUCH_UP, LP_PAD_DOWN, LP_PAD_UP, LP_KINDS };

/* One drained row: kind, event, inject, commit, present, display (CLOCK_MONOTONIC ns, 0 = never),
 * present id, swapchain presents not yet reported shown (-1 unknown), refresh period ns. */
#define LP_FIELDS 9

#define LP_OPEN 32
#define LP_OUTBOX 256
#define LP_PRESENT_WAIT_NS 250000000LL
#define LP_FRAME_WAIT_NS 2000000000LL

void lp_input(int kind, int64_t event_ns, int64_t inject_ns);
void lp_commit(int64_t now_ns);
void lp_presented(int64_t now_ns, uint32_t present_id, int swap_pending, int64_t refresh_ns);
void lp_displayed(uint32_t present_id, int64_t display_ns);
/* Some probe still waits for its frame or for that frame's display time. */
int lp_busy(void);
/* Moves finished probes, and ones that waited too long, to the outbox. */
void lp_expire(int64_t now_ns);
/* Copies up to max_rows finished rows (LP_FIELDS each) into out; thread-safe. */
int lp_drain(int64_t *out, int max_rows);
unsigned lp_dropped(void);
