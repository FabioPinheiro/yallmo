"""Exercise arbitrary-UID smoke payloads without Docker, downloads or UID changes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent / "smoke.sh"


@unittest.skipUnless(shutil.which("bash"), "Bash required")
class SmokeTests(unittest.TestCase):
    def test_alternate_uids_run_scala_cli_in_private_writable_workspaces(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            binaries = root / "bin"
            binaries.mkdir()
            calls = root / "calls.jsonl"
            docker = binaries / "docker"
            docker.write_text(
                '#!/usr/bin/env python3\n'
                'import json, os, sys\n'
                'args = sys.argv[1:]\n'
                'with open(os.environ["SMOKE_CALLS"], "a") as log:\n'
                '    log.write(json.dumps(args) + "\\n")\n'
                '# Stop before the server checks; no real containers are started.\n'
                'sys.exit(42 if "1001:1001" in args else 0)\n'
            )
            docker.chmod(0o755)
            environment = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                               SMOKE_CALLS=str(calls), BASH_ENV="/dev/null")
            result = subprocess.run(["bash", str(SCRIPT), "test-image"], env=environment,
                                    text=True, capture_output=True, timeout=10)
            self.assertEqual(result.returncode, 42, result.stderr)
            invocations = [json.loads(line) for line in calls.read_text().splitlines()]
            checks = [args for args in invocations if "--user" in args]
            self.assertEqual([args[args.index("--user") + 1] for args in checks],
                             ["0:0", "1001:1001"])

            shared = root / "shared/bin"
            shared.mkdir(parents=True)
            for name in ("scala", "scala-cli"):
                executable = shared / name
                executable.touch(mode=0o555)
            shared.chmod(0o555)
            launcher = binaries / "cs"
            launcher.write_text(
                '#!/usr/bin/env python3\n'
                'import os\n'
                'from pathlib import Path\n'
                '# Version probes must not put build state in the inherited workspace.\n'
                'assert str(Path.cwd()) != os.environ["SMOKE_INHERITED_WORKSPACE"]\n'
                'Path(".scala-build").mkdir(exist_ok=True)\n'
                'with open(os.environ["SMOKE_WORKSPACES"], "a") as log:\n'
                '    log.write(str(Path.cwd()) + "\\n")\n'
            )
            launcher.chmod(0o755)
            (binaries / "scala-cli").symlink_to(launcher)
            inherited = root / "inherited-workspace"
            inherited.mkdir()
            workspaces = root / "workspaces"
            environment.update(SMOKE_INHERITED_WORKSPACE=str(inherited),
                               SMOKE_WORKSPACES=str(workspaces))
            for name in ("COURSIER_CACHE", "COURSIER_ARCHIVE_CACHE", "COURSIER_JVM_CACHE", "COURSIER_BIN_DIR",
                         "SCALA_CLI_HOME"):
                directory = root / name.lower()
                directory.mkdir()
                environment[name] = str(directory)
            try:
                for args in checks:
                    payload = args[-1].replace("/usr/share/coursier/bin", str(shared))
                    result = subprocess.run(["bash", "-euc", payload], cwd=inherited,
                                            env=environment, text=True, capture_output=True, timeout=10)
                    self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(list(inherited.iterdir()), [])
                paths = workspaces.read_text().splitlines()
                self.assertEqual(len(paths), 4)  # cs and scala-cli for each UID
                self.assertNotEqual(paths[0], paths[2])
                self.assertTrue(all(not Path(path).exists() for path in paths))
            finally:
                shared.chmod(0o755)


if __name__ == "__main__":
    unittest.main()
