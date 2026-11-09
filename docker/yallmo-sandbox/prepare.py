#!/usr/bin/env python3
"""One-time host-side Pi setup. Never reads host auth.json or installs/executes packages."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parent
# Model files can also contain credentials and shell-based credential resolvers.
# Keep model metadata, but do not bring their authentication into this sandbox.
PRIVATE_FIELDS = {
    "apikey", "headers", "oauth", "auth", "credentials", "accesstoken",
    "refreshtoken", "clientsecret", "password",
}


def read_object(path):
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object")
    return value


def portable_models(value):
    if isinstance(value, list):
        return [portable_models(item) for item in value]
    if not isinstance(value, dict):
        return value
    result = {}
    for key, item in value.items():
        if key.lower().replace("_", "").replace("-", "") in PRIVATE_FIELDS:
            continue
        if key == "baseUrl" and isinstance(item, str):
            url = urlsplit(item)
            if url.username or url.password or url.query or url.fragment:
                raise ValueError("Model endpoints must not contain URL credentials, queries or fragments")
        result[key] = portable_models(item)
    return result


def write_new(path, value):
    # An existing file is user's persistent state, not a template to force on every run.
    try:
        with path.open("x") as target:
            json.dump(value, target, indent=2)
            target.write("\n")
    except FileExistsError:
        print(f"Preserved {path.name}")
    else:
        print(f"Created {path.name}")


def prepare(source, destination, templates):
    source = source.expanduser().resolve()
    destination = destination.expanduser().resolve()
    profile = destination / "agent"
    if source == profile or source in profile.parents:
        raise ValueError("Source profile must be outside the destination Pi directory")

    # Validate inputs before creating any output. Host auth.json is deliberately never opened.
    auth = read_object(templates / "auth.json")
    expected = {"sbx-openai": {"type": "api_key", "key": "oai-oat01-proxy-managed"}}
    if auth != expected:
        raise ValueError("Authentication template must contain only the public proxy sentinel")
    if (profile / "auth.json").exists() and read_object(profile / "auth.json") != expected:
        raise ValueError("Existing destination auth.json is not sentinel-only; it was left untouched")

    models = None
    if not (profile / "models.json").exists():
        models = portable_models(read_object(source / "models.json")) if (source / "models.json").is_file() else {}
        providers = models.setdefault("providers", {})
        if not isinstance(providers, dict):
            raise ValueError("Model providers must be an object")
        # Reserve these IDs for the sandbox endpoints, not host-specific credentials or JWT providers.
        providers.update(read_object(templates / "models.json")["providers"])
    store = None
    if not (profile / "models-store.json").exists() and (source / "models-store.json").is_file():
        store = portable_models(read_object(source / "models-store.json"))

    settings = read_object(templates / "settings.json")
    mcp = read_object(templates / "mcp.json")
    os.umask(0o077)
    profile.mkdir(parents=True, exist_ok=True)
    destination.chmod(0o700)
    profile.chmod(0o700)
    write_new(profile / "auth.json", auth)
    write_new(profile / "settings.json", settings)
    write_new(profile / "mcp.json", mcp)
    if models is not None:
        write_new(profile / "models.json", models)
    if store is not None:
        write_new(profile / "models-store.json", store)

    skills = source / "skills"
    if skills.is_dir() and not (profile / "skills").exists():
        with tempfile.TemporaryDirectory(prefix=".skills-copy-", dir=profile) as temporary:
            # Dereference host symlinks so the copy doesn't depend on host-only paths in the VM.
            copied = Path(temporary) / "skills"
            shutil.copytree(skills, copied, symlinks=False)
            copied.rename(profile / "skills")
        print("Copied skills")
    elif (profile / "skills").exists():
        print("Preserved skills")
    else:
        print("No host skills directory found; skipped")
    print("Host auth/settings/sessions were not imported; model authentication fields were omitted.")
    print("Review the private pi/agent copy before launching. Existing state is never overwritten.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=Path.home() / ".pi/agent",
                        help="Host Pi agent directory (default: ~/.pi/agent)")
    parser.add_argument("--destination", type=Path, default=ROOT / "pi",
                        help="Private copied Pi directory (default: ./pi beside this script)")
    args = parser.parse_args()
    try:
        prepare(args.source, args.destination, ROOT / "templates")
    except (OSError, ValueError):
        # Never include configuration values or JSON parser excerpts in error output.
        print("Unable to prepare Pi state: check JSON input, endpoint URLs, sentinel-only auth and permissions. "
              "Existing files were not overwritten.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
