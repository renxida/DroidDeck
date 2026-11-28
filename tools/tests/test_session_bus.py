import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SESSION = Path(__file__).resolve().parents[1] / "linuxfs/overlay/usr/local/bin/droiddeck-session"

FAKE_DAEMON = """#!/usr/bin/env python3
import socket, sys, time
open(sys.argv[0] + ".args", "a").write(" ".join(sys.argv[1:]) + "\\n")
address = next((a for a in sys.argv if a.startswith("--address=")), None)
if address:
    s = socket.socket(socket.AF_UNIX)
    s.bind(address.split("unix:path=", 1)[1])
time.sleep(5)
"""


class SessionBusTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root, True)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.runtime = self.root / "run"
        self.runtime.mkdir()
        self.launch = self.root / "launch"
        self.launch.mkdir()
        for name, text in (("dbus-daemon", FAKE_DAEMON), ("droiddeck-netmanager", "#!/bin/sh\n")):
            program = self.bin / name
            program.write_text(text)
            program.chmod(0o755)

    def run_block(self, env_address=""):
        text = SESSION.read_text()
        start = re.search(r'^start_steam_buses\(\) \{\n.*?^\}\n', text, re.M | re.S)
        wait = re.search(r'^ *if \[ -n "\$\{bl_session_bus:-\}" \]; then\n.*?^    fi\n', text, re.M | re.S)
        self.assertIsNotNone(start)
        self.assertIsNotNone(wait)
        script = ("nap() { sleep \"$1\"; }\nwrite_system_bus_config() { :; }\n" + start.group(0) + "start_steam_buses\n"
                  + wait.group(0) + 'echo "ADDRESS=$DBUS_SESSION_BUS_ADDRESS"\n'
                  + 'if [ -n "$bl_login_manager" ]; then\n'
                  + '  for i in $(seq 50); do grep -qs system-bus.conf "%s" && break; sleep 0.1; done\nfi\n' % (self.bin / "dbus-daemon.args")
                  + 'kill $(jobs -p) 2>/dev/null\n')
        env = {"PATH": "%s:/usr/bin:/bin" % self.bin, "XDG_RUNTIME_DIR": str(self.runtime), "BL_LAUNCH_DIR": str(self.launch)}
        if env_address:
            env["DBUS_SESSION_BUS_ADDRESS"] = env_address
        return subprocess.run(["bash", "-c", script], env=env, capture_output=True, text=True, timeout=30).stdout

    def daemons(self):
        return (self.bin / "dbus-daemon.args").read_text().splitlines()

    def test_steam_mode_exports_its_own_session_bus(self):
        out = self.run_block()
        self.assertIn("ADDRESS=unix:path=%s/bus" % self.runtime, out)
        self.assertEqual(1, sum("--session" in line for line in self.daemons()))
        self.assertEqual(1, sum("system-bus.conf" in line for line in self.daemons()))

    def test_an_existing_session_bus_is_kept(self):
        out = self.run_block("unix:path=/somewhere/else")
        self.assertIn("ADDRESS=unix:path=/somewhere/else", out)
        self.assertFalse(any("--session" in line for line in self.daemons()))

    def test_no_bus_starts_without_the_network_stand_in(self):
        (self.bin / "droiddeck-netmanager").unlink()
        out = self.run_block()
        self.assertIn("ADDRESS=\n", out)
        self.assertFalse((self.bin / "dbus-daemon.args").exists())

    def test_buses_start_before_the_client_setup_helpers_and_are_awaited_before_the_client(self):
        text = SESSION.read_text()
        started = text.index('    [ "${DROIDDECK_EXP_ALL:-}" = 1 ] && start_steam_buses\n')
        self.assertLess(started, text.index("step \"registering the app's game library with Steam\""))
        self.assertLess(started, text.index("droiddeck-steam-x64 --default-tool"))
        awaited = text.index('    if [ -n "${bl_login_manager:-}" ]; then')
        self.assertGreater(awaited, text.index("droiddeck-seed-redists"))
        self.assertLess(awaited, text.index("_bl_compat_env=(env -u LD_LIBRARY_PATH"))

    def test_without_the_launch_experiment_the_buses_start_just_before_the_client_as_on_main(self):
        text = SESSION.read_text()
        late = text.index('    [ "${DROIDDECK_EXP_ALL:-}" = 1 ] || start_steam_buses\n')
        self.assertGreater(late, text.index("droiddeck-seed-redists"))
        self.assertEqual(late + len('    [ "${DROIDDECK_EXP_ALL:-}" = 1 ] || start_steam_buses\n'),
                         text.index('    if [ -n "${bl_login_manager:-}" ]; then'))


if __name__ == "__main__":
    unittest.main()
