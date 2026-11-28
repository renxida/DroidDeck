#include "latency_probe.h"

#include <pthread.h>
#include <string.h>

struct probe {
    int used;
    int kind;
    int64_t event_ns, inject_ns, commit_ns, present_ns, display_ns;
    uint32_t present_id;
    int swap_pending;
    int64_t refresh_ns;
};

static struct probe g_open[LP_OPEN];
static pthread_mutex_t g_out_lock = PTHREAD_MUTEX_INITIALIZER;
static int64_t g_out[LP_OUTBOX][LP_FIELDS];
static unsigned g_out_head, g_out_count, g_dropped;

static void finish(struct probe *p) {
    pthread_mutex_lock(&g_out_lock);
    if (g_out_count == LP_OUTBOX) {
        g_out_head = (g_out_head + 1) % LP_OUTBOX;
        g_out_count--;
        g_dropped++;
    }
    int64_t *row = g_out[(g_out_head + g_out_count) % LP_OUTBOX];
    row[0] = p->kind;
    row[1] = p->event_ns;
    row[2] = p->inject_ns;
    row[3] = p->commit_ns;
    row[4] = p->present_ns;
    row[5] = p->display_ns;
    row[6] = p->present_id;
    row[7] = p->swap_pending;
    row[8] = p->refresh_ns;
    g_out_count++;
    pthread_mutex_unlock(&g_out_lock);
    memset(p, 0, sizeof(*p));
}

void lp_input(int kind, int64_t event_ns, int64_t inject_ns) {
    if (kind < 0 || kind >= LP_KINDS) return;
    struct probe *slot = NULL;
    for (int i = 0; i < LP_OPEN && !slot; i++)
        if (!g_open[i].used) slot = &g_open[i];
    if (!slot) {
        slot = &g_open[0];
        for (int i = 1; i < LP_OPEN; i++)
            if (g_open[i].inject_ns < slot->inject_ns) slot = &g_open[i];
        finish(slot);
    }
    slot->used = 1;
    slot->kind = kind;
    slot->event_ns = event_ns;
    slot->inject_ns = inject_ns;
    slot->swap_pending = -1;
}

void lp_commit(int64_t now_ns) {
    for (int i = 0; i < LP_OPEN; i++) {
        struct probe *p = &g_open[i];
        if (p->used && !p->commit_ns && p->inject_ns <= now_ns) p->commit_ns = now_ns;
    }
}

void lp_presented(int64_t now_ns, uint32_t present_id, int swap_pending, int64_t refresh_ns) {
    for (int i = 0; i < LP_OPEN; i++) {
        struct probe *p = &g_open[i];
        if (!p->used || !p->commit_ns || p->present_ns) continue;
        p->present_ns = now_ns;
        p->present_id = present_id;
        p->swap_pending = swap_pending;
        p->refresh_ns = refresh_ns;
        if (!present_id) finish(p);
    }
}

void lp_displayed(uint32_t present_id, int64_t display_ns) {
    if (!present_id) return;
    for (int i = 0; i < LP_OPEN; i++) {
        struct probe *p = &g_open[i];
        if (p->used && p->present_ns && p->present_id == present_id) {
            p->display_ns = display_ns;
            finish(p);
        }
    }
}

int lp_busy(void) {
    for (int i = 0; i < LP_OPEN; i++)
        if (g_open[i].used) return 1;
    return 0;
}

void lp_expire(int64_t now_ns) {
    for (int i = 0; i < LP_OPEN; i++) {
        struct probe *p = &g_open[i];
        if (!p->used) continue;
        if (p->present_ns ? now_ns - p->present_ns > LP_PRESENT_WAIT_NS : now_ns - p->inject_ns > LP_FRAME_WAIT_NS)
            finish(p);
    }
}

int lp_drain(int64_t *out, int max_rows) {
    int n = 0;
    pthread_mutex_lock(&g_out_lock);
    while (n < max_rows && g_out_count) {
        memcpy(out + (size_t)n * LP_FIELDS, g_out[g_out_head], sizeof(g_out[0]));
        g_out_head = (g_out_head + 1) % LP_OUTBOX;
        g_out_count--;
        n++;
    }
    pthread_mutex_unlock(&g_out_lock);
    return n;
}

unsigned lp_dropped(void) {
    pthread_mutex_lock(&g_out_lock);
    unsigned n = g_dropped;
    pthread_mutex_unlock(&g_out_lock);
    return n;
}
