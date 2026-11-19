---
name: MCDashboard troubleshooting
description: Diagnose MCDashboard problems in order - plugin not listening, app cannot connect, values empty or stuck, 403/426/429 answers, missing dashboards or charts, tunnel down, proxy 502/503, licensing and locked-out devices. Use when something with MCDashboard does not work and the cause is not yet known; check here before changing any setting.
license: AGPL-3.0-or-later
---

# Troubleshooting

Work from the inside out: the plugin on the server, then the address, then the app, then the cache in front. Change one
thing at a time; most "fixes" that disable a check (authentication, TLS) only move the problem.

## Where to look first

1. **The console at startup.** It logs the version, the API addresses, the TLS fingerprint, and every setting it had to
   ignore. A YAML error names the file and the key.
2. `/mcdashboard about` - version and the addresses the app should use.
3. `/papi parse me %the_placeholder%` - does PlaceholderAPI itself answer?
4. `/mcdashboard devices` - is the phone paired, to which player, and when was it last seen?
5. The app's server screen - live, polling or offline, and the age of the last value.

## Symptom table

| Symptom | Check, in this order |
| --- | --- |
| Nothing listens on 1553 | `server.enabled: true`; another process on the port; `server.host`; firewall/NAT. The API never falls back from TLS to plain HTTP, so a broken certificate leaves it down on purpose. |
| Certificate problems | With `tls.mode: custom` the key must be an unencrypted PKCS#8 PEM (`-----BEGIN PRIVATE KEY-----`); convert with `openssl pkcs8 -topk8 -nocrypt`. Behind a local reverse proxy use `tls.mode: off` and `host: 127.0.0.1`. |
| App cannot connect | Address equals `server.public-url` (scheme and port); `curl -v https://host:1553/v1/info` from outside; a self-signed certificate needs the pin from the pairing link or the log; `security.min-client-version` answers `426` to old apps. |
| Values are empty or show `%something%` | The expansion is not installed or outdated (`/papi parse`), the `id` is not the one in `placeholders.yml`, the entry is `PLAYER` and nobody is paired for that player, or the value only exists while the player is online. |
| A value never updates | Effective interval is the larger of `security.min-interval` (or the player's `min-interval-permissions`) and the placeholder's `min-interval`; a cache in front serves its copy until `max-age` expires. That is by design. |
| Dashboard or tab missing | File is `dashboards/<name>.yml`; its permission `mcdashboard.dashboard.<name>` is granted; restart after adding or renaming; a dashboard whose placeholders are all unreadable is not offered. |
| Charts are empty | The placeholder needs `history: <minutes>` or `history.include`; only PUBLIC values are recorded; history does not backfill; with a database, the missing driver is logged (put it in `plugins/MCDashboard/drivers/`). |
| `403` | A client without the app User-Agent, or a placeholder/dashboard permission missing. `security.allowed-clients` takes regular expressions for other clients. |
| `426` | The app is older than `security.min-client-version` (or the plugin's built-in minimum). Update the app. |
| `429` with `Retry-After` | A rate limit: per device (`requests-per-minute`), per address (`anonymous-requests-per-minute`), pairing (`auth-attempts-per-minute`), background. Behind a proxy, list the proxy in `security.trusted-proxies` or every player shares one address. |
| Live feed falls back to polling | `/v1/live` must be upgraded by the reverse proxy (`Upgrade` and `Connection` headers). Polling keeps working; fix the proxy for smooth updates. |
| Both transports off | `polling.enabled` and `websocket.enabled` cannot both be off; polling stays on. |

## Proxy and tunnel

| Answer | Meaning |
| --- | --- |
| `502` | The proxy cannot reach the plugin or the tunnel is down: check `UPSTREAM_URL`, `server.host`, firewall, `/tunnelz`, the plugin log and `/mcdashboard tunnel`. |
| `503` | Too many requests in flight to the plugin or no free live slots; raise the proxy's limits only if the server can take it. |
| `429` | A proxy rate limit; `Retry-After` says how long. Investigate `RATE_AUTH` hits before raising read limits. |
| `401` after a revoke | Correct: the revocation feed works and the device is refused. |

Tunnel down: key length 32+, `wss://` not `ws://`, the game server's address in the proxy's `TUNNEL_ALLOWED_IPS` (a
refused peer gets `404`), and the plugin retries forever with backoff. Restarting the proxy is safe.

## Licensing and locked out

- A licensed server that still shows free limits: `license.id` must be the `mcl_...` id registered for the address the
  app connected to. When a trial ends, nothing is deleted: extra tiles and older history are locked.
- Lost or stolen phone: `/mcdashboard devices` then `/mcdashboard revoke <id>`; pair the new phone. Pairing codes work
  once, for five minutes.
- Paired to the wrong player: revoke and pair again while logged in as the right account. The app never sends a player
  name, so nothing else fixes it.
- `secret.key` deleted: every token is invalid and every device pairs again. That is the recovery from a leak.

## Bedrock (PowerNukkitX)

Names differ (`%server_tps%`, `%player%`, not the Spigot names), there is no `catalog:` discovery and no QR map, and
PLAYER values are empty while the player is offline. The Spigot defaults are not valid there.

## When reporting a problem

Include the plugin version (`/mcdashboard about`), the app version, the server software and version, what was expected
and what happened, the relevant console lines, the HTTP status and `Retry-After` from a proxy. **Never** include
`secret.key`, database passwords, a `TUNNEL_TOKEN`, a pairing code or device tokens.

## Read more

- Troubleshooting by symptom: https://mcdashboard.org/en/docs/troubleshooting/
- Security and client checks: https://mcdashboard.org/en/docs/server/security/
- Every setting and its default: https://mcdashboard.org/en/docs/server/configuration/
- Proxy errors and settings: https://mcdashboard.org/en/docs/proxy/
