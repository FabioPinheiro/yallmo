#!/usr/bin/env bash
# Mixin image ENV is not inherited by the workload; keep the browser path here.
set -euo pipefail
export PLAYWRIGHT_BROWSERS_PATH=/opt/playwright-browsers
case "${0##*/}" in
  playwright) exec node /opt/playwright/node_modules/playwright/cli.js "$@" ;;
  playwright-mcp) exec node /opt/playwright/node_modules/@playwright/mcp/cli.js "$@" ;;
esac
