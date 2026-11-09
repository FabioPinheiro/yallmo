#!/usr/bin/env bash
# Reload the exact scanned archive, replace its architecture staging tag, and record the digest.
# docker push uses the existing Docker credential configuration (docker login).
set -euo pipefail
if [[ $# -ne 4 ]]; then
  printf 'Usage: push-image.sh IMAGE_ARCHIVE SOURCE_IMAGE REPOSITORY:staging-ARCH DIGEST_FILE\n' >&2
  exit 2
fi
archive="$1"
source="$2"
staging="$3"
output="$4"
if [[ ! "$staging" =~ ^[a-z0-9][a-z0-9._-]*/[a-z0-9][a-z0-9._-]*:staging-(amd64|arm64)$ ]]; then
  printf 'Expected a Docker Hub repository with staging-amd64 or staging-arm64.\n' >&2
  exit 2
fi

push_log="$(mktemp)"
trap 'rm -f "$push_log"' EXIT
# Do not depend on the daemon still containing the image that was scanned.
docker load --input "$archive"
docker tag "$source" "$staging"
docker push "$staging" | tee "$push_log"
# Read the digest returned by this push, not a mutable tag or another RepoDigests entry.
digest="$(sed -nE 's/^.*digest: (sha256:[a-f0-9]{64}) size:.*$/\1/p' "$push_log")"
if [[ ! "$digest" =~ ^sha256:[a-f0-9]{64}$ ]]; then
  printf 'Missing or invalid pushed digest; refusing to publish final tags.\n' >&2
  exit 1
fi
printf '%s\n' "$digest" > "$output"
