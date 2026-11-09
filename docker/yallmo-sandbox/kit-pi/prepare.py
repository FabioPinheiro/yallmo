#!/usr/bin/env python3
"""Initialize missing sandbox Pi files from local templates; never import a host profile."""
import argparse
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parent
TEMPLATE_FILES = ("auth.json", "settings.json", "mcp.json", "models.json")


def read_object(path):
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError("Expected a JSON object")
    return value


def write_new(path, value):
    # The bind-mounted files are user-managed state, not templates to reapply.
    try:
        with path.open("x") as target:
            json.dump(value, target, indent=2)
            target.write("\n")
    except FileExistsError:
        print(f"Preserved {path.name}")
    else:
        print(f"Created {path.name}")


def prepare(destination, templates):
    destination = destination.expanduser().resolve()
    profile = destination / "agent"
    # Validate before creating files, without printing configuration values.
    values = {name: read_object(templates / name) for name in TEMPLATE_FILES}
    expected_auth = {"sbx-openai": {"type": "api_key", "key": "oai-oat01-proxy-managed"}}
    if values["auth.json"] != expected_auth:
        raise ValueError("Authentication template must contain only the public proxy sentinel")
    if (profile / "auth.json").exists() and read_object(profile / "auth.json") != expected_auth:
        raise ValueError("Existing auth.json is not sentinel-only; it was left untouched")

    previous_umask = os.umask(0o077)
    try:
        profile.mkdir(parents=True, exist_ok=True)
        destination.chmod(0o700)
        profile.chmod(0o700)
        for name, value in values.items():
            write_new(profile / name, value)
    finally:
        os.umask(previous_umask)
    print(f"Edit {profile} directly; it is bind-mounted into the sandbox.")
    print("Existing files were preserved. No host profile, skills or model catalog were imported.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, default=ROOT.parent / "pi",
                        help="Sandbox Pi directory (default: ../pi beside this kit)")
    args = parser.parse_args()
    try:
        prepare(args.destination, ROOT / "templates")
    except (OSError, ValueError):
        # Never include configuration values or JSON parser excerpts in error output.
        print("Unable to initialize Pi state: check template JSON, sentinel-only auth and permissions. "
              "Existing files were not overwritten.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
