"""Optional Zsh mixin tests: synthetic history only; no host credentials or Docker daemon."""
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile
import unittest

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

try:
    from docker.utils.build import exclude_paths
except ModuleNotFoundError:
    exclude_paths = None

ROOT = Path(__file__).resolve().parents[1] / "yallmo-sandbox"
ZSH = ROOT / "kit-zsh"


class ZshKitTests(unittest.TestCase):
    def test_mixin_explicitly_installs_zsh_and_checks_its_configuration(self):
        dockerfile = (ZSH / "kit.dockerfile").read_text()
        self.assertIn("FROM ${YALLMO_IMAGE}", dockerfile)
        self.assertIn("apt-get install -y --no-install-recommends zsh", dockerfile)
        self.assertIn("COPY zshrc /usr/share/yallmo-zsh/zshrc", dockerfile)
        self.assertNotIn("COPY --chown=1000:1000 zshrc /home/agent/.zshrc", dockerfile)
        self.assertIn("/bin/zsh --version", dockerfile)
        self.assertIn("/bin/zsh -n /tmp/zshrc", dockerfile)
        self.assertIn("FROM scratch", dockerfile)
        self.assertIn("COPY --from=zsh-build /out/ /", dockerfile)
        self.assertIn("comm -13 /tmp/packages-before /tmp/packages-after", dockerfile)
        self.assertNotIn("ENTRYPOINT", dockerfile)
        self.assertNotIn("zsh", (ROOT.parent / "Dockerfile").read_text())

    def test_package_diff_uses_consistent_byte_ordering(self):
        dockerfile = (ZSH / "kit.dockerfile").read_text()
        sort_commands = []
        for snapshot in ("before", "after"):
            match = re.search(r"\|\s*(LC_ALL=C sort) > /tmp/packages-" + snapshot, dockerfile)
            self.assertIsNotNone(match, f"{snapshot} snapshot must use C-locale sorting")
            sort_commands.append(match.group(1))
        match = re.search(r"LC_ALL=C comm -13 /tmp/packages-before /tmp/packages-after", dockerfile)
        self.assertIsNotNone(match, "comm must use the same locale as sort")
        # These package names reproduce sort/comm disagreement on Ubuntu's
        # uutils coreutils under en_US.UTF-8. Include a version upgrade as well.
        unchanged = "libc-bin\t2.43\nlibcap2-bin\t2.75\nlibcap-ng0:arm64\t0.8.5\n"
        added = "libcap2:arm64\t2.75\n"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "before.raw").write_text(unchanged + "zsh-common\t5.8\n")
            (root / "after.raw").write_text(unchanged + added + "zsh-common\t5.9\n")
            commands = []
            for snapshot, command in zip(("before", "after"), sort_commands):
                commands.append(f"{command} {shlex.quote(str(root / (snapshot + '.raw')))} "
                                f"> {shlex.quote(str(root / snapshot))}")
            diff = match.group(0).replace("/tmp/packages-before", shlex.quote(str(root / "before")))
            diff = diff.replace("/tmp/packages-after", shlex.quote(str(root / "after")))
            result = subprocess.run(["bash", "-e", "-o", "pipefail", "-c", "; ".join(commands + [diff])],
                                    env={**os.environ, "LC_ALL": "en_US.UTF-8"},
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, added + "zsh-common\t5.9\n")

    def test_configuration_persists_history_and_completions_without_overriding_credentials(self):
        config = (ZSH / "zshrc").read_text()
        self.assertIn('HISTFILE="$HOME/.zsh-data/zsh_history"', config)
        self.assertIn('compinit -d "$HOME/.zsh-data/zcompdump-${ZSH_VERSION}"', config)
        self.assertIn('"$HOME/.local/share/coursier/bin"', config)
        self.assertNotIn("export GITHUB_TOKEN=", config)
        self.assertNotIn("export GH_TOKEN=", config)
        self.assertNotIn(":~", config)
        self.assertIn("zsh-data/", (ZSH / ".gitignore").read_text().splitlines())

    @unittest.skipUnless(yaml, "PyYAML required for kit descriptor checks")
    def test_lifecycle_creates_agent_config_without_overwriting_local_edits(self):
        kit = yaml.safe_load((ZSH / "kit.yaml").read_text())
        lifecycle = next(item["config"] for item in kit["capabilities"]
                         if item["type"] == "com.docker.sandbox/lifecycle@1")
        self.assertEqual(len(lifecycle["files"]), 1)
        config = lifecycle["files"][0]
        self.assertEqual(config["path"], "/home/agent/.zshrc")
        self.assertEqual(config["mode"], "0644")
        self.assertIs(config["overwrite"], False)
        self.assertIn("source /usr/share/yallmo-zsh/zshrc", config["content"])
        # Ownership belongs to the agent by lifecycle.files contract, not root.
        self.assertNotIn("user", config)

    @unittest.skipUnless(yaml, "PyYAML required for kit descriptor checks")
    def test_volume_and_idempotent_initialization_preserve_history(self):
        kit = yaml.safe_load((ZSH / "kit.yaml").read_text())
        self.assertEqual(kit["kind"], "mixin")
        self.assertEqual(kit["dockerfile"], "./kit.dockerfile")
        capabilities = {item["type"]: item["config"] for item in kit["capabilities"]}
        volume = capabilities["com.docker.sandbox/volume@1"]
        self.assertEqual(volume, {"path": "/home/agent/.zsh-data", "size": "512m",
                                  "tmpfs": False, "mode": "0700"})
        hook = capabilities["com.docker.sandbox/lifecycle@1"]["install"][0]
        self.assertEqual(hook["user"], "0")
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state"
            command = list(hook["command"])
            command[command.index("-o") + 1] = str(os.getuid())
            command[command.index("-g") + 1] = str(os.getgid())
            command[-1] = str(state)
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            history = state / "zsh_history"
            history.write_text("synthetic-history-only\n")
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertEqual(history.read_text(), "synthetic-history-only\n")
            self.assertEqual(state.stat().st_mode & 0o777, 0o700)

    @unittest.skipUnless(exclude_paths, "Docker SDK required for build-context checks")
    def test_build_context_never_includes_old_private_history(self):
        patterns = [line for line in (ZSH / ".dockerignore").read_text().splitlines()
                    if line and not line.startswith("#")]
        with tempfile.TemporaryDirectory() as temporary:
            for name in ("kit.yaml", "kit.dockerfile", "zshrc", "zsh-data/zsh_history", ".env"):
                path = Path(temporary) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("synthetic-sentinel\n")
            included = set(exclude_paths(temporary, patterns, dockerfile="kit.dockerfile"))
            self.assertTrue({"kit.yaml", "kit.dockerfile", "zshrc"} <= included)
            self.assertNotIn("zsh-data/zsh_history", included)
            self.assertNotIn(".env", included)

    @unittest.skipUnless(shutil.which("zsh"), "Zsh runtime checked during the kit image build")
    def test_zsh_configuration_syntax_and_runtime(self):
        syntax = subprocess.run(["zsh", "-n", str(ZSH / "zshrc")], capture_output=True, text=True)
        self.assertEqual(syntax.returncode, 0, syntax.stderr)
        with tempfile.TemporaryDirectory() as temporary:
            shutil.copyfile(ZSH / "zshrc", Path(temporary) / ".zshrc")
            env = dict(os.environ, HOME=temporary, TERM="xterm", GITHUB_TOKEN="synthetic-sentinel")
            result = subprocess.run(
                ["zsh", "-i", "-c", 'print -rl -- "$HISTFILE" "$GITHUB_TOKEN"; '
                 '[[ -o SHARE_HISTORY && ! -o INC_APPEND_HISTORY ]]'],
                env=env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(),
                             [str(Path(temporary) / ".zsh-data/zsh_history"), "synthetic-sentinel"])
            self.assertTrue(list((Path(temporary) / ".zsh-data").glob("zcompdump-*")))


