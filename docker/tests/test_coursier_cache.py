"""Shared tool/private cache contract; synthetic artifacts only, no downloads or privilege changes."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1] / "image"


@unittest.skipUnless(shutil.which("bash") and shutil.which("flock"), "Bash and flock required")
class CoursierCacheTests(unittest.TestCase):
    def fixture(self, root):
        users = root / "users"
        users.mkdir(mode=0o1777)
        shared = root / "shared"
        seed = shared / "bootstrap/cache/https/example.com"
        seed.mkdir(parents=True)
        (seed / "public.jar").write_text("public image artifact")
        helper = root / "coursier-env.sh"
        helper.write_text((ROOT / "coursier-env.sh").read_text()
                          .replace("/var/cache/coursier", str(users))
                          .replace("/usr/share/coursier", str(shared)))
        return users, shared, helper

    def invoke(self, helper, command, **overrides):
        environment = {k: v for k, v in os.environ.items()
                       if not k.startswith(("COURSIER_", "SCALA_CLI_")) and k != "BASH_ENV"}
        environment.update(overrides)
        return subprocess.run(["bash", "-euc", 'source "$1"; ' + command, "test", str(helper)],
                              env=environment, text=True, capture_output=True, timeout=30)

    def test_defaults_are_private_writable_seeded_copies_without_mutating_shared_artifacts(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            users, shared, helper = self.fixture(root)
            command = 'printf "%s\\n" "$COURSIER_CACHE" "$COURSIER_ARCHIVE_CACHE" "$COURSIER_BIN_DIR"'
            result = self.invoke(helper, command)
            self.assertEqual(result.returncode, 0, result.stderr)
            state = users / str(os.getuid())
            self.assertEqual(result.stdout.splitlines(), [str(state / "cache"), str(state / "arc"), str(state / "bin")])
            self.assertEqual(state.stat().st_mode & 0o777, 0o700)
            cached = state / "cache/https/example.com/public.jar"
            original = shared / "bootstrap/cache/https/example.com/public.jar"
            self.assertEqual(cached.read_text(), original.read_text())
            self.assertNotEqual(cached.stat().st_ino, original.stat().st_ino)
            cached.write_text("user changed artifact")
            again = self.invoke(helper, command)
            self.assertEqual(again.returncode, 0, again.stderr)
            self.assertEqual(cached.read_text(), "user changed artifact")
            self.assertEqual(original.read_text(), "public image artifact")
            for name in ("cache", "arc", "jvm", "bin", "scalacli"):
                self.assertTrue(os.access(state / name, os.W_OK | os.X_OK))

    def test_explicit_cache_and_install_directory_overrides_are_respected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            users, _, helper = self.fixture(root)
            overrides = {name: str(root / name.lower()) for name in (
                "COURSIER_CACHE", "COURSIER_ARCHIVE_CACHE", "COURSIER_JVM_CACHE", "COURSIER_BIN_DIR",
                "SCALA_CLI_HOME")}
            result = self.invoke(helper, 'printf "%s\\n" "$COURSIER_CACHE" "$PATH"', **overrides)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines()[0], overrides["COURSIER_CACHE"])
            self.assertEqual(result.stdout.splitlines()[1].split(":")[0], overrides["COURSIER_BIN_DIR"])
            self.assertFalse((users / str(os.getuid())).exists())
            self.assertTrue(all(Path(path).is_dir() for path in overrides.values()))

    def test_scala_cli_state_is_private_even_when_inherited_home_and_xdg_are_unusable(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            users, _, helper = self.fixture(root)
            # A file in place of HOME/XDG makes traversal fail, even when tests run as root.
            unavailable = root / "unavailable"
            unavailable.touch()
            result = self.invoke(helper, 'printf "%s\\n" "$SCALA_CLI_HOME" "$SCALA_CLI_CONFIG"',
                                 HOME=str(unavailable), XDG_CACHE_HOME=str(unavailable))
            self.assertEqual(result.returncode, 0, result.stderr)
            cli_home = users / str(os.getuid()) / "scalacli"
            self.assertEqual(result.stdout.splitlines(), [str(cli_home), str(cli_home / "config.json")])
            self.assertTrue(os.access(cli_home, os.W_OK | os.X_OK))
            # Explicit paths remain authoritative, including a separate config file.
            custom = root / "custom-cli"
            config = root / "custom-config.json"
            result = self.invoke(helper, 'printf "%s\\n" "$SCALA_CLI_HOME" "$SCALA_CLI_CONFIG"',
                                 SCALA_CLI_HOME=str(custom), SCALA_CLI_CONFIG=str(config))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), [str(custom), str(config)])
            self.assertTrue(custom.is_dir())

    @unittest.skipUnless(shutil.which("scala-cli"), "Scala CLI required for real launcher regression")
    def test_real_scala_cli_version_with_unusable_inherited_home(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            _, _, helper = self.fixture(root)
            unavailable = root / "unavailable"
            unavailable.touch()
            result = self.invoke(
                helper, 'cd -- "$TEST_WORKSPACE"; "$TEST_SCALA_CLI" --cli-default-scala-version 3.10.0 --version',
                HOME=str(unavailable), XDG_CACHE_HOME=str(unavailable),
                TEST_WORKSPACE=str(root), TEST_SCALA_CLI=shutil.which("scala-cli"))
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("Scala version (default): 3.10.0", result.stdout)

    def test_insecure_or_symlinked_default_directory_is_rejected_without_modification(self):
        for symlink in (False, True):
            with self.subTest(symlink=symlink), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                users, _, helper = self.fixture(root)
                state = users / str(os.getuid())
                if symlink:
                    target = root / "unrelated"
                    target.mkdir()
                    state.symlink_to(target, target_is_directory=True)
                else:
                    state.mkdir()
                    state.chmod(0o777)
                result = self.invoke(helper, 'printf "must-not-run"')
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn("must-not-run", result.stdout)
                if symlink:
                    self.assertEqual(list(target.iterdir()), [])
                else:
                    self.assertEqual(state.stat().st_mode & 0o777, 0o777)

    def test_tool_wrappers_configure_caches_and_preserve_arguments(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            users, shared, helper = self.fixture(root)
            binaries = root / "bin"
            binaries.mkdir()
            sbt = root / "sbt/bin/sbt"
            sbt.parent.mkdir(parents=True)
            wrapper = root / "coursier-tool.sh"
            wrapper.write_text((ROOT / "coursier-tool.sh").read_text()
                               .replace("/usr/local/libexec/coursier-env.sh", str(helper))
                               .replace("/usr/share/coursier", str(shared))
                               .replace("/opt/sbt/bin/sbt", str(sbt)))
            wrapper.chmod(0o755)
            for name in ("cs", "scala", "metals", "bloop", "scala-cli", "sbt"):
                target = "scala-cli" if name == "scala" else name
                executable = sbt if name == "sbt" else shared / (target if target == "cs" else "bin/" + target)
                executable.parent.mkdir(parents=True, exist_ok=True)
                executable.write_text('#!/bin/sh\nprintf "%s\\n" "$COURSIER_CACHE" "$@"\n')
                executable.chmod(0o755)
                (binaries / name).symlink_to(wrapper)
                result = subprocess.run([str(binaries / name), "space argument", "literal;$VALUE"],
                                        env={k: v for k, v in os.environ.items()
                                             if not k.startswith(("COURSIER_", "SCALA_CLI_")) and k != "BASH_ENV"},
                                        text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                defaults = ["--cli-default-scala-version", "3.10.0"] if name in ("scala", "scala-cli") else []
                self.assertEqual(result.stdout.splitlines(), [str(users / str(os.getuid()) / "cache"),
                                                             *defaults, "space argument", "literal;$VALUE"])


if __name__ == "__main__":
    unittest.main()
