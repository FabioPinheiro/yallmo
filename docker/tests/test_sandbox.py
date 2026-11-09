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
    def run_prepare(self, destination, home, script=ROOT / "prepare.py"):
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
                                 json.loads((ROOT / "templates" / name).read_text()))
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
                                 json.loads((ROOT / "templates" / name).read_text()))

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
                shutil.copyfile(ROOT / "prepare.py", root / "prepare.py")
                shutil.copytree(ROOT / "templates", root / "templates")
                (root / "templates" / name).write_text(content)
                result = self.run_prepare(root / "pi", root / "home", root / "prepare.py")
                self.assertEqual(result.returncode, 1)
                self.assertFalse((root / "pi").exists())
                self.assertNotIn("synthetic-secret", result.stdout + result.stderr)


@unittest.skipUnless(shutil.which("jq"), "jq required for shell launcher tests")
class SandboxShellTests(unittest.TestCase):
    def test_shell_with_autostart_disabled_does_not_start_services_or_mutate_pi(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = root / "pi/agent"
            profile.mkdir(parents=True)
            for name in ("auth.json", "models.json", "settings.json"):
                shutil.copyfile(ROOT / "templates" / name, profile / name)
            before = {p.name: p.read_bytes() for p in profile.iterdir()}
            workspace = root / "workspace"
            workspace.mkdir()
            script = root / "start.sh"
            script.write_text((ROOT / "kit/start.sh").read_text()
                              .replace("/home/agent/.pi/agent", str(profile))
                              .replace("/home/agent/workspace", str(workspace)))
            for mode in ("", "oauth"):
                result = subprocess.run(["bash", str(script), "-c", 'printf "%s\\n" "$PWD" "$PI_CODING_AGENT_DIR"'],
                                        env=dict(os.environ, SBX_CRED_OPENAI_MODE=mode, YALLMO_AUTOSTART="0"), text=True, capture_output=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout.splitlines(), [str(workspace), str(profile)])
                self.assertEqual(before, {p.name: p.read_bytes() for p in profile.iterdir()})
                self.assertEqual("ChatGPT needs host OAuth" in result.stderr, mode != "oauth")
            (profile / "auth.json").write_text('{"other":"synthetic-secret"}')
            failed = subprocess.run(["bash", str(script), "-c", 'printf "must-not-run"'],
                                    text=True, capture_output=True)
            self.assertEqual(failed.returncode, 1)
            self.assertNotIn("must-not-run", failed.stdout)
            self.assertNotIn("synthetic-secret", failed.stdout + failed.stderr)

    def test_default_shell_reports_service_state_without_starting_a_second_service(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = root / "profile"
            profile.mkdir()
            workspace = root / "workspace"
            workspace.mkdir()
            for name in ("auth.json", "models.json", "settings.json"):
                shutil.copyfile(ROOT / "templates" / name, profile / name)
            script = root / "start.sh"
            script.write_text((ROOT / "kit/start.sh").read_text()
                              .replace("/home/agent/.pi/agent", str(profile))
                              .replace("/home/agent/workspace", str(workspace)))
            binaries = root / "bin"
            binaries.mkdir()
            calls = root / "calls"
            service = binaries / "yallmo-service"
            service.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$SERVICE_TEST_CALLS"\nexit 3\n')
            service.chmod(0o755)
            env = {key: value for key, value in os.environ.items() if key != "YALLMO_AUTOSTART"}
            env.update(PATH=str(binaries) + os.pathsep + os.environ["PATH"], SERVICE_TEST_CALLS=str(calls),
                       SBX_CRED_OPENAI_MODE="oauth")
            result = subprocess.run(["bash", str(script), "-c", 'printf "bash-is-available"'],
                                    env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout, "bash-is-available")
            self.assertEqual(calls.read_text().splitlines(), ["status", "--wait"])
            self.assertIn("yallmo-service logs --follow", result.stderr)


class SandboxToolchainTests(unittest.TestCase):
    def test_metals_and_bloop_are_upstream_kit_only_installations(self):
        base = (ROOT.parent / "Dockerfile").read_text()
        kit = (ROOT / "kit/kit.dockerfile").read_text()
        smoke = (ROOT.parent / "tests/smoke.sh").read_text()
        self.assertIn("cs install --dir /usr/share/coursier/bin --jvm system scala-cli", base)
        self.assertIn("for tool in cs scala scala-cli sbt;", base)
        self.assertNotRegex(base, r"cs install[^\n]*(?:metals|bloop)")
        self.assertNotRegex(base, r"for tool in[^\n]*(?:metals|bloop)")
        self.assertIn("cs install --dir /usr/share/coursier/bin --jvm system metals bloop", kit)
        self.assertIn("for tool in metals bloop;", kit)
        self.assertIn("chmod -R a+rX,go-w /usr/share/coursier", kit)
        self.assertIn("RUN metals --version", kit)
        self.assertIn("RUN bloop --help", kit)
        self.assertNotIn("--force-version", base + kit)
        self.assertNotIn("install-scala-tools.py", base)
        self.assertIn("for tool in metals bloop pi;", smoke)
        self.assertNotIn("metals --version", smoke)
        self.assertNotIn("bloop --version", smoke)

    def test_build_checks_run_in_fresh_agent_steps_not_roots_inherited_cache_environment(self):
        kit = (ROOT / "kit/kit.dockerfile").read_text()
        provisioning, validation = kit.split("\nUSER agent\n", 1)
        self.assertNotIn("&& runuser", kit)
        self.assertIn("rm -rf /var/lib/apt/lists/* /var/cache/coursier/0", provisioning)
        self.assertIn("ENV HOME=/home/agent", validation)
        self.assertIn("XDG_CACHE_HOME=/home/agent/.cache", validation)
        self.assertIn("WORKDIR /home/agent/workspace", validation)
        checks = (
            "metals --version",
            "bloop --help",
            "pi --version",
            "node --test /opt/pi-sdk-tests/test-provider.mjs",
            "/usr/local/bin/playwright-mcp --version",
            "node /opt/playwright/browser-smoke.mjs",
        )
        for command in checks:
            self.assertNotIn(command, provisioning)
            self.assertIn("RUN " + command, validation.splitlines())
        self.assertLess(validation.index("WORKDIR /home/agent/workspace"),
                        validation.index("RUN metals --version"))


class SandboxClipboardTests(unittest.TestCase):
    def test_pi_clipboard_uses_host_terminal_osc52_in_kit_only(self):
        base = (ROOT.parent / "Dockerfile").read_text()
        kit = (ROOT / "kit/kit.dockerfile").read_text()
        self.assertIn("    PI_CLIPBOARD_OSC52=1 \\\n", kit)
        self.assertNotIn("PI_CLIPBOARD_OSC52", base)


@unittest.skipUnless(yaml, "PyYAML required for sandbox declaration tests")
class SandboxConfigurationTests(unittest.TestCase):
    def test_kit_provisions_node_and_npm_before_installing_pi(self):
        kit = (ROOT / "kit/kit.dockerfile").read_text()
        # The published Yallmo image may lag behind this checkout's Node-enabled image.
        steps = [
            "ARG YALLMO_IMAGE",
            "FROM node:latest AS sandbox-node",
            "FROM ${YALLMO_IMAGE}",
            "COPY --from=sandbox-node /usr/local/bin/node /usr/local/bin/node",
            "COPY --from=sandbox-node /usr/local/lib/node_modules /usr/local/lib/node_modules",
            "ln -sf /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm",
            "ln -sf /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx",
            "node --version",
            "npm --version",
            "npm install --global --ignore-scripts npm@latest",
            "npm install --global --ignore-scripts @earendil-works/pi-coding-agent@latest",
        ]
        positions = [kit.index(step) for step in steps]
        self.assertEqual(positions, sorted(positions))

    def test_pi_installation_and_sdk_build_checks_are_kit_only(self):
        base = (ROOT.parent / "Dockerfile").read_text()
        kit = (ROOT / "kit/kit.dockerfile").read_text()
        self.assertNotIn("@earendil-works/pi-coding-agent", base)
        self.assertIn("@earendil-works/pi-coding-agent@latest", kit)
        self.assertIn("node --test /opt/pi-sdk-tests/test-provider.mjs", kit)
        # These paths are aliases to the same public files, not drifting test-only copies.
        for name in ("auth.json", "models.json"):
            self.assertEqual((ROOT / "templates" / name).resolve(),
                             (ROOT / "kit/sdk-fixtures" / name).resolve())

    def test_background_service_is_a_sandbox_boot_hook_with_explicit_runtime_environment(self):
        environment = yaml.safe_load((ROOT / "sbxenv.yaml").read_text())
        kit = yaml.safe_load((ROOT / "kit/kit.yaml").read_text())
        # Both documented choices are valid; local sandbox configuration may disable autostart.
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

    def test_repository_workspace_and_fixed_kit_mounts_are_declared(self):
        environment = yaml.safe_load((ROOT / "sbxenv.yaml").read_text())
        kit = yaml.safe_load((ROOT / "kit/kit.yaml").read_text())
        self.assertEqual(environment["workspace"], "../..")
        self.assertEqual((ROOT / environment["workspace"]).resolve(), ROOT.parents[1])
        self.assertNotIn("additionalWorkspaces", environment)
        self.assertFalse(environment.get("sandboxOptions", {}).get("writableEnvFiles", False))
        self.assertEqual(environment["mounts"], {
            "/home/agent/workspace": "./workspace",
            "/home/agent/yallmo-workspace": "./yallmo-workspace",
            "/home/agent/.pi": "./pi",
        })
        declared_paths = [cap["config"]["path"] for cap in kit["capabilities"]
                          if cap["type"] == "com.docker.sandbox/host-mount@1"]
        self.assertCountEqual(declared_paths, environment["mounts"])
        coding = (ROOT / environment["mounts"]["/home/agent/workspace"]).resolve()
        service = (ROOT / environment["mounts"]["/home/agent/yallmo-workspace"]).resolve()
        self.assertFalse(coding.is_relative_to(service))
        self.assertFalse(service.is_relative_to(coding))
        for workspace in (coding, service):
            for protected in (ROOT / "sbxenv.yaml", ROOT / "kit", ROOT / "pi"):
                self.assertFalse(protected.resolve().is_relative_to(workspace))
        self.assertTrue((coding / "hello.py").is_file())
        for name in ("server.sh", "yallmo.yaml", "storage/.gitkeep"):
            self.assertTrue((service / name).is_file())
            self.assertFalse((coding / name).exists())

    def test_private_pi_mount_matches_declared_capability_and_only_8080_is_published(self):
        environment = yaml.safe_load((ROOT / "sbxenv.yaml").read_text())
        kit = yaml.safe_load((ROOT / "kit/kit.yaml").read_text())
        self.assertEqual(environment["agent"], "kit")
        self.assertEqual(environment["kits"][0]["source"], "./kit")
        self.assertEqual(environment["mounts"]["/home/agent/.pi"], "./pi")
        self.assertEqual(environment["ports"], [{"sandbox": 8080, "host": 8080, "hostIP": "127.0.0.1"}])
        self.assertNotIn("lifecycle", environment)
        mount = next(c for c in kit["capabilities"]
                     if c["type"] == "com.docker.sandbox/host-mount@1"
                     and c["config"]["path"] == "/home/agent/.pi")
        self.assertEqual(mount["config"]["mode"], "0700")
        credential = next(c for c in kit["capabilities"] if c["type"] == "com.docker.sandbox/credential@1")
        self.assertFalse(credential["config"]["oauth"]["passthrough"])
        self.assertEqual(credential["config"]["oauth"]["sentinels"]["accessToken"],
                         json.loads((ROOT / "templates/auth.json").read_text())["sbx-openai"]["key"])
        allowed = next(c for c in kit["capabilities"] if c["type"] == "com.docker.sandbox/network-policy@1")
        self.assertIn("localhost:12434", allowed["config"]["runtime"]["allow"])


if __name__ == "__main__":
    unittest.main()
