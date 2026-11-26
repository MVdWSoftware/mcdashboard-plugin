#!/usr/bin/env bash
# Smoke test of the Paper backend plus the Velocity and BungeeCord jars on real servers in Docker
# (see docs/proxy-platforms.md): every jar is checked on its own platform, and the proxies are checked again federating
# the backend's placeholders.
# Needs docker, curl, python3, unzip and a built tree (mvn -B package). Uses throwaway containers named mcd-smoke-* and the
# docker network mcd-smoke-net (no published ports: the API is read through a curl container on the same network), and removes them at the end.
# The servers run as this user (UID/GID for the paper image, PUID/PGID for the proxy one), so the config files they
# write between the two passes are ours to edit; without that the containers write them as root and the edits fail on CI.
# Usage: scripts/proxy-smoke-test.sh [workdir]
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
W=${1:-$(mktemp -d)}
NET=mcd-smoke-net
fail=0
cleanup() { for c in mcd-smoke-paper mcd-smoke-vel mcd-smoke-bun; do docker logs $c > "$W/logs-$c.txt" 2>&1 || true; docker rm -f $c >/dev/null 2>&1 || true; done; docker network rm $NET >/dev/null 2>&1 || true; }
trap cleanup EXIT
for c in mcd-smoke-paper mcd-smoke-vel mcd-smoke-bun; do docker rm -f $c >/dev/null 2>&1 || true; done
check() { if [ "$2" = "$3" ]; then echo "ok    $1"; else echo "FAIL  $1: expected '$3', got '$2'"; fail=1; fi; }
# get <host> <path>: the API of a container, with the app's User-Agent (a throwaway curl container on the same network).
get() { docker run --rm --network $NET curlimages/curl -s -A 'MCDashboard/1.0.0 (android)' "http://$1:1553$2"; }
val() { # host id -> v, vb or vs of the first record of /v1/values
  get "$1" "/v1/values?ids=$2" | python3 -c "import json,sys; r=json.load(sys.stdin)[0]; print(r.get('vb', r.get('v', r.get('vs'))))" | tr 'A-Z' 'a-z'
}
yml_version() { # <jar> <plugin.yml|bungee.yml> -> the version the server will report
  unzip -p "$1" "$2" | sed -n 's/^version: *"\{0,1\}\([^"]*\)"\{0,1\}$/\1/p' | head -1
}
json_version() { # <jar> <velocity-plugin.json> -> the version the server will report
  unzip -p "$1" "$2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('version'))"
}
wait_for() { # wait_for <seconds> <test command...>: give a passing test a deadline
  local end=$((SECONDS+$1)); shift
  until "$@" 2>/dev/null; do
    [ $SECONDS -lt $end ] || return 1
    sleep 3
  done
}
cfg() { # config.yml: open the API without TLS or pairing, poll quickly, federate the lobby
  sed -i '0,/enabled: false/s//enabled: true/; s/mode: self-signed/mode: off/; s/require-authentication: true/require-authentication: false/; s/ping-interval: 15/ping-interval: 5/; s/federation-interval: 15/federation-interval: 5/' "$1"
  sed -i 's/^  backends: \[\]/  backends:\n    - name: lobby\n      url: "http:\/\/mcd-smoke-paper:1553"\n      key: ""\n      pin: ""/' "$1"
}
mkdir -p "$W"/{paper/plugins/PlaceholderAPI/expansions,vel/plugins,bun/plugins}
cd "$W"
docker network create $NET >/dev/null 2>&1 || true
cp "$ROOT/spigot/target/MCDashboard.jar" paper/plugins/
cp "$ROOT/velocity/target/MCDashboard-Velocity.jar" vel/plugins/
cp "$ROOT/bungee/target/MCDashboard-Bungee.jar" bun/plugins/
curl -sfL -o paper/plugins/PlaceholderAPI.jar https://github.com/PlaceholderAPI/PlaceholderAPI/releases/download/2.12.3/PlaceholderAPI-2.12.3.jar
curl -sfL -o paper/plugins/PlaceholderAPI/expansions/Server.jar https://dl.placeholderapi.com/PAPI-Expansion-Server-2.7.3.jar

# Pass 1: let every server write its default files, then configure them.
docker run -d --name mcd-smoke-paper --network $NET -e EULA=TRUE -e TYPE=PAPER -e VERSION=1.21.4 -e ONLINE_MODE=false \
  -e MEMORY=1G -e UID="$(id -u)" -e GID="$(id -g)" -v "$W/paper:/data" itzg/minecraft-server >/dev/null
