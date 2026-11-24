# Networks: Velocity and BungeeCord

One dashboard for a whole network. `MCDashboard-Velocity.jar` (Velocity 3.x, Java 17) and `MCDashboard-Bungee.jar`
(BungeeCord and Waterfall 1.21, Java 17) run the same core as the Spigot plugin: the same API, pairing, dashboards, history,
events and tunnel, and the same app. This is the setup for the Network licence tier (one licence, many servers).

## Install

1. Put the jar for your proxy in `plugins/`. No other plugin is needed.
2. Start the proxy once. Edit `plugins/MCDashboard/config.yml` (Velocity: `plugins/mcdashboard/`) and set `server.enabled: true`
   and `server.public-url`. The API listens on its own port (default 1553, plus 1554 for the live feed), so open them in the
   firewall or put it behind the [reverse proxy or tunnel](reverse-proxy.md).
3. Give staff the node `mcdashboard.dashboard.network` (and `mcdashboard.admin`) in your permission plugin. Everyone can
   link a device and sees the Player dashboard.
4. In game run `/mcdashboard link` and enter the code in the app (see [installation.md](installation.md) for TLS).

## Built-in placeholders

There is no PlaceholderAPI on a proxy, so these are answered by the plugin itself. They are read from memory or from a
background cache, never by a network call on the thread that serves the API.

| Placeholder | Access | Value |
| --- | --- | --- |
| `%proxy_online%` | PUBLIC | Players connected to the proxy |
| `%proxy_max%` | PUBLIC | The proxy's player limit (0 when it has none) |
| `%proxy_servers%` | PUBLIC | Backend servers registered with the proxy |
| `%proxy_server_<name>_online%` | PUBLIC | Players on that backend server |
| `%proxy_server_<name>_up%` | PUBLIC | `true`/`false`: the server accepts a TCP connection (checked every `network.ping-interval` seconds, off the proxy threads; empty until the first check) |
| `%proxy_uptime%` | PUBLIC | Seconds since the proxy's JVM started |
| `%proxy_version%`, `%proxy_platform%` | PUBLIC | Proxy software version and name |
| `%proxy_player_name%`, `%proxy_player_ping%`, `%proxy_player_server%` | PLAYER | The paired player's name, latency to the proxy in ms and current server; empty while they are offline |

The default `placeholders.yml` lists the network wide ones as `online`, `max-players`, `servers`, `uptime`, `version` and
`platform`, and the player ones as `player-name`, `player-ping` and `player-server`. Its `catalog:` entry for the expansion `proxy`
adds `proxy_server_<name>_online` and `proxy_server_<name>_up` for every backend server the proxy has at startup (restart or
`/mcdashboard reload` after adding one; names with characters other than letters, digits, `_`, `.` and `-` are skipped).

The preset dashboards are `player` (4 tiles) and `network` (6 tiles). The free limit of 1 dashboard shows `player`; `network`
needs a licence (or the app unlock) for its second tab.

## Permissions and commands

Permissions come from the proxy's own check (Velocity `Player#hasPermission`, BungeeCord `CommandSender#hasPermission`).
LuckPerms, when installed on the proxy, backs that check, so its groups work without any code of ours. Proxies have no
permission defaults, so `mcdashboard.player` and `mcdashboard.dashboard.player` count as granted unless Velocity says they are
explicitly denied (BungeeCord cannot tell a denial from silence, so there they are always granted). Everything else, including
`mcdashboard.dashboard.network`, `mcdashboard.admin` and `mcdashboard.developer`, must be granted. Snapshots are taken every 5
seconds and when a player joins.

`/mcdashboard` (aliases `/mcd` and `/dashboard`): `link [player]`, `devices`, `revoke <id>`, `reload` (admin: restarts the plugin, also
re-reading `network:`), `developer`, `tunnel` and `about`. There is no QR map (that is a Bukkit item); `link` prints the code and,
with `server.public-url`, the `mcdashboard://` link. `link <player>` needs `mcdashboard.admin` (or the console) and an online player.

## Federation (optional, off by default)

A network dashboard can also show what the backend servers publish. Each backend that runs MCDashboard already serves `/v1`;
the proxy reads their PUBLIC placeholders and offers them as `backend_<name>_<id>`, so real PlaceholderAPI values stay resolved
on the backend.

```yaml
network:
  federation-interval: 15
  backends:
    - name: lobby
      url: "https://lobby.example.com:1553"
      key: ""        # optional bearer token
      pin: ""        # SHA-256 fingerprint of a self-signed certificate (the backend logs it)
```

