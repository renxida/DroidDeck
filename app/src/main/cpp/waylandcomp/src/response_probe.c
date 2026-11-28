#include "response_probe.h"

#include <pthread.h>
#include <stdatomic.h>
#include <string.h>

static struct {
    int x, y, w, h;
    int frames;
    int readable;
    struct rp_signature last;
    int have_last;
    int deltas[RP_NOISE_FRAMES];
    int delta_at;
} g_region;

static struct {
    int used;
    int kind;
    int64_t event_ns, inject_ns, first_ns, change_ns, present_ns;
    int frames;
    uint32_t present_id;
    int64_t refresh_ns;
    int noise, delta;
    struct rp_signature reference;
} g_probe;

static pthread_mutex_t g_out_lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t g_out[RP_OUTBOX][RP_FIELDS];
static unsigned g_out_head, g_out_count;
static atomic_int g_state_frames, g_state_noise, g_state_readable;

int rp_signature(const uint8_t *pixels, int stride, int width, int height, int x, int y, int w, int h,
                 struct rp_signature *out) {
    if (!pixels || width <= 0 || height <= 0 || stride < width * 4) return 0;
    int64_t x0 = (int64_t)width * x / 10000, y0 = (int64_t)height * y / 10000;
    int64_t x1 = (int64_t)width * (x + w) / 10000, y1 = (int64_t)height * (y + h) / 10000;
    if (x0 < 0) x0 = 0;
    if (y0 < 0) y0 = 0;
    if (x1 > width) x1 = width;
    if (y1 > height) y1 = height;
    if (x1 - x0 < RP_GRID || y1 - y0 < RP_GRID) return 0;
    for (int cy = 0; cy < RP_GRID; cy++) {
        for (int cx = 0; cx < RP_GRID; cx++) {
            const int64_t left = x0 + (x1 - x0) * cx / RP_GRID, right = x0 + (x1 - x0) * (cx + 1) / RP_GRID;
            const int64_t top = y0 + (y1 - y0) * cy / RP_GRID, bottom = y0 + (y1 - y0) * (cy + 1) / RP_GRID;
            int64_t sum = 0;
            int n = 0;
            for (int sy = 0; sy < RP_SAMPLES; sy++) {
                const int64_t py = top + (bottom - top) * (2 * sy + 1) / (2 * RP_SAMPLES);
                const uint8_t *row = pixels + (size_t)py * (size_t)stride;
                for (int sx = 0; sx < RP_SAMPLES; sx++) {
                    const int64_t px = left + (right - left) * (2 * sx + 1) / (2 * RP_SAMPLES);
                    const uint8_t *p = row + (size_t)px * 4;
                    sum += p[0] + p[1] + p[2];
                    n++;
                }
            }
            out->cell[cy * RP_GRID + cx] = (int32_t)(sum / n);
        }
    }
    return 1;
}

int rp_difference(const struct rp_signature *a, const struct rp_signature *b) {
    int most = 0;
    for (int i = 0; i < RP_CELLS; i++) {
        int d = a->cell[i] - b->cell[i];
        if (d < 0) d = -d;
        if (d > most) most = d;
    }
    return most;
}

int rp_threshold(int noise) {
    const int t = noise * 4;
    return t > RP_MIN_DELTA ? t : RP_MIN_DELTA;
}

static int noise_now(void) {
    int most = 0;
    for (int i = 0; i < RP_NOISE_FRAMES; i++)
        if (g_region.deltas[i] > most) most = g_region.deltas[i];
    return most;
}

static void publish_state(void) {
    atomic_store(&g_state_frames, g_region.frames);
    atomic_store(&g_state_noise, noise_now());
    atomic_store(&g_state_readable, g_region.readable);
}

static void finish_at(int status, int64_t display_ns) {
    pthread_mutex_lock(&g_out_lock);
    if (g_out_count == RP_OUTBOX) {
        g_out_head = (g_out_head + 1) % RP_OUTBOX;
        g_out_count--;
    }
    int64_t *row = g_out[(g_out_head + g_out_count) % RP_OUTBOX];
    row[0] = g_probe.kind;
    row[1] = g_probe.event_ns;
    row[2] = g_probe.inject_ns;
    row[3] = g_probe.first_ns;
    row[4] = g_probe.change_ns;
    row[5] = g_probe.frames;
    row[6] = g_probe.present_ns;
    row[7] = display_ns;
    row[8] = g_probe.present_id;
    row[9] = g_probe.refresh_ns;
    row[10] = g_probe.noise;
    row[11] = g_probe.delta;
    row[12] = status;
    g_out_count++;
    pthread_mutex_unlock(&g_out_lock);
    memset(&g_probe, 0, sizeof(g_probe));
}

