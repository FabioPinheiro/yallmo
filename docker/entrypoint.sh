#!/usr/bin/env bash
set -euo pipefail

# Do not evaluate shell strings/JAVA_OPTS. JVM configuration can use standard JDK_JAVA_OPTIONS.
# An explicit command remains available for Python, Bash, Pi, and maintenance tasks.
if [[ $# -eq 0 ]]; then set -- server; fi
case "$1" in
  server)
    shift
    # Named volumes inherit these directories' UID 1000 ownership on first use.
    # Bind mounts must already be writable by UID 1000; never recursively chown a mounted workspace.
    if [[ $# -eq 0 ]]; then
      set -- --storage /data
    fi
    exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
      -cp '/opt/yallmo/lib/*' fmgp.yallmo.server.OrchestratorServerMain "$@"
    ;;
  repl)
    shift
    # No fallback client: the existing application rejects unsupported/non-interactive terminals.
    exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow \
      -cp '/opt/yallmo/lib/*' fmgp.yallmo.repl.ReplMain "$@"
    ;;
  *) exec "$@" ;;
esac
