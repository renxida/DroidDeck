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
#include "latency_probe.h"

#define MS 1000000LL

static int64_t rows[LP_OUTBOX + 8][LP_FIELDS];

static int drain(void) { return lp_drain(&rows[0][0], LP_OUTBOX + 8); }

int main(void) {
    /* A tap: down and up land before one game frame; the frame is composited into present 7, which the
     * panel shows 20 ms later. */
    lp_input(LP_TOUCH_DOWN, 100 * MS, 104 * MS);
    lp_input(LP_TOUCH_UP, 150 * MS, 152 * MS);
    assert(lp_busy());
    lp_commit(120 * MS);
    lp_presented(125 * MS, 6, 2, 16666667);
    lp_commit(160 * MS);
    lp_presented(162 * MS, 7, 3, 16666667);
    assert(drain() == 0);
    lp_displayed(6, 140 * MS);
    assert(drain() == 1);
    assert(rows[0][0] == LP_TOUCH_DOWN && rows[0][1] == 100 * MS && rows[0][2] == 104 * MS);
    assert(rows[0][3] == 120 * MS && rows[0][4] == 125 * MS && rows[0][5] == 140 * MS);
    assert(rows[0][6] == 6 && rows[0][7] == 2 && rows[0][8] == 16666667);
    lp_displayed(7, 180 * MS);
    assert(drain() == 1);
    assert(rows[0][0] == LP_TOUCH_UP && rows[0][3] == 160 * MS && rows[0][4] == 162 * MS && rows[0][5] == 180 * MS);
    assert(!lp_busy());

    /* No display timing (or the zero-copy layer): the row ends at the present. */
    lp_input(LP_PAD_DOWN, 200 * MS, 201 * MS);
    lp_commit(210 * MS);
    lp_presented(211 * MS, 0, -1, 8333333);
    assert(drain() == 1);
    assert(rows[0][0] == LP_PAD_DOWN && rows[0][4] == 211 * MS && rows[0][5] == 0 && rows[0][7] == -1);

    /* A present without a new game frame leaves the probe waiting. */
    lp_input(LP_PAD_UP, 300 * MS, 300 * MS);
    lp_presented(305 * MS, 0, -1, 8333333);
    assert(drain() == 0 && lp_busy());
    /* A frame the panel never reports, and an input the game never answers, both time out. */
    lp_commit(310 * MS);
    lp_presented(311 * MS, 9, 1, 8333333);
    lp_input(LP_TOUCH_DOWN, 312 * MS, 313 * MS);
    lp_expire(311 * MS + LP_PRESENT_WAIT_NS - 1);
    assert(drain() == 0);
    lp_expire(311 * MS + LP_PRESENT_WAIT_NS + 1);
    assert(drain() == 1);
    assert(rows[0][0] == LP_PAD_UP && rows[0][5] == 0 && rows[0][4] == 311 * MS);
    lp_expire(313 * MS + LP_FRAME_WAIT_NS + 1);
    assert(drain() == 1);
    assert(rows[0][0] == LP_TOUCH_DOWN && rows[0][3] == 0 && rows[0][4] == 0);
    assert(!lp_busy());

    /* A frame committed before the input is not its answer. */
    lp_input(LP_TOUCH_UP, 400 * MS, 401 * MS);
    lp_commit(399 * MS);
    lp_presented(402 * MS, 0, -1, 0);
    assert(drain() == 0);
    lp_commit(405 * MS);
    lp_presented(406 * MS, 0, -1, 0);
    assert(drain() == 1 && rows[0][3] == 405 * MS);

    /* More open inputs than slots: the oldest is written out unanswered. */
    for (int i = 0; i < LP_OPEN + 1; i++) lp_input(LP_TOUCH_DOWN, (500 + i) * MS, (500 + i) * MS);
    assert(drain() == 1 && rows[0][2] == 500 * MS && rows[0][3] == 0);
    lp_expire(1000 * MS + LP_FRAME_WAIT_NS);
    assert(drain() == LP_OPEN);
    assert(lp_dropped() == 0);

    /* A full outbox drops the oldest rows and counts them. */
    for (int i = 0; i < LP_OUTBOX + 3; i++) {
        lp_input(LP_PAD_DOWN, i * MS, i * MS);
        lp_commit(i * MS);
        lp_presented(i * MS, 0, -1, 0);
    }
    assert(lp_dropped() == 3);
    assert(drain() == LP_OUTBOX && rows[0][2] == 3 * MS);
    assert(drain() == 0);

    lp_input(LP_KINDS, 1, 1);
    lp_input(-1, 1, 1);
    assert(!lp_busy());
    return 0;
}
'''


@unittest.skipUnless(sys.platform.startswith('linux'), 'needs a Linux host compiler')
class LatencyProbeTest(unittest.TestCase):
    def test_each_input_follows_its_first_game_frame_to_the_panel(self):
        cc = shutil.which(os.environ.get('CC', 'cc'))
        self.assertIsNotNone(cc, 'A C compiler is required')
        with tempfile.TemporaryDirectory(prefix='droiddeck-latency-') as tmp:
            source = Path(tmp) / 'checks.c'
            binary = Path(tmp) / 'checks'
            source.write_text(CHECKS)
            built = subprocess.run([cc, '-std=c11', '-Wall', '-Wextra', '-Werror', '-UNDEBUG', '-pthread',
                                    '-I', str(SRC), str(source), str(SRC / 'latency_probe.c'), '-o', str(binary)],
                                   capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stdout + built.stderr)
            ran = subprocess.run([str(binary)], capture_output=True, text=True, timeout=10)
            self.assertEqual(ran.returncode, 0, ran.stdout + ran.stderr)


if __name__ == '__main__':
    unittest.main()
