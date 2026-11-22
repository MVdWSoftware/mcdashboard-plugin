---
name: MCDashboard networks
description: Run MCDashboard on Velocity or BungeeCord/Waterfall, connect it to MCDashboard-proxy, add a reverse tunnel, publish per-backend values via federation, or send device revocations to a cache. Use for network-wide dashboards, proxy setup, tunnel/endpoint configuration, federation of backend servers, or when the proxy returns 502/503.
license: AGPL-3.0-or-later
---

# Networks, proxies and the tunnel

Three separate things are often confused; establish which one is meant before changing anything:

1. **A proxy plugin** (`MCDashboard-Velocity.jar`, `MCDashboard-Bungee.jar`) publishes the *network* itself, because a
   proxy has no PlaceholderAPI.
2. **MCDashboard-proxy** is a separate caching service in front of a plugin (Spigot or proxy), with Redis, coalescing
   and rate limits. The plugin can reach it with `server.endpoints` and/or the outbound tunnel.
3. **Federation** lets a proxy plugin also show the PUBLIC values of backend servers that each run MCDashboard.

## Proxy plugin (Velocity 3.x / BungeeCord 1.21, Java 17)

1. Put the jar in `plugins/`; no other plugin is needed. Data folder: `plugins/mcdashboard/` on Velocity,
   `plugins/MCDashboard/` on BungeeCord.
2. Set `server.enabled: true` and `server.public-url`, choose TLS/ports as on Spigot, restart.
3. Grant `mcdashboard.dashboard.network` and `mcdashboard.admin` to staff. Everyone can link a device and sees the
   Player dashboard. Proxies have no permission defaults: `mcdashboard.player` and `mcdashboard.dashboard.player` count
   as granted unless Velocity denies them explicitly (BungeeCord cannot tell denial from silence, so always granted).
4. `/mcdashboard link` in game; there is no QR map and `reload` (admin) exists only here.

Built-in placeholders replace PlaceholderAPI, answered from memory or a background cache:

| Placeholder | Access | Value |
| --- | --- | --- |
| `%proxy_online%`, `%proxy_max%` | PUBLIC | players on the proxy / its limit |
| `%proxy_servers%` | PUBLIC | backend servers |
| `%proxy_server_<name>_online%`, `..._up%` | PUBLIC | players on that backend / whether its port answers |
| `%proxy_uptime%`, `%proxy_version%`, `%proxy_platform%` | PUBLIC | process facts |
| `%proxy_player_name%`, `%proxy_player_ping%`, `%proxy_player_server%` | PLAYER | the paired player; empty while offline |

The default `placeholders.yml` maps these to `online`, `max-players`, `servers`, `uptime`, `version`, `platform`, and
the `proxy` catalog adds the per-backend ones. Add a backend and restart (or reload). Limits: no third-party
expansions, `_up` is only a TCP connect, no ticks on a proxy, PLAYER values are empty offline.

## Federation: backend values on the network dashboard

```yaml
network:
  federation-interval: 15      # seconds, 5-3600
  backends:
    - name: lobby
      url: "https://lobby.example.com:1553"
      key: ""                  # optional bearer token
      pin: ""                  # SHA-256 fingerprint of a self-signed certificate
```

Then add `catalog: [{ expansion: backend, group: Backends, access: PUBLIC }]` to the proxy's `placeholders.yml` and
restart/reload. Ids appear as `backend_<name>_<id>`. On each backend allow `MCDashboard-Proxy/.*` in
`security.allowed-clients`, and either set `require-authentication: false` there (PUBLIC only) or give a `key`.
Safety: only the configured URLs, no redirects, 3 s connect / 5 s request timeouts, 1 MiB and 500 placeholders per
backend, PUBLIC only, so player values cannot leak. A value older than three intervals is dropped. A rotating `key`
is not refreshed yet: prefer no authentication or replace the token yourself.

## MCDashboard-proxy and the tunnel

The proxy listens for the app and for the plugin. If the game server has no inbound port, let the plugin dial out:

```yaml
server:
  endpoints: ["https://proxy.example.com"]   # best first, the app switches to the first that answers
tunnel:
  enabled: true
  url: "wss://proxy.example.com/tunnel"
  key: "<the proxy's TUNNEL_TOKEN, 32+ random characters>"
```

- REST, live streams and device events all use that one WebSocket. `server.discovery-only: true` makes the plugin's own
  port answer only discovery to untrusted peers, so the API is reachable only through the proxy or the tunnel.
- The plugin refuses a key shorter than 32 characters and `ws://` to anything but localhost or a private IP
  (`allow-insecure` exists, do not use it on a public host). The key never goes in the URL and is never logged.
- Health: ping every 20 s, reconnect with backoff 1 s to 60 s forever; `/mcdashboard tunnel` (admin) shows state,
  attempt and ping. The proxy can restrict peers with `TUNNEL_ALLOWED_IPS` (a refused peer gets `404`).
- Versions: the handshake carries a tunnel protocol on both sides and the API features the proxy can carry (a proxy
  that forwards over plain HTTP sends the same list in `X-MCDashboard-Proxy-Features`). `/v1/info` through an older proxy
  hides `presets`, `history-points`, `upload`, `client-verification` or `player-names` instead of advertising what the
  proxy would drop; update the proxy to get them back. A newer plugin works through an older proxy.
- Events: with the tunnel the proxy needs neither `events.token` nor `events.webhooks`. Without a tunnel, a caching
  proxy needs one of them to learn about revocations; prefer the stream, both are off until configured, and the
  webhooks are retried and then dropped, so a receiver must be idempotent.

## Verify

1. `/mcdashboard tunnel` says connected (or the plugin log has one outage line, not a storm).
2. `curl` the proxy for `/v1/info` with the app User-Agent; `502` means the proxy cannot reach the plugin or the
   tunnel is down, `503` is concurrency/live slots, `429` is a rate limit with `Retry-After`.
3. Per-backend values: `%proxy_server_<name>_online%` moves when a player joins that backend; a federated id appears
   after a reload.
4. `security.trusted-proxies` on the plugin (and `TRUSTED_PROXIES` on the proxy) names the TLS terminator, otherwise
   every client shares one address and hits a shared rate limit.

## Read more

- Networks on Velocity and BungeeCord: https://mcdashboard.org/en/docs/server/networks/
- Caching proxy, tunnel and proxy settings: https://mcdashboard.org/en/docs/proxy/
- Reverse proxy, TLS and caching headers: https://mcdashboard.org/en/docs/server/installation/
- Security: https://mcdashboard.org/en/docs/server/security/
