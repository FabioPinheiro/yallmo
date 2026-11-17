"""Optional shell mixin tests: synthetic history only; no host credentials or Docker daemon."""
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
        self.assertIn("COPY bash-history.bash /usr/share/yallmo-zsh/bash-history.bash", dockerfile)
        self.assertIn("/bin/bash -n /tmp/bash-history.bash", dockerfile)
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
        volumes = [item["config"] for item in kit["capabilities"]
                   if item["type"] == "com.docker.sandbox/volume@1"]
        self.assertEqual(volumes, [
            {"path": "/home/agent/.zsh-data", "size": "512m", "tmpfs": False, "mode": "0700"},
        ])
        lifecycle = next(item["config"] for item in kit["capabilities"]
                         if item["type"] == "com.docker.sandbox/lifecycle@1")
        self.assertEqual(len(lifecycle["install"]), 2)
        hook = lifecycle["install"][0]
        self.assertEqual(hook["command"][-1], "/home/agent/.zsh-data")
        self.assertEqual(hook["user"], "0")
        with tempfile.TemporaryDirectory() as temporary:
            state = Path(temporary) / "state"
            command = list(hook["command"])
            command[command.index("-o") + 1] = str(os.getuid())
            command[command.index("-g") + 1] = str(os.getgid())
            command[-1] = str(state)
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            for shell in ("zsh", "bash"):
                (state / f"{shell}_history").write_text(f"synthetic-{shell}-history-only\n")
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            for shell in ("zsh", "bash"):
                self.assertEqual((state / f"{shell}_history").read_text(),
                                 f"synthetic-{shell}-history-only\n")
            self.assertEqual(state.stat().st_mode & 0o777, 0o700)

    @unittest.skipUnless(exclude_paths, "Docker SDK required for build-context checks")
    def test_build_context_never_includes_old_private_history(self):
        patterns = [line for line in (ZSH / ".dockerignore").read_text().splitlines()
                    if line and not line.startswith("#")]
        with tempfile.TemporaryDirectory() as temporary:
            for name in ("kit.yaml", "kit.dockerfile", "zshrc", "bash-history.bash",
                         "zsh-data/zsh_history", "zsh-data/bash_history", ".env"):
                path = Path(temporary) / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("synthetic-sentinel\n")
            included = set(exclude_paths(temporary, patterns, dockerfile="kit.dockerfile"))
            self.assertTrue({"kit.yaml", "kit.dockerfile", "zshrc", "bash-history.bash"} <= included)
            self.assertNotIn("zsh-data/zsh_history", included)
            self.assertNotIn("zsh-data/bash_history", included)
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