static void finish(int status) { finish_at(status, 0); }

void rp_arm(int x, int y, int w, int h) {
    if (g_probe.used) finish(RP_SUPERSEDED);
    memset(&g_region, 0, sizeof(g_region));
    if (w > 0 && h > 0) {
        g_region.x = x;
        g_region.y = y;
        g_region.w = w;
        g_region.h = h;
    }
    publish_state();
}

int rp_armed(void) { return g_region.w > 0; }

int rp_waiting(void) { return g_probe.used && !g_probe.change_ns; }

void rp_input(int kind, int64_t event_ns, int64_t inject_ns) {
    if (!rp_armed()) return;
    if (g_probe.used) finish(RP_SUPERSEDED);
    g_probe.used = 1;
    g_probe.kind = kind;
    g_probe.event_ns = event_ns;
    g_probe.inject_ns = inject_ns;
    g_probe.noise = noise_now();
    if (!g_region.readable && g_region.frames) {
        finish(RP_UNREADABLE);
        return;
    }
    if (!g_region.have_last || g_region.frames < RP_MIN_FRAMES) {
        finish(RP_NOT_CALIBRATED);
        return;
    }
    g_probe.reference = g_region.last;
}

void rp_frame(const uint8_t *pixels, int stride, int width, int height, int64_t now_ns) {
    if (!rp_armed()) return;
    struct rp_signature sig;
    const int ok = rp_signature(pixels, stride, width, height, g_region.x, g_region.y, g_region.w, g_region.h, &sig);
    g_region.frames++;
    g_region.readable = ok;
    if (g_probe.used && !g_probe.change_ns && now_ns >= g_probe.inject_ns) {
        if (!g_probe.first_ns) g_probe.first_ns = now_ns;
        g_probe.frames++;
        if (!ok) {
            finish(RP_UNREADABLE);
        } else {
            const int delta = rp_difference(&g_probe.reference, &sig);
            if (delta > rp_threshold(g_probe.noise)) {
                g_probe.change_ns = now_ns;
                g_probe.delta = delta;
            }
        }
    } else if (ok && g_region.have_last && !g_probe.used) {
        g_region.deltas[g_region.delta_at] = rp_difference(&g_region.last, &sig);
        g_region.delta_at = (g_region.delta_at + 1) % RP_NOISE_FRAMES;
    }
    if (ok) {
        g_region.last = sig;
        g_region.have_last = 1;
    } else {
        g_region.have_last = 0;
    }
    publish_state();
}

void rp_presented(int64_t now_ns, uint32_t present_id, int64_t refresh_ns) {
    if (!g_probe.used || !g_probe.change_ns || g_probe.present_ns) return;
    g_probe.present_ns = now_ns;
    g_probe.present_id = present_id;
    g_probe.refresh_ns = refresh_ns;
    if (!present_id) finish(RP_OK);
}

void rp_displayed(uint32_t present_id, int64_t display_ns) {
    if (!present_id || !g_probe.used || !g_probe.present_ns || g_probe.present_id != present_id) return;
    finish_at(RP_OK, display_ns);
}

int rp_busy(void) { return g_probe.used; }

void rp_expire(int64_t now_ns) {
    if (!g_probe.used) return;
    if (g_probe.present_ns) {
        if (now_ns - g_probe.present_ns > RP_PRESENT_WAIT_NS) finish(RP_OK);
    } else if (g_probe.change_ns) {
        if (now_ns - g_probe.change_ns > RP_PRESENT_WAIT_NS) finish(RP_NO_PRESENT);
    } else if (now_ns - g_probe.inject_ns > RP_CHANGE_WAIT_NS) {
        finish(RP_NO_CHANGE);
    }
}

int rp_drain(int64_t *out, int max_rows) {
    int n = 0;
    pthread_mutex_lock(&g_out_lock);
    while (n < max_rows && g_out_count) {
        memcpy(out + (size_t)n * RP_FIELDS, g_out[g_out_head], sizeof(g_out[0]));
        g_out_head = (g_out_head + 1) % RP_OUTBOX;
        g_out_count--;
        n++;
    }
    pthread_mutex_unlock(&g_out_lock);
    return n;
}

void rp_state(int *frames, int *noise, int *readable) {
    if (frames) *frames = atomic_load(&g_state_frames);
    if (noise) *noise = atomic_load(&g_state_noise);
    if (readable) *readable = atomic_load(&g_state_readable);
}
