#!/usr/bin/env bash
# Run on EACH architecture, against a locally loaded image. Requires Docker Engine and host curl.
set -euo pipefail
image="${1:?usage: docker/smoke.sh IMAGE [development]}"
variant="${2:-runtime}"
container=""
volumes=()
work="$(mktemp -d)"
cleanup() {
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi
  # Bash 3.2 on macOS treats an empty array expansion as unset under set -u.
  if [[ ${#volumes[@]} -gt 0 ]]; then
    for volume in "${volumes[@]}"; do docker volume rm "$volume" >/dev/null 2>&1 || true; done
  fi
  rm -rf "$work"
}
trap cleanup EXIT

docker run --rm "$image" bash -euc '
  test "$(id -u)" = 1000
  test "$HOME" = /home/agent
  # Catch root-owned sbt/Coursier/npm cache regressions as the real UID 1000, not as root.
  for directory in "$HOME" "$XDG_CACHE_HOME" "$XDG_CACHE_HOME/coursier" "$HOME/.sbt" "$HOME/.ivy2" "$HOME/.npm"; do
    probe="$(mktemp "$directory/yallmo-smoke.XXXXXX")"
    rm "$probe"
  done
  test -x /bin/sh && test -x /bin/bash
  test -s /etc/ssl/certs/ca-certificates.crt
  . /etc/os-release; test "$ID" = ubuntu
  python --version; python3 -m pip --version; python3 -m venv /tmp/check-venv
  git --version; ssh -V; java -version
  test -n "$(find /opt/yallmo/lib -name "*.jar" -print -quit)"
'
if [[ "$variant" = development ]]; then
  docker run --rm "$image" bash -euc 'node --version; npm --version; pi --version; scala-cli --version; test -x /usr/local/bin/sbt'
  # Actual Pi SDK + fake HTTP streams: no provider credentials or network, and no host auth mounts.
  pi_tests="$(cd "$(dirname "$0")/yallmo-sandbox-sbx-example/pi" && pwd)"
  docker run --rm --mount "type=bind,src=$pi_tests,dst=/tmp/pi-sbx-tests,readonly" \
    "$image" node --test /tmp/pi-sbx-tests/test-provider.mjs
fi
check_server() {
  container="$(docker run --detach --publish 127.0.0.1::8080 "$@")"
  port="$(docker port "$container" 8080/tcp | sed -n 's/^127\.0\.0\.1://p')"
  [[ "$port" =~ ^[0-9]+$ ]]
  for attempt in {1..90}; do
    if curl --silent --fail --max-time 2 "http://127.0.0.1:$port/" -o "$work/index.html"; then
      grep -qi '<!doctype html' "$work/index.html"
      printf 'PASS: %s serves the bundled website on loopback\n' "$image"
      docker rm -f "$container" >/dev/null
      container=""
      return 0
    fi
    if [[ "$(docker inspect --format '{{.State.Running}}' "$container")" != true ]]; then break; fi
    sleep 1
  done
  docker logs --tail 60 "$container" >&2
  printf 'FAIL: server did not serve the bundled website\n' >&2
  return 1
}
# Fresh named volumes verify Docker's first-mount ownership and the documented config seed path.
# No real login, host workspace, provider request, or published public listener is involved.
data_volume="yallmo-smoke-data-$$-$RANDOM"
config_volume="yallmo-smoke-config-$$-$RANDOM"
docker volume create "$data_volume" >/dev/null
volumes+=("$data_volume")
docker volume create "$config_volume" >/dev/null
volumes+=("$config_volume")
config="$(cd "$(dirname "$0")" && pwd)/config.example.yaml"
docker run --rm -i --mount "type=volume,src=$config_volume,dst=/config" \
  "$image" bash -c 'umask 077; cat > /config/yallmo.yaml; test "$(stat -c %a /config/yallmo.yaml)" = 600' < "$config"
check_server --mount "type=volume,src=$data_volume,dst=/data" "$image" server
# Restart the same storage with configuration, then restart it again to exercise persisted ownership.
for restart in 1 2; do
  check_server --mount "type=volume,src=$data_volume,dst=/data" \
    --mount "type=volume,src=$config_volume,dst=/config" \
    "$image" server --storage /data --config /config/yallmo.yaml --workspace /workspace
done
