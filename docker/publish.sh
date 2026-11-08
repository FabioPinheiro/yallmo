#!/usr/bin/env bash
# Local Docker Hub publishing, compatible with macOS's Bash 3.2. Run from any directory.
# Build the current working tree; never read tokens or log in automatically. Authenticate with docker login first.
set -euo pipefail

usage() {
  printf 'Usage: bash docker/publish.sh [DOCKERHUB_USER/REPOSITORY]\n'
  printf 'Default: fmgp/yallmo. Requires Docker Desktop/Engine, Buildx, curl and Trivy.\n'
  printf 'Builds runtime/development for arm64/amd64, tests/scans ALL images, then publishes latest/dev.\n'
}
if [[ "${1:-}" = --help || "${1:-}" = -h ]]; then usage; exit 0; fi
if [[ $# -gt 1 ]]; then usage >&2; exit 2; fi
repo="${1:-fmgp/yallmo}"
if [[ ! "$repo" =~ ^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*$ ]]; then
  printf 'Expected a Docker Hub namespace/repository, for example fmgp/yallmo.\n' >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir/.."
for executable in docker trivy curl; do
  if ! command -v "$executable" >/dev/null 2>&1; then
    printf 'Missing required command: %s\n' "$executable" >&2
    exit 1
  fi
done
docker info >/dev/null
docker buildx version >/dev/null
trivy --version

# Reuse a dedicated builder without changing the user's selected default builder.
builder=yallmo-publisher
if ! docker buildx inspect "$builder" >/dev/null 2>&1; then
  docker buildx create --name "$builder" --driver docker-container
fi
docker buildx inspect "$builder" --bootstrap

build="build-$(date -u +%Y%m%d%H%M%S)-$$-$RANDOM"
scan_dir="$(mktemp -d)"
trap 'rm -rf "$scan_dir"' EXIT
printf '\nBuilding %s:%s-* from %s\n' "$repo" "$build" "$PWD"
printf 'Both architectures must pass. On Apple Silicon, amd64 normally uses slow emulation.\n'

for target in runtime development; do
  for arch in arm64 amd64; do
    image="$repo:$build-$target-$arch"
    docker buildx build \
      --builder "$builder" --platform "linux/$arch" \
      --pull --no-cache --load --provenance=false \
      --file docker/Dockerfile --target "$target" --tag "$image" .

    # Test exactly this loaded image, including named-volume ownership and configured server restarts.
    DOCKER_DEFAULT_PLATFORM="linux/$arch" bash "$script_dir/smoke.sh" "$image" "$target"
    docker save --output "$scan_dir/image.tar" "$image"
    # No ignore-unfixed/skip flags: high or critical findings block publication.
    trivy image --input "$scan_dir/image.tar" --severity HIGH,CRITICAL --exit-code 1
    rm -f "$scan_dir/image.tar"
  done
done

# Do not move this loop into the build loop: nothing is pushed until ALL four images pass.
printf '\nAll checks passed. Pushing the tested platform images.\n'
for target in runtime development; do
  for arch in arm64 amd64; do
    docker push "$repo:$build-$target-$arch"
  done
done

# Combine tested images, without rebuilding. Updating these two tags is sequential, not atomic.
docker buildx imagetools create --tag "$repo:latest" \
  "$repo:$build-runtime-arm64" "$repo:$build-runtime-amd64"
docker buildx imagetools create --tag "$repo:dev" \
  "$repo:$build-development-arm64" "$repo:$build-development-amd64"
docker buildx imagetools inspect "$repo:latest"
docker buildx imagetools inspect "$repo:dev"
printf '\nPublished %s:latest and %s:dev. Retained platform tags: %s:%s-*\n' "$repo" "$repo" "$repo" "$build"
