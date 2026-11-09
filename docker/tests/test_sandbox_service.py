"""Real Supervisor lifecycle tests with a synthetic server; no Docker, real storage or providers."""
import importlib.util
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

ROOT = Path(__file__).resolve().parents[1] / "yallmo-sandbox"
CONTROL = ROOT / "kit-yallmo/service/control.py"
CONFIG = ROOT / "kit-yallmo/service/supervisord.conf"
SUPERVISOR = importlib.util.find_spec("supervisor")
FAKE_SERVER = '''import http.server,os,signal,sys,time
print("stdout from fake Yallmo",flush=True)
print("stderr from fake Yallmo",file=sys.stderr,flush=True)
def stop(*args):
    print("fake Yallmo stopped",flush=True)
    sys.exit(0)
signal.signal(signal.SIGTERM,stop)
time.sleep(float(os.environ.get("FAKE_STARTUP_DELAY","0")))
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Length","2")
        self.end_headers()
        self.wfile.write(b"ok")
    def log_message(self,*args):pass
class Server(http.server.HTTPServer):allow_reuse_address=True
with Server(("127.0.0.1",int(os.environ["FAKE_PORT"])),Handler) as server:
    print("fake Yallmo ready",flush=True)
    server.serve_forever(poll_interval=0.05)
'''


