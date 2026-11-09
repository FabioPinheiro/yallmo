"""Harmless build-context sentinels using Docker SDK's matcher, without a daemon or real secrets.

Optional locally: install `docker` into a venv or a temporary --target directory. CI installs it
before these tests. This guards ignore-rule regressions; an actual BuildKit build is still required.
"""
from pathlib import Path
import tempfile
import unittest

try:
    from docker.utils.build import exclude_paths
except ModuleNotFoundError:
    exclude_paths = None

POLICY = Path(__file__).resolve().parents[1] / "Dockerfile.dockerignore"


@unittest.skipUnless(exclude_paths, "Docker SDK required for context sentinel tests (pip package: docker)")
class ContextTests(unittest.TestCase):
    def test_build_inputs_admitted_and_private_sentinels_excluded(self):
        allowed = [
            "build.sbt", "project/build.properties", "project/plugins.sbt",
            "core/jvm/src/main/scala/Example.scala", "core/shared/src/main/resources/example.json",
            "web-harness/src/main/scala/Main.scala", "web-harness/vite/package-lock.json",
            "web-harness/vite/vite.config.js", "browser-STT-TTS/index.html",
            "docker/image/install-sbt.py", "docker/image/install-coursier.py", "docker/image/entrypoint.sh",
            "docker/image/coursier-env.sh", "docker/image/coursier-tool.sh",
            "repl/src/main/scala/fmgp/yallmo/repl/debug/Example.scala",
        ]
        # Every existing production source tree must stay available, not just one representative module.
        source_trees = [
            "model/shared", "model/jvm", "model/js", "filesystem/shared", "filesystem/jvm",
            "scalacode-api/shared", "scalacode-api/jvm", "core/shared", "core/jvm", "protocol/shared",
            "rpc/shared", "rpc/jvm", "client-harness/shared", "repl", "server", "web-harness",
        ]
        allowed += [tree + "/src/main/scala/Required.scala" for tree in source_trees]
        denied = [
            ".env", "yallmo.yaml", "server-storage/data.json", "web-harness/vite/.npmrc",
            "core/.pi/auth.json", "core/private.json", "core/jvm/src/main/resources/.env",
            "core/jvm/src/main/resources/.ssh/id_rsa", "core/jvm/src/main/resources/yallmo.yaml",
            "web-harness/vite/node_modules/secret.js", "project/metals.sbt", "docker/private.json",
            "docker/yallmo-sandbox/pi/agent/auth.json", "docker/yallmo-sandbox/pi/agent/models-store.json",
            "docker/yallmo-sandbox/workspace/storage/private.json", "docker/yallmo-sandbox/workspace/.env",
            "docker/yallmo-sandbox/yallmo-workspace/storage/private.json",
            "docker/yallmo-sandbox/yallmo-workspace/logs/yallmo.log",
            "docker/yallmo-sandbox/yallmo-workspace/yallmo.yaml",
            "core/jvm/src/test/scala/PrivateTest.scala", "core/private/src/main/resources/private.json",
            "private-project/shared/src/main/resources/private.json", "model/js/src/main/resources/.npmrc",
            "docker/image/private.json", "docker/image/extra.py", "docker/publishing/push-image.sh",
            "docker/tests/test_context.py", "docker/tests/fixtures/server-config.yaml", "Dockerfile",
        ]
        patterns = [line for line in POLICY.read_text().splitlines() if line and not line.startswith("#")]
        with tempfile.TemporaryDirectory(prefix="yallmo-context-") as root:
            for name in allowed + denied:
                path = Path(root) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("Harmless sentinel, not a credential.\n")
            included = set(exclude_paths(root, patterns, dockerfile="docker/Dockerfile"))
        self.assertFalse(set(allowed) - included, "Required build inputs excluded: " + str(set(allowed) - included))
        self.assertFalse(set(denied) & included, "Private sentinels admitted: " + str(set(denied) & included))


if __name__ == "__main__":
    unittest.main()