class BashHistoryTests(unittest.TestCase):
    def run_bash(self, home, script, setup="", interactive=True):
        rc = home / ".bashrc"
        rc.write_text(setup + "\nsource " + shlex.quote(str(ZSH / "bash-history.bash")) + "\n")
        args = ["bash", "--noprofile"]
        if interactive:
            args += ["--rcfile", str(rc), "-i"]
        else:
            args += ["--norc"]
        # Do not inherit real shell history, credentials or startup scripts.
        env = {"HOME": str(home), "PATH": os.environ["PATH"], "TERM": "dumb"}
        result = subprocess.run(args, input=script, env=env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    @unittest.skipUnless(yaml, "PyYAML required for kit descriptor checks")
    def test_lifecycle_appends_bash_config_once_without_replacing_local_edits(self):
        kit = yaml.safe_load((ZSH / "kit.yaml").read_text())
        lifecycle = next(item["config"] for item in kit["capabilities"]
                         if item["type"] == "com.docker.sandbox/lifecycle@1")
        hook = lifecycle["install"][1]
        self.assertEqual(hook["user"], "1000")
        source = "source /usr/share/yallmo-zsh/bash-history.bash"
        for existing in (None, "# synthetic local settings without trailing newline"):
            with self.subTest(existing=existing), tempfile.TemporaryDirectory() as temporary:
                rc = Path(temporary) / ".bashrc"
                if existing is not None:
                    rc.write_text(existing)
                for _ in range(2):
                    result = subprocess.run(hook["command"], env={**os.environ, "HOME": temporary},
                                            capture_output=True, text=True)
                    self.assertEqual(result.returncode, 0, result.stderr)
                content = rc.read_text()
                self.assertEqual(content.splitlines().count(source), 1)
                if existing is not None:
                    self.assertTrue(content.startswith(existing + "\n"))

    def test_preserves_string_array_and_unset_prompt_hooks_without_duplication(self):
        setups = (
            ("unset PROMPT_COMMAND", []),
            ("PROMPT_COMMAND='printf string-hook'", ["printf string-hook"]),
            ("PROMPT_COMMAND=('printf first-hook' 'printf second-hook')",
             ["printf first-hook", "printf second-hook"]),
        )
        for setup, hooks in setups:
            with self.subTest(setup=setup), tempfile.TemporaryDirectory() as temporary:
                # Capture the array in a file because interactive prompts run the hooks too.
                home = Path(temporary)
                script = (f"source {shlex.quote(str(ZSH / 'bash-history.bash'))}\n"
                          "printf '%s\\n' \"${PROMPT_COMMAND[@]}\" > \"$HOME/hooks\"\n"
                          "exit\n")
                self.run_bash(home, script, setup=setup)
                self.assertEqual((home / "hooks").read_text().splitlines(),
                                 hooks + ["_yallmo_bash_history_sync"])

    def test_history_is_written_at_each_prompt_and_loaded_in_new_sessions(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            # Neither legacy Bash history nor Zsh history should be imported or changed.
            (home / ".bash_history").write_text("synthetic-legacy-bash\n")
            zsh_state = home / ".zsh-data"
            zsh_state.mkdir()
            (zsh_state / "zsh_history").write_text(": 1712345678:0;synthetic-zsh\n")
            output = self.run_bash(home, "echo synthetic-prompt-command\ncat \"$HISTFILE\"\n"
                                   "printf '%s\\n' \"$HISTFILE\" \"$HISTSIZE\" \"$HISTFILESIZE\"\n"
                                   "shopt -q histappend || exit 1\nexit\n")
            self.assertIn("echo synthetic-prompt-command", output)
            self.assertIn(str(home / ".zsh-data/bash_history") + "\n100000\n100000\n", output)
            output = self.run_bash(home, "history\nexit\n")
            self.assertIn("echo synthetic-prompt-command", output)
            self.assertNotIn("synthetic-legacy-bash", output)
            self.assertNotIn("synthetic-zsh", output)
            self.assertEqual((home / ".bash_history").read_text(), "synthetic-legacy-bash\n")
            self.assertEqual((zsh_state / "zsh_history").read_text(),
                             ": 1712345678:0;synthetic-zsh\n")

    def test_sync_imports_another_live_sessions_commands(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            # A nested interactive Bash runs while the parent is still alive.
            script = ("history -s synthetic-parent-command\n"
                      "_yallmo_bash_history_sync\n"
                      "bash --noprofile --rcfile \"$HOME/.bashrc\" -i -c "
                      "'history -s synthetic-child-command; _yallmo_bash_history_sync'\n"
                      "_yallmo_bash_history_sync\nhistory\nexit\n")
            output = self.run_bash(home, script)
            self.assertIn("synthetic-parent-command", output)
            self.assertRegex(output, r"(?m)^\s*\d+\s+synthetic-child-command$")
            history = (home / ".zsh-data/bash_history").read_text().splitlines()
            self.assertIn("synthetic-parent-command", history)
            self.assertIn("synthetic-child-command", history)

    def test_noninteractive_sourcing_does_not_enable_history(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            output = self.run_bash(
                home, f"HISTFILE=unchanged\nsource {shlex.quote(str(ZSH / 'bash-history.bash'))}\n"
                "printf '%s\\n' \"$HISTFILE\"\ndeclare -F _yallmo_bash_history_sync && exit 1\nexit 0\n",
                interactive=False)
            self.assertEqual(output, "unchanged\n")
            self.assertFalse((home / ".zsh-data").exists())


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