@unittest.skipUnless(SUPERVISOR and os.name == "posix", "Install supervisor to run managed-service integration tests")
class SandboxServiceTests(unittest.TestCase):
    def setUp(self):
        # Keep Unix socket paths short, including on macOS.
        self.temporary = tempfile.TemporaryDirectory(prefix="ys-", dir="/tmp")
        self.root = Path(self.temporary.name)
        self.coding_workspace = self.root / "coding workspace"
        self.coding_workspace.mkdir()
        self.workspace = self.root / "yallmo-workspace with spaces"
        self.workspace.mkdir()
        (self.workspace / "fake.py").write_text(FAKE_SERVER)
        (self.workspace / "server.sh").write_text('#!/bin/sh\nexec "$TEST_PYTHON" -u "$TEST_SERVER"\n')
        # pip --target puts CLI entrypoints alongside the imported module; no global install is needed.
        import supervisor
        binaries = Path(supervisor.__file__).resolve().parents[1] / "bin"
        self.env = dict(os.environ, HOME=str(self.root / "home"), XDG_STATE_HOME=str(self.root / "state"),
                        YALLMO_WORKSPACE=str(self.workspace), YALLMO_AUTOSTART="0", FAKE_PORT="0", BASH_ENV="/dev/null",
                        TEST_PYTHON=sys.executable, TEST_SERVER=str(self.workspace / "fake.py"),
                        PATH=str(binaries) + os.pathsep + os.environ["PATH"])
        with socket.socket() as allocation:
            allocation.bind(("127.0.0.1", 0))
            self.env["FAKE_PORT"] = self.env["YALLMO_SERVICE_PORT"] = str(allocation.getsockname()[1])
        self.process = None
        self.boot_log = (self.root / "boot.log").open("w+")
        self.addCleanup(self.cleanup_service)

    def command(self, *args):
        return subprocess.run([sys.executable, str(CONTROL), *args], env=self.env,
                              cwd=self.coding_workspace, text=True, capture_output=True, timeout=40)

    def boot(self, autostart=False, expected=None):
        self.env["YALLMO_AUTOSTART"] = "1" if autostart else "0"
        self.process = subprocess.Popen([sys.executable, str(CONTROL), "supervise"], env=self.env,
                                        cwd=self.coding_workspace,
                                        stdin=subprocess.DEVNULL, stdout=self.boot_log, stderr=subprocess.STDOUT)
        result = self.command("status", "--wait")
        expected = expected or ("RUNNING" if autostart else "STOPPED")
        self.assertIn(expected, result.stdout, result.stdout + result.stderr + self.boot_output())
        return result

    def boot_output(self):
        self.boot_log.flush()
        return (self.root / "boot.log").read_text()[-2500:]

    def cleanup_service(self):
        # Stop only this fixture's manager; Supervisor gracefully stops/reaps its program group.
        env = dict(self.env, YALLMO_SERVICE_WORKSPACE=str(self.workspace),
                   YALLMO_SERVICE_STATE=str(self.root / "state/yallmo-service"),
                   YALLMO_SERVICE_LOG_DIR=str(self.workspace / "logs"), YALLMO_SERVICE_AUTOSTART="false")
        if shutil.which("supervisorctl", path=env["PATH"]):
            subprocess.run(["supervisorctl", "-c", str(CONFIG), "shutdown"], env=env,
                           text=True, capture_output=True, timeout=25)
        if self.process is not None:
            try:
                self.process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                self.process.terminate()
                self.process.wait(timeout=20)
        self.boot_log.close()
        self.temporary.cleanup()

    @staticmethod
    def pid(result):
        return int(re.search(r"pid (\d+)", result.stdout).group(1))

    def test_autostart_captures_both_streams_with_private_persistent_logs(self):
        result = self.boot(autostart=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        log = self.workspace / "logs/yallmo.log"
        self.assertIn("stdout from fake Yallmo", log.read_text())
        self.assertIn("stderr from fake Yallmo", log.read_text())
        self.assertIn(str(log), result.stdout)
        self.assertEqual(log.stat().st_mode & 0o777, 0o600)
        self.assertEqual(log.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(list(self.coding_workspace.iterdir()), [])
        read = self.command("logs", "--lines", "10")
        self.assertEqual(read.returncode, 0, read.stderr)
        self.assertIn("fake Yallmo ready", read.stdout)
        manager = self.command("logs", "--supervisor", "--lines", "5")
        self.assertEqual(manager.returncode, 0, manager.stderr)
        self.assertIn("supervisor.log", manager.stderr)

    @unittest.skipUnless(yaml, "PyYAML required to simulate the Sandbox startup hook environment")
    def test_boot_hook_preserves_image_java_path_for_autostart_and_manual_restart(self):
        # Temurin puts java under JAVA_HOME/bin, outside the hook's default system PATH.
        jdk = self.root / "jdk with spaces"
        (jdk / "bin").mkdir(parents=True)
        java = jdk / "bin/java"
        java.write_text('#!/bin/sh\nexec "$TEST_PYTHON" -u "$TEST_SERVER"\n')
        java.chmod(0o755)
        (self.workspace / "server.sh").write_text(
            '#!/bin/bash\n'
            'if [[ "$(command -v java)" != "$JAVA_HOME/bin/java" ]]; then\n'
            '  printf "image Java missing from service PATH\\n" >&2; exit 127\n'
            'fi\nexec java\n')
        kit = yaml.safe_load((ROOT / "kit-yallmo/kit.yaml").read_text())
        lifecycle = next(cap for cap in kit["capabilities"]
                         if cap["type"] == "com.docker.sandbox/lifecycle@1")
        forwarded = lifecycle["config"]["startup"][0]["env"]
        image = dict(self.env, JAVA_HOME=str(jdk),
                     PATH=str(jdk / "bin") + os.pathsep + self.env["PATH"])
        # Preserve fixture-only inputs; apply the hook allowlist to the image's Java environment.
        for name in ("PATH", "JAVA_HOME"):
            if name in forwarded:
                self.env[name] = image[name]
            elif name != "PATH":
                self.env.pop(name, None)
        result = self.boot(autostart=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        # The manager retains its boot environment, independent of a later client's PATH.
        self.env["PATH"] = image["PATH"].split(os.pathsep, 1)[1]
        restarted = self.command("restart")
        self.assertEqual(restarted.returncode, 0, restarted.stdout + restarted.stderr)
        self.assertGreaterEqual((self.workspace / "logs/yallmo.log").read_text().count("fake Yallmo ready"), 2)

    def test_shared_launcher_finds_java_when_manager_path_omits_the_jdk(self):
        shutil.copyfile(ROOT / "yallmo-workspace/server.sh", self.workspace / "server.sh")
        jdk = self.root / "jdk with spaces"
        (jdk / "bin").mkdir(parents=True)
        java = jdk / "bin/java"
        java.write_text('#!/bin/sh\nexec "$TEST_PYTHON" -u "$TEST_SERVER"\n')
        java.chmod(0o755)
        binaries = self.root / "bin"
        binaries.mkdir()
        yallmo = binaries / "yallmo"
        yallmo.write_text(
            '#!/bin/bash\n'
            'if [[ "$(command -v java)" != "$JAVA_HOME/bin/java" ]]; then exit 127; fi\n'
            'exec /bin/bash "$TEST_ENTRYPOINT" "$@"\n')
        yallmo.chmod(0o755)
        self.env.update(JAVA_HOME=str(jdk), PATH=str(binaries) + os.pathsep + self.env["PATH"],
                        TEST_ENTRYPOINT=str(ROOT.parent / "image/entrypoint.sh"),
                        YALLMO_SBX_OPENAI_ACCESS_TOKEN="oai-oat01-proxy-managed",
                        YALLMO_SBX_OPENAI_REFRESH_TOKEN="oai-ort01-proxy-managed")
        result = self.boot(autostart=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.command("restart").returncode, 0)

    def test_disabled_autostart_allows_manual_start_stop_and_restart(self):
        self.boot()
        started = self.command("start")
        self.assertEqual(started.returncode, 0, started.stdout + started.stderr)
        first = self.pid(self.command("status"))
        restarted = self.command("restart")
        self.assertEqual(restarted.returncode, 0, restarted.stdout + restarted.stderr)
        self.assertNotEqual(first, self.pid(self.command("status")))
        self.assertEqual(self.command("stop").returncode, 0)
        self.assertIn("STOPPED", self.command("status").stdout)
        self.assertEqual(self.command("stop").returncode, 0)
        # Supervisor appends across starts/restarts instead of truncating the old run.
        text = (self.workspace / "logs/yallmo.log").read_text()
        self.assertGreaterEqual(text.count("stdout from fake Yallmo"), 2)
        self.assertIn("fake Yallmo stopped", text)

    def test_start_waits_for_http_readiness_not_just_a_running_pid(self):
        self.env["FAKE_STARTUP_DELAY"] = "3"
        self.boot()
        began = time.monotonic()
        result = self.command("start")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertGreaterEqual(time.monotonic() - began, 3)
        self.assertIn("(responding)", result.stdout)
        self.assertIn("fake Yallmo ready", (self.workspace / "logs/yallmo.log").read_text())

    def test_autostart_failure_is_reported_without_retrying_or_hiding_its_logs(self):
        with socket.socket() as existing:
            existing.bind(("127.0.0.1", 0))
            existing.listen(1)
            self.env["FAKE_PORT"] = self.env["YALLMO_SERVICE_PORT"] = str(existing.getsockname()[1])
            result = self.boot(autostart=True, expected="FATAL")
            self.assertEqual(result.returncode, 3)
            self.assertIn("(not ready)", result.stdout)
            log = (self.workspace / "logs/yallmo.log").read_text()
            self.assertIn("Address already in use", log)
            self.assertEqual(log.count("stdout from fake Yallmo"), 1)

    def test_repeated_start_and_boot_hook_keep_the_same_managed_process(self):
        first = self.boot(autostart=True)
        self.assertEqual(self.command("start").returncode, 0)
        duplicate_hook = self.command("supervise")
        self.assertEqual(duplicate_hook.returncode, 0, duplicate_hook.stderr)
        self.assertEqual(self.pid(first), self.pid(self.command("status")))

    def test_stop_is_preserved_across_reconnection_status_checks(self):
        self.boot(autostart=True)
        self.assertEqual(self.command("stop").returncode, 0)
        for _ in range(2):
            status = self.command("status", "--wait")
            self.assertEqual(status.returncode, 3)
            self.assertIn("STOPPED", status.stdout)
        self.assertEqual((self.workspace / "logs/yallmo.log").read_text().count("fake Yallmo ready"), 1)

    def test_port_conflict_fails_normally_and_leaves_the_other_listener_untouched(self):
        with socket.socket() as existing:
            existing.bind(("127.0.0.1", 0))
            existing.listen(1)
            self.env["FAKE_PORT"] = self.env["YALLMO_SERVICE_PORT"] = str(existing.getsockname()[1])
            self.boot()
            result = self.command("start")
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("yallmo-service logs", result.stderr)
            self.assertIn("FATAL", self.command("status").stdout)
            log = (self.workspace / "logs/yallmo.log").read_text()
            self.assertIn("Address already in use", log)
            self.assertEqual(self.command("stop").returncode, 0)
            self.assertEqual(existing.getsockopt(socket.SOL_SOCKET, socket.SO_ACCEPTCONN), 1)

    def test_live_logs_follow_a_rotated_file_without_stopping_the_service(self):
        self.boot(autostart=True)
        log = self.workspace / "logs/yallmo.log"
        output = self.root / "follow.output"
        with output.open("w") as stream:
            follower = subprocess.Popen([sys.executable, str(CONTROL), "logs", "--follow", "--lines", "1"],
                                        env=self.env, stdout=stream, stderr=subprocess.DEVNULL)
            try:
                deadline = time.monotonic() + 8
                while "fake Yallmo ready" not in output.read_text() and time.monotonic() < deadline:
                    time.sleep(0.1)
                self.assertIn("fake Yallmo ready", output.read_text())
                log.rename(log.with_suffix(".log.1"))
                log.write_text("synthetic line after rotation\n")
                deadline = time.monotonic() + 8
                while "after rotation" not in output.read_text() and time.monotonic() < deadline:
                    time.sleep(0.1)
                self.assertIn("synthetic line after rotation", output.read_text())
            finally:
                follower.terminate()
                follower.wait(timeout=5)
        self.assertIn("RUNNING", self.command("status").stdout)

    @unittest.skipUnless((ROOT.parents[1] / "target/docker/lib").is_dir() and shutil.which("java"),
                         "run sbt dockerStage first; Java required")
    def test_actual_staged_jvm_starts_and_stops_under_the_service_manager(self):
        (self.workspace / "storage").mkdir()
        (self.workspace / "server.sh").write_text(
            '#!/bin/sh\nexec "$TEST_JAVA" --enable-native-access=ALL-UNNAMED '
            '--sun-misc-unsafe-memory-access=allow -cp "$TEST_CLASSPATH" '
            'fmgp.yallmo.server.OrchestratorServerMain --storage ./storage --port "$FAKE_PORT"\n')
        self.env.update(TEST_JAVA=shutil.which("java"),
                        TEST_CLASSPATH=str(ROOT.parents[1] / "target/docker/lib/*"))
        result = self.boot(autostart=True)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("(responding)", result.stdout)
        self.assertEqual(self.command("stop").returncode, 0)
        self.assertIn("STOPPED", self.command("status").stdout)
        self.assertGreater((self.workspace / "logs/yallmo.log").stat().st_size, 0)

    def test_start_recovers_a_missing_manager_and_stop_without_a_manager_is_harmless(self):
        self.assertEqual(self.command("stop").returncode, 0)
        started = self.command("start")
        self.assertEqual(started.returncode, 0, started.stdout + started.stderr)
        self.assertIn("RUNNING", self.command("status").stdout)
        self.assertEqual(self.command("stop").returncode, 0)


if __name__ == "__main__":
    unittest.main()
