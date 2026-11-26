#!/usr/bin/env bash
# Checks that the four built jars carry their own descriptor, filtered to the version in the poms. The version is what
# /v1/info, the app and the server console report, so a jar whose descriptor lost it (or was replaced by a generated
# one) is a broken release even when the tests pass. CI and the release workflow both run this.
# Usage: scripts/check-jar-descriptors.sh [version]     default: the version in pom.xml
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
V=${1:-$(cd "$ROOT" && mvn -B -ntp -q help:evaluate -Dexpression=project.version -DforceStdout)}
VP=${V//./\\.} # the version in a regular expression
fail=0

check() { # check <jar> <descriptor> <pattern> <what it proves>
  if unzip -p "$1" "$2" 2>/dev/null | grep -qE "$3"; then
    echo "ok    $(basename "$1") $4"
  else
    echo "FAIL  $1: $2 does not contain $4 ($3)"
    fail=1
  fi
}

check "$ROOT/spigot/target/MCDashboard.jar" plugin.yml "^version: $VP\$" "the version"
check "$ROOT/nukkit/target/MCDashboard-Nukkit.jar" plugin.yml "^version: \\\"?$VP\\\"?\$" "the version"
check "$ROOT/velocity/target/MCDashboard-Velocity.jar" velocity-plugin.json "\"version\":[[:space:]]*\"$VP\"" "the version"
check "$ROOT/bungee/target/MCDashboard-Bungee.jar" bungee.yml "^version: $VP\$" "the version"

# The main class each platform loads, so a descriptor that is there but not ours is caught too.
check "$ROOT/spigot/target/MCDashboard.jar" plugin.yml "^main: org\.mcdashboard\." "the main class"
check "$ROOT/nukkit/target/MCDashboard-Nukkit.jar" plugin.yml "^main: org\.mcdashboard\.nukkit\." "the main class"
check "$ROOT/velocity/target/MCDashboard-Velocity.jar" velocity-plugin.json "\"main\":[[:space:]]*\"org\.mcdashboard\.velocity\." "the main class"
check "$ROOT/bungee/target/MCDashboard-Bungee.jar" bungee.yml "^main: org\.mcdashboard\.bungee\." "the main class"

if [ $fail -ne 0 ]; then
  echo
  echo "Every jar must ship its own descriptor with version $V; the Velocity one is filtered by Maven, not generated"
  echo "by the Velocity @Plugin processor (see velocity/pom.xml)."
fi
exit $fail
