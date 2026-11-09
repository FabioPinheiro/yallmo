"""Check release discovery/integrity without downloads, credentials or host installation changes."""
import hashlib
from html import escape
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import MagicMock, patch

SPEC = importlib.util.spec_from_file_location("install_sbt", Path(__file__).resolve().parents[1] / "image/install-sbt.py")
TOOLS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TOOLS)


class ReleaseTests(unittest.TestCase):
    def release(self, digest, filename="tool.gz"):
        clipboard = "" if digest is None else (
            f'<clipboard-copy aria-label="Copy to clipboard digest for {escape(filename)}" '
            f'value="{escape(digest)}"></clipboard-copy>'
        )
        return (f'<a href="/owner/tool/releases/download/v1.2.3/{filename}">{filename}</a>' + clipboard).encode()

    def test_verified_asset_uses_public_pages_not_the_rest_api(self):
        content = b"release payload"
        digest = "sha256:" + hashlib.sha256(content).hexdigest()
        with patch.object(TOOLS, "latest_tag", return_value="v1.2.3"), \
             patch.object(TOOLS, "fetch", side_effect=[self.release(digest), content]) as fetch:
            self.assertEqual(TOOLS.asset("owner/tool", lambda name: name == "tool.gz"), content)
            self.assertEqual([call.args[0] for call in fetch.call_args_list], [
                "https://github.com/owner/tool/releases/expanded_assets/v1.2.3",
                "https://github.com/owner/tool/releases/download/v1.2.3/tool.gz",
            ])

    def test_digest_mismatch_fails_closed(self):
        with patch.object(TOOLS, "latest_tag", return_value="v1.2.3"), \
             patch.object(TOOLS, "fetch", side_effect=[self.release("sha256:" + "0" * 64), b"payload"]):
            with self.assertRaisesRegex(RuntimeError, "Mismatched release digest"):
                TOOLS.asset("owner/tool", lambda name: True)

    def test_missing_digest_fails_before_downloading_binary(self):
        with patch.object(TOOLS, "latest_tag", return_value="v1.2.3"), \
             patch.object(TOOLS, "fetch", return_value=self.release(None)) as fetch:
            with self.assertRaisesRegex(RuntimeError, "Missing release digest"):
                TOOLS.asset("owner/tool", lambda name: True)
            self.assertEqual(fetch.call_count, 1)

    def test_malformed_digest_is_not_accepted(self):
        with patch.object(TOOLS, "latest_tag", return_value="v1.2.3"), \
             patch.object(TOOLS, "fetch", return_value=self.release("sha256:bad")):
            with self.assertRaisesRegex(RuntimeError, "Missing release digest"):
                TOOLS.asset("owner/tool", lambda name: True)

    def test_digest_for_a_different_asset_is_not_used(self):
        other = self.release("sha256:" + "0" * 64, "other.gz")
        with patch.object(TOOLS, "latest_tag", return_value="v1.2.3"), \
             patch.object(TOOLS, "fetch", return_value=self.release(None) + other):
            with self.assertRaisesRegex(RuntimeError, "Missing release digest"):
                TOOLS.asset("owner/tool", lambda name: name == "tool.gz")

    def test_unrecognized_release_layout_fails_closed(self):
        with patch.object(TOOLS, "latest_tag", return_value="v1.2.3"), \
             patch.object(TOOLS, "fetch", return_value=b"<html>Changed upstream layout</html>"):
            with self.assertRaisesRegex(RuntimeError, "exactly one matching release asset"):
                TOOLS.asset("owner/tool", lambda name: True)

    def test_external_asset_links_are_rejected(self):
        page = self.release(None).replace(b'href="/owner/', b'href="https://example.invalid/owner/')
        with patch.object(TOOLS, "latest_tag", return_value="v1.2.3"), \
             patch.object(TOOLS, "fetch", return_value=page):
            with self.assertRaisesRegex(RuntimeError, "exactly one matching release asset"):
                TOOLS.asset("owner/tool", lambda name: True)

    def test_latest_release_is_discovered_via_head_redirect(self):
        response = MagicMock()
        response.__enter__.return_value.geturl.return_value = "https://github.com/owner/tool/releases/tag/v1.2.3"
        with patch.object(TOOLS, "urlopen", return_value=response) as open_url:
            self.assertEqual(TOOLS.latest_tag("owner/tool"), "v1.2.3")
            request = open_url.call_args.args[0]
            self.assertEqual(request.get_method(), "HEAD")
            self.assertEqual(request.full_url, "https://github.com/owner/tool/releases/latest")

    def test_unexpected_latest_redirect_is_rejected(self):
        response = MagicMock()
        response.__enter__.return_value.geturl.return_value = "https://example.invalid/owner/tool/releases/tag/v1.2.3"
        with patch.object(TOOLS, "urlopen", return_value=response):
            with self.assertRaisesRegex(RuntimeError, "Unexpected latest release redirect"):
                TOOLS.latest_tag("owner/tool")

    def test_unsupported_architecture_fails_before_installation(self):
        with patch.object(TOOLS.platform, "machine", return_value="unknown"):
            with self.assertRaisesRegex(RuntimeError, "Unsupported architecture"):
                TOOLS.main()


if __name__ == "__main__":
    unittest.main()
