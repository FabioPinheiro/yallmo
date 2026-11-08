"""Guard the token-isolation contract; never inspect host/user credential files or perform a login."""
import json
import os
from pathlib import Path
import subprocess
import shutil
import tempfile
import unittest

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

EXAMPLE = Path(__file__).with_name("yallmo-sandbox-sbx-example")
KIT = EXAMPLE / "pi"
SENTINEL = "oai-oat01-proxy-managed"


class SandboxPlaceholderTests(unittest.TestCase):
    def test_checked_in_auth_is_only_the_public_proxy_sentinel(self):
        auth = json.loads((KIT / "auth.json").read_text())
        # No assertEqual diff: do not print values if someone accidentally replaces the fixture with real credentials.
        self.assertTrue(auth == {"sbx-openai": {"type": "api_key", "key": SENTINEL}},
                        "auth.json must contain only the public proxy sentinel, never a host credential")

    def test_custom_provider_avoids_jwt_decode_and_uses_subscription_endpoint(self):
        providers = json.loads((KIT / "models.json").read_text())["providers"]
        self.assertEqual(set(providers), {"sbx-openai"})
        provider = providers["sbx-openai"]
        self.assertEqual(provider["api"], "openai-responses")
        self.assertEqual(provider["baseUrl"], "https://chatgpt.com/backend-api/codex")
        self.assertFalse(provider["compat"]["supportsMaxOutputTokens"])
        self.assertFalse(provider["compat"]["supportsLongCacheRetention"])
        self.assertFalse(provider["models"][0]["samplingParams"]["store"])

    def test_launcher_refuses_missing_or_api_key_mode_before_any_seed(self):
        for mode in ("", "apikey", "api_key"):
            env = dict(os.environ, SBX_CRED_OPENAI_MODE=mode)
            result = subprocess.run(["bash", str(KIT / "start.sh")], env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn("Host OpenAI OAuth is required", result.stderr)

    def test_launcher_rejects_invalid_model_before_touching_any_config_directory(self):
        env = dict(os.environ, SBX_CRED_OPENAI_MODE="oauth", YALLMO_PI_MODEL="bad/model\nname")
        result = subprocess.run(["bash", str(KIT / "start.sh")], env=env, text=True, capture_output=True)
        self.assertEqual(result.returncode, 1)
        self.assertIn("Invalid model ID", result.stderr)

    def test_server_launcher_creates_workspace_storage_and_uses_the_requested_flags(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            workspace = root / "workspace"
            workspace.mkdir()
            shutil.copyfile(EXAMPLE / "workspace/server.sh", workspace / "server.sh")
            shutil.copyfile(EXAMPLE / "workspace/yallmo.yaml", workspace / "yallmo.yaml")
            binaries = root / "bin"
            binaries.mkdir()
            command = binaries / "yallmo"
            command.write_text('#!/bin/sh\nprintf "%s\\n" "$PWD" "$@"\n')
            command.chmod(0o755)
            env = {"PATH": str(binaries) + os.pathsep + os.environ.get("PATH", os.defpath), "HOME": str(root)}
            result = subprocess.run(["bash", str(workspace / "server.sh")], cwd=root, env=env,
                                    text=True, capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.splitlines(), [str(workspace), "server", "--storage", "./storage",
                                                          "--config", "./yallmo.yaml", "--log-level", "info"])
            self.assertTrue((workspace / "storage").is_dir())
            self.assertFalse((root / "storage").exists())

    def test_server_launcher_refuses_non_sentinel_values_before_creating_storage(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copyfile(EXAMPLE / "workspace/server.sh", root / "server.sh")
            env = {"PATH": os.environ.get("PATH", os.defpath), "HOME": str(root),
                   "YALLMO_SBX_OPENAI_ACCESS_TOKEN": "synthetic-do-not-log"}
            result = subprocess.run(["bash", str(root / "server.sh")], env=env, text=True, capture_output=True)
            self.assertEqual(result.returncode, 1)
            self.assertIn("Refusing non-sentinel credentials", result.stderr)
            self.assertNotIn("synthetic-do-not-log", result.stdout + result.stderr)
            self.assertFalse((root / "storage").exists())

    def test_documentation_keeps_live_validation_and_no_host_auth_mount_explicit(self):
        readme = (EXAMPLE / "README-SBX-SANDBOX.md").read_text()
        self.assertIn("No host auth file or real token is mounted", readme)
        self.assertIn("not verified in this coding-agent container", readme)
        self.assertNotIn('PI_CODING_AGENT_DIR="$PWD/pi-agent" pi', readme)


@unittest.skipUnless(yaml, "PyYAML required for kit/env schema-contract tests; CI installs it")
class SandboxDeclarationTests(unittest.TestCase):
    def test_environment_mounts_only_workspace_and_inherits_the_pi_kit_launcher(self):
        config = yaml.safe_load((EXAMPLE / "sbxenv.yaml").read_text())
        for key in ("workspace", "mounts", "secrets", "lifecycle", "entrypoint", "cmd"):
            self.assertNotIn(key, config)
        self.assertEqual(config["agent"], "pi")
        self.assertEqual(config["additionalWorkspaces"], [
            {"path": "./workspace", "target": "/home/agent/workspace", "readOnly": False}
        ])
        self.assertEqual(config["kits"][0]["source"], "./pi")
        self.assertTrue((EXAMPLE / "workspace/hello.py").is_file())

    def test_server_config_contains_only_selected_agents_providers_and_environment_references(self):
        config = yaml.safe_load((EXAMPLE / "workspace/yallmo.yaml").read_text())
        self.assertEqual(set(config["agents"]), {"Transcriber", "Gemma", "sol"})
        self.assertEqual(set(config["providers"]), {"docker-mr", "chatgpt-1"})
        credentials = config["providers"]["chatgpt-1"]["credentials"]
        self.assertTrue(credentials == {"accessToken": "${YALLMO_SBX_OPENAI_ACCESS_TOKEN}",
                                        "refreshToken": "${YALLMO_SBX_OPENAI_REFRESH_TOKEN}", "expiresAtMillis": 0},
                        "Server config must contain only placeholder references, never saved credentials")
        environment = yaml.safe_load((EXAMPLE / "sbxenv.yaml").read_text())
        self.assertTrue(environment["env"]["YALLMO_SBX_OPENAI_ACCESS_TOKEN"] == SENTINEL)
        self.assertTrue(environment["env"]["YALLMO_SBX_OPENAI_REFRESH_TOKEN"] == "oai-ort01-proxy-managed")
        self.assertEqual(environment["ports"], [{"sandbox": 8080, "host": 8080, "hostIP": "127.0.0.1"}])
        self.assertEqual(config["providers"]["docker-mr"]["endpoint"],
                         "http://host.docker.internal:12434/engines/v1")

    def test_oauth_is_required_and_sentinel_matches_without_token_passthrough(self):
        kit = yaml.safe_load((KIT / "pi.yaml").read_text())
        credential = next(c for c in kit["capabilities"] if c["type"] == "com.docker.sandbox/credential@1")
        self.assertFalse(credential.get("optional", False))
        config = credential["config"]
        self.assertEqual(config["service"], "openai")
        self.assertEqual(config["phase"], "runtime")
        self.assertNotIn("apiKey", config) # no paid-platform API-key fallback
        oauth = config["oauth"]
        self.assertFalse(oauth["passthrough"])
        self.assertEqual(oauth["sentinels"]["accessToken"], SENTINEL)
        self.assertEqual(oauth["resourceHosts"], ["chatgpt.com"])
        self.assertEqual(oauth["tokenEndpoint"], {"host": "auth.openai.com", "path": "/oauth/token"})
        policy = next(c for c in kit["capabilities"] if c["type"] == "com.docker.sandbox/network-policy@1")
        allowed = policy["config"]["runtime"]["allow"]
        self.assertIn("chatgpt.com:443", allowed)
        self.assertIn("auth.openai.com:443", allowed)
        self.assertIn("localhost:12434", allowed)
        self.assertNotIn("localhost", allowed)
        self.assertNotIn("**", allowed)


if __name__ == "__main__":
    unittest.main()
