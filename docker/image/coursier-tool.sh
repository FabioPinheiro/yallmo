#!/usr/bin/env bash
set -euo pipefail
source /usr/local/libexec/coursier-env.sh || exit 1
name="${0##*/}"
case "$name" in
  cs) executable=/usr/share/coursier/cs ;;
  scala|scala-cli)
    executable=/usr/share/coursier/bin/scala-cli
    # Match build.sbt without overriding project directives or an explicit --scala version.
    set -- --cli-default-scala-version 3.10.1 "$@"
    ;;
  metals|bloop) executable="/usr/share/coursier/bin/$name" ;;
  sbt) executable=/opt/sbt/bin/sbt ;;
  *) printf 'Invoke this launcher as cs, scala, metals, bloop, scala-cli or sbt.\n' >&2; exit 2 ;;
esac
exec "$executable" "$@"
