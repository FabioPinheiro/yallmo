"""Exercise publishing order/failure gates with fake CLIs: no daemon, registry, credentials or uploads."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).with_name("publish.sh").resolve()
BASH = shutil.which("bash")
FAKE = r'''#!/usr/bin/env python3
import json, os, pathlib, sys
program = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
log = pathlib.Path(os.environ["PUBLISH_TEST_LOG"])
with log.open("a") as stream:
    stream.write(json.dumps({"program": program, "args": args, "cwd": os.getcwd(),
                            "platform": os.environ.get("DOCKER_DEFAULT_PLATFORM")}) + "\n")
if program == "docker" and args[:2] == ["buildx", "inspect"]:
    print("Platforms: linux/arm64, linux/amd64")
if program == "trivy" and args[:1] == ["image"]:
    scans = [json.loads(line) for line in log.read_text().splitlines()]
    scans = [row for row in scans if row["program"] == "trivy" and row["args"][:1] == ["image"]]
    if len(scans) == int(os.environ.get("FAIL_SCAN", "0")):
        sys.exit(42)
if program == "bash" and os.environ.get("FAIL_SMOKE") == "1":
    sys.exit(43)
'''


@unittest.skipUnless(BASH, "Bash required")
class PublishTests(unittest.TestCase):
    def invoke(self, *arguments, **overrides):
        with tempfile.TemporaryDirectory(prefix="publish-test-") as temp:
            root = Path(temp)
            for name in ("docker", "trivy", "curl", "bash"):
                path = root / name
                path.write_text(FAKE)
                path.chmod(0o755)
            log = root / "calls.jsonl"
            environment = dict(os.environ, PATH=temp + os.pathsep + os.environ["PATH"],
                               PUBLISH_TEST_LOG=str(log), FAIL_SCAN="0", FAIL_SMOKE="0")
            environment.update(overrides)
            # The real Bash starts publish.sh; only its child smoke.sh invocation is replaced by a fake.
            result = subprocess.run([BASH, str(SCRIPT), *arguments], cwd=temp, env=environment,
                                    text=True, capture_output=True, check=False)
            calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            return result, calls

    @staticmethod
    def uploads(calls):
        return [row for row in calls if row["program"] == "docker" and
                (row["args"][:1] == ["push"] or row["args"][:3] == ["buildx", "imagetools", "create"])]

    def test_all_four_images_pass_before_any_upload_and_manifests_reuse_them(self):
        result, calls = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        builds = [row for row in calls if row["program"] == "docker" and row["args"][:2] == ["buildx", "build"]]
        smokes = [row for row in calls if row["program"] == "bash"]
        scans = [row for row in calls if row["program"] == "trivy" and row["args"][:1] == ["image"]]
        self.assertEqual((len(builds), len(smokes), len(scans)), (4, 4, 4))
        self.assertEqual([row["platform"] for row in smokes], ["linux/arm64", "linux/amd64"] * 2)
        self.assertTrue(all(row["cwd"] == str(SCRIPT.parents[1]) for row in builds))
        self.assertTrue(all("--no-cache" in row["args"] and "--pull" in row["args"] for row in builds))
        uploads = self.uploads(calls)
        self.assertEqual(len(uploads), 6)
        self.assertLess(max(calls.index(row) for row in scans), min(calls.index(row) for row in uploads))
        pushed = {row["args"][1] for row in uploads if row["args"][:1] == ["push"]}
        manifests = [row["args"] for row in uploads if row["args"][:3] == ["buildx", "imagetools", "create"]]
        self.assertEqual({args[4] for args in manifests}, {"fmgp/yallmo:latest", "fmgp/yallmo:dev"})
        self.assertEqual({ref for args in manifests for ref in args[5:]}, pushed)

    def test_last_scan_failure_still_prevents_all_uploads(self):
        result, calls = self.invoke(FAIL_SCAN="4")
        self.assertEqual(result.returncode, 42)
        self.assertEqual(self.uploads(calls), [])

    def test_smoke_failure_prevents_all_uploads(self):
        result, calls = self.invoke(FAIL_SMOKE="1")
        self.assertEqual(result.returncode, 43)
        self.assertEqual(self.uploads(calls), [])

    def test_custom_repository(self):
        result, calls = self.invoke("example/yallmo")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(all(row["args"][1].startswith("example/yallmo:")
                            for row in self.uploads(calls) if row["args"][:1] == ["push"]))

    def test_help_and_invalid_repository_do_not_call_external_tools(self):
        result, calls = self.invoke("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("fmgp/yallmo", result.stdout)
        self.assertEqual(calls, [])
        result, calls = self.invoke("invalid:tag")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
