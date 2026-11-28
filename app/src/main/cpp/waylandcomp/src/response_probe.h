#pragma once
#include <stdint.h>

/*
 * Input-to-response probe (droiddeckctl latency). latency_probe.c follows the first game frame after an
 * input, which may have been drawn before the game read it. This one watches a region of the game's
 * frames and follows the first frame whose content there differs from the frame before the input: the
 * frame that shows the response.
 *
 * A frame's region is reduced to an 8x8 grid of mean colour sums. While no input is open, the largest
 * difference between consecutive frames over the last RP_NOISE_FRAMES frames is the region's own noise
 * (an animated background); a frame counts as changed when it differs from the reference by more than
 * rp_threshold(noise). The reference is the last frame before the input was injected.
 *
 * Everything but rp_drain runs on the compositor thread.
 */

#define RP_GRID 8
#define RP_CELLS (RP_GRID * RP_GRID)
#define RP_SAMPLES 8
#define RP_NOISE_FRAMES 30
#define RP_MIN_FRAMES 5
#define RP_MIN_DELTA 12
#define RP_CHANGE_WAIT_NS 1000000000LL
#define RP_PRESENT_WAIT_NS 250000000LL
#define RP_OUTBOX 256

enum { RP_OK, RP_NO_CHANGE, RP_UNREADABLE, RP_NOT_CALIBRATED, RP_SUPERSEDED, RP_NO_PRESENT };

/* One drained row (CLOCK_MONOTONIC ns, 0 = never): kind, event, inject, first frame after the input,
 * changed frame, frames from the input to the changed one, present, display, present id, refresh period,
 * noise, difference of the changed frame, status. */
#define RP_FIELDS 13

struct rp_signature { int32_t cell[RP_CELLS]; };

/* 32-bit pixels, any byte order: the sum of the three low bytes' neighbours is order-independent enough
 * for a difference. Region in 1/10000 of the frame. Returns 0 when the region is empty. */
int rp_signature(const uint8_t *pixels, int stride, int width, int height, int x, int y, int w, int h,
                 struct rp_signature *out);
int rp_difference(const struct rp_signature *a, const struct rp_signature *b);
int rp_threshold(int noise);

/* Region in 1/10000 of the game frame; w or h of 0 disarms. */
void rp_arm(int x, int y, int w, int h);
int rp_armed(void);
/* An input is open and waits for its changed frame: the caller may wait for the frame to be readable. */
int rp_waiting(void);
void rp_input(int kind, int64_t event_ns, int64_t inject_ns);
/* A new game frame; pixels NULL when it cannot be read. */
void rp_frame(const uint8_t *pixels, int stride, int width, int height, int64_t now_ns);
void rp_presented(int64_t now_ns, uint32_t present_id, int64_t refresh_ns);
void rp_displayed(uint32_t present_id, int64_t display_ns);
int rp_busy(void);
void rp_expire(int64_t now_ns);
int rp_drain(int64_t *out, int max_rows);
/* Frames seen and the noise since arming, for the caller's "ready" check; thread-safe. */
void rp_state(int *frames, int *noise, int *readable);
