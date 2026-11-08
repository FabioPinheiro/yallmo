#!/usr/bin/env bash
# Runs INSIDE the Sandbox. Only public sentinel/config values are seeded; never load a host auth file.
set -euo pipefail
if [[ "${SBX_CRED_OPENAI_MODE:-}" != oauth ]]; then
  printf 'Host OpenAI OAuth is required. On your Mac run: sbx secret set openai --oauth\n' >&2
  printf 'Approve this kit to use that service. Do not log into Pi inside the Sandbox.\n' >&2
  exit 1
fi
# Explicit kit argument wins; otherwise reuse the safely imported host model, not an obsolete hard-coded ID.
model="${YALLMO_PI_MODEL:-$(jq -r '.defaultModel // "gpt-6.1-sol"' /opt/pi-sbx/preferences.json)}"
if [[ ! "$model" =~ ^[A-Za-z0-9._-]+$ ]]; then
  printf 'Invalid model ID.\n' >&2
  exit 1
fi
export PI_CODING_AGENT_DIR=/home/agent/.pi/sbx
umask 077
mkdir -p "$PI_CODING_AGENT_DIR"
chmod 700 "$PI_CODING_AGENT_DIR"
temporary="$(mktemp -d "$PI_CODING_AGENT_DIR/.seed.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT
# This checked-in auth.json contains ONLY oai-oat01-proxy-managed, matching the kit's OAuth sentinel.
cp /opt/pi-sbx/auth.json "$temporary/auth.json"
jq --arg model "$model" '.providers["sbx-openai"].models[0].id = $model' \
  /opt/pi-sbx/models.json > "$temporary/models.json"
chmod 600 "$temporary/auth.json" "$temporary/models.json"
mv -f "$temporary/auth.json" "$PI_CODING_AGENT_DIR/auth.json"
mv -f "$temporary/models.json" "$PI_CODING_AGENT_DIR/models.json"
# Import only the sanitized preferences snapshot, never a host Pi home/auth/models file.
# Existing in-Sandbox preferences win on restart, except the provider/model must stay on the proxy path.
previous="$PI_CODING_AGENT_DIR/settings.json"
if [[ ! -f "$previous" ]]; then
  printf '{}\n' > "$temporary/previous-settings.json"
  previous="$temporary/previous-settings.json"
fi
jq -s --arg model "$model" \
  '.[0] * .[1] | .defaultProvider = "sbx-openai" | .defaultModel = $model |
   .enabledModels = ["sbx-openai/*"] | .cacheWarming = "off"' \
  /opt/pi-sbx/preferences.json "$previous" > "$temporary/settings.json"
chmod 600 "$temporary/settings.json"
mv -f "$temporary/settings.json" "$PI_CODING_AGENT_DIR/settings.json"
rm -rf "$temporary"
trap - EXIT
# "api_key" is Pi's opaque-bearer storage format here, NOT a paid OpenAI API key or an OAuth refresh token.
# The custom provider uses Responses directly, avoiding the built-in ChatGPT provider's JWT decoding.
# sbx may otherwise choose the host project path as the initial cwd, even with an explicit mount target.
cd -- /home/agent/workspace
exec pi --provider sbx-openai --model "$model" "$@"
