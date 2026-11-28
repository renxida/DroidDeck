#!/usr/bin/env python3
"""The standard bench: one measured run of Slay the Spire 2 on the test phone. See tools/bench/README.md.

    tools/bench/run.py --scenario fight --path native --route bridge --name fight-native-bridge-1
"""
from __future__ import annotations

import argparse
import base64
import datetime
import glob
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

    # ------------------------------------------------------------ plumbing
    def log(self, text: str) -> None:
        line = f"{datetime.datetime.now().strftime('%H:%M:%S')} {text}"
        print(line, flush=True)
        if self.log_file:
            self.log_file.write(line + "\n")
            self.log_file.flush()

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
            time.sleep(delay)

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
        time.sleep(1.0)
        for tab in tabs:
            self.ctl("ui", "click", "--match", tab, "--index", "0", check=True)
            time.sleep(1.0)
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
        time.sleep(1.0)
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
            time.sleep(1.0)
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
        time.sleep(1.0)
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

        waited = 0
        memory = self.meminfo()
        while memory["MemAvailable"] < 2560 and waited < 180:
            time.sleep(5)
            waited += 5
            memory = self.meminfo()
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
        self.background("logcat-lmk.txt", ["adb", "-s", self.serial, "logcat", "-v", "epoch", "-T", "1", "-s", "lowmemorykiller:*"])
        started = time.time()
        answer = self.ctl("start", "steam", "--wait", "--timeout", "240", timeout=300)
        if not answer.get("ok"):
            raise Abort(f"start steam failed: {json.dumps(answer)[:300]}")
        pre["start_to_ready_s"] = round(time.time() - started, 1)
        self.session_id = (answer.get("session") or {}).get("id") or self.ctl("state")["session"]["id"]
        icon = 0
        for attempt in range(6):
            time.sleep(6)
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
            time.sleep(4)
            layout = self.screen("load")
            if layout.early_access_prompt():
                prompts += 1
                self.tap(*EARLY_ACCESS_PROCEED)
                self.log("answered the game's 'What's Early Access?' notice (Proceed)")
                self.checks["early_access_prompts"] = prompts
                time.sleep(3)
                continue
            if layout.main_menu():
                time.sleep(2)
                layout = self.screen("menu")
                if layout.main_menu() and not layout.early_access_prompt():
                    self.marks["menu"] = self.phone_ms()
                    self.log(f"main menu after {(self.marks['menu'] - self.marks['launch']) / 1000:.0f} s, profile icon {layout.profile_icon()}")
                    return layout
        raise Abort("the main menu did not show (see shots/): does the session draw?")

    def focus_menu(self, where: str) -> int | None:
        """Move the pad focus to the top or the bottom entry of the main menu; returns its reference y."""
        self.pad("down")
        time.sleep(0.5)
        last = self.screen("focus").menu_focus_y()
        if last is None:
            self.pad("down")
            time.sleep(0.5)
            last = self.screen("focus").menu_focus_y()
        if last is None:
            raise Abort("no focused entry on the main menu after a d-pad press")
        button = "up" if where == "top" else "down"
        for _ in range(8):
            self.pad(button)
            time.sleep(0.5)
            now = self.screen("focus").menu_focus_y()
            if now is None:
                raise Abort("lost the main menu while moving the focus")
            moved = now - last if button == "down" else last - now
            if moved <= 2:
                if moved < -2:
                    self.pad("down" if button == "up" else "up")
                    time.sleep(0.5)
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
                time.sleep(max(0.0, started + at - time.time()))
                flicks.append(time.time() - started)
                self.sh(f"input swipe {x0} 760 {x1} 760 {XCHECK_FLICK_MS}")
            recorder.wait(timeout=30)
            time.sleep(1.0)
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
        self.sleep_until(launch + MENU_START_S * 1000)
        self.owner_check()
        self.screen("window-start")
        self.marks["splits_start"] = self.engine_split_count()
        self.top_start(MENU_WINDOW_S)
        self.perfetto_start(40)
        self.pacing_start(MENU_WINDOW_S)
        self.marks["window_start"] = self.phone_ms()
        time.sleep(MENU_WINDOW_S)
        self.marks["window_end"] = self.phone_ms()
        self.marks["splits_end"] = self.engine_split_count()
        self.pacing_result = self.pacing_finish()
        self.trace = self.perfetto_finish()
        self.screen("window-end")
        self.thermal_end = self.ctl("thermal")
        self.focus_menu("top")
        self.screen("latency-region")
        self.latency_result = self.latency("menu", self.args.menu_region, "pad:down", "pad:up")
        self.quit_game()

    def scenario_fight(self) -> None:
        script = json.loads((HERE / "scenarios" / "fight.json").read_text())
        launch = self.marks["launch"]
        layout = self.wait_menu()
        icon = layout.profile_icon()
        self.checks["profile_icon"] = icon
        if icon != script["profile_icon"]:
            raise Abort(f"the game is on the {icon} profile, not the bench profile ({script['profile_icon']}): not touching it")
        self.sleep_until(launch + FIGHT_START_S * 1000)
        self.owner_check()
        y = self.focus_menu("top")
        low, high = script["continue_focus_y"]
        if not low <= y <= high:
            raise Abort(f"the top menu entry is at y={y}, not where Continue sits ({low}-{high}): no saved bench run on this profile?")
        self.pad("a")
        self.log("Continue pressed")
        deadline = time.time() + 60
        while True:
            time.sleep(3)
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
        time.sleep(3)

        self.screen("latency-region")
        self.latency_result = self.latency("fight", self.args.fight_region, "pad:right", "pad:left")
        self.xcheck_result = self.xcheck("fight")
        time.sleep(1.0)
        layout = self.screen("after-latency")
        if layout.enemy_hp() is None or abs(layout.enemy_hp() - script["start_enemy_hp"]) > script["hp_tolerance"]:
            raise Abort("the latency presses changed the fight")

        estimate = int(sum(len(step.get("pads", [])) * 0.5 + step.get("settle_ms", 0) / 1000.0 + step.get("pause_ms", 0) / 1000.0 + 1.2
                           for step in script["steps"])) + 6
        self.marks["splits_start"] = self.engine_split_count()
        self.top_start(estimate)
        self.perfetto_start(min(60, estimate))
        self.pacing_start(estimate)
        time.sleep(1.5)
        self.marks["window_start"] = self.phone_ms()
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
                    time.sleep(1.5)
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
        self.marks["window_end"] = self.phone_ms()
        self.marks["splits_end"] = self.engine_split_count()
        self.thermal_end = self.ctl("thermal")
        self.pacing_result = self.pacing_finish()
        self.trace = self.perfetto_finish()
        self.checks["script_completed"] = True

        self.pad("start")
        time.sleep(1.5)
        paused = self.screen("paused")
        if paused.hand_glow() > 0.003 or paused.main_menu():
            raise Abort("the pause menu did not open")
        for _ in range(3):
            self.pad("down")
            time.sleep(0.4)
        self.screen("save-and-quit-focused")
        self.pad("a")
        self.log("Save and Quit pressed")
        deadline = time.time() + 40
        while not self.screen("back-to-menu").main_menu():
            if time.time() > deadline:
                raise Abort("Save and Quit did not return to the main menu")
            time.sleep(3)
        self.quit_game()

    def quit_game(self) -> None:
        """Quit through the game's own menu: bottom entry, then Yes."""
        self.focus_menu("bottom")
        self.screen("quit-focused")
        self.pad("a")
        time.sleep(2)
        if not self.screen("quit-confirm").dimmed():
            self.notes.append("no quit confirmation seen")
        self.pad("a")
        self.marks["quit"] = self.phone_ms()
        deadline = time.time() + 45
        gone = False
        while time.time() < deadline:
            time.sleep(3)
            focus = (self.ctl("state").get("session") or {}).get("focus") or {}
            if str(focus.get("focusedApp")) != APP_ID and int(APP_ID) not in (focus.get("focusableApps") or []):
                gone = True
                break
        self.checks["quit_through_game_menu"] = gone
        if not gone:
            self.notes.append("the game did not exit from its menu within 45 s; used droiddeckctl quit")
            self.ctl("quit", APP_ID, "--timeout", "60")
        self.log(f"game quit through its menu: {gone}")
        time.sleep(6)

    # ------------------------------------------------------------ teardown and analysis
    def teardown(self) -> None:
        state = self.ctl("state")
        phase = (state.get("session") or {}).get("phase")
        if phase not in ("IDLE", None):
            focus = (state.get("session") or {}).get("focus") or {}
            if str(focus.get("focusedApp")) == APP_ID:
                self.ctl("quit", APP_ID, "--timeout", "60")
                time.sleep(8)
            self.ctl("stop", "--timeout", "120", timeout=180)
            self.ctl("wait", "idle", "--timeout", "120", timeout=180)
        self.stop_background()
        logs = self.dir / "logs"
        if not logs.exists():
            self.ctl("logs", "latest", str(logs), timeout=300)
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
        summary = self.summarize(pre, failure)
        problems = benchlib.validate_summary(summary)
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
    args = parser.parse_args()
    if not args.serial:
        parser.error("--serial or ANDROID_SERIAL is required")
    return Bench(args).run()


if __name__ == "__main__":
    sys.exit(main())
