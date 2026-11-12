import errno
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

PRELOAD = Path(__file__).resolve().parents[1] / "linuxfs/preload"

PROGRAM = r"""
#define _GNU_SOURCE
#include <errno.h>
#include <spawn.h>
#include <stdio.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

extern char **environ;

int main(int argc, char **argv) {
  if (argc < 3) return 2;
  const char *how = argv[1], *file = argv[2];
  char *node_env[] = {"ELECTRON_RUN_AS_NODE=1", NULL};
  char *const *child_env = !strcmp(how, "execve-node") || !strcmp(how, "execvpe-node") ||
                           !strcmp(how, "spawn-node") || !strcmp(how, "spawnp-node") ? node_env : environ;
  char **args = argv + 2;
  if (!strcmp(how, "access")) {
    int result = access(file, X_OK);
    printf("access %d %d\n", result, result ? errno : 0);
    return 0;
  }
  if (!strcmp(how, "spawn") || !strcmp(how, "spawnp") || !strcmp(how, "spawn-node") ||
      !strcmp(how, "spawnp-node")) {
    pid_t pid;
    int spawnp = !strcmp(how, "spawnp") || !strcmp(how, "spawnp-node");
    int error = spawnp ? posix_spawnp(&pid, file, NULL, NULL, args, child_env)
                       : posix_spawn(&pid, file, NULL, NULL, args, child_env);
    if (error) { printf("errno %d\n", error); return 3; }
    int status;
    waitpid(pid, &status, 0);
    printf("exit %d\n", WEXITSTATUS(status));
    return 0;
  }
  if (!strcmp(how, "execve") || !strcmp(how, "execve-node")) execve(file, args, (char *const *)child_env);
  else if (!strcmp(how, "execvpe-node")) execvpe(file, args, (char *const *)child_env);
  else if (!strcmp(how, "execv")) execv(file, args);
  else if (!strcmp(how, "execvp")) execvp(file, args);
  else if (!strcmp(how, "execvpe")) execvpe(file, args, environ);
  printf("errno %d\n", errno);
  return 3;
}
"""


@unittest.skipUnless(shutil.which("gcc") and os.uname().machine == "x86_64", "needs gcc on an x86-64 host")
class ExecPreloadTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.library = cls.tmp / "libexec.so"
        subprocess.run(["gcc", "-shared", "-fPIC", "-O2", "-Wall", "-Wextra", "-Werror", "-DBL_EXEC_ANY_ARCH",
                        "-o", str(cls.library),
                        "-pthread"] + [str(PRELOAD / name) for name in ("net.c", "udevmon.c", "pathcache.c", "perms.c", "webhelper.c")]
                       + [str(Path(__file__).with_name("netlink_stubs.c")), "-ldl"],
                       check=True)
        source = cls.tmp / "program.c"
        source.write_text(PROGRAM)
        cls.program = cls.tmp / "program"
        subprocess.run(["gcc", "-O2", "-o", str(cls.program), str(source)], check=True)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, True)

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp(dir=self.tmp))

    def file(self, name, data, mode=0o755):
        path = self.dir / name
        path.write_bytes(data)
        path.chmod(mode)
        return path

    def x86(self, name="tool", mode=0o755):
        path = self.dir / name
        shutil.copyfile(shutil.which("true"), path)
        path.chmod(mode)
        return path

    def chromium_script(self):
        folder = self.dir / "vscode"
        folder.mkdir()
        executable = folder / "code"
        executable.write_text("#!/bin/sh\nprintf '%s\\n' \"$@\"\n")
        executable.chmod(0o755)
        (folder / "chrome-sandbox").touch()
        return executable

    def run_program(self, how, target, *args):
        env = {"PATH": "%s:/usr/bin:/bin" % self.dir, "LD_PRELOAD": str(self.library)}
        done = subprocess.run([str(self.program), how, str(target)] + list(args), env=env,
                              stdout=subprocess.PIPE, text=True, timeout=30)
        return done.returncode, done.stdout.strip()

    def test_a_program_runs_as_itself_through_every_exec_call(self):
        tool = self.x86()
        for how in ("execve", "execv"):
            self.assertEqual(self.run_program(how, tool, "a"), (0, ""))
        for how in ("execvp", "execvpe"):
            self.assertEqual(self.run_program(how, "tool", "a"), (0, ""))
        self.assertEqual(self.run_program("spawn", tool), (0, "exit 0"))
        self.assertEqual(self.run_program("spawnp", "tool"), (0, "exit 0"))

    def test_a_program_without_execute_permission_stays_refused(self):
        tool = self.x86(mode=0o644)
        self.assertEqual(self.run_program("execve", tool), (3, "errno %d" % errno.EACCES))

    def test_a_native_program_the_kernel_cannot_run_keeps_its_error(self):
        arm64 = bytearray(64)
        arm64[0:4], arm64[4], arm64[5], arm64[18] = b"\x7fELF", 2, 1, 183
        program = self.file("arm64", bytes(arm64))
        self.assertEqual(self.run_program("execve", program), (3, "errno %d" % errno.ENOEXEC))

    def test_a_script_runs_as_itself(self):
        script = self.file("script.sh", b"#!/bin/sh\nexit 5\n")
        self.assertEqual(self.run_program("execve", script), (5, ""))

    def test_a_spawned_native_script_is_left_alone(self):
        self.file("script.sh", b"#!/bin/sh\nexit 5\n")
        self.assertEqual(self.run_program("spawnp", "script.sh"), (0, "exit 5"))

    def test_access_keeps_the_kernels_answer(self):
        self.assertEqual(self.run_program("access", self.x86(mode=0o644)), (0, "access -1 %d" % errno.EACCES))
        self.assertEqual(self.run_program("access", self.x86()), (0, "access 0 0"))

    def test_access_keeps_the_kernels_answer_on_noexec_storage(self):
        storage = next((d for d in (os.environ.get("XDG_RUNTIME_DIR"), "/dev/shm", "/run/user/%d" % os.getuid())
                        if d and os.path.isdir(d) and os.access(d, os.W_OK) and os.statvfs(d).f_flag & os.ST_NOEXEC), None)
        if storage is None:
            self.skipTest("no writable noexec mount here")
        folder = Path(tempfile.mkdtemp(dir=storage))
        self.addCleanup(shutil.rmtree, folder, True)
        tool = folder / "tool"
        shutil.copyfile(shutil.which("true"), tool)
        self.assertEqual(self.run_program("access", tool), (0, "access -1 %d" % errno.EACCES))

    def test_explicit_node_child_environment_skips_injection_for_execve_and_spawn(self):
        code = self.chromium_script()
        self.assertEqual(self.run_program("execve-node", code, "--version"), (0, "--version"))
        self.assertEqual(self.run_program("execvpe-node", code, "--version"), (0, "--version"))
        self.assertEqual(self.run_program("spawn-node", code, "--version"), (0, "--version\nexit 0"))
        self.assertEqual(self.run_program("spawnp-node", code, "--version"), (0, "--version\nexit 0"))

    def test_chromium_gui_launch_without_node_mode_still_receives_no_sandbox(self):
        code = self.chromium_script()
        self.assertEqual(self.run_program("execve", code, "--version"), (0, "--no-sandbox\n--version"))
        self.assertEqual(self.run_program("execvpe", code, "--version"), (0, "--no-sandbox\n--version"))
        self.assertEqual(self.run_program("spawnp", code, "--version"), (0, "--no-sandbox\n--version\nexit 0"))


if __name__ == "__main__":
    unittest.main()
