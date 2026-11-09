"""Bash-first sandbox setup tests. Only synthetic profiles; no host secrets or network."""
import importlib.util
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
spec = importlib.util.spec_from_file_location("sandbox_prepare", ROOT / "prepare.py")
prepare = importlib.util.module_from_spec(spec)
spec.loader.exec_module(prepare)


class SandboxSetupTests(unittest.TestCase):
    def test_copy_imports_skills_and_models_but_not_host_auth_or_credential_resolvers(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "host"
            source.mkdir()
            # Invalid JSON proves the host credential file is not read at all.
            (source / "auth.json").write_text("synthetic-do-not-import")
            (source / "settings.json").write_text("synthetic-do-not-import")
            models = {"providers": {"example": {
                "baseUrl": "https://example.com/v1", "api": "openai-completions",
                "apiKey": "!do-not-execute", "headers": {"Authorization": "synthetic-do-not-import"},
                "models": [{"id": "example", "headers": {"x-key": "synthetic-do-not-import"}}],
            }}}
            (source / "models.json").write_text(json.dumps(models))
            (source / "models-store.json").write_text(json.dumps({"catalog": ["local-model"]}))
            skill = source / "skills/test"
            skill.mkdir(parents=True)
            (skill / "SKILL.md").write_text("synthetic skill")
            (source / "skills/linked").symlink_to(skill, target_is_directory=True)
            destination = root / "pi"
            result = subprocess.run([sys.executable, str(ROOT / "prepare.py"),
                                     "--source", str(source), "--destination", str(destination)],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            profile = destination / "agent"
            imported = json.loads((profile / "models.json").read_text())
            self.assertEqual(set(imported["providers"]), {"example", "sbx-openai", "docker-mr"})
            self.assertNotIn("apiKey", imported["providers"]["example"])
            self.assertNotIn("headers", imported["providers"]["example"]["models"][0])
            self.assertNotIn("do-not-execute", json.dumps(imported))
            self.assertNotIn("synthetic-do-not-import", json.dumps(imported) + result.stdout + result.stderr)
            self.assertEqual(json.loads((profile / "models-store.json").read_text()), {"catalog": ["local-model"]})
            self.assertEqual((profile / "skills/linked/SKILL.md").read_text(), "synthetic skill")
            self.assertFalse((profile / "skills/linked").is_symlink())
            self.assertEqual((profile / "auth.json").stat().st_mode & 0o777, 0o600)
            self.assertEqual(destination.stat().st_mode & 0o777, 0o700)
            settings = json.loads((profile / "settings.json").read_text())
            self.assertEqual(settings["packages"], ["/opt/pi-extensions/node_modules/pi-subagents",
                                                     "/opt/pi-extensions/node_modules/pi-ssh-remote"])
            # Reruns preserve every existing file, even after changing the source.
            (profile / "settings.json").write_text('{"theme":"light"}')
            (profile / "mcp.json").write_text('{"mcpServers":{"custom":{"command":"synthetic"}}}')
            before = {p.relative_to(profile): p.read_bytes() for p in profile.rglob("*") if p.is_file()}
            (source / "models.json").write_text("invalid-source-not-reread")
            second = subprocess.run([sys.executable, str(ROOT / "prepare.py"),
                                      "--source", str(source), "--destination", str(destination)],
                                     text=True, capture_output=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            self.assertEqual(before, {p.relative_to(profile): p.read_bytes() for p in profile.rglob("*") if p.is_file()})

    def test_setup_supports_an_absent_host_profile_using_only_public_templates(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            result = subprocess.run([sys.executable, str(ROOT / "prepare.py"),
                                     "--source", str(root / "missing"), "--destination", str(root / "pi")],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            profile = root / "pi/agent"
            self.assertTrue((profile / "settings.json").is_file())
            mcp = json.loads((profile / "mcp.json").read_text())["mcpServers"]["playwright"]
            self.assertEqual(mcp["command"], "playwright-mcp")
            self.assertEqual(mcp["exposure"], "direct")
            self.assertEqual(mcp["cwd"], "/home/agent/workspace")
            self.assertEqual(mcp["args"], ["--browser", "chromium", "--headless", "--isolated"])
            self.assertNotIn("directTools", mcp)
            self.assertEqual((profile / "mcp.json").stat().st_mode & 0o777, 0o600)
            self.assertFalse((profile / "models-store.json").exists())

    def test_import_rejects_endpoint_credentials_without_creating_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"
            source.mkdir()
            (source / "models.json").write_text(json.dumps({"providers": {"example": {
                "baseUrl": "https://user:synthetic-secret@example.com/v1"}}}))
            result = subprocess.run([sys.executable, str(ROOT / "prepare.py"), "--source", str(source),
                                     "--destination", str(root / "pi")], text=True, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertFalse((root / "pi").exists())
            self.assertNotIn("synthetic-secret", result.stdout + result.stderr)

    def test_existing_non_sentinel_auth_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            profile = root / "pi/agent"
            profile.mkdir(parents=True)
            auth = profile / "auth.json"
            auth.write_text('{"other":{"type":"api_key","key":"synthetic-secret"}}')
            before = auth.read_bytes()
            result = subprocess.run([sys.executable, str(ROOT / "prepare.py"),
                                     "--source", str(root / "source"), "--destination", str(root / "pi")],
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertEqual(auth.read_bytes(), before)
            self.assertNotIn("synthetic-secret", result.stdout + result.stderr)
            self.assertFalse((profile / "settings.json").exists())


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

    def test_workspace_is_mounted_once_at_a_stable_path_without_sharing_kit_or_pi(self):
        environment = yaml.safe_load((ROOT / "sbxenv.yaml").read_text())
        kit = yaml.safe_load((ROOT / "kit/kit.yaml").read_text())
        self.assertNotIn("workspace", environment)
        self.assertNotIn("additionalWorkspaces", environment)
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
