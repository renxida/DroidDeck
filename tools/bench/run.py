#!/usr/bin/env python3
"""The standard bench: one measured run of Slay the Spire 2 on the test phone. See tools/bench/README.md.

    tools/bench/run.py --scenario fight --path native --route bridge --name fight-native-bridge-1
"""
from __future__ import annotations

import argparse
import base64
import copy
import datetime
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import benchlib  # noqa: E402

REPO = HERE.parents[1]
PACKAGE = os.environ.get("DROIDDECK_PACKAGE", "chat.comb.lanner")
APP_ID = "2868840"
OUT_ROOT = Path(os.environ.get("BENCH_OUT", str(Path.home() / "projects/droiddeck/output/bench")))
OTHER_APPS = ("com.android.chrome", "chat.comb.familiar.voice")
ROUTES = {"panvk": (False, False), "bridge": (True, False), "venus": (False, True)}
MENU_START_S = 240
MENU_WINDOW_S = 120
FIGHT_START_S = 100
XCHECK_FLICK_MS = 50
EARLY_ACCESS_PROCEED = (807, 568)  # session-output coordinates of the notice's only button

PERFETTO_CONFIG = """
buffers { size_kb: 98304 fill_policy: RING_BUFFER }
buffers { size_kb: 4096 fill_policy: RING_BUFFER }
data_sources { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking" ftrace_events: "sched/sched_wakeup"
  ftrace_events: "power/cpu_frequency" ftrace_events: "power/cpu_idle" ftrace_events: "power/gpu_frequency"
  ftrace_events: "task/task_rename" ftrace_events: "task/task_newtask" ftrace_events: "sched/sched_process_exit"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "input" atrace_categories: "freq"
  atrace_categories: "sched" atrace_categories: "memory"
  atrace_apps: "%(package)s"
  buffer_size_kb: 16384 drain_period_ms: 250 } } }
data_sources { config { name: "linux.process_stats" target_buffer: 1
  process_stats_config { scan_all_processes_on_start: true record_thread_names: true proc_stats_poll_ms: 2000 } } }
data_sources { config { name: "linux.sys_stats" target_buffer: 1 sys_stats_config {
  meminfo_period_ms: 1000 meminfo_counters: MEMINFO_MEM_AVAILABLE meminfo_counters: MEMINFO_MEM_FREE
  meminfo_counters: MEMINFO_SWAP_FREE meminfo_counters: MEMINFO_CACHED meminfo_counters: MEMINFO_ANON_PAGES
  vmstat_period_ms: 1000 vmstat_counters: VMSTAT_PSWPIN vmstat_counters: VMSTAT_PSWPOUT
  vmstat_counters: VMSTAT_PGMAJFAULT stat_period_ms: 1000 stat_counters: STAT_CPU_TIMES } } }
data_sources { config { name: "android.surfaceflinger.frametimeline" } }
duration_ms: %(duration_ms)d
"""


class Abort(Exception):
    pass


class SettingsUnreachable(Abort):
    pass


