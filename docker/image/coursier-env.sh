#!/usr/bin/env bash
# Sourced by Bash and tool wrappers. Shared tools are read-only; mutable state is per UID.
# Do not change the caller's shell flags, umask, working directory or positional arguments.
_coursier_environment() {
  local uid user_root name directory kind seed
  uid="$(id -u)" || return 1
  user_root="/var/cache/coursier/$uid"
  if [[ -z "${COURSIER_CACHE:-}" || -z "${COURSIER_ARCHIVE_CACHE:-}" ||
        -z "${COURSIER_JVM_CACHE:-}" || -z "${COURSIER_BIN_DIR:-}" ||
        -z "${SCALA_CLI_HOME:-}" ]]; then
    # The root-owned parent is sticky (1777). Refuse pre-created/symlinked directories
    # belonging to someone else instead of following them or changing their ownership.
    mkdir -m 700 -- "$user_root" 2>/dev/null || true
    if [[ -L "$user_root" || ! -d "$user_root" ||
          "$(stat -c %u -- "$user_root")" != "$uid" ||
          "$(stat -c %a -- "$user_root")" != 700 ]]; then
      printf 'Cannot initialize private per-user Coursier state: check directory ownership and mode.\n' >&2
      return 1
    fi
    export COURSIER_CACHE="${COURSIER_CACHE:-$user_root/cache}"
    export COURSIER_ARCHIVE_CACHE="${COURSIER_ARCHIVE_CACHE:-$user_root/arc}"
    export COURSIER_JVM_CACHE="${COURSIER_JVM_CACHE:-$user_root/jvm}"
    export COURSIER_BIN_DIR="${COURSIER_BIN_DIR:-$user_root/bin}"
  fi
  # Scala CLI has its own cache/data/config paths, separate from Coursier. Docker --user
  # keeps the image HOME/XDG_CACHE_HOME, which belong to agent rather than an arbitrary UID.
  export SCALA_CLI_HOME="${SCALA_CLI_HOME:-$user_root/scalacli}"
  export SCALA_CLI_CONFIG="${SCALA_CLI_CONFIG:-$SCALA_CLI_HOME/config.json}"
  for name in COURSIER_CACHE COURSIER_ARCHIVE_CACHE COURSIER_JVM_CACHE COURSIER_BIN_DIR SCALA_CLI_HOME; do
    directory="${!name}"
    if ! mkdir -p -- "$directory" || [[ ! -w "$directory" || ! -x "$directory" ]]; then
      printf '%s must be a writable, searchable directory for the current user.\n' "$name" >&2
      return 1
    fi
  done
  # Bootstrap launchers need their image-built artifacts even when the network is unavailable.
  # Seed private copies (reflinks where supported), never writable hardlinks to shared JARs.
  if [[ "$COURSIER_CACHE" == "$user_root/cache" || "$COURSIER_ARCHIVE_CACHE" == "$user_root/arc" ||
        "$COURSIER_JVM_CACHE" == "$user_root/jvm" ]]; then
    if [[ -L "$user_root" || "$(stat -c %u -- "$user_root")" != "$uid" ||
          "$(stat -c %a -- "$user_root")" != 700 ]]; then
      printf 'Refusing unsafe Coursier cache ownership or permissions.\n' >&2
      return 1
    fi
    (
      flock -x 9 || exit 1
      for kind in cache arc jvm; do
        case "$kind" in
          cache) directory="$COURSIER_CACHE" ;;
          arc) directory="$COURSIER_ARCHIVE_CACHE" ;;
          jvm) directory="$COURSIER_JVM_CACHE" ;;
        esac
        [[ "$directory" == "$user_root/$kind" && ! -f "$directory/.image-seeded" ]] || continue
        seed="/usr/share/coursier/bootstrap/$kind"
        if [[ -d "$seed" ]]; then
          # Retry an interrupted first seed completely; after the marker, never refresh user state.
          cp -R --reflink=auto --no-preserve=ownership -- "$seed/." "$directory/" || exit 1
        fi
        touch "$directory/.image-seeded" || exit 1
      done
    ) 9>"$user_root/.seed.lock" || return 1
  fi
  case ":$PATH:" in
    *":$COURSIER_BIN_DIR:"*) ;;
    *) export PATH="$COURSIER_BIN_DIR:$PATH" ;;
  esac
}
_coursier_environment
_coursier_status=$?
unset -f _coursier_environment
if [[ $_coursier_status -ne 0 ]]; then
  unset _coursier_status
  return 1
fi
unset _coursier_status
