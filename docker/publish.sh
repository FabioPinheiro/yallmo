#!/usr/bin/env bash
# Local Docker Hub publishing, compatible with macOS's Bash 3.2. Run from any directory.
# Build the current working tree; never read tokens or log in automatically. Authenticate with docker login first.
set -euo pipefail

usage() {
  printf 'Usage: bash docker/publish.sh [--arch both|x86_64|aarch64] [DOCKERHUB_USER/REPOSITORY]\n'
  printf 'Defaults: both architectures, fmgp/yallmo. Aliases: amd64, arm64.\n'
  printf 'Requires Docker Desktop/Engine, Buildx, curl and Trivy.\n'
  printf 'Replaces selected staging-ARCH tags and publishes one build-ID tag. Only both updates latest.\n'
}
repo=fmgp/yallmo
selection=both
repo_set=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --arch)
      if [[ $# -lt 2 ]]; then usage >&2; exit 2; fi
      selection="$2"; shift 2 ;;
    --arch=*) selection="${1#--arch=}"; shift ;;
    -*) printf 'Unknown option: %s\n' "$1" >&2; usage >&2; exit 2 ;;
    *)
      if [[ "$repo_set" = true ]]; then usage >&2; exit 2; fi
      repo="$1"; repo_set=true; shift ;;
  esac
done
case "$selection" in
  both) arches=(arm64 amd64) ;;
  x86_64|amd64) arches=(amd64) ;;
  aarch64|arm64) arches=(arm64) ;;
  *) printf 'Expected --arch both, x86_64 or aarch64 (aliases: amd64, arm64).\n' >&2; exit 2 ;;
esac
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
printf 'Selected Docker architectures: %s. Every selected image must pass.\n' "${arches[*]}"
printf 'On Apple Silicon, amd64 normally uses slow emulation.\n'

for arch in "${arches[@]}"; do
  image="$repo:$build-runtime-$arch"
  docker buildx build \
    --builder "$builder" --platform "linux/$arch" \
    --pull --no-cache --load --provenance=false \
    --file docker/Dockerfile --target runtime --tag "$image" .

  # Test exactly this loaded image, including named-volume ownership and configured server restarts.
  DOCKER_DEFAULT_PLATFORM="linux/$arch" bash "$script_dir/tests/smoke.sh" "$image"
  docker save --output "$scan_dir/image-$arch.tar" "$image"
  # Retain each exact scanned archive until every selected image has passed.
  trivy image --input "$scan_dir/image-$arch.tar" --severity HIGH,CRITICAL --exit-code 1
done

# Do not move this loop into the build loop: nothing is pushed until ALL selected images pass.
printf '\nAll checks passed. Pushing selected tested images to staging-ARCH tags.\n'
images=()
for arch in "${arches[@]}"; do
  "$BASH" "$script_dir/publishing/push-image.sh" "$scan_dir/image-$arch.tar" \
    "$repo:$build-runtime-$arch" "$repo:staging-$arch" "$scan_dir/$arch.txt"
  digest="$(< "$scan_dir/$arch.txt")"
  images+=("$arch=$digest")
done

# Share tag semantics with CI and use the same Bash interpreter on macOS/Linux.
"$BASH" "$script_dir/publishing/publish-manifests.sh" "$repo" "$build" "${images[@]}"
