import json
from pathlib import Path
import re
import subprocess
import unittest


SERVICE = Path(__file__).resolve().parents[2] / (
    "app/src/main/java/com/droiddeck/launcher/session/SessionService.kt")

VARIANTS = Path(__file__).resolve().parents[1] / "release/variants.txt"
PACKAGES = [line.split()[1] for line in VARIANTS.read_text().splitlines()
            if line.strip() and not line.startswith("#")]


def guest_prefix(package):
    source = SERVICE.read_text()
    before_environment = source.split("val guest = ArrayList<String>()", 1)[1].split(
        "addClientEnvironment(guest,", 1)[0]
    environment = source.split("private fun addClientEnvironment(", 1)[1].split(
        'guest.add("HOME=/root")', 1)[0]
    prefix = []
    for line in (before_environment + environment).splitlines():
        line = line.strip()
        if not line.startswith("guest.add("):
            continue
        expression = line[len("guest.add("):-1]
        match = re.fullmatch(r'("[^"\\]*")', expression)
        if match is None:
            raise AssertionError("Launch prefix changed; update its regression harness: " + line)
        prefix.append(json.loads(match[1]))
    return prefix


class SessionGuestCommandTest(unittest.TestCase):
    def test_env_is_the_executable_for_every_package(self):
        for package in PACKAGES:
            with self.subTest(package=package):
                self.assertEqual(["/usr/bin/env", "-i"], guest_prefix(package)[:2])

    @unittest.skipUnless(Path("/usr/bin/env").is_file(), "needs /usr/bin/env")
    def test_child_starts_with_a_cleared_environment(self):
        for package in PACKAGES:
            with self.subTest(package=package):
                result = subprocess.run(
                    guest_prefix(package), capture_output=True, text=True, check=True,
                    timeout=5, env={"STALE_HOST": "1"})
                self.assertEqual([], result.stdout.splitlines())


if __name__ == "__main__":
    unittest.main()
