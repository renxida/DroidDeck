"""The session's keep-alive contract with Android (docs/research/crash-catalogue.md, section K).

Android 14 makes a foreground service without a declared type a SecurityException for apps that
target API 34+, and Android 15 time-limits the dataSync and mediaProcessing types for API 35+.
The session runs for hours, so raising targetSdkVersion must come with a type that has no limit.
"""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
TIME_LIMITED = {"dataSync", "mediaProcessing", "shortService"}


def target_sdk():
    return int(re.search(r"targetSdkVersion\s+(\d+)", (ROOT / "app/build.gradle").read_text()).group(1))


def manifest():
    return ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()


def session_service(root):
    for service in root.iter("service"):
        if service.get(ANDROID + "name") == ".session.SessionService":
            return service
    raise AssertionError("SessionService is not declared")


class SessionKeepAliveTest(unittest.TestCase):
    def test_session_service_survives_its_task_and_holds_a_wake_lock(self):
        root = manifest()
        self.assertEqual(session_service(root).get(ANDROID + "stopWithTask"), "false")
        permissions = {p.get(ANDROID + "name") for p in root.iter("uses-permission")}
        self.assertIn("android.permission.WAKE_LOCK", permissions)
        self.assertIn("android.permission.FOREGROUND_SERVICE", permissions)

    def test_raising_target_sdk_declares_an_unlimited_foreground_service_type(self):
        root = manifest()
        declared = session_service(root).get(ANDROID + "foregroundServiceType")
        if target_sdk() < 34:
            return
        self.assertTrue(declared, "API 34+ needs android:foregroundServiceType on SessionService")
        types = set(declared.split("|"))
        self.assertFalse(types & TIME_LIMITED, "a session must not use a time-limited type: %s" % declared)
        permissions = {p.get(ANDROID + "name") for p in root.iter("uses-permission")}
        for kind in types:
            name = "android.permission.FOREGROUND_SERVICE_" + re.sub(r"(?<!^)([A-Z])", r"_\1", kind).upper()
            self.assertIn(name, permissions)


if __name__ == "__main__":
    unittest.main()
