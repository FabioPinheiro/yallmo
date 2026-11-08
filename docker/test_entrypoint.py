"""Fast, dependency-free launcher tests; these do not substitute for native image smoke tests."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ENTRYPOINT = Path(__file__).with_name("entrypoint.sh")


class EntrypointTests(unittest.TestCase):
    def invoke(self, *arguments):
        with tempfile.TemporaryDirectory() as temp:
            java = Path(temp) / "java"
            java.write_text("#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n")
            java.chmod(0o755)
            environment = dict(os.environ, PATH=temp + os.pathsep + os.environ["PATH"])
            return subprocess.run(["bash", str(ENTRYPOINT), *arguments], env=environment,
                                  text=True, capture_output=True, check=False)

    def test_server_arguments_are_preserved_without_evaluation(self):
        result = self.invoke("server", "--storage", "/some directory", "--config", "$(false);private.yaml")
        self.assertEqual(result.returncode, 0, result.stderr)
        arguments = json.loads(result.stdout)
        self.assertIn("fmgp.yallmo.server.OrchestratorServerMain", arguments)
        self.assertEqual(arguments[-4:], ["--storage", "/some directory", "--config", "$(false);private.yaml"])
        self.assertIn("/opt/yallmo/lib/*", arguments)

    def test_default_server_uses_the_data_volume_root(self):
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(result.stdout)[-2:], ["--storage", "/data"])

    def test_repl_dispatch(self):
        result = self.invoke("repl", "--user-name", "alice")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("fmgp.yallmo.repl.ReplMain", json.loads(result.stdout))

    def test_arbitrary_command(self):
        result = self.invoke("python3", "-c", "print('python works')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "python works")

    def test_failure_is_not_hidden(self):
        result = self.invoke("bash", "-c", "exit 37")
        self.assertEqual(result.returncode, 37)


if __name__ == "__main__":
    unittest.main()
