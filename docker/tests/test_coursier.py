"""Coursier installer tests: synthetic release bytes, no network or host installation changes."""
import gzip
import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

SCRIPT = Path(__file__).resolve().parents[1] / "image/install-coursier.py"
SPEC = importlib.util.spec_from_file_location("sandbox_coursier", SCRIPT)
COURSIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COURSIER)


class CoursierTests(unittest.TestCase):
    def test_both_architectures_use_the_matching_verified_official_release(self):
        payload = b"synthetic native launcher"
        compressed = gzip.compress(payload)
        digest = hashlib.sha256(compressed).hexdigest()
        for machine, (filename, _) in COURSIER.RELEASES.items():
            with self.subTest(machine=machine):
                response = MagicMock()
                response.__enter__.return_value.read.return_value = compressed
                with patch.dict(COURSIER.RELEASES, {machine: (filename, digest)}), \
                     patch.object(COURSIER, "urlopen", return_value=response) as download:
                    self.assertEqual(COURSIER.launcher(machine), payload)
                    request = download.call_args.args[0]
                    self.assertEqual(request.full_url,
                                     f"https://github.com/coursier/coursier/releases/download/v{COURSIER.VERSION}/{filename}")
                    self.assertEqual(download.call_args.kwargs["timeout"], 120)

    def test_digest_mismatch_fails_before_any_installation(self):
        response = MagicMock()
        response.__enter__.return_value.read.return_value = gzip.compress(b"tampered")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(COURSIER.platform, "machine", return_value="x86_64"), \
                 patch.object(COURSIER, "urlopen", return_value=response):
                with self.assertRaisesRegex(RuntimeError, "SHA-256 mismatch"):
                    COURSIER.install(root / "coursier", root / "cs")
            self.assertFalse((root / "coursier").exists())
            self.assertFalse((root / "cs").exists())

    def test_unsupported_architecture_never_downloads_or_installs(self):
        with patch.object(COURSIER, "urlopen") as download:
            with self.assertRaisesRegex(RuntimeError, "Unsupported Coursier architecture"):
                COURSIER.launcher("unsupported")
            download.assert_not_called()

    def test_install_creates_executable_and_symlink_outside_root_home(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with patch.object(COURSIER, "launcher", return_value=b"synthetic native launcher"):
                COURSIER.install(root / "coursier", root / "cs")
            executable = root / "coursier/cs"
            self.assertEqual(executable.read_bytes(), b"synthetic native launcher")
            self.assertEqual(executable.stat().st_mode & 0o777, 0o755)
            self.assertTrue((root / "cs").is_symlink())
            self.assertEqual((root / "cs").resolve(), executable)


if __name__ == "__main__":
    unittest.main()
