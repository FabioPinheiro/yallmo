#!/usr/bin/env bash
# Runs INSIDE the Sandbox from the shared workspace. No host token files or credential commands are used.
set -euo pipefail
cd -- "$(dirname -- "$0")"
umask 077
# Defaults are public sentinels, never a fallback to real OPENAI_API_KEY/OAuth environment variables.
export YALLMO_SBX_OPENAI_ACCESS_TOKEN="${YALLMO_SBX_OPENAI_ACCESS_TOKEN:-oai-oat01-proxy-managed}"
export YALLMO_SBX_OPENAI_REFRESH_TOKEN="${YALLMO_SBX_OPENAI_REFRESH_TOKEN:-oai-ort01-proxy-managed}"
if [[ "$YALLMO_SBX_OPENAI_ACCESS_TOKEN" != oai-oat01-proxy-managed ||
      "$YALLMO_SBX_OPENAI_REFRESH_TOKEN" != oai-ort01-proxy-managed ]]; then
  printf 'Refusing non-sentinel credentials. Run this example through its Sandbox environment.\n' >&2
  exit 1
fi
# Netty needs an explicit forward proxy and the Sandbox PEM CA bundle; internal RPC remains direct.
export YALLMO_SBX_HTTP_PROXY=true
export SSL_CERT_FILE="${SSL_CERT_FILE:-/etc/ssl/certs/ca-certificates.crt}"
mkdir -p ./storage
exec yallmo server --storage ./storage --config ./yallmo.yaml --log-level info "$@"
