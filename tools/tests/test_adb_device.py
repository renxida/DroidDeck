from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import adb_device


def fake_adb(devices, serials=None, states=None):
    serials = serials or {}
    states = states or {}

    def run(adb, *args, timeout=15):
        if args == ("devices", "-l"):
            out = "List of devices attached\n" + "".join(f"{line}\n" for line in devices)
            return subprocess.CompletedProcess(args, 0, out, "")
        if args[0] == "-s" and args[2:] == ("get-state",):
            state = states.get(args[1])
            return subprocess.CompletedProcess(args, 0 if state else 1, f"{state}\n" if state else "", "")
        if args[0] == "-s" and args[2:4] == ("shell", "getprop"):
            value = serials.get(args[1], {}).get(args[4], "")
            return subprocess.CompletedProcess(args, 0, value + "\n", "")
        raise AssertionError(f"unexpected adb {args}")

    return mock.patch.object(adb_device, "_run", run)


class ResolveSerialTest(unittest.TestCase):
    def setUp(self):
        patcher = mock.patch.dict("os.environ", {}, clear=False)
        patcher.start()
        self.addCleanup(patcher.stop)
        for name in ("ADB_SERIAL", "ANDROID_SERIAL"):
            adb_device.os.environ.pop(name, None)

    def test_one_device_on_usb(self):
        with fake_adb(["R5CT1 device usb:1-1 product:x model:y transport_id:3"], {"R5CT1": {"ro.serialno": "R5CT1"}}):
            self.assertEqual("R5CT1", adb_device.resolve_serial("adb"))

    def test_transports_of_one_device_collapse_preferring_wireless_debugging(self):
        devices = [
            "192.168.1.5:5555 device product:x",
            "adb-R5CT1-abc._adb-tls-connect._tcp device product:x",
            "R5CT1 device usb:1-1",
        ]
        serials = {d.split()[0]: {"ro.serialno": "R5CT1"} for d in devices}
        with fake_adb(devices, serials):
            self.assertEqual("adb-R5CT1-abc._adb-tls-connect._tcp", adb_device.resolve_serial("adb"))

    def test_usb_beats_a_tcp_port_for_the_same_device(self):
        devices = ["10.0.0.2:40000 device", "R5CT1 device usb:1-1"]
        with fake_adb(devices, {d.split()[0]: {"ro.serialno": "R5CT1"} for d in devices}):
            self.assertEqual("R5CT1", adb_device.resolve_serial("adb"))

    def test_boot_serial_identifies_a_device_without_ro_serialno(self):
        devices = ["10.0.0.2:40000 device", "R5CT1 device usb:1-1"]
        serials = {d.split()[0]: {"ro.serialno": "unknown", "ro.boot.serialno": "B1"} for d in devices}
        with fake_adb(devices, serials):
            self.assertEqual("R5CT1", adb_device.resolve_serial("adb"))

    def test_two_devices_are_ambiguous(self):
        devices = ["A1 device usb:1", "B2 device usb:2"]
        with fake_adb(devices, {"A1": {"ro.serialno": "A1"}, "B2": {"ro.serialno": "B2"}}):
            with self.assertRaises(adb_device.DeviceResolutionError) as raised:
                adb_device.resolve_serial("adb")
        self.assertIn("Multiple Android devices", str(raised.exception))

    def test_unauthorized_and_offline_devices_are_ignored(self):
        devices = ["A1 unauthorized usb:1", "B2 offline", "C3 device usb:3"]
        with fake_adb(devices, {"C3": {"ro.serialno": "C3"}}):
            self.assertEqual("C3", adb_device.resolve_serial("adb"))

    def test_no_device(self):
        with fake_adb(["A1 unauthorized usb:1"]):
            with self.assertRaises(adb_device.DeviceResolutionError):
                adb_device.resolve_serial("adb")

    def test_an_explicit_serial_must_be_an_authorized_device(self):
        with fake_adb([], states={"A1": "device", "B2": "unauthorized"}):
            self.assertEqual("A1", adb_device.resolve_serial("adb", "A1"))
            with self.assertRaises(adb_device.DeviceResolutionError):
                adb_device.resolve_serial("adb", "B2")
            with self.assertRaises(adb_device.DeviceResolutionError):
                adb_device.resolve_serial("adb", "C3")

    def test_the_environment_names_the_serial(self):
        with fake_adb(["A1 device", "B2 device"], states={"B2": "device"}), \
                mock.patch.dict("os.environ", {"ANDROID_SERIAL": "B2"}):
            self.assertEqual("B2", adb_device.resolve_serial("adb"))

    def test_a_missing_adb_is_a_resolution_error(self):
        with self.assertRaises(adb_device.DeviceResolutionError):
            adb_device.resolve_serial("/nonexistent/adb-for-test")


if __name__ == "__main__":
    unittest.main()
