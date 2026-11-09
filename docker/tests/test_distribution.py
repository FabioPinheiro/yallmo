"""Smoke the actual staged JVM classpath without Docker: build with sbt 'dockerStage' first."""
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest
from urllib.error import URLError
from urllib.request import urlopen

LIB = Path(__file__).resolve().parents[2] / "target/docker/lib"


@unittest.skipUnless(LIB.is_dir() and shutil.which("java"), "run sbt dockerStage first; Java required")
class DistributionTests(unittest.TestCase):
    def test_server_serves_packaged_frontend(self):
        self.assert_server_starts(configured=False)

    def test_secret_free_chatgpt_configuration_starts_without_login(self):
        self.assert_server_starts(configured=True)

    def assert_server_starts(self, configured):
        # Pick a loopback ephemeral port. There is a small bind race; the server's logs explain failures.
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        with tempfile.TemporaryDirectory() as temp:
            log_path = Path(temp) / "server.log"
            options = ["--storage", temp, "--port", str(port)]
            if configured:
                workspace = Path(temp) / "workspace"
                workspace.mkdir()
                config = Path(temp) / "config.yaml"
                example = (Path(__file__).parent / "fixtures/server-config.yaml").read_text()
                config.write_text(example.replace("root: /workspace", "root: " + str(workspace)))
                config.chmod(0o600)
                options += ["--config", str(config), "--workspace", str(workspace)]
            with log_path.open("w+") as log:
                process = subprocess.Popen([
                    "java", "--enable-native-access=ALL-UNNAMED", "--sun-misc-unsafe-memory-access=allow",
                    "-cp", str(LIB / "*"), "fmgp.yallmo.server.OrchestratorServerMain", *options,
                ], stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 45
                    while time.monotonic() < deadline and process.poll() is None:
                        try:
                            with urlopen(f"http://127.0.0.1:{port}/", timeout=1) as response:
                                self.assertEqual(response.status, 200)
                                self.assertIn(b"<!doctype html", response.read().lower())
                                return
                        except (URLError, TimeoutError):
                            time.sleep(0.2)
                    self.fail("Packaged server failed to start:\n" + log_path.read_text()[-8000:])
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)


if __name__ == "__main__":
    unittest.main()
