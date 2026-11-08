"""Portable Pi configuration tests; synthetic settings only, no real provider or user credentials."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

EXAMPLE = Path(__file__).with_name("yallmo-sandbox-sbx-example")
KIT = EXAMPLE / "pi"
spec = importlib.util.spec_from_file_location("pi_settings_import", EXAMPLE / "import-settings.py")
settings_import = importlib.util.module_from_spec(spec)
spec.loader.exec_module(settings_import)


class PiSettingsTests(unittest.TestCase):
    def test_imports_model_preferences_and_plain_npm_packages_but_not_credentials_or_commands(self):
        result = settings_import.portable_settings({
            "defaultProvider": "openai-codex", "defaultModel": "gpt-6.1-sol", "defaultThinkingLevel": "high",
            "theme": "dark", "hideThinkingBlock": False,
            "apiKey": "do-not-export", "headers": {"Authorization": "do-not-export"},
            "authPath": "do-not-export", "command": "do-not-export", "extensionState": "do-not-export",
            "enabledModels": ["openai-codex/gpt-6.1-sol"],
            "packages": ["npm:pi-subagents", "npm:@example/tools@1.2.3", "npm:pi-subagents",
                         "https://user:do-not-export@example.com/pkg", "git:example.com/pkg", "./local",
                         {"source": "npm:filtered", "extensions": ["do-not-export"]}],
        })
        self.assertEqual(result, {"defaultModel": "gpt-6.1-sol", "defaultThinkingLevel": "high", "theme": "dark",
                                  "hideThinkingBlock": False,
                                  "packages": ["npm:pi-subagents", "npm:@example/tools@1.2.3"]})
        self.assertNotIn("do-not-export", json.dumps(result))

    def test_checked_in_snapshot_contains_only_portable_fields_and_current_model(self):
        preferences = json.loads((KIT / "preferences.json").read_text())
        self.assertTrue(preferences == settings_import.portable_settings(preferences),
                        "Preferences snapshot contains unsupported fields")
        self.assertEqual(preferences["defaultModel"], "gpt-6.1-sol")
        self.assertEqual(preferences["defaultThinkingLevel"], "high")

    def test_import_rejects_other_providers_and_invalid_model_ids_without_echoing_values(self):
        for data in ({"defaultProvider": "other-provider"}, {"defaultModel": "bad/model"}):
            with self.assertRaises(ValueError):
                settings_import.portable_settings(data)

    def test_cli_imports_only_settings_and_refuses_an_auth_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            script = root / "import-settings.py"
            shutil.copyfile(EXAMPLE / "import-settings.py", script)
            profile = root / "profile"
            profile.mkdir()
            (profile / "settings.json").write_text(json.dumps({
                "defaultProvider": "openai-codex", "defaultModel": "gpt-6.1-sol", "apiKey": "do-not-export",
            }))
            # Invalid JSON verifies that no attempt is made to parse this credential file.
            (profile / "auth.json").write_text("do-not-export")
            command = [sys.executable, str(script)]
            success = subprocess.run(command + [str(profile)], capture_output=True, text=True)
            self.assertEqual(success.returncode, 0, success.stderr)
            exported = (root / "pi/preferences.json").read_text()
            self.assertNotIn("do-not-export", exported + success.stdout + success.stderr)
            failure = subprocess.run(command + [str(profile / "auth.json")], capture_output=True, text=True)
            self.assertEqual(failure.returncode, 2)
            self.assertIn("Only settings.json may be imported", failure.stderr)
            self.assertNotIn("do-not-export", failure.stdout + failure.stderr)


@unittest.skipUnless(shutil.which("jq"), "jq required for actual launcher rendering tests; present in development image/CI")
class PiLauncherTests(unittest.TestCase):
    def test_launcher_uses_imported_model_and_preserves_preferences_without_native_oauth(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "kit"
            source.mkdir()
            for name in ("auth.json", "models.json", "preferences.json"):
                shutil.copyfile(KIT / name, source / name)
            profile = root / "profile"
            script = root / "start.sh"
            # Redirect only filesystem constants in a test copy; production has no host-path override.
            script.write_text((KIT / "start.sh").read_text().replace("/opt/pi-sbx", str(source))
                              .replace("/home/agent/.pi/sbx", str(profile)))
            binaries = root / "bin"
            binaries.mkdir()
            pi = binaries / "pi"
            pi.write_text('#!/bin/sh\nprintf "%s\\n" "$@"\n')
            pi.chmod(0o755)
            env = {"PATH": str(binaries) + os.pathsep + os.environ.get("PATH", os.defpath),
                   "HOME": str(root), "SBX_CRED_OPENAI_MODE": "oauth", "YALLMO_PI_MODEL": ""}
            first = subprocess.run(["bash", str(script)], env=env, text=True, capture_output=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            self.assertIn("gpt-6.1-sol", first.stdout)
            preferences = json.loads((profile / "settings.json").read_text())
            self.assertEqual(preferences["defaultThinkingLevel"], "high")
            self.assertEqual(preferences["theme"], "dark")
            self.assertEqual(preferences["defaultProvider"], "sbx-openai")
            self.assertEqual(preferences["enabledModels"], ["sbx-openai/*"])
            auth = json.loads((profile / "auth.json").read_text())
            self.assertTrue(auth == {"sbx-openai": {"type": "api_key", "key": "oai-oat01-proxy-managed"}})
            for name in ("auth.json", "models.json", "settings.json"):
                self.assertEqual((profile / name).stat().st_mode & 0o777, 0o600)

            preferences.update(theme="light", defaultProvider="openai-codex", defaultModel="old-model")
            (profile / "settings.json").write_text(json.dumps(preferences))
            env["YALLMO_PI_MODEL"] = "gpt-6-sol"
            second = subprocess.run(["bash", str(script)], env=env, text=True, capture_output=True)
            self.assertEqual(second.returncode, 0, second.stderr)
            preferences = json.loads((profile / "settings.json").read_text())
            self.assertEqual(preferences["theme"], "light")
            self.assertEqual(preferences["defaultProvider"], "sbx-openai")
            self.assertEqual(preferences["defaultModel"], "gpt-6-sol")
            models = json.loads((profile / "models.json").read_text())
            self.assertEqual(models["providers"]["sbx-openai"]["models"][0]["id"], "gpt-6-sol")
            self.assertFalse(list(profile.glob(".seed.*")))


if __name__ == "__main__":
    unittest.main()
