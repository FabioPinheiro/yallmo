"""Publishing gates and digest-based final tags with fake CLIs: no daemon or registry."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "publish.sh"
BASH = shutil.which("bash")
DIGESTS = {"amd64": "sha256:" + "a" * 64, "arm64": "sha256:" + "b" * 64}
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
if program == "docker" and args[:1] == ["save"]:
    pathlib.Path(args[args.index("--output") + 1]).write_text("synthetic archive")
if program == "trivy" and args[:1] == ["image"]:
    scans = [json.loads(line) for line in log.read_text().splitlines()]
    scans = [row for row in scans if row["program"] == "trivy" and row["args"][:1] == ["image"]]
    if len(scans) == int(os.environ.get("FAIL_SCAN", "0")):
        sys.exit(42)
if program == "docker" and args[:1] == ["load"]:
    assert pathlib.Path(args[args.index("--input") + 1]).read_text() == "synthetic archive"
    if os.environ.get("FAIL_LOAD") == "1":
        sys.exit(45)
if program == "docker" and args[:1] == ["tag"] and os.environ.get("FAIL_TAG") == "1":
    sys.exit(46)
if program == "docker" and args[:1] == ["push"]:
    digest = "sha256:" + ("a" if args[-1].endswith("-amd64") else "b") * 64
    if os.environ.get("INVALID_DIGEST") == "1":
        digest = "not-a-digest"
    if os.environ.get("MISSING_DIGEST") != "1":
        print(args[-1] + ": digest: " + digest + " size: 1234")
    if os.environ.get("DUPLICATE_DIGEST") == "1":
        print(args[-1] + ": digest: " + digest + " size: 1234")
    pushes = [json.loads(line) for line in log.read_text().splitlines()]
    pushes = [row for row in pushes if row["program"] == "docker" and row["args"][:1] == ["push"]]
    if len(pushes) == int(os.environ.get("FAIL_UPLOAD", "0")):
        sys.exit(44)
if program == "bash" and os.environ.get("FAIL_SMOKE") == "1":
    sys.exit(43)
'''


@unittest.skipUnless(BASH, "Bash required")
class PublishTests(unittest.TestCase):
    def invoke(self, *arguments, _script=SCRIPT, **overrides):
        with tempfile.TemporaryDirectory(prefix="publish-test-") as temp:
            root = Path(temp)
            for name in ("docker", "trivy", "curl", "bash"):
                path = root / name
                path.write_text(FAKE)
                path.chmod(0o755)
            log = root / "calls.jsonl"
            environment = dict(os.environ, PATH=temp + os.pathsep + os.environ["PATH"],
                               PUBLISH_TEST_LOG=str(log), FAIL_SCAN="0", FAIL_SMOKE="0",
                               INVALID_DIGEST="0", MISSING_DIGEST="0", DUPLICATE_DIGEST="0",
                               FAIL_UPLOAD="0", FAIL_LOAD="0", FAIL_TAG="0")
            environment.update(overrides)
            result = subprocess.run([BASH, str(_script), *arguments], cwd=temp, env=environment,
                                    text=True, capture_output=True, check=False)
            calls = [json.loads(line) for line in log.read_text().splitlines()] if log.exists() else []
            return result, calls

    @staticmethod
    def manifests(calls):
        return [row for row in calls if row["program"] == "docker" and
                row["args"][:3] == ["buildx", "imagetools", "create"]]

    @classmethod
    def uploads(cls, calls):
        return cls.manifests(calls) + [row for row in calls if row["args"][:1] == ["push"] and
                                       row["program"] == "docker"]

    def assert_publication(self, calls, arches, repo="fmgp/yallmo"):
        builds = [row for row in calls if row["program"] == "docker" and row["args"][:2] == ["buildx", "build"]]
        smokes = [row for row in calls if row["program"] == "bash"]
        scans = [row for row in calls if row["program"] == "trivy" and row["args"][:1] == ["image"]]
        pushes = [row for row in calls if row["program"] == "docker" and row["args"][:1] == ["push"]]
        loads = [row for row in calls if row["program"] == "docker" and row["args"][:1] == ["load"]]
        tags = [row for row in calls if row["program"] == "docker" and row["args"][:1] == ["tag"]]
        self.assertEqual((len(builds), len(smokes), len(scans), len(pushes), len(loads), len(tags)),
                         (len(arches),) * 6)
        self.assertEqual([row["platform"] for row in smokes], ["linux/" + arch for arch in arches])
        self.assertEqual([row["args"][row["args"].index("--platform") + 1] for row in builds],
                         ["linux/" + arch for arch in arches])
        self.assertTrue(all(row["args"][row["args"].index("--target") + 1] == "runtime" for row in builds))
        self.assertTrue(all(row["cwd"] == str(SCRIPT.parents[1]) for row in builds))
        self.assertTrue(all("--no-cache" in row["args"] and "--pull" in row["args"] for row in builds))
        built = {row["args"][row["args"].index("--tag") + 1] for row in builds}
        self.assertEqual({row["args"][1] for row in smokes}, built)
        archives = {row["args"][row["args"].index("--input") + 1] for row in scans}
        self.assertEqual({row["args"][2] for row in loads}, archives)
        self.assertEqual({row["args"][1] for row in tags}, built)
        staging = {row["args"][2] for row in tags}
        self.assertEqual({row["args"][1] for row in pushes}, staging)
        self.assertEqual(staging, {repo + ":staging-" + arch for arch in arches})
        for row, arch in zip(tags, arches):
            self.assertTrue(row["args"][1].endswith("-runtime-" + arch))
            self.assertEqual(row["args"][2], repo + ":staging-" + arch)
        for load, tag, push in zip(loads, tags, pushes):
            self.assertLess(calls.index(load), calls.index(tag))
            self.assertLess(calls.index(tag), calls.index(push))
        refs = {repo + "@" + DIGESTS[arch] for arch in arches}
        self.assertLess(max(calls.index(row) for row in scans), min(calls.index(row) for row in loads))
        self.assertLess(max(calls.index(row) for row in scans), min(calls.index(row) for row in self.uploads(calls)))
        manifests = self.manifests(calls)
        self.assertLess(max(calls.index(row) for row in pushes), min(calls.index(row) for row in manifests))
        versioned = [row["args"][4] for row in manifests if row["args"][4].startswith(repo + ":build-")]
        self.assertEqual(len(versioned), 1)
        self.assertRegex(versioned[0], "^" + repo + r":build-\d+-\d+-\d+$")
        expected = set(versioned)
        if len(arches) == 2:
            expected.add(repo + ":latest")
        self.assertEqual({row["args"][4] for row in manifests}, expected)
        for row in manifests:
            sources = set(row["args"][5:])
            self.assertEqual(sources, refs)
        registry_tags = staging | {row["args"][4] for row in manifests}
        self.assertFalse(any(":upload-" in tag or ":latest-" in tag for tag in registry_tags))

    def test_both_architectures_push_scanned_archives_then_publish_by_digest(self):
        result, calls = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_publication(calls, ["arm64", "amd64"])

    def test_single_architecture_and_aliases_produce_one_build_tag_without_changing_latest(self):
        for selection, arch in (("x86_64", "amd64"), ("aarch64", "arm64"),
                                ("amd64", "amd64"), ("arm64", "arm64")):
            with self.subTest(selection=selection):
                result, calls = self.invoke("--arch", selection)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assert_publication(calls, [arch])

    def test_arch_equals_and_repository_can_be_used_together(self):
        result, calls = self.invoke("example/yallmo", "--arch=aarch64")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_publication(calls, ["arm64"], repo="example/yallmo")

    def test_invalid_selection_or_arguments_never_call_external_tools(self):
        for arguments in (("--arch",), ("--arch=",), ("--arch", "sparc"), ("--unknown",),
                          ("example/yallmo", "other/yallmo")):
            with self.subTest(arguments=arguments):
                result, calls = self.invoke(*arguments)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(calls, [])

    def test_single_architecture_scan_failure_prevents_all_uploads(self):
        result, calls = self.invoke("--arch=x86_64", FAIL_SCAN="1")
        self.assertEqual(result.returncode, 42)
        self.assertEqual(self.uploads(calls), [])

    def test_manifest_helper_rejects_invalid_or_duplicate_references_before_any_tag_update(self):
        helper = SCRIPT.parent / "publishing/publish-manifests.sh"
        valid = "amd64=" + DIGESTS["amd64"]
        for arguments in (("fmgp/yallmo", "build-test"),
                          ("fmgp/yallmo", "build-test", valid, valid),
                          ("fmgp/yallmo", "build-test", valid, "sparc=" + DIGESTS["arm64"]),
                          ("fmgp/yallmo", "build-test", valid, "arm64=not-a-digest"),
                          ("fmgp/yallmo", "invalid:tag", valid),
                          ("invalid:repo", "build-test", valid)):
            with self.subTest(arguments=arguments):
                result, calls = self.invoke(*arguments, _script=helper)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(calls, [])

    def test_invalid_missing_or_duplicate_push_digest_prevents_final_tag_creation(self):
        for override in ("INVALID_DIGEST", "MISSING_DIGEST", "DUPLICATE_DIGEST"):
            with self.subTest(override=override):
                result, calls = self.invoke(**{override: "1"})
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertEqual(len(self.uploads(calls)), 1)
                self.assertEqual(self.manifests(calls), [])

    def test_archive_load_or_tag_failure_prevents_upload(self):
        for override, code in (("FAIL_LOAD", 45), ("FAIL_TAG", 46)):
            with self.subTest(override=override):
                result, calls = self.invoke(**{override: "1"})
                self.assertEqual(result.returncode, code)
                self.assertEqual(self.uploads(calls), [])

    def test_push_helper_rejects_invalid_staging_refs_before_external_tools(self):
        helper = SCRIPT.parent / "publishing/push-image.sh"
        for arguments in (("archive.tar", "source", "fmgp/yallmo"),
                          ("archive.tar", "source", "fmgp/yallmo:latest", "digest.txt"),
                          ("archive.tar", "source", "invalid:repo:staging-amd64", "digest.txt"),
                          ("archive.tar", "source", "fmgp/yallmo:upload-old", "digest.txt"),
                          ("archive.tar", "source", "fmgp/yallmo:staging-sparc", "digest.txt"),
                          ("archive.tar", "source", "fmgp/yallmo:staging-amd64-extra", "digest.txt")):
            with self.subTest(arguments=arguments):
                result, calls = self.invoke(*arguments, _script=helper)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(calls, [])

    def test_second_upload_failure_prevents_all_final_tags(self):
        result, calls = self.invoke(FAIL_UPLOAD="2")
        self.assertEqual(result.returncode, 44)
        self.assertEqual(self.manifests(calls), [])

    def test_last_scan_failure_still_prevents_all_uploads(self):
        result, calls = self.invoke(FAIL_SCAN="2")
        self.assertEqual(result.returncode, 42)
        self.assertEqual(self.uploads(calls), [])

    def test_smoke_failure_prevents_all_uploads(self):
        result, calls = self.invoke(FAIL_SMOKE="1")
        self.assertEqual(result.returncode, 43)
        self.assertEqual(self.uploads(calls), [])

    def test_repeated_runs_reuse_only_the_two_architecture_staging_tags(self):
        for _ in range(2):
            result, calls = self.invoke()
            self.assertEqual(result.returncode, 0, result.stderr)
            pushes = [row["args"][1] for row in calls
                      if row["program"] == "docker" and row["args"][:1] == ["push"]]
            self.assertEqual(pushes, ["fmgp/yallmo:staging-arm64", "fmgp/yallmo:staging-amd64"])

    def test_custom_repository(self):
        result, calls = self.invoke("example/yallmo")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assert_publication(calls, ["arm64", "amd64"], repo="example/yallmo")

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