class Bench:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.serial = args.serial
        self.dir = OUT_ROOT / args.name
        self.notes: list[str] = []
        self.checks: dict = {}
        self.steps: list[dict] = []
        self.marks: dict[str, int] = {}
        self.procs: list[subprocess.Popen] = []
        self.restore: list[tuple[str, str]] = []
        self.route_before: tuple[bool, bool] | None = None
        self.commands = False
        self.shot_index = 0
        self.log_file = None
        self.phases = benchlib.PhaseLog(time.monotonic)

    # ------------------------------------------------------------ plumbing
    def log(self, text: str) -> None:
        line = f"{datetime.datetime.now().strftime('%H:%M:%S')} {text}"
        print(line, flush=True)
        if self.log_file:
            self.log_file.write(line + "\n")
            self.log_file.flush()

    def nap(self, seconds: float, fixed: bool = True) -> None:
        if seconds > 0:
            time.sleep(seconds)
            self.phases.slept(seconds, fixed)

    def phase(self, name: str, rep: int | None = None) -> None:
        self.phases.begin(name, rep)

    def adb(self, *command: str, timeout: float = 120, binary: bool = False):
        result = subprocess.run(["adb", "-s", self.serial, *command], capture_output=True, timeout=timeout)
        return result.stdout if binary else result.stdout.decode(errors="replace")

    def sh(self, script: str, timeout: float = 120) -> str:
        return self.adb("shell", script, timeout=timeout)

    def ctl(self, *command: str, timeout: float = 300, check: bool = False) -> dict:
        env = dict(os.environ, DROIDDECK_PACKAGE=PACKAGE)
        result = subprocess.run([sys.executable, str(REPO / "tools/droiddeckctl"), "--serial", self.serial, *command],
                                capture_output=True, timeout=timeout, env=env)
        try:
            answer = json.loads(result.stdout.decode(errors="replace"))
        except ValueError:
            answer = {"ok": False, "raw": result.stdout.decode(errors="replace")[-400:],
                      "stderr": result.stderr.decode(errors="replace")[-400:]}
        if check and not answer.get("ok"):
            raise Abort(f"droiddeckctl {' '.join(command)} failed: {json.dumps(answer)[:400]}")
        return answer

    def guest(self, script: str, timeout: int = 30) -> str | None:
        if not self.commands:
            return None
        answer = self.ctl("guest", "--timeout", str(timeout), "--", "/bin/sh", "-c", script)
        if not answer.get("ok"):
            return None
        return (answer.get("stdout") or {}).get("text", "")

    def phone_ms(self) -> int:
        return int(self.sh("date +%s%N").strip()) // 1_000_000

    def sync_clock(self) -> None:
        offsets = []
        for _ in range(5):
            before = time.time()
            phone = self.phone_ms()
            after = time.time()
            offsets.append((after - before, phone - (before + after) * 500.0))
        self.clock_offset_ms = min(offsets)[1]

    def now_ms(self) -> int:
        return int(time.time() * 1000 + self.clock_offset_ms)

    def sleep_until(self, phone_ms: int) -> None:
        delay = (phone_ms - self.now_ms()) / 1000.0
        if delay > 0:
            self.nap(delay)

    def pads(self, buttons: list[str], between_ms: int = 0) -> list[tuple[int, int]]:
        """Pad presses through the agent bridge's broadcast receiver (`cmd activity broadcast` starts no
        runtime on the phone, unlike `content call`, so it does not disturb the frames it is timing).
        Returns, per press, the phone's wall clock in ms just before and just after the broadcast was sent;
        the press lands within a few ms of the second."""
        calls = f"/sdcard/Android/data/{PACKAGE}/files/agent-calls"
        parts = []
        ids = []
        for index, button in enumerate(buttons):
            request = base64.b64encode(json.dumps({"type": "pad", "button": button, "action": "tap", "holdMs": 100},
                                                  separators=(",", ":")).encode()).decode()
            self.call_index = getattr(self, "call_index", 0) + 1
            call_id = f"bench{os.getpid()}x{self.call_index}"
            ids.append(call_id)
            if index and between_ms:
                parts.append(f"sleep {between_ms / 1000.0:.3f}")
            parts.append(f"echo T0 $(date +%s%N); cmd activity broadcast -a {PACKAGE}.AGENT_CALL -p {PACKAGE} --es method input "
                         f"--es id {call_id} --es request {request} >/dev/null 2>&1; echo T1 $(date +%s%N)")
        parts.append("sleep 0.3")
        parts += [f"echo A $(cat {calls}/{call_id}.json 2>/dev/null); rm -f {calls}/{call_id}.json" for call_id in ids]
        out = self.sh("; ".join(parts), timeout=60)
        sent = [int(value) // 1_000_000 for value in re.findall(r"^T0 (\d+)", out, re.M)]
        done = [int(value) // 1_000_000 for value in re.findall(r"^T1 (\d+)", out, re.M)]
        answers = re.findall(r"^A (.*)$", out, re.M)
        if len(done) != len(buttons) or len(answers) != len(buttons) or any('"ok":true' not in answer.replace(" ", "") for answer in answers):
            raise Abort(f"pad {buttons} failed: {out[-400:]}")
        return list(zip(sent, done))

    def pad(self, button: str) -> tuple[int, int]:
        return self.pads([button])[0]

    def tap(self, x: int, y: int) -> None:
        self.ctl("input", "tap", str(x), str(y), check=True)

    def screen(self, label: str) -> benchlib.Layout:
        data = self.adb("exec-out", "screencap", binary=True, timeout=60)
        picture = benchlib.Picture.from_screencap(data)
        self.shot_index += 1
        path = self.dir / "shots" / f"{self.shot_index:03d}-{label}.png"
        path.write_bytes(picture.png())
        return benchlib.Layout(picture)

    def background(self, name: str, command: list[str]) -> subprocess.Popen:
        handle = open(self.dir / name, "wb")
        process = subprocess.Popen(command, stdout=handle, stderr=subprocess.DEVNULL)
        self.procs.append(process)
        return process

    def stop_background(self) -> None:
        for process in self.procs:
            if process.poll() is None:
                process.terminate()
        for process in self.procs:
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
        self.procs = []

    # ------------------------------------------------------------ Lanner's own UI
    def ui(self) -> list[dict]:
        answer = self.ctl("ui", "dump", check=True)
        return [node for window in answer.get("windows", []) for node in window.get("nodes", [])]

    def open_settings(self, *tabs: str) -> list[dict]:
        self.ctl("ui", "click", "rail-setup", check=True)
        self.nap(1.0)
        for tab in tabs:
            self.ctl("ui", "click", "--match", tab, "--index", "0", check=True)
            self.nap(1.0)
        return self.ui()

    @staticmethod
    def tag_state(nodes: list[dict], tag: str) -> str | None:
        for node in nodes:
            if node.get("tag") == tag:
                return node.get("state") or node.get("text")
        return None

    def set_switch(self, tag: str, want: str, remember: bool = True) -> None:
        nodes = self.open_settings("Advanced")
        have = self.tag_state(nodes, tag)
        if have is None:
            raise Abort(f"switch {tag} is not in Settings > Advanced on this build")
        if have == want:
            return
        self.ctl("ui", "click", tag, check=True)
        self.nap(1.0)
        now = self.tag_state(self.ui(), tag)
        if now != want:
            raise Abort(f"switch {tag} stayed {now}, wanted {want}")
        if remember:
            self.restore.append((tag, have))
        self.log(f"switch {tag}: {have} -> {want}")

    def route_state(self, nodes: list[dict]) -> tuple[bool, bool]:
        text = " ".join(str(node.get("text") or "") for node in nodes)
        bridge = re.search(r"Mali bridge: (on|off)", text)
        venus = re.search(r"Mali \(Venus\): (on|off)", text)
        if not bridge or not venus:
            raise Abort("the route buttons are not in Settings > Advanced")
        return bridge.group(1) == "on", venus.group(1) == "on"

    ROUTE_OWNER_KEYS = ("maliRouteBy", "bridgeDefaultUndo")

    def route_owner(self) -> dict[str, str] | None:
        if not self.commands:
            return None
        values = self.ctl("prefs", "get", "session").get("values") or {}
        return {key: values[key] for key in self.ROUTE_OWNER_KEYS if isinstance(values.get(key), str)}

    def restore_route_owner(self) -> None:
        """A route button press marks the route as set by hand, and bridge-default then stops managing it
        (BridgeDefault.setByHand). Put the owner marks back after the bench has restored the route itself."""
        before = getattr(self, "route_owner_before", None)
        if not before or self.route_owner() == before:
            return
        for key, value in before.items():
            self.ctl("prefs", "set", "session", key, value, "--type", "string", check=True)
        self.log(f"route owner marks restored: {before}")

    def set_route(self, want: tuple[bool, bool]) -> tuple[bool, bool]:
        have = self.route_state(self.open_settings("Advanced"))
        first = have
        for _ in range(3):
            if have == want:
                break
            if have[1] != want[1]:
                self.ctl("ui", "click", "--match", f"Mali (Venus): {'on' if have[1] else 'off'}", check=True)
            elif have[0] != want[0]:
                self.ctl("ui", "click", "--match", f"Mali bridge: {'on' if have[0] else 'off'}", check=True)
            self.nap(1.0)
            have = self.route_state(self.ui())
        if have != want:
            raise Abort(f"route is bridge={have[0]} venus={have[1]}, wanted bridge={want[0]} venus={want[1]}")
        return first

    # ------------------------------------------------------------ preflight
    def owner_check(self) -> None:
        front = self.sh("dumpsys activity activities | grep -m1 topResumedActivity")
        powered = self.sh("dumpsys battery | grep -E 'powered: true' | wc -l").strip()
        if PACKAGE not in front:
            raise Abort(f"owner pickup? foreground is not Lanner: {front.strip()[:160]}")
        if powered in ("", "0"):
            raise Abort("owner pickup? the phone is not on power")

    def meminfo(self) -> dict[str, float]:
        return benchlib.parse_meminfo(self.sh("cat /proc/meminfo"))

    def lmk_since_last(self) -> dict:
        mark = OUT_ROOT / ".last-run-end"
        since = float(mark.read_text()) if mark.exists() else None
        text = self.adb("logcat", "-d", "-v", "epoch", "-s", "lowmemorykiller:*", timeout=60)
        result = benchlib.lmkd_kills(text, since)
        result["since_epoch"] = since
        return result

    def preflight(self) -> dict:
        args = self.args
        self.phase("preflight")
        self.owner_check()
        state = self.ctl("state", check=True)
        if state["session"]["phase"] != "IDLE":
            raise Abort(f"session is {state['session']['phase']}, not IDLE")
        self.commands = bool(self.ctl("access").get("commands"))
        if (args.env or args.latency_count) and not self.commands:
            raise Abort("Debugging tools are off: needed for --env and the latency probe (tap Setup > Diagnostics on the phone)")
        self.sh("; ".join(f"am force-stop {app}" for app in OTHER_APPS))
        self.sh("cmd media_session volume --stream 3 --set 0")
        volume = re.search(r"volume is (\d+)", self.sh("cmd media_session volume --stream 3 --get"))
        if not volume or volume.group(1) != "0":
            raise Abort("media volume is not 0")

        steam = self.open_settings("Steam")
        offline = self.tag_state(steam, "setting-offline")
        self.ctl("ui", "click", "--match", "Sound", "--index", "0", check=True)
        self.nap(1.0)
        boot_sound = self.tag_state(self.ui(), "setting-boot-movie-sound")
        game_sound = self.tag_state(self.open_settings("Audio"), "setting-game-sound")
        if offline != "On":
            raise Abort(f"Offline mode is {offline}: rule 1, not starting")
        if game_sound != "Off" or boot_sound != "Off":
            raise Abort(f"sound: game sound {game_sound}, boot-movie sound {boot_sound}: rule 2, not starting")

        self.route_owner_before = self.route_owner()
        self.route_before = self.set_route(ROUTES[args.route])
        if self.route_before != ROUTES[args.route]:
            self.log(f"route: bridge={self.route_before[0]} venus={self.route_before[1]} -> {args.route}")
        self.set_switch("setting-exp-native-engine", "On" if args.path == "native" else "Off")
        for tag in args.switch:
            self.set_switch(tag if tag.startswith("setting-") else f"setting-exp-{tag}", "On")
        for tag in args.switch_off:
            self.set_switch(tag if tag.startswith("setting-") else f"setting-exp-{tag}", "Off")
        advanced = self.open_settings("Advanced")
        experiments = {node["tag"][len("setting-exp-"):]: (node.get("state") or node.get("text"))
                       for node in advanced if str(node.get("tag") or "").startswith("setting-exp-")}
        route_now = self.route_state(advanced)
        safe_mode = next((node.get("text") for node in advanced if str(node.get("text") or "").startswith("Safe mode:")), None)

        env_before = None
        if self.commands:
            listing = self.ctl("env", "list")
            env_before = {key: listing.get(key) for key in ("pending", "persistent", "active")}
            if listing.get("pending") or listing.get("persistent"):
                raise Abort(f"env lines are already set: {env_before}")
            for line in args.env:
                self.ctl("env", "set", line, check=True)

        self.phase("memory-wait")
        waited = 0
        memory = self.meminfo()
        while memory["MemAvailable"] < 2560 and waited < 180:
            self.nap(5, fixed=False)
            waited += 5
            memory = self.meminfo()
        self.phase("preflight")
        thermal = self.ctl("thermal")
        battery = self.sh("dumpsys battery")
        level = re.search(r"level: (\d+)", battery)
        pre = {
            "at_phone": self.sh("date '+%F %T %Z'").strip(),
            "offline_setting": offline, "game_sound": game_sound, "boot_movie_sound": boot_sound, "media_volume": 0,
            "debugging_tools": self.commands,
            "mem_available_mb": round(memory["MemAvailable"]), "mem_wait_s": waited,
            "mem_wait_met": memory["MemAvailable"] >= 2560,
            "swap_free_mb": round(memory.get("SwapFree", 0)), "swap_total_mb": round(memory.get("SwapTotal", 0)),
            "gpu_mb": round(memory.get("Gpu", 0)), "zram_mb": round(memory.get("Zram", 0)),
            "battery_c": thermal.get("batteryC"), "thermal_status": thermal.get("status"),
            "battery_level": int(level.group(1)) if level else None,
            "lmkd_since_last_run": self.lmk_since_last(),
            "route": {"bridge": route_now[0], "venus": route_now[1], "safe_mode": safe_mode},
            "experiments": experiments, "env_before": env_before,
            "other_apps_stopped": list(OTHER_APPS),
        }
        self.build = {"label": state.get("build"), "commit": state.get("apkCommit"), "app_version": state.get("appVersion"),
                      "runtime": (state.get("runtime") or {}).get("version")}
        self.log(f"preflight ok: {pre['mem_available_mb']} MB available, swap free {pre['swap_free_mb']}, "
                 f"{pre['battery_c']} C status {pre['thermal_status']}, lmkd since last {pre['lmkd_since_last_run']['kills']}")
        return pre

    # ------------------------------------------------------------ session
    def start_steam(self, pre: dict) -> None:
        self.phase("steam-start")
        self.background("logcat-lmk.txt", ["adb", "-s", self.serial, "logcat", "-v", "epoch", "-T", "1", "-s", "lowmemorykiller:*"])
        started = time.time()
        answer = self.ctl("start", "steam", "--wait", "--timeout", "240", timeout=300)
        if not answer.get("ok"):
            raise Abort(f"start steam failed: {json.dumps(answer)[:300]}")
        pre["start_to_ready_s"] = round(time.time() - started, 1)
        self.session_id = (answer.get("session") or {}).get("id") or self.ctl("state")["session"]["id"]
        self.phase("offline-check")
        icon = 0
        for attempt in range(6):
            self.nap(6, fixed=False)
            layout = self.screen(f"steam-{attempt}")
            icon = layout.steam_offline_icon()
            if icon >= 40:
                (self.dir / "shots" / "steam-offline-icon.png").write_bytes(layout.offline_crop().png())
                break
        pre["offline_icon_pixels"] = icon
        if icon < 40:
            raise Abort("Steam's offline icon is not on screen: rule 1, stopping without launching")
        self.log(f"Steam ready in {pre['start_to_ready_s']} s; offline icon seen ({icon} px)")

    def start_samplers(self) -> None:
        loop = ('while true; do echo "@ $(date +%s)"; '
                'grep -E "^(MemAvailable|MemFree|SwapFree|SwapTotal|Cached|Shmem|AnonPages|Gpu|Zram|ION_heap|ION_heap_pool|Slab|Mapped):" /proc/meminfo; '
                'G=$(dumpsys gpu --gpumem | grep -E "Global|^Proc"); echo "$G"; '
                'for p in $(echo "$G" | sed -n "s/^Proc \\([0-9]*\\) .*/\\1/p"); do echo "P $p $(cat /proc/$p/comm 2>/dev/null | tr \' \' _)"; done; '
                'echo "T $(dumpsys battery | sed -n "s/ *temperature: //p") $(dumpsys thermalservice | sed -n "s/.*Thermal Status: //p" | head -1)"; '
                'echo "F $(cat /sys/class/misc/mali0/device/cur_freq 2>/dev/null) $(cat /sys/devices/system/cpu/cpufreq/policy*/scaling_cur_freq | tr "\\n" " ")"; '
                'sleep 10; done')
        self.background("sampler.txt", ["adb", "-s", self.serial, "shell", loop])

    def launch(self) -> int:
        self.phase("launch-to-menu")
        self.owner_check()
        self.start_samplers()
        self.marks["launch"] = self.phone_ms()
        self.ctl("launch", APP_ID, check=True)
        self.log("game launch requested")
        return self.marks["launch"]

    def wait_menu(self, timeout_s: int = 200) -> benchlib.Layout:
        """Poll screenshots until the main menu is up, answering the game's Early Access notice (its only
        button) when it shows."""
        deadline = time.time() + timeout_s
        prompts = 0
        while time.time() < deadline:
            self.nap(4, fixed=False)
            layout = self.screen("load")
            if layout.early_access_prompt():
                prompts += 1
                self.tap(*EARLY_ACCESS_PROCEED)
                self.log("answered the game's 'What's Early Access?' notice (Proceed)")
                self.checks["early_access_prompts"] = prompts
                self.nap(3)
                continue
            if layout.main_menu():
                self.nap(2)
                layout = self.screen("menu")
                if layout.main_menu() and not layout.early_access_prompt():
                    self.marks["menu"] = self.phone_ms()
                    self.log(f"main menu after {(self.marks['menu'] - self.marks['launch']) / 1000:.0f} s, profile icon {layout.profile_icon()}")
                    return layout
        raise Abort("the main menu did not show (see shots/): does the session draw?")

    def focus_menu(self, where: str) -> int | None:
        """Move the pad focus to the top or the bottom entry of the main menu; returns its reference y."""
        self.pad("down")
        self.nap(0.5)
        last = self.screen("focus").menu_focus_y()
        if last is None:
            self.pad("down")
            self.nap(0.5)
            last = self.screen("focus").menu_focus_y()
        if last is None:
            raise Abort("no focused entry on the main menu after a d-pad press")
        button = "up" if where == "top" else "down"
        for _ in range(8):
            self.pad(button)
            self.nap(0.5)
            now = self.screen("focus").menu_focus_y()
            if now is None:
                raise Abort("lost the main menu while moving the focus")
            moved = now - last if button == "down" else last - now
            if moved <= 2:
                if moved < -2:
                    self.pad("down" if button == "up" else "up")
                    self.nap(0.5)
                    now = self.screen("focus").menu_focus_y()
                return now
            last = now
        raise Abort("the main menu focus never reached the end")

    def latency(self, name: str, region: str, first: str, second: str, double: bool = False) -> dict | None:
        count = self.args.latency_count * (2 if double else 1)
        if not count or not self.commands:
            return None
        answer = self.ctl("latency", "--input", first, "--input", second, "--region", region, "--count", str(count),
                          "--rows", timeout=count * 3 + 180)
        (self.dir / f"latency-{name}.json").write_text(json.dumps(answer, indent=1))
        if not answer.get("ok"):
            self.notes.append(f"latency probe failed: {json.dumps(answer)[:200]}")
            return {"ok": False, "error": answer.get("error") or answer.get("raw")}
        keep = {key: answer.get(key) for key in ("samples", "answered", "statuses", "endsAt", "toScreen", "toChangedFrame",
                                                 "toFirstFrame", "framesToChange", "byInput", "refreshMs", "noise",
                                                 "smallestDelta", "displayTiming", "readable", "refreshHz")}
        keep.update(ok=True, inputs=[first, second], region=region)
        self.log(f"latency {name}: toScreen {keep['toScreen']} ({keep['endsAt']}), statuses {keep['statuses']}")
        return keep

    def xcheck(self, name: str) -> dict | None:
        """The coarse cross-check: record the screen while two real flicks land on the on-screen swipe pad (each
        flick is one d-pad press, the way the game is played by hand), with Android's touch dots shown, and
        read from the recording how long after a dot the picture answered."""
        if not self.args.xcheck:
            return None
        remote = "/sdcard/bench-xcheck.mp4"
        local = self.dir / f"xcheck-{name}.mp4"
        before = self.sh("settings get system show_touches").strip()
        self.sh("settings put system show_touches 1")
        flicks = []
        try:
            recorder = subprocess.Popen(["adb", "-s", self.serial, "shell", f"screenrecord --time-limit 9 --bit-rate 20000000 {remote}"],
                                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            started = time.time()
            for at, (x0, x1) in ((3.0, (220, 470)), (6.0, (470, 220))):
                self.nap(max(0.0, started + at - time.time()))
                flicks.append(time.time() - started)
                self.sh(f"input swipe {x0} 760 {x1} 760 {XCHECK_FLICK_MS}")
            recorder.wait(timeout=30)
            self.nap(1.0)
            subprocess.run(["adb", "-s", self.serial, "pull", remote, str(local)], capture_output=True, timeout=120)
        finally:
            self.sh(f"settings put system show_touches {before if before in ('0', '1') else '0'}; rm -f {remote}")
        try:
            width, height = 606, 270
            probe = subprocess.run(["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries", "frame=pts_time",
                                    "-of", "csv=p=0", str(local)], capture_output=True, timeout=120)
            times = [float(value.strip(",")) for value in probe.stdout.decode().split() if value.strip(",")]
            raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(local), "-fps_mode", "passthrough", "-vf",
                                  f"scale={width}:{height},format=gray", "-f", "rawvideo", "-"], capture_output=True, timeout=300).stdout
            frames = [raw[index * width * height:(index + 1) * width * height] for index in range(len(raw) // (width * height))]
            times = times[:len(frames)]
            touch = benchlib.region_change(frames, width, (40, 170, 135, 210))
            answer = benchlib.region_change(frames, width, (165, 150, 450, 215))
            results = [benchlib.touch_to_picture(times, touch, answer, max(0.0, at - 0.8)) for at in flicks]
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            self.notes.append(f"screen recording cross-check not analysed: {error}")
            return {"video": str(local), "flicks": None}
        self.log(f"screen recording cross-check: {results}")
        return {"video": str(local), "frames": len(frames), "flick_ms": XCHECK_FLICK_MS, "flicks": results,
                "what": "frame where Android's touch dot appears to the first frame where the hand changes; includes the flick "
                        "itself (the pad press is sent when the flick is recognised) and one recorded frame of uncertainty at each end"}

    def perfetto_start(self, seconds: int) -> None:
        config = PERFETTO_CONFIG % {"package": PACKAGE, "duration_ms": seconds * 1000}
        remote = "/data/misc/perfetto-traces/bench.pftrace"
        self.sh(f"rm -f {remote}")
        process = subprocess.Popen(["adb", "-s", self.serial, "shell", f"perfetto --txt -c - -o {remote}"],
                                   stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        process.stdin.write(config.encode())
        process.stdin.close()
        self.perfetto = process

    def perfetto_finish(self) -> str | None:
        process = getattr(self, "perfetto", None)
        if process is None:
            return None
        try:
            process.wait(timeout=90)
        except subprocess.TimeoutExpired:
            process.kill()
        remote = "/data/misc/perfetto-traces/bench.pftrace"
        local = self.dir / "trace.pftrace"
        subprocess.run(["adb", "-s", self.serial, "pull", remote, str(local)], capture_output=True, timeout=300)
        self.sh(f"rm -f {remote}")
        return str(local) if local.exists() and local.stat().st_size > 0 else None

    def top_start(self, seconds: int) -> None:
        iterations = max(3, seconds // 5 + 1)
        self.background("top-threads.txt", ["adb", "-s", self.serial, "shell",
                                            f"top -H -b -d 5 -n {iterations} -m 70 -s 3 -o PID,TID,%CPU,CMD,NAME"])

    def pacing_start(self, seconds: int) -> None:
        def work():
            self.pacing = self.ctl("pacing", "--seconds", str(seconds), timeout=seconds + 120)
        self.pacing = None
        self.pacing_thread = threading.Thread(target=work, daemon=True)
        self.pacing_thread.start()

    def pacing_finish(self) -> dict | None:
        thread = getattr(self, "pacing_thread", None)
        if thread is None:
            return None
        thread.join(timeout=400)
        answer = self.pacing or {}
        (self.dir / "pacing.json").write_text(json.dumps(answer, indent=1))
        if not answer.get("ok"):
            return None
        return {key: answer.get(key) for key in ("fpsOnScreen", "frames", "p50Ms", "p95Ms", "p99Ms", "offCadencePercent", "seconds")}

    def engine_split_count(self) -> int | None:
        if self.args.path != "native":
            return None
        out = self.guest('grep -c "frame split" /root/.local/share/SlayTheSpire2/logs/godot.log')
        try:
            return int(out.strip())
        except (AttributeError, ValueError):
            return None

    # ------------------------------------------------------------ scenarios
    def scenario_menu(self) -> None:
        launch = self.marks["launch"]
        layout = self.wait_menu()
        self.checks["profile_icon"] = layout.profile_icon()
        self.phase("fixed-wait")
        self.sleep_until(launch + MENU_START_S * 1000)
        self.phase("capture-start")
        self.owner_check()
        self.screen("window-start")
        self.marks["splits_start"] = self.engine_split_count()
        self.top_start(MENU_WINDOW_S)
        self.perfetto_start(40)
        self.pacing_start(MENU_WINDOW_S)
        self.phase("menu-window")
        self.marks["window_start"] = self.phone_ms()
        self.nap(MENU_WINDOW_S)
        self.marks["window_end"] = self.phone_ms()
        self.phase("capture-finish")
        self.marks["splits_end"] = self.engine_split_count()
        self.pacing_result = self.pacing_finish()
        self.trace = self.perfetto_finish()
        self.screen("window-end")
        self.thermal_end = self.ctl("thermal")
        self.phase("latency")
        self.focus_menu("top")
        self.screen("latency-region")
        self.latency_result = self.latency("menu", self.args.menu_region, "pad:down", "pad:up")
        self.quit_game()

    def play_script(self, script: dict) -> None:
        """Send the scripted steps, checking the enemy's health bar and the hand where the script names them."""
        for index, step in enumerate(script["steps"]):
            record = {"index": index, "kind": step["kind"], "name": step.get("name"), "settle_ms": step.get("settle_ms", 0),
                      "presses": []}
            for button, (sent, done) in zip(step["pads"], self.pads(step["pads"], step.get("between_ms", 0))):
                record["presses"].append({"button": button, "t_sent_ms": sent, "t_done_ms": done})
            record["t_first_done_ms"] = record["presses"][0]["t_done_ms"]
            record["t_first_sent_ms"] = record["presses"][0]["t_sent_ms"]
            record["t_done_ms"] = record["presses"][-1]["t_done_ms"]
            self.sleep_until(record["t_done_ms"] + record["settle_ms"] + step.get("pause_ms", 0))
            if step.get("expect_enemy_hp") is not None or step.get("expect_hand"):
                layout = self.screen(f"step-{index:02d}-{step['kind']}")
                record["enemy_hp"] = layout.enemy_hp()
                record["hand_glow"] = layout.hand_glow()
                want = step.get("expect_enemy_hp")

                def as_expected() -> bool:
                    if want is not None and (record["enemy_hp"] is None or abs(record["enemy_hp"] - want) > script["hp_tolerance"]):
                        return False
                    return not (step.get("expect_hand") and record["hand_glow"] < script["hand_glow_min"])
                retries = 0
                while not as_expected() and retries < 4:
                    retries += 1
                    self.nap(1.5, fixed=False)
                    layout = self.screen(f"step-{index:02d}-late{retries}")
                    record["enemy_hp"] = layout.enemy_hp()
                    record["hand_glow"] = layout.hand_glow()
                if retries:
                    record["late_checks"] = retries
                    record["settle_ms"] = max(record["settle_ms"], self.now_ms() - record["t_done_ms"])
                if not as_expected():
                    self.steps.append(record)
                    raise Abort(f"step {index} ({step.get('name')}): enemy bar {record['enemy_hp']} (expected {want}), "
                                f"hand glow {record['hand_glow']}")
            self.steps.append(record)
            self.log(f"step {index} {step['kind']} {step.get('name') or ''}: enemy {record.get('enemy_hp')} hand {record.get('hand_glow')}")

    def scenario_fight(self) -> None:
        script = json.loads((HERE / "scenarios" / "fight.json").read_text())
        launch = self.marks["launch"]
        layout = self.wait_menu()
        icon = layout.profile_icon()
        self.checks["profile_icon"] = icon
        if icon != script["profile_icon"]:
            raise Abort(f"the game is on the {icon} profile, not the bench profile ({script['profile_icon']}): not touching it")
        self.phase("fixed-wait")
        self.sleep_until(launch + FIGHT_START_S * 1000)
        self.phase("continue")
        self.owner_check()
        y = self.focus_menu("top")
        low, high = script["continue_focus_y"]
        if not low <= y <= high:
            raise Abort(f"the top menu entry is at y={y}, not where Continue sits ({low}-{high}): no saved bench run on this profile?")
        self.pad("a")
        self.log("Continue pressed")
        self.phase("fight-ready")
        deadline = time.time() + 60
        while True:
            self.nap(3, fixed=False)
            layout = self.screen("combat-wait")
            glow, hp = layout.hand_glow(), layout.enemy_hp()
            if glow >= script["hand_glow_min"] and hp is not None:
                break
            if time.time() > deadline:
                raise Abort(f"the fight did not come up (hand glow {glow}, enemy bar {hp})")
        if abs(hp - script["start_enemy_hp"]) > script["hp_tolerance"]:
            raise Abort(f"the fight is not at its start: enemy bar {hp}, expected {script['start_enemy_hp']}")
        self.log(f"combat ready: enemy bar {hp}, hand glow {glow}")
        self.marks["combat_ready"] = self.phone_ms()
        self.phase("latency")
        self.nap(3)

        self.screen("latency-region")
        self.latency_result = self.latency("fight", self.args.fight_region, "pad:right", "pad:left")
        self.xcheck_result = self.xcheck("fight")
        self.nap(1.0)
        layout = self.screen("after-latency")
        if layout.enemy_hp() is None or abs(layout.enemy_hp() - script["start_enemy_hp"]) > script["hp_tolerance"]:
            raise Abort("the latency presses changed the fight")

        self.phase("capture-start")
        estimate = int(sum(len(step.get("pads", [])) * 0.5 + step.get("settle_ms", 0) / 1000.0 + step.get("pause_ms", 0) / 1000.0 + 1.2
                           for step in script["steps"])) + 6
        self.marks["splits_start"] = self.engine_split_count()
        self.top_start(estimate)
        self.perfetto_start(min(60, estimate))
        self.pacing_start(estimate)
        self.nap(1.5)
        self.phase("fight")
        self.marks["window_start"] = self.phone_ms()
        self.play_script(script)
        self.marks["window_end"] = self.phone_ms()
        self.phase("capture-finish")
        self.marks["splits_end"] = self.engine_split_count()
        self.thermal_end = self.ctl("thermal")
        self.pacing_result = self.pacing_finish()
        self.trace = self.perfetto_finish()
        self.checks["script_completed"] = True

        self.phase("save-and-quit")
        self.pad("start")
        self.nap(1.5)
        paused = self.screen("paused")
        if paused.hand_glow() > 0.003 or paused.main_menu():
            raise Abort("the pause menu did not open")
        for _ in range(3):
            self.pad("down")
            self.nap(0.4)
        self.screen("save-and-quit-focused")
        self.pad("a")
        self.log("Save and Quit pressed")
        deadline = time.time() + 40
        while not self.screen("back-to-menu").main_menu():
            if time.time() > deadline:
                raise Abort("Save and Quit did not return to the main menu")
            self.nap(3, fixed=False)
        self.quit_game()

    def quit_game(self) -> None:
        """Quit through the game's own menu: bottom entry, then Yes."""
        self.phase("quit-game")
        self.focus_menu("bottom")
        self.screen("quit-focused")
        self.pad("a")
        self.nap(2)
        if not self.screen("quit-confirm").dimmed():
            self.notes.append("no quit confirmation seen")
        self.pad("a")
        self.marks["quit"] = self.phone_ms()
        deadline = time.time() + 45
        gone = False
        while time.time() < deadline:
            self.nap(3, fixed=False)
            focus = (self.ctl("state").get("session") or {}).get("focus") or {}
            if str(focus.get("focusedApp")) != APP_ID and int(APP_ID) not in (focus.get("focusableApps") or []):
                gone = True
                break
        self.checks["quit_through_game_menu"] = gone
        if not gone:
            self.notes.append("the game did not exit from its menu within 45 s; used droiddeckctl quit")
            self.ctl("quit", APP_ID, "--timeout", "60")
        self.log(f"game quit through its menu: {gone}")
        self.nap(6)

    # ------------------------------------------------------------ teardown and analysis
    def teardown(self) -> None:
        self.phase("stop-session")
        state = self.ctl("state")
        phase = (state.get("session") or {}).get("phase")
        if phase not in ("IDLE", None):
            focus = (state.get("session") or {}).get("focus") or {}
            if str(focus.get("focusedApp")) == APP_ID:
                self.ctl("quit", APP_ID, "--timeout", "60")
                self.nap(8)
            self.ctl("stop", "--timeout", "120", timeout=180)
            self.ctl("wait", "idle", "--timeout", "120", timeout=180)
        self.stop_background()
        self.phase("log-pull")
        logs = self.dir / "logs"
        if not logs.exists():
            self.ctl("logs", "latest", str(logs), timeout=300)
        self.phase("restore-config")
        if self.commands:
            self.ctl("env", "clear", "all")
            listing = self.ctl("env", "list")
            self.checks["env_lines_after"] = {key: listing.get(key) for key in ("pending", "persistent")}
        if not self.args.keep_config:
            try:
                for tag, state_before in reversed(self.restore):
                    self.set_switch(tag, state_before, remember=False)
                if self.route_before is not None:
                    self.set_route(self.route_before)
                    self.restore_route_owner()
                self.checks["config_restored"] = True
            except Abort as error:
                self.checks["config_restored"] = False
                self.notes.append(f"could not restore switches: {error}")
        (OUT_ROOT / ".last-run-end").write_text(str(time.time() + self.clock_offset_ms / 1000.0))

    def find_log(self, name: str) -> Path | None:
        found = sorted(glob.glob(str(self.dir / "logs" / "**" / name), recursive=True))
        return Path(found[0]) if found else None

    def find_by_header(self, prefix: str) -> Path | None:
        for path in glob.glob(str(self.dir / "logs" / "*.csv")):
            with open(path, errors="replace") as handle:
                if handle.readline().startswith(prefix):
                    return Path(path)
        return None

    def find_containing(self, needle: str) -> Path | None:
        for path in sorted(glob.glob(str(self.dir / "logs" / "**" / "*"), recursive=True)):
            if os.path.isfile(path) and os.path.getsize(path) < 64 * 1024 * 1024:
                with open(path, errors="replace") as handle:
                    if needle in handle.read():
                        return Path(path)
        return None

    def summarize(self, pre: dict, failure: str | None) -> dict:
        args = self.args
        start, end = self.marks.get("window_start"), self.marks.get("window_end")
        read = lambda path: path.read_text(errors="replace") if path and path.exists() else ""
        frames = benchlib.parse_frames(read(self.find_log("frames.csv")))
        frame_block: dict = {"overall": benchlib.frame_stats([], 0)}
        if args.scenario == "fight":
            frame_block["animation"] = benchlib.frame_stats([], 0)
        if start and end:
            frame_block["overall"] = benchlib.window_stats(frames, [(start, end)])
            if args.scenario == "fight":
                frame_block["animation"] = benchlib.window_stats(frames, benchlib.animation_windows(self.steps))
                frame_block["card_plays"] = benchlib.window_stats(frames, benchlib.animation_windows(self.steps, ("card",)))
                frame_block["enemy_turns"] = benchlib.window_stats(frames, benchlib.animation_windows(self.steps, ("end_turn",)))
                frame_block["windows"] = benchlib.merge_windows(benchlib.animation_windows(self.steps))

        events = []
        for line in read(self.find_log("events.jsonl")).splitlines():
            try:
                events.append(json.loads(line))
            except ValueError:
                continue
        engine_events = [event for event in events if event.get("event") == "game.engine"]
        engine = {"path": engine_events[-1].get("path") if engine_events else None,
                  "build": engine_events[-1].get("build") if engine_events else None,
                  "native_skipped": engine_events[-1].get("native_skipped") if engine_events else None}
        report = read(self.find_log("graphics-report.txt"))
        route_line = re.search(r"^path: (.*)$", report, re.M)
        engine["graphics_path"] = route_line.group(1) if route_line else None
        session_log = read(self.find_log("session.log"))
        native_log_path = self.find_containing("native_compat: frame split") if args.path == "native" else None
        native_log = read(native_log_path)
        device = re.search(r"Using Device #\d+: (.*)", native_log)
        engine["vulkan_device"] = device.group(1).strip() if device else None
        experiments_line = re.search(r"experiments: (.*)", session_log)
        engine["session_experiments"] = experiments_line.group(1)[:400] if experiments_line else None
        if args.path == "native":
            count_start, count_end = self.marks.get("splits_start"), self.marks.get("splits_end")
            last = (count_end - count_start) if count_start is not None and count_end is not None else None
            if last and count_end:
                rows_text = "\n".join(re.findall(r".*frame split.*", native_log)[:count_end])
                engine["own_counter_window"] = benchlib.parse_frame_splits(rows_text, last=last)
            engine["game_log"] = benchlib.count_game_log(native_log)
            profiler = [line for line in native_log.splitlines() if "native_compat: prof" in line]
            if profiler:
                (self.dir / "native-profiler.txt").write_text("\n".join(profiler))
                engine["profiler_lines"] = len(profiler)
        vkcount = [line.strip()[:600] for line in (session_log + "\n" + native_log).splitlines() if "droiddeck-vkcount:" in line]
        if vkcount:
            (self.dir / "vkcount.txt").write_text("\n".join(vkcount))
            presents = [float(value) for value in re.findall(r"present ([\d.]+)", "\n".join(vkcount[-8:]))]
            engine["vkcount"] = {"lines": len(vkcount), "swapchain": [line for line in vkcount if "swapchain" in line][-1:],
                                 "present_ms_last_lines": presents, "last": vkcount[-1]}

        samples = benchlib.parse_sampler(read(self.dir / "sampler.txt"))
        memory: dict = {"window": benchlib.sampler_summary(samples, start, end) if start and end else {}}
        launch = self.marks.get("launch")
        if launch:
            after = [sample["meminfo_mb"].get("MemAvailable") for sample in samples if sample["t_ms"] >= launch]
            after = [value for value in after if value is not None]
            memory["available_min_after_launch_mb"] = round(min(after)) if after else None
        memory_rows = benchlib.parse_csv(read(self.find_log("memory.csv")))
        if start and end and memory_rows:
            memory["session_csv_window"] = benchlib.csv_mean(memory_rows, start, end, ("available_mb", "swap_used_mb", "game_mb",
                                                                                     "game_swap_mb", "droiddeck_mb", "app_mb", "gpu_mb"))
        memory["lmk_risk"] = [{key: event.get(key) for key in ("t", "short", "swapFreeMb", "availableMb")}
                              for event in events if event.get("event") == "memory.lmk_risk"]
        temps = [(float(a), int(b)) for a, b in re.findall(r"^T (\d+) (\d+)", read(self.dir / "sampler.txt"), re.M)]
        gpu_khz = [int(value) for value in re.findall(r"^F (\d+)", read(self.dir / "sampler.txt"), re.M)]

        cpu: dict = {}
        process_rows = benchlib.parse_csv(read(self.find_by_header("t_wall_ms,pid,name,side")))
        thread_rows = benchlib.parse_csv(read(self.find_by_header("t_wall_ms,pid,tid,process,name")))
        if start and end:
            cpu["session_processes"] = benchlib.csv_cpu(process_rows, start, end, ("name", "side"), top=16)
            cpu["session_threads"] = benchlib.csv_cpu([row for row in thread_rows if row.get("name") != "*"], start, end,
                                                      ("process", "name"), top=30)
        top_rows = benchlib.parse_top(read(self.dir / "top-threads.txt"))
        cpu["top_processes"] = {name: value for name, value in benchlib.top_by_process(top_rows).items() if value >= 0.5}
        cpu["top_threads"] = top_rows[:40]

        lmk_text = read(self.dir / "logcat-lmk.txt")
        thermal_end = getattr(self, "thermal_end", None) or {}
        summary = {
            "schema": benchlib.SCHEMA_VERSION,
            "name": args.name,
            "started_utc": self.started_utc,
            "scenario": args.scenario, "path": args.path, "route": args.route,
            "switches": list(args.switch) + [f"-{tag}" for tag in args.switch_off], "env": list(args.env),
            "ok": failure is None, "failure": failure,
            "build": getattr(self, "build", {}),
            "session_id": getattr(self, "session_id", None),
            "engine": engine,
            "preflight": pre,
            "marks_ms": self.marks,
            "frames": frame_block,
            "pacing": getattr(self, "pacing_result", None),
            "latency": {"probe": getattr(self, "latency_result", None), "screenrecord": getattr(self, "xcheck_result", None)},
            "memory": memory,
            "cpu": cpu,
            "thermal": {"start": {"battery_c": pre.get("battery_c"), "status": pre.get("thermal_status")},
                        "end": {"battery_c": thermal_end.get("batteryC"), "status": thermal_end.get("status"),
                                "capped": [name for name, value in (thermal_end.get("cpu") or {}).items() if value.get("capped")]},
                        "battery_c_max": max((value[0] for value in temps), default=None) and max(value[0] for value in temps) / 10.0,
                        "status_max": max((value[1] for value in temps), default=None),
                        "gpu_mhz_mean": round(sum(gpu_khz) / len(gpu_khz) / 1000) if gpu_khz else None},
            "lmkd": {"run": benchlib.lmkd_kills(lmk_text), "since_last_run": pre.get("lmkd_since_last_run")},
            "steps": self.steps,
            "artifacts": {"dir": str(self.dir), "trace": getattr(self, "trace", None),
                          "native_log": str(native_log_path) if native_log_path else None},
            "checks": self.checks,
            "notes": self.notes,
        }
        return summary

    # ------------------------------------------------------------ run
    def run(self) -> int:
        if self.dir.exists() and any(self.dir.iterdir()):
            print(f"{self.dir} already has files: pick another --name", file=sys.stderr)
            return 2
        (self.dir / "shots").mkdir(parents=True)
        self.log_file = open(self.dir / "run.log", "w")
        self.started_utc = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.sync_clock()
        pre: dict = {}
        failure = None
        try:
            pre = self.preflight()
            self.start_steam(pre)
            self.launch()
            if self.args.scenario == "menu":
                self.scenario_menu()
            else:
                self.scenario_fight()
        except Abort as error:
            failure = str(error)
            self.log(f"ABORT: {failure}")
            try:
                self.screen("abort")
            except Exception:
                pass
        except Exception as error:  # the phone must still be put back
            failure = f"{type(error).__name__}: {error}"
            self.log(f"ERROR: {failure}")
        try:
            self.teardown()
        except Exception as error:
            self.notes.append(f"teardown: {type(error).__name__}: {error}")
            self.stop_background()
        self.phase("analysis")
        summary = self.summarize(pre, failure)
        summary["phases"] = self.phases.block()
        problems = benchlib.validate_summary(summary) + benchlib.validate_phases(summary["phases"])
        if problems:
            summary["notes"].append(f"schema problems: {problems}")
        (self.dir / "summary.json").write_text(json.dumps(summary, indent=1))
        if failure is None and not self.args.no_results_row:
            results = REPO / "docs/bench/RESULTS.md"
            with open(results, "a") as handle:
                handle.write(benchlib.results_row(summary) + "\n")
        overall = summary["frames"]["overall"]
        animation = summary["frames"].get("animation") or {}
        self.log(f"done: ok={failure is None} overall fps {overall.get('fps')} p99 {overall.get('p99')} | animation fps "
                 f"{animation.get('fps')} p99 {animation.get('p99')} | engine {summary['engine'].get('path')} | {self.dir}/summary.json")
        return 0 if failure is None else 1


SCREEN_MARKER = OUT_ROOT / ".screen-session.json"
GAME_LOG = "/root/.local/share/SlayTheSpire2/logs/godot.log"


class ScreenBench(Bench):
    """--mode screen: Continue, the scripted fight, Save and Quit, N times in one game launch, in a session and
    a game that stay up between arms and invocations when the configuration asked for allows it."""

    def __init__(self, args: argparse.Namespace):
        super().__init__(args)
        self.script = json.loads((HERE / "scenarios" / "fight.json").read_text())
        self.script["steps"] = benchlib.truncate_steps(self.script["steps"], args.turns)
        self.reps: list[dict] = []
        self.reuse: dict = {}
        self.pushed: list[dict] = []
        self.experiments: dict = {}
        self.launches = 0
        self.rep_in_launch = 0
        self.launched_local: str | None = None
        self.sampling = False
        self.lmk_stream = False
        self.offline: dict | None = None
        self.restored_files: list[dict] = []
        self.ours = False

    # ------------------------------------------------------------ what is asked for, what is running
    def tag(self, name: str) -> str:
        return name if name.startswith("setting-") else f"setting-exp-{name}"

    def wanted(self) -> dict:
        args = self.args
        switches = {"native-engine": "On" if args.path == "native" else "Off"}
        switches.update({benchlib.switch_id(self.tag(name)): "On" for name in args.switch})
        switches.update({benchlib.switch_id(self.tag(name)): "Off" for name in args.switch_off})
        pushes = {}
        for spec in ([] if args.restore_pushed else args.push_file):
            local, guest = benchlib.parse_push(spec)
            pushes[guest] = hashlib.sha256(Path(local).read_bytes()).hexdigest()
        return {"path": args.path, "route": args.route, "switches": switches, "env": list(args.env), "pushes": pushes}

    def load_marker(self) -> dict | None:
        try:
            return json.loads(SCREEN_MARKER.read_text())
        except (OSError, ValueError):
            return None

    def save_marker(self, pre: dict) -> None:
        baseline: dict[str, str] = {}
        for tag, state in self.restore:
            baseline.setdefault(tag, state)
        marker = {
            "written_utc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), "by": self.args.name,
            "session_id": getattr(self, "session_id", None), "build_commit": getattr(self, "build", {}).get("commit"),
            "path": self.args.path, "route": self.args.route, "env": list(self.args.env), "switches": self.experiments,
            "baseline": baseline, "route_before": list(self.route_before) if self.route_before else None,
            "route_owner_before": getattr(self, "route_owner_before", None),
            "pushes": {item["guest_path"]: item["sha256_pushed"] for item in self.pushed}, "pushed": self.pushed,
            "launches": self.launches, "rep_in_launch": self.rep_in_launch, "launched_local": self.launched_local,
            "preflight": pre,
        }
        SCREEN_MARKER.write_text(json.dumps(marker, indent=1))

    def adopt(self, marker: dict) -> None:
        self.restore = [(tag, state) for tag, state in (marker.get("baseline") or {}).items()]
        self.route_before = tuple(marker["route_before"]) if marker.get("route_before") else None
        self.route_owner_before = marker.get("route_owner_before")
        self.pushed = list(marker.get("pushed") or [])
        self.experiments = dict(marker.get("switches") or {})
        self.launches = int(marker.get("launches") or 0)
        self.rep_in_launch = int(marker.get("rep_in_launch") or 0)
        self.launched_local = marker.get("launched_local")

    def game_running(self, state: dict | None = None) -> bool:
        focus = ((state or self.ctl("state")).get("session") or {}).get("focus") or {}
        return str(focus.get("focusedApp")) == APP_ID or int(APP_ID) in (focus.get("focusableApps") or [])

    # ------------------------------------------------------------ the rules, re-checked while a session is up
    def cheap_checks(self) -> dict:
        self.owner_check()
        self.sh("cmd media_session volume --stream 3 --set 0")
        volume = re.search(r"volume is (\d+)", self.sh("cmd media_session volume --stream 3 --get"))
        if not volume or volume.group(1) != "0":
            raise Abort("media volume is not 0")
        values = self.ctl("prefs", "get", "session", check=True).get("values") or {}
        if values.get("steam_offline") is not True:
            raise Abort(f"Offline mode is {values.get('steam_offline')}: rule 1, stopping")
        if values.get("gameSound", False) is not False or values.get("bootMovieSound", True) is not False:
            raise Abort(f"sound: game sound {values.get('gameSound')}, boot-movie sound {values.get('bootMovieSound', True)}: rule 2, stopping")
        thermal = self.ctl("thermal")
        status = thermal.get("status")
        if isinstance(status, int) and status >= self.args.max_thermal_status:
            raise Abort(f"thermal status {status} (battery {thermal.get('batteryC')} C): at or over --max-thermal-status")
        return {"offline_setting": True, "game_sound": False, "boot_movie_sound": False, "media_volume": 0,
                "battery_c": thermal.get("batteryC"), "thermal_status": status,
                "capped": [name for name, value in (thermal.get("cpu") or {}).items() if value.get("capped")]}

    def inside(self, script: str, timeout: int = 120) -> str:
        """A shell script in the container: through the guest agent while a session is up, without proot otherwise."""
        running = (self.ctl("state").get("session") or {}).get("phase") == "READY"
        answer = self.ctl("guest" if running else "native", "--timeout", str(timeout), "--", "/bin/sh", "-c", script, check=True)
        if answer.get("exitCode") != 0:
            raise Abort(f"container command failed ({answer.get('exitCode')}): {((answer.get('stderr') or {}).get('text') or '')[-300:]}")
        return (answer.get("stdout") or {}).get("text", "")

    # ------------------------------------------------------------ pushed files
    def adb_push(self, local: str, remote: str) -> str | None:
        pushed = subprocess.run(["adb", "-s", self.serial, "push", local, remote], capture_output=True, timeout=600)
        return None if pushed.returncode == 0 else pushed.stderr.decode(errors="replace")[-200:] or "failed"

    def restore_pushed(self) -> None:
        for item in list(self.pushed):
            target = item["guest_path"]
            out = self.inside(f'set -e; T={target}; test -f "$T.bench-orig"; mv -f "$T.bench-orig" "$T"; sha256sum "$T"')
            now = out.split()[0] if out.split() else None
            if now != item["sha256_original"]:
                raise Abort(f"{target} was put back but its hash is {now}, not the original {item['sha256_original']}")
            self.pushed.remove(item)
            self.restored_files.append(dict(item, restored=True))
            self.log(f"restored {target} ({now[:12]})")

    def apply_pushes(self) -> None:
        wanted = [] if self.args.restore_pushed else [benchlib.parse_push(spec) for spec in self.args.push_file]
        if not wanted and not self.pushed:
            return
        self.phase("push-files")
        self.restore_pushed()
        inbox = self.ctl("access", check=True).get("inbox")
        if wanted and not inbox:
            raise Abort("the app did not name its inbox folder")
        for index, (local, target) in enumerate(wanted):
            digest = hashlib.sha256(Path(local).read_bytes()).hexdigest()
            staged = f"{inbox}/bench-push-{index}"
            self.sh(f"mkdir -p {inbox}")
            problem = self.adb_push(local, staged)
            if problem:
                raise Abort(f"adb push {local} failed: {problem}")
            out = self.inside(f'set -e; T={target}; S={staged}; test -f "$T"; test ! -e "$T.bench-orig"; cp -p "$T" "$T.bench-orig"; '
                              f'cp "$S" "$T.bench-new"; chmod --reference="$T" "$T.bench-new"; mv -f "$T.bench-new" "$T"; rm -f "$S"; '
                              f'sha256sum "$T.bench-orig" "$T"', timeout=300)
            hashes = re.findall(r"^([0-9a-f]{64}) ", out, re.M)
            record = {"local": str(local), "guest_path": target, "backup": target + ".bench-orig",
                      "sha256_original": hashes[0] if hashes else None, "sha256_pushed": hashes[1] if len(hashes) > 1 else None,
                      "sha256_local": digest}
            self.pushed.append(record)
            if record["sha256_pushed"] != digest:
                self.restore_pushed()
                raise Abort(f"{target} does not have the pushed file's hash after the copy")
            self.log(f"pushed {local} -> {target} ({digest[:12]}, original {str(record['sha256_original'])[:12]})")

    # ------------------------------------------------------------ getting to the main menu
    def restore_config(self) -> None:
        for tag, state_before in reversed(self.restore):
            self.set_switch(tag, state_before, remember=False)
        if self.route_before is not None:
            self.set_route(self.route_before)
            self.restore_route_owner()
        self.restore = []
        self.route_before = None

    def at_main_menu(self) -> bool:
        return self.screen("where").main_menu()

    def leave_game(self) -> None:
        if not self.game_running():
            return
        try:
            if not self.at_main_menu():
                raise Abort("the game is not at its main menu")
            self.quit_game()
        except Abort as error:
            self.notes.append(f"game ended with droiddeckctl quit, not its menu: {error}")
            self.ctl("quit", APP_ID, "--timeout", "60")
            self.nap(8)

    def end_session(self) -> None:
        self.phase("stop-session")
        if self.pushed:
            self.restore_pushed()
        self.leave_game()
        self.ctl("stop", "--timeout", "120", timeout=180)
        self.ctl("wait", "idle", "--timeout", "120", timeout=180)
        self.ctl("env", "clear", "all")
        self.restore_config()
        SCREEN_MARKER.unlink(missing_ok=True)

    def start_streams(self) -> None:
        if not self.lmk_stream:
            self.background("logcat-lmk.txt", ["adb", "-s", self.serial, "logcat", "-v", "epoch", "-T", "1", "-s", "lowmemorykiller:*"])
            self.lmk_stream = True
        if not self.sampling:
            self.start_samplers()
            self.sampling = True

    def start_steam(self, pre: dict) -> None:
        super().start_steam(pre)
        self.lmk_stream = True

    def launch_game(self) -> None:
        self.phase("launch-to-menu")
        self.owner_check()
        self.start_streams()
        self.launched_local = self.sh("date '+%F %T'").strip()
        self.marks["launch"] = self.phone_ms()
        self.ctl("launch", APP_ID, check=True)
        self.launches += 1
        self.rep_in_launch = 0
        self.log("game launch requested")
        icon = self.wait_menu().profile_icon()
        self.checks["profile_icon"] = icon
        if icon != self.script["profile_icon"]:
            raise Abort(f"the game is on the {icon} profile, not the bench profile ({self.script['profile_icon']}): not touching it")

    def read_experiments(self) -> None:
        nodes = self.open_settings("Advanced")
        self.experiments = {node["tag"][len("setting-exp-"):]: (node.get("state") or node.get("text"))
                            for node in nodes if str(node.get("tag") or "").startswith("setting-exp-")}

    def attach(self) -> dict:
        """Bring the phone to the game's main menu in the wanted configuration, restarting as little as the
        switch table allows. Returns the preflight header."""
        args = self.args
        self.phase("attach")
        self.owner_check()
        state = self.ctl("state", check=True)
        session = state["session"]
        self.build = {"label": state.get("build"), "commit": state.get("apkCommit"), "app_version": state.get("appVersion"),
                      "runtime": (state.get("runtime") or {}).get("version")}
        self.commands = bool(self.ctl("access").get("commands"))
        if not self.commands:
            raise Abort("Debugging tools are off: --mode screen needs them for the per-rep checks, env lines and --push-file "
                        "(tap Setup > Diagnostics on the phone)")
        marker = self.load_marker()
        current = None
        if session["phase"] == "IDLE":
            if marker:
                self.adopt(marker)
                self.launches = self.rep_in_launch = 0
        elif (session["phase"] == "READY" and session.get("mode") == "steam" and marker
              and marker.get("session_id") == session.get("id") and marker.get("build_commit") == state.get("apkCommit")):
            self.adopt(marker)
            self.session_id = session.get("id")
            current = {key: marker.get(key) for key in ("path", "route", "env", "switches", "baseline", "pushes")}
            current["game_running"] = self.game_running(state)
        else:
            raise Abort(f"session is {session['phase']} (id {session.get('id')}) and --mode screen did not start it: not touching it; "
                        "stop it, or use --stop if an earlier screen run left it")
        self.ours = True
        wanted = self.wanted()
        level, reasons = benchlib.plan_change(current, wanted)
        if args.restart and level != benchlib.SESSION:
            level, reasons = benchlib.SESSION, reasons + ["session: --restart"]
        pre: dict = {}
        if level == benchlib.LAUNCH:
            try:
                pre = self.relaunch(marker, wanted)
            except SettingsUnreachable as error:
                reasons.append(f"session: Lanner's settings could not be used with the session up ({str(error)[:120]})")
                level = benchlib.SESSION
        if level == benchlib.SESSION:
            if session["phase"] != "IDLE" or current is not None:
                self.end_session()
            elif marker:
                self.restore_config()
                SCREEN_MARKER.unlink(missing_ok=True)
            pre = self.preflight()
            self.start_steam(pre)
            self.experiments = dict(pre.get("experiments") or {})
            self.apply_pushes()
            self.launch_game()
        elif level == benchlib.NONE:
            pre = dict((marker or {}).get("preflight") or {}, reused="session and game")
            self.sh("; ".join(f"am force-stop {app}" for app in OTHER_APPS))
            self.start_streams()
            layout = self.screen("reuse")
            if not layout.main_menu() or layout.profile_icon() != self.script["profile_icon"]:
                raise Abort("the game kept from the last run is not at its main menu on the bench profile")
        self.reuse = {"level": level, "reasons": reasons, "session_id": getattr(self, "session_id", None),
                      "launches_in_session": self.launches}
        self.log(f"attach: restart level {level} ({'; '.join(reasons) or 'same configuration'})")
        return pre

    def relaunch(self, marker: dict, wanted: dict) -> dict:
        """Same session, new game process: for switches droiddeck-game-env reads at each launch, and pushed files."""
        self.sh("; ".join(f"am force-stop {app}" for app in OTHER_APPS))
        self.cheap_checks()
        self.leave_game()
        baseline = {benchlib.switch_id(tag): state for tag, state in self.restore}
        targets = dict(wanted["switches"])
        for name, state in self.experiments.items():
            if name not in targets and name in baseline and baseline[name] != state:
                targets[name] = baseline[name]
        changed = [name for name, state in targets.items() if self.experiments.get(name) != state]
        if changed:
            try:
                for name in changed:
                    self.set_switch(self.tag(name), targets[name])
                self.read_experiments()
            except Abort as error:
                raise SettingsUnreachable(str(error)) from error
            self.ctl("resume")
            self.nap(2)
        self.apply_pushes()
        self.launch_game()
        return dict((marker or {}).get("preflight") or {}, reused="session")

    # ------------------------------------------------------------ one repetition
    def game_counts(self) -> list[int] | None:
        if self.args.path != "native":
            return None
        out = self.guest(f'for w in "frame split" "playing card" "performing move"; do grep -c "$w" {GAME_LOG}; done')
        try:
            counts = [int(value) for value in out.split()]
        except (AttributeError, ValueError):
            return None
        return counts if len(counts) == 3 else None

    def wait_screen(self, label: str, done, timeout_s: float, every_s: float = 1.0):
        deadline = time.time() + timeout_s
        while True:
            layout = self.screen(label)
            if done(layout):
                return layout
            if time.time() > deadline:
                return None
            self.nap(every_s, fixed=False)

    def rep(self, index: int, warmup: bool) -> None:
        script, args = self.script, self.args
        self.rep_in_launch += 1
        self.phase("rep-checks", index)
        checks = self.cheap_checks()
        layout = self.screen("rep-menu")
        if not layout.main_menu() or layout.profile_icon() != script["profile_icon"]:
            raise Abort(f"rep {index}: not at the main menu on the bench profile (badge {layout.profile_icon()})")
        self.phase("continue", index)
        y = self.focus_menu("top")
        low, high = script["continue_focus_y"]
        if not low <= y <= high:
            raise Abort(f"rep {index}: the top menu entry is at y={y}, not where Continue sits ({low}-{high})")
        self.pad("a")
        self.phase("fight-ready", index)
        ready = self.wait_screen("combat-wait", lambda shot: shot.hand_glow() >= script["hand_glow_min"] and shot.enemy_hp() is not None, 60)
        if ready is None:
            raise Abort(f"rep {index}: the fight did not come up within 60 s")
        hp = ready.enemy_hp()
        if abs(hp - script["start_enemy_hp"]) > script["hp_tolerance"]:
            raise Abort(f"rep {index}: the fight is not at its start (enemy bar {hp}): Save and Quit did not put it back")
        glow = [ready.hand_glow()]

        def steady(shot) -> bool:
            glow.append(shot.hand_glow())
            return abs(glow[-1] - glow[-2]) <= 0.002
        self.nap(1.0, fixed=False)
        if self.wait_screen("hand-steady", steady, 10) is None:
            self.notes.append(f"rep {index}: the hand was still moving 10 s after it showed")
        self.log(f"rep {index}: combat ready, enemy bar {hp}, hand glow {glow[-1]}")
        latency = None
        if args.latency_count:
            self.phase("latency", index)
            latency = self.latency(f"fight-rep{index}", args.fight_region, "pad:right", "pad:left")
            self.nap(1.0)
            after = self.screen("after-latency").enemy_hp()
            if after is None or abs(after - script["start_enemy_hp"]) > script["hp_tolerance"]:
                raise Abort("the latency presses changed the fight")
        self.phase("capture-start", index)
        seconds = int(benchlib.script_seconds(script["steps"])) + 8
        before = self.game_counts()
        if args.with_cpu:
            self.background(f"top-threads-rep{index}.txt", ["adb", "-s", self.serial, "shell",
                            f"top -H -b -d 5 -n {max(3, seconds // 5 + 1)} -m 70 -s 3 -o PID,TID,%CPU,CMD,NAME"])
        if args.with_perfetto:
            self.perfetto_start(min(60, seconds))
        if args.with_pacing:
            self.pacing_start(seconds)
        self.nap(1.5)
        self.steps = []
        self.phase("fight", index)
        start = self.phone_ms()
        self.play_script(script)
        end = self.phone_ms()
        self.phase("capture-finish", index)
        after_counts = self.game_counts()
        thermal = self.ctl("thermal")
        record = {"index": index, "launch": self.launches, "rep_in_launch": self.rep_in_launch, "cold": self.rep_in_launch == 1,
                  "warmup": warmup, "checks": checks, "enemy_hp_start": hp, "window_ms": [start, end], "steps": self.steps,
                  "counts": [before, after_counts], "latency": latency,
                  "thermal_end": {"battery_c": thermal.get("batteryC"), "status": thermal.get("status")}}
        if args.with_pacing:
            record["pacing"] = self.pacing_finish()
        if args.with_perfetto:
            trace = self.perfetto_finish()
            if trace:
                kept = self.dir / f"trace-rep{index}.pftrace"
                os.replace(trace, kept)
                record["trace"] = str(kept)
        self.reps.append(record)
        self.phase("save-and-quit", index)
        self.pad("start")
        self.nap(1.5)
        paused = self.screen("paused")
        if paused.hand_glow() > 0.003 or paused.main_menu():
            raise Abort("the pause menu did not open")
        for _ in range(3):
            self.pad("down")
            self.nap(0.4)
        self.screen("save-and-quit-focused")
        self.pad("a")
        if self.wait_screen("back-to-menu", lambda shot: shot.main_menu(), 40) is None:
            raise Abort("Save and Quit did not return to the main menu")
        self.log(f"rep {index}{' (warm-up)' if warmup else ''}: played, back at the main menu")

    # ------------------------------------------------------------ results
    def archive_texts(self) -> list[str]:
        texts = []
        for path in sorted(glob.glob(str(self.dir / "logs" / "**" / "*"), recursive=True)):
            if os.path.isfile(path) and os.path.getsize(path) < 16 * 1024 * 1024:
                with open(path, errors="replace") as handle:
                    text = handle.read()
                lines = [line for line in text.splitlines() if "offline" in line]
                if lines:
                    texts.append("\n".join(lines))
        return texts

    def summarize_screen(self, pre: dict, failure: str | None) -> dict:
        measured = [rep for rep in self.reps if not rep["warmup"]]
        self.steps = [step for rep in measured for step in rep["steps"]]
        if measured:
            self.marks["window_start"], self.marks["window_end"] = measured[0]["window_ms"][0], measured[-1]["window_ms"][1]
            first, last = measured[0]["counts"][0], measured[-1]["counts"][1]
            self.marks["splits_start"], self.marks["splits_end"] = (first[0] if first else None), (last[0] if last else None)
        summary = self.summarize(pre, failure)
        read = lambda path: path.read_text(errors="replace") if path and path.exists() else ""
        frames = benchlib.parse_frames(read(self.find_log("frames.csv")))
        samples = benchlib.parse_sampler(read(self.dir / "sampler.txt"))
        game_log = benchlib.count_game_log(read(self.find_containing("native_compat: frame split"))) if self.args.path == "native" else {}
        if measured:
            summary["frames"]["overall"] = benchlib.window_stats(frames, [tuple(rep["window_ms"]) for rep in measured])
        for rep in self.reps:
            start, end = rep["window_ms"]
            rep["frames"] = {"overall": benchlib.window_stats(frames, [(start, end)]),
                             "animation": benchlib.window_stats(frames, benchlib.animation_windows(rep["steps"])),
                             "card_plays": benchlib.window_stats(frames, benchlib.animation_windows(rep["steps"], ("card",))),
                             "enemy_turns": benchlib.window_stats(frames, benchlib.animation_windows(rep["steps"], ("end_turn",)))}
            rep["memory"] = benchlib.sampler_summary(samples, start, end)
            rep["enemy_hp_checks"] = [step["enemy_hp"] for step in rep["steps"] if "enemy_hp" in step]
            before, after = rep["counts"]
            if before and after and game_log:
                rep["cards"] = game_log["cards"][before[1]:after[1]]
                rep["monster_moves"] = [list(move) for move in game_log["monster_moves"][before[2]:after[2]]]
            if self.args.with_cpu:
                rows = benchlib.parse_top(read(self.dir / f"top-threads-rep{rep['index']}.txt"))
                rep["cpu_top_processes"] = {name: value for name, value in benchlib.top_by_process(rows).items() if value >= 0.5}
        differences = benchlib.reps_identical(self.reps)
        values = [benchlib.rep_metrics(rep) for rep in measured]
        warm = [benchlib.rep_metrics(rep) for rep in measured if not rep["cold"]]
        summary.update({
            "mode": "screen", "turns": self.args.turns or None, "reps": self.reps,
            "spread": {metric: benchlib.spread([value[metric] for value in values]) for metric in benchlib.CONFIRM_METRICS},
            "spread_warm": {metric: benchlib.spread([value[metric] for value in warm]) for metric in benchlib.CONFIRM_METRICS},
            "identical": {"ok": not differences, "problems": differences,
                          "basis": "enemy bar full at each start, the scripted health checks, and on the native engine the cards and enemy moves the game logged"},
            "reuse": self.reuse, "pushed_files": self.pushed + self.restored_files,
            "offline_evidence": self.offline,
            "left_running": bool(failure is None and not self.args.stop_after),
        })
        empty = [rep["index"] for rep in measured if not rep["frames"]["animation"]["n"]]
        if empty or differences:
            summary["ok"] = False
            summary["failure"] = summary["failure"] or (f"no frames for reps {empty}" if empty else f"the reps were not the same fight: {differences[0]}")
        return summary

    # ------------------------------------------------------------ run
    def stop_only(self) -> int:
        self.sync_clock()
        self.commands = bool(self.ctl("access").get("commands"))
        marker = self.load_marker()
        if not marker:
            print("no kept screen session is recorded: nothing to stop", file=sys.stderr)
            return 0
        self.adopt(marker)
        state = self.ctl("state", check=True)["session"]
        if state["phase"] not in ("IDLE", "READY") or (state["phase"] == "READY" and state.get("id") != marker.get("session_id")):
            print(f"session is {state['phase']} (id {state.get('id')}), not the one the last screen run kept: not touching it", file=sys.stderr)
            return 1
        try:
            if state["phase"] == "READY":
                self.owner_check()
                self.end_session()
            else:
                if self.pushed:
                    self.restore_pushed()
                self.restore_config()
                SCREEN_MARKER.unlink(missing_ok=True)
        except Abort as error:
            print(f"ABORT: {error}", file=sys.stderr)
            return 1
        (OUT_ROOT / ".last-run-end").write_text(str(time.time() + self.clock_offset_ms / 1000.0))
        print("kept session stopped, switches, route and pushed files put back")
        return 0

    def run(self) -> int:
        if self.dir.exists() and any(self.dir.iterdir()):
            print(f"{self.dir} already has files: pick another --name", file=sys.stderr)
            return 2
        (self.dir / "shots").mkdir(parents=True)
        self.log_file = open(self.dir / "run.log", "w")
        self.started_utc = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        self.sync_clock()
        pre: dict = {}
        failure = None
        try:
            pre = self.attach()
            total = self.args.warmup_reps + self.args.reps
            for index in range(1, total + 1):
                self.rep(index, warmup=index <= self.args.warmup_reps)
            if not self.args.stop_after:
                self.phase("log-pull")
                self.ctl("logs", "latest", str(self.dir / "logs"), timeout=300, check=True)
                self.offline = benchlib.offline_evidence(self.archive_texts(), self.launched_local)
                if not self.offline["ok"]:
                    raise Abort(f"offline evidence in the session's logs: {self.offline['problems']}: rule 1")
        except Abort as error:
            failure = str(error)
            self.log(f"ABORT: {failure}")
            try:
                self.screen("abort")
            except Exception:
                pass
        except Exception as error:  # the phone must still be put back
            failure = f"{type(error).__name__}: {error}"
            self.log(f"ERROR: {failure}")
        try:
            if not self.ours:
                self.notes.append("nothing on the phone was changed or stopped")
                self.stop_background()
            elif failure is not None or self.args.stop_after:
                try:
                    if self.pushed:
                        self.restore_pushed()
                    if failure is None:
                        self.leave_game()
                except Exception as error:
                    self.notes.append(f"before teardown: {type(error).__name__}: {error}")
                if (self.dir / "logs").exists():
                    os.replace(self.dir / "logs", self.dir / "logs-live")
                self.teardown()
                SCREEN_MARKER.unlink(missing_ok=True)
                if self.offline is None:
                    self.offline = benchlib.offline_evidence(self.archive_texts(), self.launched_local)
            else:
                self.stop_background()
                self.save_marker(pre)
                (OUT_ROOT / ".last-run-end").write_text(str(time.time() + self.clock_offset_ms / 1000.0))
        except Exception as error:
            self.notes.append(f"teardown: {type(error).__name__}: {error}")
            self.stop_background()
        self.phase("analysis")
        summary = self.summarize_screen(pre, failure)
        summary["phases"] = self.phases.block()
        problems = benchlib.validate_summary(summary) + benchlib.validate_screen(summary)
        if problems:
            summary["notes"].append(f"schema problems: {problems}")
        (self.dir / "summary.json").write_text(json.dumps(summary, indent=1))
        spread = summary["spread"]
        self.log(f"done: ok={summary['ok']} reps {spread['anim_p99_ms']['n']} | animation p99 median {spread['anim_p99_ms']['median']} "
                 f"(range {spread['anim_p99_ms']['min']}-{spread['anim_p99_ms']['max']}) fps {spread['anim_fps']['median']} | "
                 f"restart level {self.reuse.get('level')} | left running {summary['left_running']} | {self.dir}/summary.json")
        return 0 if summary["ok"] else 1


def run_confirm(args: argparse.Namespace) -> int:
    """--mode confirm: A and B in turn, each a screen-mode arm, until the interval on the animation p99 difference
    excludes 0 (and clears the earlier noise floor) or --max-pairs."""
    top = OUT_ROOT / args.name
    if top.exists() and any(top.iterdir()):
        print(f"{top} already has files: pick another --name", file=sys.stderr)
        return 2
    top.mkdir(parents=True, exist_ok=True)
    arms = {"a": benchlib.parse_arm(args.a), "b": benchlib.parse_arm(args.b)}
    started = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    values: dict = {metric: {"a": [], "b": []} for metric in benchlib.CONFIRM_METRICS}
    runs, share, notes = [], [], []
    intervals: dict = {}
    decision: dict = {"stop": False, "verdict": None, "why": "no pair completed"}
    failure = None
    build = None
    pairs = 0
    for pair in range(1, args.max_pairs + 1):
        for key in ("a", "b"):
            arm = arms[key]
            arm_args = copy.copy(args)
            arm_args.mode, arm_args.name = "screen", f"{args.name}/p{pair:02d}-{key}"
            arm_args.switch, arm_args.switch_off = list(args.switch) + arm["on"], list(args.switch_off) + arm["off"]
            arm_args.env = list(args.env) + arm["env"]
            arm_args.path, arm_args.route = arm["path"] or args.path, arm["route"] or args.route
            arm_args.stop_after, arm_args.no_results_row = False, True
            code = ScreenBench(arm_args).run()
            summary = json.loads((OUT_ROOT / arm_args.name / "summary.json").read_text())
            runs.append(arm_args.name)
            if code != 0:
                failure = f"{arm_args.name}: {summary.get('failure')}"
                break
            build = summary["build"].get("commit")
            share.append(summary["reuse"]["level"])
            measured = [benchlib.rep_metrics(rep) for rep in summary["reps"] if not rep["warmup"]]
            for metric in benchlib.CONFIRM_METRICS:
                values[metric][key].append([value[metric] for value in measured])
        if failure:
            break
        pairs = pair
        for metric in benchlib.CONFIRM_METRICS:
            a, b = values[metric]["a"], values[metric]["b"]
            if all(value is not None for group in a + b for value in group):
                intervals[metric] = benchlib.bootstrap_diff_ci(a, b, seed=args.seed)
            else:
                intervals[metric] = None
        if intervals["anim_p99_ms"] is None:
            failure = "an arm has no animation p99"
            break
        decision = benchlib.confirm_decision(intervals["anim_p99_ms"], pair, args.min_pairs, args.max_pairs, args.noise_sd)
        ci = intervals["anim_p99_ms"]
        print(f"pair {pair}: animation p99 B-A {ci['diff']} ms [{ci['low']}, {ci['high']}] -> "
              f"{'stop: ' + str(decision['verdict']) if decision['stop'] else 'go on: ' + decision['why']}", flush=True)
        if decision["stop"]:
            break
    left_running = bool(args.keep_running and failure is None)
    if failure is None and not args.keep_running:
        stop_args = copy.copy(args)
        stop_args.mode, stop_args.name = "screen", f"{args.name}/stop"
        if ScreenBench(stop_args).stop_only() != 0:
            notes.append("the kept session could not be stopped at the end: run --mode screen --stop")
            left_running = True
    flat = lambda groups: [value for group in groups for value in group]
    summary = {
        "schema": benchlib.SCHEMA_VERSION, "mode": "confirm", "name": args.name, "started_utc": started,
        "scenario": args.scenario, "path": args.path, "route": args.route, "build": build, "turns": args.turns or None,
        "a": dict(arms["a"], text=args.a), "b": dict(arms["b"], text=args.b),
        "ok": failure is None, "failure": failure, "pairs": pairs, "reps_per_arm": args.reps, "runs": runs,
        "share": {"levels": share, "what": "restart level of each arm in order: none = same game launch, launch = same session, session = new session"},
        "values": values, "intervals": intervals, "seed": args.seed, "noise_sd_ms": args.noise_sd,
        "min_pairs": args.min_pairs, "max_pairs": args.max_pairs, "decision": decision,
        "spread": {metric: {key: benchlib.spread(flat(values[metric][key])) for key in ("a", "b")} for metric in benchlib.CONFIRM_METRICS},
        "left_running": left_running, "notes": notes,
    }
    problems = benchlib.validate_confirm(summary)
    if problems:
        summary["notes"].append(f"schema problems: {problems}")
    (top / "summary.json").write_text(json.dumps(summary, indent=1))
    if failure is None and pairs and not args.no_results_row:
        groups = {metric: [flat(values[metric]["a"]), flat(values[metric]["b"])] for metric in benchlib.CONFIRM_METRICS}
        config = f"{args.path}, {args.route}, A `{args.a or 'as is'}` / B `{args.b or 'as is'}`"
        line = benchlib.noise_floor_line(started[:10], build or "?", config, f"confirm, {pairs} pairs x {args.reps} reps", groups, args.name)
        results = REPO / "docs/bench/RESULTS.md"
        results.write_text(benchlib.insert_noise_line(results.read_text(), line))
    print(f"confirm: ok={failure is None} pairs {pairs} verdict {decision.get('verdict')} | {top}/summary.json", flush=True)
    return 0 if failure is None else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--scenario", choices=("menu", "fight"), required=True)
    parser.add_argument("--path", choices=("native", "proton"), required=True)
    parser.add_argument("--route", choices=tuple(ROUTES), required=True)
    parser.add_argument("--switch", action="append", default=[], metavar="TAG", help="experiment to turn on for this run (e.g. present-dmabuf)")
    parser.add_argument("--switch-off", action="append", default=[], metavar="TAG", help="experiment to turn off for this run")
    parser.add_argument("--env", action="append", default=[], metavar="KEY=VALUE", help="session env line for this run (Debugging tools)")
    parser.add_argument("--name", required=True, help="run folder under the bench output root")
    parser.add_argument("--serial", default=os.environ.get("ANDROID_SERIAL") or os.environ.get("ADB_SERIAL"))
    parser.add_argument("--latency-count", type=int, default=100, help="presses for the latency probe; 0 skips it")
    parser.add_argument("--menu-region", default="0.36,0.655,0.13,0.09")
    parser.add_argument("--fight-region", default="0.535,0.62,0.03,0.11")
    parser.add_argument("--xcheck", action="store_true", help="fight: also record the screen during two real flicks (needs ffmpeg here)")
    parser.add_argument("--keep-config", action="store_true", help="leave the route and switches as this run set them")
    parser.add_argument("--no-results-row", action="store_true")
    parser.add_argument("--mode", choices=("full", "screen", "confirm"), default="full",
                        help="full: one launch, one measured window, everything captured (the default). screen: repeat the fight in a "
                             "kept session. confirm: A/B pairs of screen arms with a stopping rule")
    parser.add_argument("--reps", type=int, default=3, help="screen/confirm: measured fights per arm")
    parser.add_argument("--warmup-reps", type=int, default=0, help="screen/confirm: unmeasured fights before them")
    parser.add_argument("--turns", type=int, default=0, help="screen/confirm: play only the first N turns of the script (0: all)")
    parser.add_argument("--with-latency", type=int, default=0, metavar="N", help="screen/confirm: latency probe of N presses in every rep")
    parser.add_argument("--with-cpu", action="store_true", help="screen/confirm: top -H through every rep")
    parser.add_argument("--with-perfetto", action="store_true", help="screen/confirm: a Perfetto trace of every rep")
    parser.add_argument("--with-pacing", action="store_true", help="screen/confirm: droiddeckctl pacing over every rep")
    parser.add_argument("--push-file", action="append", default=[], metavar="LOCAL:GUEST_PATH",
                        help="screen: replace an engine or overlay file in the container before the launch (original kept beside it)")
    parser.add_argument("--restore-pushed", action="store_true", help="screen: put back every file an earlier --push-file replaced")
    parser.add_argument("--stop-after", action="store_true", help="screen: end the game and the session afterwards and restore the switches")
    parser.add_argument("--stop", action="store_true", help="screen: only end what an earlier screen run kept running")
    parser.add_argument("--restart", action="store_true", help="screen: start a new session even when the kept one would do")
    parser.add_argument("--max-thermal-status", type=int, default=2, help="screen/confirm: stop when the thermal status reaches this")
    parser.add_argument("--a", default="", help="confirm: arm A, e.g. \"\" or \"zram-game-late -hints-idle KEY=VALUE route:panvk\"")
    parser.add_argument("--b", default="", help="confirm: arm B")
    parser.add_argument("--max-pairs", type=int, default=6)
    parser.add_argument("--min-pairs", type=int, default=3)
    parser.add_argument("--noise-sd", type=float, default=2.0,
                        help="confirm: run-to-run sd of one arm's animation p99 in ms from earlier repeats (docs/bench/RESULTS.md); 0 turns that check off")
    parser.add_argument("--seed", type=int, default=benchlib.CONFIRM_SEED)
    parser.add_argument("--keep-running", action="store_true", help="confirm: leave the last arm's session up")
    args = parser.parse_args()
    if not args.serial:
        parser.error("--serial or ANDROID_SERIAL is required")
    if args.mode == "full":
        if args.push_file or args.restore_pushed or args.stop or args.a or args.b:
            parser.error("--push-file, --restore-pushed, --stop, --a and --b need --mode screen or confirm")
        return Bench(args).run()
    if args.scenario != "fight":
        parser.error(f"--mode {args.mode} is for --scenario fight")
    if args.push_file and args.restore_pushed:
        parser.error("--push-file and --restore-pushed do not go together")
    try:
        for spec in args.push_file:
            local, _guest = benchlib.parse_push(spec)
            if not Path(local).is_file():
                parser.error(f"--push-file: no such file {local}")
        benchlib.truncate_steps(json.loads((HERE / "scenarios" / "fight.json").read_text())["steps"], args.turns)
        if args.mode == "confirm":
            benchlib.parse_arm(args.a), benchlib.parse_arm(args.b)
    except ValueError as error:
        parser.error(str(error))
    args.latency_count = args.with_latency
    args.noise_sd = args.noise_sd or None
    if args.mode == "confirm":
        if args.push_file or args.restore_pushed or args.stop:
            parser.error("--mode confirm takes switches and env lines, not pushed files")
        if args.reps < 1 or args.min_pairs < 2 or args.max_pairs < args.min_pairs:
            parser.error("--reps at least 1, --min-pairs at least 2, --max-pairs at least --min-pairs")
        return run_confirm(args)
    if args.stop:
        return ScreenBench(args).stop_only()
    if args.reps < 1 and not args.restore_pushed:
        parser.error("--reps must be at least 1")
    return ScreenBench(args).run()


if __name__ == "__main__":
    sys.exit(main())