Then add this to the proxy's `placeholders.yml` and restart or reload:

```yaml
catalog:
  - expansion: backend
    group: Backends
    access: PUBLIC
```

On each backend add `MCDashboard-Proxy/.*` to `security.allowed-clients`, and either set `security.require-authentication: false`
(only PUBLIC placeholders are readable that way) or give a `key`.

What it does, and its limits:

- Safe by design: only the configured URLs are contacted (never one from a response), over http or https without credentials in
  the URL, no redirects, 3 s connect and 5 s request timeouts, at most 1 MiB per response, at most 500 placeholders per backend.
  Only PUBLIC placeholders are ever requested, so a player's values cannot leak through it.
- A value older than three intervals is dropped instead of shown as current; an unreachable backend is logged once.
- The ids that exist are those the backend listed when the proxy last read it, so the catalog needs a moment after start (it
  retries for 30 seconds) and a new backend placeholder appears after a reload.
- Not done yet (TODO): refreshing a rotating access token for `key` (a pairing token expires, so use a backend without
  authentication or a token you replace), history for federated values and licence aggregation.

## Limitations

- **No PlaceholderAPI parity.** Only the placeholders above exist on a proxy; third party expansions are not available. An optional
  bridge to MiniPlaceholders on Velocity is a possible future option; it is not implemented and not verified.
- `proxy_player_*` read live state, so they have no value while the player is offline.
- `_up` is a TCP connect, not a Minecraft status ping: it says the port answers, not that the game is healthy. Servers on unix
  sockets (BungeeCord) are not listed.
- The proxy has no ticks; the core's scheduler runs on the proxy's scheduler (1 tick = 50 ms) and there is no main thread.
- Velocity does not give plugins a YAML library, so the Velocity jar carries a relocated SnakeYAML (`org/mcdashboard/libs/`).
- The jars were compiled against velocity-api 3.4.0 and bungeecord-api 1.21.

## Tested on real proxies

Run in Docker on 18 November 2026 with `scripts/proxy-smoke-test.sh` (backend up/down, federation, API values) and an interactive pass with a mineflayer bot (player counts, pairing, commands, permissions):
Velocity 4.3.1 (itzg/mc-proxy, `TYPE=VELOCITY`), BungeeCord build 2113 (version 26.1-R0.1-SNAPSHOT, `TYPE=BUNGEECORD`), both with
one Paper 1.21.4 backend (offline mode, no forwarding) that ran `MCDashboard.jar` and PlaceholderAPI 2.12.3. Verified on both:

- The plugin loads without errors; `config.yml`, `placeholders.yml` and `dashboards/` are written.
- `/v1/info`, `/v1/placeholders` and `/v1/values` answer with the app User-Agent, pairing works (`/mcdashboard link`, the code
  to `POST /v1/auth`, player placeholders with the token, 401 without a token), and `devices`, `revoke` (the refresh token
  stops working), `reload`, `about`, `developer` and `tunnel` run from a player and from the console (`link` needs a player,
  as documented).
- `proxy_online`, `proxy_server_<name>_online`, `proxy_servers`, `proxy_version` and `proxy_platform` are right, with 0 and with
  1 connected player; `proxy_server_<name>_up` is `true` for the running backend, `false` for a port nobody listens on and
  flips to `false` within one `ping-interval` after the backend is stopped and back to `true` after it starts.
- Federation: `backend_lobby_*` appears in the catalog and returns the Spigot backend's values; a stopped backend is logged once.
- BungeeCord permissions through the `groups`/`permissions` of its `config.yml` (the network dashboard appears with
  `mcdashboard.dashboard.network`, `/mcdashboard reload` needs `mcdashboard.admin`).

Not verified: a proxy with LuckPerms, Velocity modern forwarding to the backend, TLS (the tests used `tls.mode: off`),
Waterfall, the tunnel and the live feed (WebSocket) on a proxy, history storage other than memory, the licence and the
clickable chat text as seen in a real client, and the older proxy versions (Velocity 3.x, BungeeCord 1.21 builds): the jars
were compiled against velocity-api 3.4.0 and bungeecord-api 1.21 but only the newer proxies above were run. Federation
needs the backend to be up when the proxy starts or within the first 30 seconds of the catalog's retries; otherwise run
`/mcdashboard reload` once the backend is reachable.
