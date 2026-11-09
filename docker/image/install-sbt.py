#!/usr/bin/env python3
"""Install current stable sbt from official releases; no credentials or version pins.

Use GitHub's public release pages rather than its rate-limited REST API. Release asset SHA-256
values are published on the assets page too; fail closed if that metadata changes or is missing.
The sbt launcher still honors project/build.properties; application/compiler versions stay project-owned.
"""
import hashlib
from html.parser import HTMLParser
import os
from pathlib import Path
import platform
import re
import shutil
import tarfile
import tempfile
from urllib.parse import unquote, urlsplit
from urllib.request import Request, urlopen

HEADERS = {"User-Agent": "yallmo-image-build", "Accept-Language": "en"}


def fetch(url):
    with urlopen(Request(url, headers=HEADERS), timeout=120) as response:
        return response.read()


def latest_tag(repo):
    # The normal /releases/latest redirect has no REST API quota. HEAD avoids downloading a full release page.
    request = Request(f"https://github.com/{repo}/releases/latest", headers=HEADERS, method="HEAD")
    with urlopen(request, timeout=120) as response:
        destination = urlsplit(response.geturl())
    prefix = f"/{repo}/releases/tag/"
    tag = destination.path.removeprefix(prefix)
    if (destination.scheme != "https" or destination.netloc != "github.com"
            or not destination.path.startswith(prefix) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*", tag)):
        raise RuntimeError(f"Unexpected latest release redirect: {repo}")
    return tag


class ReleaseAssets(HTMLParser):
    """Read exact asset links and their named clipboard digests, not unrelated hashes elsewhere on the page."""

    def __init__(self, repo, tag):
        super().__init__()
        self.prefix = f"/{repo}/releases/download/{tag}/"
        self.links = {}
        self.digests = {}

    def handle_starttag(self, tag, attributes):
        attributes = dict(attributes)
        href = attributes.get("href", "")
        if tag == "a" and href.startswith(self.prefix):
            name = unquote(href[len(self.prefix):])
            if name and "/" not in name and "?" not in name and "#" not in name:
                self.links[name] = "https://github.com" + href
        if tag == "clipboard-copy":
            label = attributes.get("aria-label", "")
            prefix = "Copy to clipboard digest for "
            digest = attributes.get("value", "")
            if label.startswith(prefix) and re.fullmatch(r"sha256:[a-fA-F0-9]{64}", digest):
                self.digests[label[len(prefix):]] = digest.lower()


def asset(repo, name):
    tag = latest_tag(repo)
    metadata = ReleaseAssets(repo, tag)
    metadata.feed(fetch(f"https://github.com/{repo}/releases/expanded_assets/{tag}").decode("utf-8"))
    matches = [filename for filename in metadata.links if name(filename)]
    if len(matches) != 1:
        raise RuntimeError(f"Expected exactly one matching release asset: {repo}/{tag}")
    filename = matches[0]
    expected = metadata.digests.get(filename)
    if expected is None:
        raise RuntimeError(f"Missing release digest: {repo}/{filename}")
    content = fetch(metadata.links[filename])
    actual = "sha256:" + hashlib.sha256(content).hexdigest()
    if expected != actual:
        raise RuntimeError(f"Mismatched release digest: {repo}/{filename}")
    print(f"Installing {repo} {tag} ({filename}); SHA-256 verified", flush=True)
    return content


def main():
    machine = platform.machine()
    if machine not in {"x86_64", "aarch64"}:
        raise RuntimeError(f"Unsupported architecture: {machine}")
    with tempfile.TemporaryDirectory(prefix="yallmo-tools-") as temp:
        archive = Path(temp) / "sbt.tgz"
        archive.write_bytes(asset("sbt/sbt", lambda name: name.endswith(".tgz")))
        destination = Path("/opt/sbt")
        destination.mkdir(parents=True, exist_ok=True)
        with tarfile.open(archive) as bundle:
            # Python's data filter rejects path traversal and unsafe archive entries.
            bundle.extractall(temp, filter="data")
        for item in (Path(temp) / "sbt").iterdir():
            shutil.move(str(item), destination / item.name)
        os.symlink("/opt/sbt/bin/sbt", "/usr/local/bin/sbt")


if __name__ == "__main__":
    main()
