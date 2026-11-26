#!/usr/bin/env bash
# Smoke test of the MCDashboard-Nukkit jar on a real PowerNukkitX server in Docker (see docs/bedrock.md).
# Needs docker, curl, python3, unzip and a built tree (mvn -B package). Uses a throwaway container named mcd-smoke-pnx and the
# docker network mcd-smoke-net (no published ports: the API is read through a curl container on the same network), and
# removes them at the end. The server runs as the current user so that config.yml can be edited between the passes.
# Usage: scripts/nukkit-smoke-test.sh [workdir]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
W=${1:-$(mktemp -d)}
NET=mcd-smoke-net
PNX_API=v1.2.4 # PlaceholderAPI for PowerNukkitX, same release the nukkit pom compiles against
fail=0
cleanup() { for c in mcd-smoke-pnx; do docker logs $c > "$W/logs-$c.txt" 2>&1 || true; docker rm -f $c >/dev/null 2>&1 || true; done; docker network rm $NET >/dev/null 2>&1 || true; }
trap cleanup EXIT
for c in mcd-smoke-pnx; do docker rm -f $c >/dev/null 2>&1 || true; done
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: expected '$3', got '$2'"; fail=1; fi; }
# get <host> <path>: the API of a container, with the app's User-Agent (a throwaway curl container on the same network).
get() { docker run --rm --network $NET curlimages/curl -s -A 'MCDashboard/1.0.0 (android)' "http://$1:1553$2"; }
val() { # host id -> v, vb or vs of the first record of /v1/values
  get "$1" "/v1/values?ids=$2" | python3 -c "import json,sys; r=json.load(sys.stdin)[0]; print(r.get('vb', r.get('v', r.get('vs'))))" | tr 'A-Z' 'a-z'
}
yml_version() { # <jar> <plugin.yml> -> the version the server will report
  unzip -p "$1" "$2" | sed -n 's/^version: *"\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' | head -1
}
wait_for() { # wait_for <seconds> <test command...>: give a passing test a deadline
  local end=$((SECONDS+$1)); shift
  until "$@" 2>/dev/null; do
    [ $SECONDS -lt $end ] || return 1
    sleep 2
  done
}
run() { docker rm -f mcd-smoke-pnx >/dev/null 2>&1 || true; docker run -d --name mcd-smoke-pnx --network $NET -u "$(id -u):$(id -g)" -e MEMORY=512M -v "$W/pnx:/data" powernukkitx/server:latest >/dev/null; }
mkdir -p "$W"/pnx/plugins
cd "$W"
docker network create $NET >/dev/null 2>&1 || true
cp "$ROOT/nukkit/target/MCDashboard-Nukkit.jar" pnx/plugins/
curl -sfL -o pnx/plugins/PlaceholderAPI.jar "https://github.com/PowerNukkitX-Bundle/PlaceholderAPI/releases/download/$PNX_API/PlaceholderAPI.jar"

# Pass 1: let the server write its default files, then configure them: open the API without TLS or pairing,
# and offer the plugin's own counters, which prove its placeholders reached the PlaceholderAPI.
run
wait_for 180 test -f pnx/plugins/MCDashboard/config.yml
docker stop mcd-smoke-pnx >/dev/null
sed -i '0,/enabled: false/s//enabled: true/; s/mode: self-signed/mode: off/; s/require-authentication: true/require-authentication: false/; s/offer-to-app: false/offer-to-app: true/' pnx/plugins/MCDashboard/config.yml

# Pass 2: run and read the API the way the app does.
run
wait_for 180 sh -c "docker logs mcd-smoke-pnx 2>&1 | grep -q 'Done'"
sleep 30
echo "== powernukkitx"
check "powernukkitx plugin loaded without errors" "$(docker logs mcd-smoke-pnx 2>&1 | grep -v 'network.backends' | grep -c -E 'Exception|NoClassDefFound|Could not load plugin' || true)" 0
check "powernukkitx plugin enabled" "$(docker logs mcd-smoke-pnx 2>&1 | grep -c 'Enabling MCDashboard v' || true)" 1
check "powernukkitx /v1/info plugin" "$(get mcd-smoke-pnx /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["plugin"])')" MCDashboard
check "powernukkitx /v1/info server" "$(get mcd-smoke-pnx /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')" PowerNukkitX
check "powernukkitx reports its jar's version" "$(get mcd-smoke-pnx /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')" "$(yml_version pnx/plugins/MCDashboard-Nukkit.jar plugin.yml)"
check "powernukkitx online" "$(val mcd-smoke-pnx online)" 0
check "powernukkitx max_players is not negative" "$(val mcd-smoke-pnx max-players | grep -c -- '^-' || true)" 0
check "powernukkitx tps is a number" "$(val mcd-smoke-pnx tps | grep -cE '^[0-9]+(\.[0-9]+)?$' || true)" 1
check "powernukkitx mcdashboard-devices" "$(val mcd-smoke-pnx mcdashboard-devices)" 0
exit $fail
