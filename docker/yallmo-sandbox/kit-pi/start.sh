#!/usr/bin/env bash
# Validate only when Pi is launched; the workload shell works without a Pi profile.
set -euo pipefail
export PI_CODING_AGENT_DIR=/home/agent/.pi/agent
export PI_CLIPBOARD_OSC52="${PI_CLIPBOARD_OSC52:-1}"
export PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
if [[ ! -f "$PI_CODING_AGENT_DIR/settings.json" || ! -f "$PI_CODING_AGENT_DIR/models.json" ]]; then
  printf 'Missing mounted Pi configuration. On the host, run python3 docker/yallmo-sandbox/kit-pi/prepare.py from the repository root to create missing files.\n' >&2
  exit 1
fi
if ! jq -e '. == {"sbx-openai":{"type":"api_key","key":"oai-oat01-proxy-managed"}}' \
    "$PI_CODING_AGENT_DIR/auth.json" >/dev/null 2>&1; then
  printf 'Expected the public OAuth sentinel template, not real credentials, in Pi auth.json.\n' >&2
  exit 1
fi
exec /usr/local/bin/pi-cli "$@"
