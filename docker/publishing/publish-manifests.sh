#!/usr/bin/env bash
# Called only after all selected images passed tests/scanning and their pushed digests were recorded.
# No builds or login here. Compatible with Bash 3.2.
set -euo pipefail
if [[ $# -lt 3 || $# -gt 4 ]]; then
  printf 'Usage: publish-manifests.sh NAMESPACE/REPOSITORY BUILD_TAG ARCH=sha256:DIGEST [ARCH=sha256:DIGEST]\n' >&2
  exit 2
fi
repo="$1"
build="$2"
shift 2
if [[ ! "$repo" =~ ^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*$ ||
      ! "$build" =~ ^build-[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  printf 'Invalid repository or build tag.\n' >&2
  exit 2
fi
seen=" "
refs=()
# Validate every reference before creating any tag.
for image in "$@"; do
  arch="${image%%=*}"
  digest="${image#*=}"
  case "$arch" in amd64|arm64) ;; *) printf 'Invalid Docker architecture.\n' >&2; exit 2 ;; esac
  if [[ ! "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]; then
    printf 'Invalid image digest.\n' >&2
    exit 2
  fi
  if [[ "$seen" = *" $arch "* ]]; then printf 'Duplicate architecture.\n' >&2; exit 2; fi
  seen="$seen$arch "
  refs+=("$repo@$digest")
done

# Merge this run's selected staging images by their recorded digests, never by mutable tags.
# Exactly one versioned tag; a single-architecture run never reuses the other staging image.
docker buildx imagetools create --tag "$repo:$build" "${refs[@]}"
docker buildx imagetools inspect "$repo:$build"
printf 'Published %s:%s\n' "$repo" "$build"
# Never replace multi-architecture latest with a partial/single-architecture run.
if [[ ${#refs[@]} -eq 2 ]]; then
  docker buildx imagetools create --tag "$repo:latest" "${refs[@]}"
  docker buildx imagetools inspect "$repo:latest"
  printf 'Published %s:latest (amd64 + arm64)\n' "$repo"
else
  printf 'Single-architecture run: %s:latest was left unchanged.\n' "$repo"
fi
