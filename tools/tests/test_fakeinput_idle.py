from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"
MAIN = r"""
#include <cstdint>
#include <cstdio>
#include <cstring>
%s
int main() {
  IdleGate gate;
  uint8_t idle[56] = {}, pressed[56] = {};
  pressed[0] = 0x80;
  uint64_t now = 1000000;
  int sent = 0;
  for (int ms = 0; ms < 1000; ms++, now += 1000)
    sent += gate.send(idle, sizeof(idle), now, true, 3500);
  printf("idle %%d\n", sent);
  printf("press %%d\n", gate.send(pressed, sizeof(pressed), now, true, 3500));
  now += 1000;
  printf("held %%d\n", gate.send(pressed, sizeof(pressed), now, true, 3500));
  now += 1000;
  printf("release %%d\n", gate.send(idle, sizeof(idle), now, true, 3500));
  now += 1000;
  printf("shorter %%d\n", gate.send(idle, 40, now, true, 3500));
  IdleGate off;
  sent = 0;
  for (int ms = 0; ms < 1000; ms++, now += 1000)
    sent += off.send(idle, sizeof(idle), now, false, 3500);
  printf("off %%d\n", sent);
  uint8_t big[80] = {};
  printf("oversize %%d %%d\n", gate.send(big, sizeof(big), now, true, 3500), gate.send(big, sizeof(big), now, true, 3500));
  return 0;
}
"""


@unittest.skipUnless(shutil.which("g++"), "needs g++")
class IdleGateTest(unittest.TestCase):
    def test_unchanged_reports_wait_for_the_device_interval_and_changes_go_at_once(self):
        gate = re.search(r"struct IdleGate \{.*?\n\};\n", SOURCE.read_text(), re.S).group(0)
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "gate.cpp"
            source.write_text(MAIN % gate)
            subprocess.run(["g++", "-std=c++17", "-Wall", "-Werror", "-o", str(Path(tmp) / "gate"), str(source)], check=True)
            out = subprocess.run([str(Path(tmp) / "gate")], capture_output=True, text=True, check=True).stdout.splitlines()
        self.assertEqual(["idle 250", "press 1", "held 0", "release 1", "shorter 1", "off 1000", "oversize 1 1"], out)

    def test_both_report_loops_use_the_gate_under_the_switch_bit(self):
        text = SOURCE.read_text()
        self.assertEqual(2, text.count("low_latency(LATENCY_IDLE_REPORTS)"))
        self.assertIn("LATENCY_IDLE_REPORTS = 4", text)
        writer = (SOURCE.parents[1] / "java/com/droiddeck/launcher/input/FakeInputWriter.java").read_text()
        self.assertIn("LATENCY_IDLE_REPORTS = 4;", writer)


if __name__ == "__main__":
    unittest.main()
