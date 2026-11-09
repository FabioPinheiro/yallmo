#!/usr/bin/env python3
"""Install a SHA-256-verified native Coursier release, on amd64 or arm64.

Digests are the official coursier/coursier v2.1.26 release asset digests.
Update version and both digests together; never fall back to an unchecked download.
"""
import gzip
import hashlib
from pathlib import Path
import platform
from urllib.request import Request, urlopen

VERSION = "2.1.26"
RELEASES = {
    "x86_64": ("cs-x86_64-pc-linux.gz", "348e37bc2a8c706640e6b032c4551a0e055a7f1537485b4a91540e9d3598ec6d"),
    "aarch64": ("cs-aarch64-pc-linux.gz", "6c358ac223332c8981eb86417d3faf9338a5ef6c95d1879a58162b24c510025c"),
}


def launcher(machine):
    if machine not in RELEASES:
        raise RuntimeError(f"Unsupported Coursier architecture: {machine}")
    name, digest = RELEASES[machine]
    url = f"https://github.com/coursier/coursier/releases/download/v{VERSION}/{name}"
    request = Request(url, headers={"User-Agent": "yallmo-image-build"})
    with urlopen(request, timeout=120) as response:
        compressed = response.read()
    if hashlib.sha256(compressed).hexdigest() != digest:
        raise RuntimeError("Coursier release SHA-256 mismatch")
    return gzip.decompress(compressed)


def install(directory=Path("/usr/share/coursier"), link=Path("/usr/local/bin/cs")):
    # Download, verify and decompress before changing the installation.
    binary = launcher(platform.machine())
    directory.mkdir(parents=True, exist_ok=True)
    executable = directory / "cs"
    executable.write_bytes(binary)
    executable.chmod(0o755)
    link.symlink_to(executable)
    print(f"Installed Coursier {VERSION}; release SHA-256 verified", flush=True)


if __name__ == "__main__":
    install()
