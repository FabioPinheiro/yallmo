"""Bash-first sandbox setup tests. Only synthetic profiles; no host secrets or network."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

ROOT = Path(__file__).resolve().parents[1] / "yallmo-sandbox"


class SandboxSetupTests(unittest.TestCase):
    def run_prepare(self, destination, home, script=ROOT / "kit-pi/prepare.py"):
        return subprocess.run([sys.executable, str(script), "--destination", str(destination)],
                              env=dict(os.environ, HOME=str(home)), text=True, capture_output=True)

    def test_fresh_profile_contains_only_local_templates_with_private_permissions(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            destination = root / "pi"
            result = self.run_prepare(destination, root / "missing-home")
            self.assertEqual(result.returncode, 0, result.stderr)
            profile = destination / "agent"
            names = {"auth.json", "settings.json", "mcp.json", "models.json"}
            self.assertEqual({p.name for p in profile.iterdir()}, names)
            for name in names:
                self.assertEqual(json.loads((profile / name).read_text()),
                                 json.loads((ROOT / "kit-pi/templates" / name).read_text()))
                self.assertEqual((profile / name).stat().st_mode & 0o777, 0o600)
            for directory in (destination, profile):
                self.assertEqual(directory.stat().st_mode & 0o777, 0o700)
            self.assertEqual(set(json.loads((profile / "models.json").read_text())["providers"]),
                             {"sbx-openai", "docker-mr"})
            mcp = json.loads((profile / "mcp.json").read_text())["mcpServers"]["playwright"]
            self.assertEqual(mcp["command"], "playwright-mcp")
            self.assertEqual(mcp["exposure"], "direct")
            self.assertEqual(mcp["cwd"], "/home/agent/workspace")
            self.assertEqual(mcp["args"], ["--browser", "chromium", "--headless", "--isolated"])
            self.assertNotIn("directTools", mcp)

    def test_host_profile_is_never_imported_or_required(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            home = root / "home"
            host = home / ".pi/agent"
            host.mkdir(parents=True)
            # Invalid JSON and recognizable content must never be read or copied.
            for name in ("auth.json", "settings.json", "mcp.json", "models.json", "models-store.json"):
                (host / name).write_text("synthetic-do-not-import")
            (host / "skills/test").mkdir(parents=True)
            (host / "skills/test/SKILL.md").write_text("synthetic-do-not-import")
            (host / "sessions").mkdir()
            (host / "sessions/test.jsonl").write_text("synthetic-do-not-import")
            before = {p.relative_to(host): p.read_bytes() for p in host.rglob("*") if p.is_file()}
            result = self.run_prepare(root / "pi", home)
            self.assertEqual(result.returncode, 0, result.stderr)
            profile = root / "pi/agent"
            self.assertEqual({p.name for p in profile.iterdir()},
                             {"auth.json", "settings.json", "mcp.json", "models.json"})
            self.assertNotIn("synthetic-do-not-import", result.stdout + result.stderr +
                             "".join(p.read_text() for p in profile.iterdir()))
            self.assertEqual(before, {p.relative_to(host): p.read_bytes()
                                      for p in host.rglob("*") if p.is_file()})

    def test_rerun_preserves_direct_edits_and_unmanaged_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertEqual(self.run_prepare(root / "pi", root / "home").returncode, 0)
            profile = root / "pi/agent"
            edits = {
                "models.json": '{"providers":{"custom":{}}}\n',
                "settings.json": '{"theme":"light"}\n',
                "mcp.json": '{"mcpServers":{}}\n',
                "models-store.json": '{"catalog":[]}\n',
                "skills/custom/SKILL.md": "Local skill\n",
                "sessions/test.jsonl": "Local session\n",
            }
            for name, content in edits.items():
                path = profile / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            before = {p.relative_to(profile): p.read_bytes() for p in profile.rglob("*") if p.is_file()}
            result = self.run_prepare(root / "pi", root / "home")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(before, {p.relative_to(profile): p.read_bytes()
                                      for p in profile.rglob("*") if p.is_file()})

    def test_incomplete_profile_gets_only_missing_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = root / "pi/agent"
            profile.mkdir(parents=True)
            settings = profile / "settings.json"
            settings.write_text('{"theme":"light"}\n')
            before = settings.read_bytes()
            result = self.run_prepare(root / "pi", root / "home")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(settings.read_bytes(), before)
            for name in ("auth.json", "models.json", "mcp.json"):
                self.assertEqual(json.loads((profile / name).read_text()),
                                 json.loads((ROOT / "kit-pi/templates" / name).read_text()))

    def test_existing_non_sentinel_or_invalid_auth_is_rejected_without_writes(self):
        for content in ('{"other":{"type":"api_key","key":"synthetic-secret"}}',
                        'invalid-synthetic-secret', '["synthetic-secret"]'):
            with self.subTest(content_type=content[:1]), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                profile = root / "pi/agent"
                profile.mkdir(parents=True)
                auth = profile / "auth.json"
                auth.write_text(content)
                before = auth.read_bytes()
                result = self.run_prepare(root / "pi", root / "home")
                self.assertEqual(result.returncode, 1)
                self.assertEqual(auth.read_bytes(), before)
                self.assertEqual({p.name for p in profile.iterdir()}, {"auth.json"})
                self.assertNotIn("synthetic-secret", result.stdout + result.stderr)

    def test_invalid_templates_are_rejected_before_creating_profile(self):
        for name, content in (("auth.json", '{"key":"synthetic-secret"}'),
                              ("models.json", 'invalid-synthetic-secret')):
            with self.subTest(template=name), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                shutil.copyfile(ROOT / "kit-pi/prepare.py", root / "prepare.py")
                shutil.copytree(ROOT / "kit-pi/templates", root / "templates")
                (root / "templates" / name).write_text(content)
                result = self.run_prepare(root / "pi", root / "home", root / "prepare.py")
                self.assertEqual(result.returncode, 1)
                self.assertFalse((root / "pi").exists())
                self.assertNotIn("synthetic-secret", result.stdout + result.stderr)


class SandboxLauncherTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("jq"), "jq required for Pi sentinel validation")
    def test_pi_launcher_validates_profile_without_modifying_it(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = root / "profile"
            shutil.copytree(ROOT / "kit-pi/templates", profile)
            before = {p.name: p.read_bytes() for p in profile.iterdir()}
            script = root / "pi.sh"
            script.write_text((ROOT / "kit-pi/start.sh").read_text()
                              .replace("/home/agent/.pi/agent", str(profile))
                              .replace("/usr/local/bin/pi-cli", str(root / "bin/pi-cli")))
            binaries = root / "bin"
            binaries.mkdir()
            node = binaries / "pi-cli"
            node.write_text('#!/bin/sh\nprintf "%s\\n" "$PI_CODING_AGENT_DIR" "$PI_CLIPBOARD_OSC52" "$PLAYWRIGHT_BROWSERS_PATH" "$@"\n')
            node.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                       PI_CLIPBOARD_OSC52="1")
            result = subprocess.run(["bash", str(script), "--version"], env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), [str(profile), "1", "/opt/playwright-browsers",
                             "--version"])
            self.assertEqual(before, {p.name: p.read_bytes() for p in profile.iterdir()})
            (profile / "auth.json").write_text('{"other":"synthetic-secret"}')
            failed = subprocess.run(["bash", str(script)], env=env, text=True, capture_output=True)
            self.assertEqual(failed.returncode, 1)
            self.assertIn("public OAuth sentinel", failed.stderr)
            self.assertNotIn("synthetic-secret", failed.stdout + failed.stderr)
            (profile / "settings.json").unlink()
            failed = subprocess.run(["bash", str(script)], env=env, text=True, capture_output=True)
            self.assertEqual(failed.returncode, 1)
            self.assertIn("kit-pi/prepare.py", failed.stderr)

    def test_workload_shell_needs_no_pi_profile_and_never_starts_a_second_service(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace = root / "workspace"
            workspace.mkdir()
            script = root / "start.sh"
            script.write_text((ROOT / "kit-yallmo/start.sh").read_text()
                              .replace("/home/agent/workspace", str(workspace)))
            binaries = root / "bin"
            binaries.mkdir()
            calls = root / "calls"
            service = binaries / "yallmo-service"
            service.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$SERVICE_TEST_CALLS"\nexit 3\n')
            service.chmod(0o755)
            env = dict(os.environ, PATH=str(binaries) + os.pathsep + os.environ["PATH"],
                       SERVICE_TEST_CALLS=str(calls), YALLMO_SHELL="/bin/bash")
            for autostart in ("0", "1"):
                env["YALLMO_AUTOSTART"] = autostart
                result = subprocess.run(["bash", str(script), "-c", 'printf "%s" "$PWD"'],
                                        env=env, text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, str(workspace))
                self.assertEqual(calls.exists(), autostart == "1")
            self.assertEqual(calls.read_text().splitlines(), ["status", "--wait"])
            self.assertIn("yallmo-service logs --follow", result.stderr)


class SandboxToolchainTests(unittest.TestCase):
    def test_tools_and_build_checks_belong_to_their_respective_kits(self):
        base = (ROOT.parent / "Dockerfile").read_text()
        workload = (ROOT / "kit-yallmo/kit.dockerfile").read_text()
        pi = (ROOT / "kit-pi/kit.dockerfile").read_text()
        self.assertNotIn("@earendil-works/pi-coding-agent", base + workload)
        self.assertNotRegex(base + pi, r"cs install[^\n]*(?:metals|bloop)")
        self.assertIn("cs install --dir /usr/share/coursier/bin --jvm system metals bloop", workload)
        self.assertIn("@earendil-works/pi-coding-agent@latest", pi)
        self.assertIn("pi-subagents@latest pi-ssh-remote@latest", pi)
        self.assertIn("FROM scratch", pi)
        self.assertNotIn("ENTRYPOINT", pi)
        for dockerfile, commands in ((workload, ("metals --version", "bloop --help")),
                                     (pi, ("node --test /opt/pi-sdk-tests/test-provider.mjs",
                                           "/usr/local/bin/playwright-mcp --version",
                                           "node /opt/playwright/browser-smoke.mjs"))):
            provisioning, validation = dockerfile.split("\nUSER agent\n", 1)
            self.assertNotIn("&& runuser", provisioning)
            for command in commands:
                self.assertIn("RUN " + command, validation.splitlines())
        self.assertIn('PI_CLIPBOARD_OSC52="${PI_CLIPBOARD_OSC52:-1}"',
                      (ROOT / "kit-pi/start.sh").read_text())
        self.assertNotIn("PI_CLIPBOARD_OSC52", base + workload)


@unittest.skipUnless(yaml, "PyYAML required for sandbox declaration tests")
class SandboxConfigurationTests(unittest.TestCase):
    def test_background_service_is_a_sandbox_boot_hook_with_explicit_runtime_environment(self):
        environment = yaml.safe_load((ROOT / "sbxenv.yaml").read_text())
        kit = yaml.safe_load((ROOT / "kit-yallmo/kit.yaml").read_text())
        self.assertIn(environment["env"]["YALLMO_AUTOSTART"], {"0", "1"})
        self.assertEqual(environment["env"]["YALLMO_WORKSPACE"], "/home/agent/yallmo-workspace")
        long_running = next(cap for cap in kit["capabilities"]
                            if cap["type"] == "com.docker.sandbox/long-running@1")
        self.assertNotIn("config", long_running)
        lifecycle = next(cap for cap in kit["capabilities"]
                         if cap["type"] == "com.docker.sandbox/lifecycle@1")
        hook = lifecycle["config"]["startup"][0]
        self.assertEqual(hook["command"], ["yallmo-service", "supervise"])
        self.assertEqual(hook["user"], "agent")
        self.assertTrue(hook["background"])
        self.assertTrue({"PATH", "JAVA_HOME", "YALLMO_AUTOSTART", "YALLMO_WORKSPACE",
                         "HTTPS_PROXY", "HTTP_PROXY", "SSL_CERT_FILE"} <= set(hook["env"]))
        self.assertNotIn("OPENAI_API_KEY", hook["env"])
        self.assertNotIn("GITHUB_TOKEN", hook["env"])

    def test_kits_compose_with_separate_private_profile_and_workspace_mounts(self):
        environment = yaml.safe_load((ROOT / "sbxenv.yaml").read_text())
        self.assertEqual(environment["agent"], "kit-yallmo")
        self.assertEqual(environment["workspace"], "../..")
        self.assertEqual(environment["mounts"], {
            "/home/agent/workspace": "./workspace",
            "/home/agent/yallmo-workspace": "./yallmo-workspace",
            "/home/agent/.pi": "./pi",
        })
        declared = {}
        for name, kind in (("kit-yallmo", "workload"), ("kit-pi", "mixin"), ("kit-zsh", "mixin")):
            kit = yaml.safe_load((ROOT / name / "kit.yaml").read_text())
            self.assertEqual(kit["kind"], kind)
            self.assertTrue((ROOT / name / kit["dockerfile"]).is_file())
            for cap in kit["capabilities"]:
                if cap["type"] == "com.docker.sandbox/host-mount@1":
                    declared[cap["config"]["path"]] = cap["config"]
        self.assertCountEqual(declared, environment["mounts"])
        self.assertEqual(declared["/home/agent/.pi"]["mode"], "0700")
        # Loopback-only publication, including optional frontend development ports.
        self.assertTrue(all(port["hostIP"] == "127.0.0.1" for port in environment["ports"]))
        self.assertIn({"sandbox": 8080, "host": 8080, "hostIP": "127.0.0.1"}, environment["ports"])
        self.assertEqual([kit["source"] for kit in environment["kits"]],
                         ["./kit-yallmo", "./kit-pi", "./kit-zsh"])


if __name__ == "__main__":
    unittest.main()
