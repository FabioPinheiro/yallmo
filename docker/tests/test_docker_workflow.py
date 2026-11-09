"""Execute the workflow's architecture selector locally, without GitHub, Docker or secrets."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

WORKFLOW = Path(__file__).resolve().parents[2] / ".github/workflows/docker.yml"


@unittest.skipUnless(yaml and shutil.which("bash"), "PyYAML and Bash required")
class DockerWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.workflow = yaml.safe_load(WORKFLOW.read_text())

    def test_only_manual_and_weekly_dispatch_are_enabled_and_default_to_both_architectures(self):
        # PyYAML's YAML 1.1 parser treats the unquoted GitHub key `on` as True.
        triggers = self.workflow.get("on", self.workflow.get(True))
        self.assertEqual(set(triggers), {"workflow_dispatch", "schedule"})
        self.assertEqual(triggers["schedule"], [{"cron": "23 5 * * 0"}])
        architecture = triggers["workflow_dispatch"]["inputs"]["architecture"]
        self.assertEqual(architecture["type"], "choice")
        self.assertEqual(architecture["default"], "both")
        self.assertEqual(architecture["options"], ["both", "x86_64", "aarch64"])

    def test_scheduled_runs_default_to_both_and_keep_publication_safety_gates(self):
        selector = self.workflow["jobs"]["plan"]["steps"][0]
        self.assertEqual(selector["env"]["REQUESTED_ARCH"], "${{ inputs.architecture || 'both' }}")
        for condition in (self.workflow["jobs"]["verify"]["env"]["PUBLISH"],
                          self.workflow["jobs"]["publish"]["if"]):
            self.assertIn("github.event_name == 'schedule'", condition)
            self.assertIn("github.event_name == 'workflow_dispatch'", condition)
            self.assertIn("github.ref == 'refs/heads/main'", condition)
            self.assertIn("vars.DOCKERHUB_PUBLISH == 'true'", condition)
            self.assertIn("vars.DOCKERHUB_USERNAME != ''", condition)

    def select(self, architecture):
        script = self.workflow["jobs"]["plan"]["steps"][0]["run"]
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "outputs"
            result = subprocess.run(["bash", "-euc", script],
                                    env=dict(os.environ, REQUESTED_ARCH=architecture, GITHUB_OUTPUT=str(output)),
                                    text=True, capture_output=True)
            values = dict(line.split("=", 1) for line in output.read_text().splitlines()) if output.exists() else {}
            return result, values

    def test_selected_architectures_only_schedule_their_native_runners(self):
        runners = {"amd64": "ubuntu-latest", "arm64": "ubuntu-24.04-arm"}
        for selection, expected in (("both", ["amd64", "arm64"]),
                                    ("x86_64", ["amd64"]), ("aarch64", ["arm64"])):
            with self.subTest(selection=selection):
                result, output = self.select(selection)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(output["architectures"].split(), expected)
                self.assertEqual(json.loads(output["matrix"])["include"],
                                 [{"arch": arch, "runner": runners[arch]} for arch in expected])
        verify = self.workflow["jobs"]["verify"]
        self.assertEqual(verify["needs"], "plan")
        self.assertEqual(verify["strategy"]["matrix"], "${{ fromJSON(needs.plan.outputs.matrix) }}")

    def test_invalid_selection_is_rejected_without_evaluating_input(self):
        for selection in ("", "sparc", '$(printf "must-not-execute" >&2)'):
            with self.subTest(selection=selection):
                result, output = self.select(selection)
                self.assertEqual(result.returncode, 2)
                self.assertEqual(output, {})
                self.assertNotIn("must-not-execute", result.stdout + result.stderr)

    def test_publication_uses_the_same_selected_architectures_and_shared_tagging_script(self):
        publish = self.workflow["jobs"]["publish"]
        self.assertEqual(set(publish["needs"]), {"plan", "verify"})
        self.assertIn("refs/heads/main", publish["if"])
        self.assertIn("DOCKERHUB_PUBLISH", publish["if"])
        step = publish["steps"][-1]
        self.assertEqual(step["env"]["ARCHITECTURES"], "${{ needs.plan.outputs.architectures }}")
        self.assertIn("bash docker/publishing/publish-manifests.sh", step["run"])
        uploads = [step for step in self.workflow["jobs"]["verify"]["steps"]
                   if step.get("uses", "").startswith("actions/upload-artifact@")]
        self.assertEqual(len(uploads), 1)
        self.assertEqual(uploads[0]["with"]["name"], "image-digest-${{ github.run_attempt }}-${{ matrix.arch }}")
        download = next(step for step in publish["steps"]
                        if step.get("uses", "").startswith("actions/download-artifact@"))
        self.assertEqual(download["with"]["pattern"], "image-digest-${{ github.run_attempt }}-*")
        self.assertTrue(uploads[0]["with"]["path"].endswith("${{ matrix.arch }}.txt"))
        push = next(step for step in self.workflow["jobs"]["verify"]["steps"]
                    if "bash docker/publishing/push-image.sh" in step.get("run", ""))
        self.assertEqual(push["if"], "env.PUBLISH == 'true'")
        self.assertNotIn("UPLOAD_ID", push["env"])
        self.assertIn('"$IMAGE" "$REPOSITORY:staging-$ARCH"', push["run"])
        self.assertNotIn("crane", WORKFLOW.read_text())

    def test_upload_step_passes_scanned_archive_and_reusable_staging_tag_to_shared_helper(self):
        step = next(step for step in self.workflow["jobs"]["verify"]["steps"]
                    if "bash docker/publishing/push-image.sh" in step.get("run", ""))
        for arch in ("amd64", "arm64"):
            with self.subTest(arch=arch), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                fake = root / "bash"
                fake.write_text('#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
                fake.chmod(0o755)
                result = subprocess.run([shutil.which("bash"), "-euc", step["run"]],
                                        text=True, capture_output=True,
                                        env=dict(os.environ, PATH=temporary + os.pathsep + os.environ["PATH"],
                                                 RUNNER_TEMP=temporary, REPOSITORY="fmgp/yallmo",
                                                 IMAGE="yallmo:latest", ARCH=arch))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout),
                                 ["docker/publishing/push-image.sh", temporary + "/yallmo-scan/image.tar",
                                  "yallmo:latest", "fmgp/yallmo:staging-" + arch,
                                  temporary + "/yallmo-digests/" + arch + ".txt"])

    def test_publication_reads_only_selected_digests_and_passes_one_clean_build_id(self):
        script = self.workflow["jobs"]["publish"]["steps"][-1]["run"]
        for arches in (["amd64"], ["arm64"], ["amd64", "arm64"]):
            with self.subTest(arches=arches), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                (root / "yallmo-digests").mkdir()
                digests = {arch: "sha256:" + ("a" if arch == "amd64" else "b") * 64 for arch in arches}
                # An unselected architecture's stale artifact must never join this build.
                for arch in ("amd64", "arm64"):
                    digest = digests.get(arch, "sha256:" + "c" * 64)
                    (root / "yallmo-digests" / (arch + ".txt")).write_text(digest + "\n")
                fake = root / "bash"
                fake.write_text('#!/usr/bin/env python3\nimport json,sys\nprint(json.dumps(sys.argv[1:]))\n')
                fake.chmod(0o755)
                result = subprocess.run([shutil.which("bash"), "-euc", script], text=True, capture_output=True,
                                        env=dict(os.environ, PATH=temporary + os.pathsep + os.environ["PATH"],
                                                 RUNNER_TEMP=temporary, REPOSITORY="fmgp/yallmo", BUILD_TAG="build-123-1",
                                                 ARCHITECTURES=" ".join(arches)))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(json.loads(result.stdout), ["docker/publishing/publish-manifests.sh", "fmgp/yallmo", "build-123-1"] +
                                 [arch + "=" + digests[arch] for arch in arches])

    def test_missing_selected_digest_stops_before_tagging(self):
        script = self.workflow["jobs"]["publish"]["steps"][-1]["run"]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "yallmo-digests").mkdir()
            (root / "yallmo-digests/amd64.txt").write_text("sha256:" + "a" * 64 + "\n")
            fake = root / "bash"
            fake.write_text('#!/bin/sh\nprintf "must-not-tag"\n')
            fake.chmod(0o755)
            result = subprocess.run([shutil.which("bash"), "-euc", script], text=True, capture_output=True,
                                    env=dict(os.environ, PATH=temporary + os.pathsep + os.environ["PATH"],
                                             RUNNER_TEMP=temporary, REPOSITORY="fmgp/yallmo", BUILD_TAG="build-123-2",
                                             ARCHITECTURES="amd64 arm64"))
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("must-not-tag", result.stdout)


if __name__ == "__main__":
    unittest.main()
