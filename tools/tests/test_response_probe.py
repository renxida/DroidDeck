from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/cpp/waylandcomp/src"

CHECKS = r'''
#include <assert.h>
#include <stdint.h>
#include <string.h>
#include "response_probe.h"

#define MS 1000000LL
#define W 320
#define H 180

static uint8_t frame[H][W * 4];
static int64_t rows[RP_OUTBOX][RP_FIELDS];

/* A grey frame with a moving band in the top half (an animated background) and, when lit, a highlight box
 * in the bottom half (a focused menu item). */
static void draw(int tick, int lit) {
    for (int y = 0; y < H; y++)
        for (int x = 0; x < W; x++) {
            uint8_t v = 60;
            if (y < H / 2 && (x + tick * 7) % 64 < 8) v = 200;
            if (lit && y >= 120 && y < 150 && x >= 100 && x < 220) v = 230;
            memset(&frame[y][x * 4], v, 3);
            frame[y][x * 4 + 3] = 255;
        }
}
static void show(int tick, int lit, int64_t now) { draw(tick, lit); rp_frame(&frame[0][0], W * 4, W, H, now); }
static int drain(void) { return rp_drain(&rows[0][0], RP_OUTBOX); }

int main(void) {
    struct rp_signature a, b;
    draw(0, 0);
    assert(rp_signature(&frame[0][0], W * 4, W, H, 0, 5000, 10000, 5000, &a));
    draw(3, 0);
    assert(rp_signature(&frame[0][0], W * 4, W, H, 0, 5000, 10000, 5000, &b));
    assert(rp_difference(&a, &b) == 0);
    draw(3, 1);
    assert(rp_signature(&frame[0][0], W * 4, W, H, 0, 5000, 10000, 5000, &b));
    assert(rp_difference(&a, &b) > rp_threshold(0));
    assert(!rp_signature(&frame[0][0], W * 4, W, H, 0, 0, 100, 100, &b));
    assert(!rp_signature(NULL, W * 4, W, H, 0, 0, 10000, 10000, &b));
    assert(rp_threshold(0) == RP_MIN_DELTA && rp_threshold(10) == 40);

    /* Not armed: inputs and frames are ignored. */
    rp_input(2, 1 * MS, 1 * MS);
    show(0, 0, 2 * MS);
    assert(!rp_busy() && drain() == 0);

    /* Armed on the static bottom half: an input before enough frames is refused. */
    rp_arm(0, 5000, 10000, 5000);
    assert(rp_armed());
    show(0, 0, 10 * MS);
    rp_input(2, 11 * MS, 12 * MS);
    assert(drain() == 1 && rows[0][12] == RP_NOT_CALIBRATED);

    int tick = 1;
    int64_t t = 20 * MS;
    for (; tick < 12; tick++, t += 8 * MS) show(tick, 0, t);
    int frames, noise, readable;
    rp_state(&frames, &noise, &readable);
    assert(frames == 12 && noise == 0 && readable == 1);

    /* The game answers on the third frame after the input; that frame is followed to the panel. */
    const int64_t inject = t + 1 * MS;
    rp_input(2, t, inject);
    assert(rp_busy() && rp_waiting());
    show(tick++, 0, inject + 5 * MS);
    rp_presented(inject + 6 * MS, 40, 8333333);
    show(tick++, 0, inject + 13 * MS);
    assert(rp_waiting());
    show(tick++, 1, inject + 21 * MS);
    assert(rp_busy() && !rp_waiting());
    rp_presented(inject + 22 * MS, 42, 8333333);
    rp_displayed(41, inject + 25 * MS);
    assert(drain() == 0);
    rp_displayed(42, inject + 33 * MS);
    assert(drain() == 1 && !rp_busy());
    assert(rows[0][0] == 2 && rows[0][1] == t && rows[0][2] == inject);
    assert(rows[0][3] == inject + 5 * MS && rows[0][4] == inject + 21 * MS && rows[0][5] == 3);
    assert(rows[0][6] == inject + 22 * MS && rows[0][7] == inject + 33 * MS && rows[0][8] == 42);
    assert(rows[0][9] == 8333333 && rows[0][10] == 0 && rows[0][11] > RP_MIN_DELTA && rows[0][12] == RP_OK);

    /* The highlight is now the picture before the next input; turning it off is a change too. Without
     * display timing the row ends at the present. */
    t = inject + 40 * MS;
    for (int i = 0; i < 6; i++, t += 8 * MS) show(tick++, 1, t);
    rp_input(3, t, t);
    show(tick++, 0, t + 9 * MS);
    rp_presented(t + 10 * MS, 0, 8333333);
    assert(drain() == 1 && rows[0][5] == 1 && rows[0][7] == 0 && rows[0][12] == RP_OK);

    /* An input the game does not answer times out; a present that is never reported ends at the present. */
    t += 100 * MS;
    rp_input(2, t, t);
    show(tick++, 0, t + 8 * MS);
    rp_expire(t + RP_CHANGE_WAIT_NS - 1);
    assert(drain() == 0);
    rp_expire(t + RP_CHANGE_WAIT_NS + 1);
    assert(drain() == 1 && rows[0][12] == RP_NO_CHANGE && rows[0][3] == t + 8 * MS && rows[0][4] == 0);
    t += 2000 * MS;
    rp_input(2, t, t);
    show(tick++, 1, t + 8 * MS);
    rp_presented(t + 9 * MS, 77, 8333333);
    rp_expire(t + 9 * MS + RP_PRESENT_WAIT_NS + 1);
    assert(drain() == 1 && rows[0][12] == RP_OK && rows[0][6] == t + 9 * MS && rows[0][7] == 0);

    /* A second input while one is open closes the first. */
    t += 1000 * MS;
    for (int i = 0; i < 6; i++, t += 8 * MS) show(tick++, 1, t);
    rp_input(2, t, t);
    rp_input(3, t + 1 * MS, t + 1 * MS);
    assert(drain() == 1 && rows[0][12] == RP_SUPERSEDED);
    show(tick++, 0, t + 9 * MS);
    rp_presented(t + 10 * MS, 0, 0);
    assert(drain() == 1 && rows[0][0] == 3 && rows[0][12] == RP_OK);

    /* A region over the animation: its own frame-to-frame difference raises the threshold above what the
     * animation does, so the moving band alone is not taken for a response. */
    rp_arm(0, 0, 10000, 5000);
    t += 100 * MS;
    for (int i = 0; i < RP_NOISE_FRAMES + 2; i++, t += 8 * MS) show(tick++, 0, t);
    rp_state(&frames, &noise, &readable);
    assert(noise > 0);
    rp_input(2, t, t);
    for (int i = 0; i < 20; i++) show(tick++, 0, t + (i + 1) * 8 * MS);
    rp_expire(t + RP_CHANGE_WAIT_NS + 1);
    assert(drain() == 1 && rows[0][12] == RP_NO_CHANGE && rows[0][10] == noise);

    /* Frames that cannot be read (a compressed or multi-plane buffer). */
    rp_arm(0, 5000, 10000, 5000);
    for (int i = 0; i < 8; i++) rp_frame(NULL, 0, 0, 0, t + i * MS);
    rp_input(2, t + 10 * MS, t + 10 * MS);
    assert(drain() == 1 && rows[0][12] == RP_UNREADABLE);

    rp_arm(0, 0, 0, 0);
    assert(!rp_armed());
    return 0;
}
'''


@unittest.skipUnless(sys.platform.startswith('linux'), 'needs a Linux host compiler')
class ResponseProbeTest(unittest.TestCase):
    def test_an_input_follows_the_first_frame_that_changes_in_the_watched_region(self):
        cc = shutil.which(os.environ.get('CC', 'cc'))
        self.assertIsNotNone(cc, 'A C compiler is required')
        with tempfile.TemporaryDirectory(prefix='droiddeck-response-') as tmp:
            source = Path(tmp) / 'checks.c'
            source.write_text(CHECKS)
            binary = Path(tmp) / 'checks'
            built = subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror', '-UNDEBUG', '-pthread', '-I', str(SRC),
                                    str(source), str(SRC / 'response_probe.c'), '-o', str(binary)],
                                   capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(binary)], capture_output=True, text=True, timeout=60)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == '__main__':
    unittest.main()