docker run -d --name mcd-smoke-vel --network $NET -e TYPE=VELOCITY -e MEMORY=512M -e PUID="$(id -u)" -e PGID="$(id -g)" -v "$W/vel:/server" itzg/mc-proxy >/dev/null
docker run -d --name mcd-smoke-bun --network $NET -e TYPE=BUNGEECORD -e MEMORY=512M -e PUID="$(id -u)" -e PGID="$(id -g)" -v "$W/bun:/server" itzg/mc-proxy >/dev/null
wait_for 300 sh -c 'for f in paper/plugins/MCDashboard/config.yml vel/plugins/mcdashboard/config.yml bun/plugins/MCDashboard/config.yml vel/velocity.toml bun/config.yml; do [ -f "$f" ] || exit 1; done'
sleep 5
docker stop mcd-smoke-paper mcd-smoke-vel mcd-smoke-bun >/dev/null
sed -i '0,/enabled: false/s//enabled: true/; s/mode: self-signed/mode: off/; s/require-authentication: true/require-authentication: false/; s/allowed-clients: \[\]/allowed-clients: ["MCDashboard-Proxy\/.*"]/; s/offer-to-app: false/offer-to-app: true/' paper/plugins/MCDashboard/config.yml
cfg vel/plugins/mcdashboard/config.yml
cfg bun/plugins/MCDashboard/config.yml
for f in vel/plugins/mcdashboard/placeholders.yml bun/plugins/MCDashboard/placeholders.yml; do
  sed -i 's/^#  - expansion: backend/  - expansion: backend/; s/^#    group: Backends/    group: Backends/; s/^#    access: PUBLIC/    access: PUBLIC/' "$f"
done
sed -i 's/^online-mode = true/online-mode = false/; s#lobby = "127.0.0.1:30066"#lobby = "mcd-smoke-paper:25565"#; s#minigames = "127.0.0.1:30068"#minigames = "mcd-smoke-paper:25999"#' vel/velocity.toml
sed -i 's/^online_mode: true/online_mode: false/; s/address: localhost:25565/address: mcd-smoke-paper:25565/; s/^    restricted: false/    restricted: false\n  minigames:\n    motd: down\n    address: mcd-smoke-paper:25999\n    restricted: false/' bun/config.yml

# Pass 2: run.
docker start mcd-smoke-paper >/dev/null
wait_for 300 sh -c "docker logs mcd-smoke-paper 2>&1 | grep -q 'Done ('"
docker start mcd-smoke-vel mcd-smoke-bun >/dev/null
sleep 50
echo "== paper"
check "paper plugin loaded without errors" "$(docker logs mcd-smoke-paper 2>&1 | grep -c -E 'Exception|NoClassDefFound|Could not load plugin' || true)" 0
check "paper /v1/info plugin" "$(get mcd-smoke-paper /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["plugin"])')" MCDashboard
check "paper /v1/info server" "$(get mcd-smoke-paper /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["name"])')" Paper
check "paper reports its jar's version" "$(get mcd-smoke-paper /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')" "$(yml_version paper/plugins/MCDashboard.jar plugin.yml)"
check "paper online" "$(val mcd-smoke-paper online)" 0
check "paper max_players is not negative" "$(val mcd-smoke-paper max-players | grep -c -- '^-' || true)" 0
check "paper tps is a number" "$(val mcd-smoke-paper tps | grep -cE '^[0-9]+(\.[0-9]+)?$' || true)" 1
check "paper mcdashboard-devices" "$(val mcd-smoke-paper mcdashboard-devices)" 0
for p in "velocity vel Velocity 3 MCDashboard-Velocity.jar velocity-plugin.json" "bungee bun BungeeCord 2 MCDashboard-Bungee.jar bungee.yml"; do
  set -- $p
  echo "== $1"
  check "$1 plugin loaded without errors" "$(docker logs mcd-smoke-$2 2>&1 | grep -v 'network.backends' | grep -c -E 'Exception|NoClassDefFound|Could not load plugin' || true)" 0
  check "$1 /v1/info plugin" "$(get mcd-smoke-$2 /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["plugin"])')" MCDashboard
  if [ "${6##*.}" = json ]; then want=$(json_version "$2/plugins/$5" "$6"); else want=$(yml_version "$2/plugins/$5" "$6"); fi
  check "$1 reports its jar's version" "$(get mcd-smoke-$2 /v1/info | python3 -c 'import json,sys; print(json.load(sys.stdin)["version"])')" "$want"
  check "$1 proxy_platform" "$(val mcd-smoke-$2 proxy_platform)" "$(echo "$3" | tr 'A-Z' 'a-z')"
  check "$1 proxy_online" "$(val mcd-smoke-$2 proxy_online)" 0
  check "$1 proxy_servers" "$(val mcd-smoke-$2 proxy_servers)" "$4"
  check "$1 proxy_max is not negative" "$(val mcd-smoke-$2 proxy_max | grep -c -- '^-' || true)" 0
  check "$1 lobby up" "$(val mcd-smoke-$2 proxy_server_lobby_up)" true
  check "$1 minigames up (nothing listens)" "$(val mcd-smoke-$2 proxy_server_minigames_up)" false
  check "$1 federated tps" "$(val mcd-smoke-$2 backend_lobby_tps)" 20
done
docker stop mcd-smoke-paper >/dev/null
sleep 15
check "velocity lobby down" "$(val mcd-smoke-vel proxy_server_lobby_up)" false
check "bungee lobby down" "$(val mcd-smoke-bun proxy_server_lobby_up)" false
exit $fail
