#!/usr/bin/env python3
"""Export portable, non-secret Pi preferences into the local kit. Never read auth.json or models.json.

Run on the Mac before creating the Sandbox. Only plain npm package references are portable here:
local paths, git URLs, commands, credential fields and unknown extension settings are never exported.
"""
import argparse
import json
from pathlib import Path
import re
import tempfile
import os

DESTINATION = Path(__file__).with_name("pi") / "preferences.json"
MODEL = re.compile(r"[A-Za-z0-9._-]+")
NPM_PACKAGE = re.compile(r"npm:(?:@[A-Za-z0-9._-]+/)?[A-Za-z0-9._-]+(?:@[A-Za-z0-9._+~^*-]+)?")
CHOICES = {
    "defaultThinkingLevel": {"off", "minimal", "low", "medium", "high", "xhigh", "max"},
    "theme": {"dark", "light", "system"},
    "tuiMode": {"regular", "fullscreen"},
    "steeringMode": {"all", "one-at-a-time"},
    "followUpMode": {"all", "one-at-a-time"},
    "treeFilterMode": {"default", "no-tools", "user-only", "labeled-only", "all"},
    "doubleEscapeAction": {"tree", "fork", "none"},
    "fullscreenExitOutput": {"transcript", "resume-hint"},
    "fullscreenScrollbar": {"auto", "always", "hidden"},
}
BOOLEANS = ("hideThinkingBlock", "showCacheMissNotices", "fullscreenCopyOnSelect", "collapseChangelog")


def portable_settings(data):
    if not isinstance(data, dict):
        raise ValueError("Expected a settings object")
    if data.get("defaultProvider") not in (None, "openai", "openai-codex", "sbx-openai"):
        raise ValueError("This example imports a ChatGPT/OpenAI profile only")
    output = {}
    model = data.get("defaultModel")
    if model is not None:
        if not isinstance(model, str) or not MODEL.fullmatch(model):
            raise ValueError("Invalid portable defaultModel")
        output["defaultModel"] = model
    for key, choices in CHOICES.items():
        value = data.get(key)
        if isinstance(value, str) and value in choices:
            output[key] = value
    for key in BOOLEANS:
        if isinstance(data.get(key), bool):
            output[key] = data[key]
    if isinstance(data.get("quietStartup"), bool) or data.get("quietStartup") == "header":
        output["quietStartup"] = data["quietStartup"]
    packages = data.get("packages", [])
    if isinstance(packages, list):
        output["packages"] = list(dict.fromkeys(
            package for package in packages if isinstance(package, str) and NPM_PACKAGE.fullmatch(package)
        ))
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", default=str(Path.home() / ".pi/agent/settings.json"),
                        help="Pi agent directory or settings.json (default: ~/.pi/agent/settings.json)")
    source = Path(parser.parse_args().source).expanduser()
    if source.is_dir():
        source /= "settings.json"
    if source.name != "settings.json":
        parser.error("Only settings.json may be imported; never pass auth.json or models.json")
    try:
        preferences = portable_settings(json.loads(source.read_text()))
    except (OSError, ValueError):
        parser.error("Unable to import portable preferences; check settings.json and its default provider/model")
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", dir=DESTINATION.parent,
                                     prefix=".preferences-", delete=False) as temporary:
        path = Path(temporary.name)
        json.dump(preferences, temporary, indent=2)
        temporary.write("\n")
    try:
        os.replace(path, DESTINATION)
    finally:
        path.unlink(missing_ok=True)
    # Report field names/count only, never the original config or discarded credential/command values.
    print("Imported portable fields: " + ", ".join(preferences))
    print(f"Plain npm package references: {len(preferences.get('packages', []))}")
    print(f"Wrote {DESTINATION}. Provider/auth remain Sandbox-managed; recreate the Sandbox to apply kit changes.")


if __name__ == "__main__":
    main()
