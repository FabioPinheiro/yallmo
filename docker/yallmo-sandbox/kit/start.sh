#!/usr/bin/env bash
# The selected shell stays interactive; the boot lifecycle manages Yallmo. Never auto-start Pi.
set -euo pipefail
export PI_CODING_AGENT_DIR=/home/agent/.pi/agent
if [[ ! -f "$PI_CODING_AGENT_DIR/settings.json" || ! -f "$PI_CODING_AGENT_DIR/models.json" ]]; then
  printf 'Missing mounted Pi configuration. On the host, run python3 docker/yallmo-sandbox/prepare.py from the repository root to create missing files.\n' >&2
  exit 1
fi
if ! jq -e '. == {"sbx-openai":{"type":"api_key","key":"oai-oat01-proxy-managed"}}' \
    "$PI_CODING_AGENT_DIR/auth.json" >/dev/null 2>&1; then
  printf 'Expected the public OAuth sentinel template, not real credentials, in Pi auth.json.\n' >&2
  exit 1
fi
if [[ "${SBX_CRED_OPENAI_MODE:-}" != oauth ]]; then
  printf 'ChatGPT needs host OAuth: run sbx secret set openai --oauth and approve this kit.\n' >&2
fi
cd -- /home/agent/workspace
case "${YALLMO_AUTOSTART:-1}" in
  0|false|no|off) printf 'Yallmo autostart disabled; use yallmo-service start when ready.\n' >&2 ;;
  *) yallmo-service status --wait || true ;; # Keep Bash available for inspecting startup failures/stopped services.
esac
printf 'Yallmo controls: yallmo-service start|stop|restart|status; logs: yallmo-service logs --follow\n' >&2
printf 'Run pi manually. Website: http://127.0.0.1:8080\n' >&2
# Keep Bash as the workload default; an optional Zsh mixin can supply /bin/zsh.
shell="${YALLMO_SHELL:-/bin/bash}"
case "$shell" in
  /bin/bash|/bin/zsh) ;;
  *) printf 'Unsupported YALLMO_SHELL: choose /bin/bash or /bin/zsh.\n' >&2; exit 1 ;;
esac
if [[ ! -x "$shell" ]]; then
  printf 'Selected shell %s is not installed; include its kit and recreate the sandbox.\n' "$shell" >&2
  exit 1
fi
export SHELL="$shell"
exec "$shell" "$@"