class ZshLauncherTests(unittest.TestCase):
    def prepare_launcher(self, root, shell_path):
        workspace = root / "workspace"
        workspace.mkdir()
        script = root / "start.sh"
        script.write_text((ROOT / "kit-yallmo/start.sh").read_text()
                          .replace("/home/agent/workspace", str(workspace))
                          .replace("/bin/zsh", str(shell_path)))
        return script

    def test_selected_shell_receives_arguments_environment_and_working_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shell = root / "zsh"
            shell.write_text('#!/bin/sh\nprintf "%s\\n" "$SHELL" "$PWD" "$@"\n')
            shell.chmod(0o755)
            script = self.prepare_launcher(root, shell)
            env = dict(os.environ, YALLMO_SHELL=str(shell), YALLMO_AUTOSTART="0",
                       SBX_CRED_OPENAI_MODE="oauth")
            result = subprocess.run(["bash", str(script), "-i"], env=env,
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), [str(shell), str(root / "workspace"), "-i"])

    def test_uninstalled_or_unsupported_shell_fails_clearly(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            missing = root / "missing-zsh"
            script = self.prepare_launcher(root, missing)
            for selected, message in ((str(missing), "is not installed"),
                                      ("/tmp/untrusted-shell", "Unsupported YALLMO_SHELL")):
                with self.subTest(selected=selected):
                    env = dict(os.environ, YALLMO_SHELL=selected, YALLMO_AUTOSTART="0")
                    result = subprocess.run(["bash", str(script), "-i"], env=env,
                                            capture_output=True, text=True)
                    self.assertEqual(result.returncode, 1)
                    self.assertIn(message, result.stderr)


if __name__ == "__main__":
    unittest.main()
