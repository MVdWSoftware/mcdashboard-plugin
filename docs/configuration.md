# Configuration reference (`config.yml`)

Every setting of `plugins/MCDashboard/config.yml`, with its default. Restart the server after a change; only
`/mcdashboard developer on|off` takes effect at runtime.

## `server`

| Setting | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Off until you have reviewed `placeholders.yml`. Nothing is exposed while it is off. |
| `name` | the server's name | Name the app shows. |
| `default-language` | `en` | Language used when a dashboard has no translation for the app's language: `en`, `nl`, `fr`, `de` or `es` ([dashboards.md](dashboards.md#languages)). |
| `public-url` | empty | The address players' apps use, e.g. `https://mc.example.com`. When set, `/mcdashboard link` also prints a `mcdashboard://` link and `/mcdashboard qr` hands out a QR map. |
| `endpoints` | `[]` | Addresses the app should really use, best first, e.g. your MCDashboard-proxy. The app asks this server and switches to the first endpoint that answers ([tunnel.md](tunnel.md#endpoint-discovery)). |
| `discovery-only` | `false` | Answer only the discovery request on this port to anyone but a trusted proxy; the API is then reached through the tunnel or a reverse proxy. |
| `host` | empty (all interfaces) | Address to bind to; `127.0.0.1` behind a reverse proxy on the same machine. |
| `port` | `1553` | Port the app connects to. |
| `tls.mode` | `self-signed` | HTTPS for the API and the live feed. `self-signed`: the plugin creates a certificate once in `plugins/MCDashboard/tls/` (`cert.pem`, `key.pem`, mode 600), keeps it across restarts and prints its SHA-256 fingerprint at startup. `custom`: your own certificate. `off`: plain HTTP, only for a reverse proxy on the same machine that terminates TLS ([reverse-proxy.md](reverse-proxy.md)). If the certificate cannot be loaded the web server stays down; it never falls back to plain HTTP. |
| `tls.certificate`, `tls.key` | empty | `custom` mode: PEM certificate chain and an unencrypted PKCS#8 private key (`-----BEGIN PRIVATE KEY-----`; convert with `openssl pkcs8 -topk8 -nocrypt`). Relative paths are inside `plugins/MCDashboard/`. |
| `tls.keystore`, `tls.keystore-password` | empty | `custom` mode alternative: a PKCS#12 (`.p12`, `.pfx`) or JKS keystore with a private key. |
| `tls.names` | `[]` | Host names and IP addresses a generated certificate must name, besides `localhost`, the loopback addresses, the host of `public-url` and `host`. A new name makes the plugin generate a new certificate, so devices that pinned the old one pair again. |
| `polling.enabled` | `true` | Plain requests for values. Turn it off to serve values over the live feed only; at least one of polling and the live feed stays on. Charts and dashboards work either way. |
| `websocket.enabled` | `true` | The live feed, which pushes values when they change. |
| `websocket.port` | `1554` | Live feed port. |
| `websocket.max-connections` | `200` | Open live connections at most. |

## `security`

| Setting | Default | Meaning |
| --- | --- | --- |
| `require-authentication` | `true` | Only paired players can read anything, PUBLIC placeholders included. Live connections must authenticate within 10 seconds. Set `false` to let anyone who can reach the server read its PUBLIC placeholders without pairing; the plugin logs a warning at startup when you do. |
| `allow-player-names` | `false` | **Security risk.** Lets an unpaired app choose a player by typing a name in the app (resolved on the server) instead of redeeming a pairing code. That player's `PLAYER` placeholders, personal data and permission-gated dashboards become readable to anyone who can reach the API and knows or guesses the name, including staff dashboards. There is no authentication and no proof that the name belongs to the caller. The app only shows the name field when `/v1/info` says `allowPlayerNames` (or lists the `player-names` feature). When both this and `require-authentication` are on, a request needs either a paired token or a name; a name-only request is treated as that player. The plugin logs a warning at startup when this is on. Use only on a private or trusted network. |
| `max-devices` | `5` | Paired devices per player. |
| `otp-timeout` | `300` | Seconds a `/mcdashboard link` code stays valid. It works once. |
| `access-token-timeout` | `900` | Seconds an access token is valid. |
| `refresh-token-timeout` | `31536000` | Seconds a refresh token is valid (one year). |
| `auth-attempts-per-minute` | `10` | Pairing and refresh attempts per minute from one address. Behind a reverse proxy list the proxy in `trusted-proxies`, otherwise every player shares the proxy's address and this limit. |
| `min-interval` | `1` | Seconds; no placeholder is resolved more often than this for callers without a permission override. `min-interval` in `placeholders.yml` can make one slower. |
| `min-interval-permissions` | `{}` | Optional permission node to seconds map. A paired player holding a node uses that interval instead of `min-interval`; the fastest node they hold wins. Example: `mcdashboard.admin: 15` lets admins refresh every 15 seconds while everyone else keeps the default. Placeholder-level `min-interval` still makes one placeholder slower. |
| `allowed-clients` | `[]` | Regular expressions (matched against the whole `User-Agent`) for clients besides the official app, e.g. a monitoring tool. The User-Agent identifies a client; it is not a password ([security.md](security.md)). |
| `min-client-version` | empty | Official apps older than this (`x.y.z`) get `426` so players update. A plugin release can build in its own minimum (`ClientPolicy.BUILT_IN_MIN_CLIENT`); this setting can only raise it ([versioning.md](versioning.md)). |
| `background.enabled` | `true` | Allow requests that declare themselves background updates (notifications, home-screen widgets). `false`: they get `403` and the app hides those features for this server ([client-verification.md](client-verification.md)). |
| `background.requests-per-minute` | `30` | Background requests per minute from one paired device, on top of `requests-per-minute`. |
| `client-verification.mode` | `off` | `required`: every request except `GET /v1/info` needs a client assertion from the MVdW attestation service ([client-verification.md](client-verification.md)). |
| `client-verification.min-level` | `registered` | `device`: only platform-attested apps; `registered` also accepts registered server-side clients. |
| `client-verification.kinds` | `[]` | Accepted client kinds (`android`, `ios`, ...); empty accepts all. |
| `client-verification.hosts` | `[]` | Host names an assertion may be for; empty means the host the client connected to. Set it behind a proxy that rewrites `Host`. |
| `client-verification.keys` | `[]` | Extra trusted public keys as JWKs (`kty: EC`, `crv: P-256`, `x`, `y`), besides the ones in this release. |

An assertion with a `lid` claim (issued to a server-side client of one licensed server) is accepted only when `lid` equals this server's `license.id`; without `license.id` the server accepts none. Assertions without `lid` stay valid.
| `requests-per-minute` | `120` | Requests per minute from one paired device. |
| `anonymous-requests-per-minute` | `60` | Requests per minute from one address that is not paired. |
| `trusted-proxies` | `["127.0.0.1", "0:0:0:0:0:0:0:1"]` | Peers whose `X-Forwarded-For` is believed (rate limits, discovery-only). Keep it to your reverse proxy. |

## `history`

Samples for the chart widgets: storage (memory, SQLite, MySQL/MariaDB, PostgreSQL), sampling, retention and which
placeholders are recorded. See [history.md](history.md).

## `tunnel`

| Setting | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Dial out to MCDashboard-proxy over one WebSocket ([tunnel.md](tunnel.md)). |
| `url` | empty | The proxy's tunnel address, e.g. `wss://proxy.example.com/tunnel`. `ws://` is refused unless the proxy is on a private network (an IP literal) or `allow-insecure` is set. |
| `key` | empty | The proxy's `TUNNEL_TOKEN`, at least 32 random characters. Rotate it on both sides together. |
| `allow-insecure` | `false` | Allow `ws://` to a public host, which sends the key in the clear. |

## `events`

| Setting | Default | Meaning |
| --- | --- | --- |
| `token` | empty | 16+ random characters; opens the device event stream for a proxy. Empty: no stream. |
| `webhooks` | `[]` | `url` and `secret` (16+ characters) per receiver; every event is POSTed, signed. |

Both are described in [events.md](events.md). With the tunnel, the proxy receives events through it and needs neither.

## `developer`

`enabled` (default `false`) lets players with `mcdashboard.developer` upload a dashboard from the app.
`/mcdashboard developer on|off` changes it at runtime and stores the choice in `developer.yml`, which then wins over
this setting ([dashboards.md](dashboards.md#developer-mode-build-a-dashboard-in-the-app)).

## `limits`

Caps that keep the server fast. They apply to every app, also one with the App unlock or a licence, and `GET /v1/info` publishes
them as `limits` so the app shows at most that many and tells the player the server limits it (buying cannot lift it).

| Key | Default | Meaning |
| --- | --- | --- |
| `max-dashboards` | `0` | Dashboards (presets) per server the app shows. `0` = no cap. Developer uploads that would add a dashboard above it get `422` (`dashboards-limit`). |
| `max-tiles` | `0` | Tiles per dashboard the app shows. `0` = no cap. Developer uploads with more tiles get `422` (`tiles-limit`). |

A negative or invalid value counts as `0` and logs a warning.

## `messages`

| Setting | Default | Meaning |
| --- | --- | --- |
| `messages.prefix` | `&8[&bMCDashboard&8] &r` | Starts every chat message of `/mcdashboard` and the "a device was linked" notice. `&` colour codes work; empty sends plain messages. Normal text is grey, good news green and problems red. |

## `license`

| Setting | Default | Meaning |
| --- | --- | --- |
| `id` | empty | The licence id from the MVdW portal (https://mvdw-software.com), where the owner registers this server's address. The plugin publishes only its SHA-256 (`licenseHash`) in `/v1/info`; apps ask the portal whether that hash is valid for the address they connected to. If so, players get every widget on this server and it does not count toward their free server limit. The hash only works for the registered address; the id itself stays in this config and in the outbound heartbeat. |
| `api-url` | `https://portal.mcdashboard.org/api` | The portal API for the heartbeat. Empty turns it off. |
| `host` | empty | The address players connect to. Empty: derived from `server.public-url`, then the first `server.endpoints` entry. |
| `heartbeat-minutes` | `60` | How often the plugin reports the licence and this address; `0` turns it off. |

Once in a while the plugin sends `POST /v1/licenses/{id}/heartbeat` with this address and its version. The portal answers
whether the licence exists, is banned, has a running subscription and covers this address, and records the report for the
platform administrator's licence overview. **This is UI/UX and a help against putting a licence id on the wrong server,
not copy protection:** the plugin is open source, so a modified copy can skip it. The signed, host-bound token that the
app verifies is the enforcement; the heartbeat cannot grant anything.

The heartbeat uses `license.id`, not the public hash, so scraping `/v1/info` does not give anyone the value needed to run it
or to configure another server with this licence. Future owner actions (for example adding a LAN IP) must use a server
secret, never the public hash.

Devices tell the plugin which licence they use (the server's licence, the App unlock or none), which `/mcdashboard devices` and the
`%mcdashboard_devices_server|app|free%` placeholders show ([security.md](security.md#device-entitlement)). That is informational: whether
the id verifies is decided by the app and the portal, never by the plugin, and `license.id` alone sets `licensed`.

Planned, not implemented: an in-game reminder when the server's trial is about to end. The heartbeat tells the plugin only
whether the licence is active, licensed and registered, not when the trial ends. The licence token the portal issues carries
only the server, the host and the plan (and expires after three days whatever the trial), so the app cannot learn the trial
end from it either. A reminder needs a deliberate contract change first (for example a `trialEndsAt` claim in the token, a
field in the heartbeat answer, or a field in the check response that the app reports with the entitlement header), designed
together with the portal; until then the portal's own trial emails are the reminder.

## `diagnostics`

| Key | Default | Meaning |
| --- | --- | --- |
| `error-reports` | `false` | Opt-in. When on, an **unexpected** plugin error (an uncaught exception or a failed start) is sent to MVdW's own GlitchTip (`monitor.mvdw-software.com`): the exception type, message and stack trace, the plugin version and `startup`/`uncaught`. Placeholder values, player names, server addresses and e-mail addresses are never sent, and a placeholder that does not resolve is not reported (those stay in the server log). Reports are deduplicated (one per error a minute, at most 20 an hour), kept 30 days and stored on MVdW's server in the EU. Off by default; see the [privacy statement](https://mcdashboard.org/en/privacy-statement/). |

## `stats`

| Key | Default | Meaning |
| --- | --- | --- |
| `stats.offer-to-app` | `false` | Offers the `MCDashboard` group of `placeholders.yml` (the entries whose value is a `%mcdashboard_...%` placeholder) to the app. They are PUBLIC, so any paired client (anyone, with `security.require-authentication: false`) can read these counts and their charts. Off, the entries are ignored. The `%mcdashboard_...%` placeholders themselves are always registered in PlaceholderAPI. See [placeholders-api.md](placeholders-api.md). |

## `network` (Velocity and BungeeCord only)

Written to a new `config.yml` by the proxy plugins; see [proxy-platforms.md](proxy-platforms.md).

| Key | Default | Meaning |
| --- | --- | --- |
| `network.ping-interval` | `15` | Seconds between the up/down checks of the backend servers (5 to 3600). |
| `network.federation-interval` | `15` | Seconds between reads of the backend servers (5 to 3600). |
| `network.backends` | `[]` | Backend servers running MCDashboard: `name`, `url`, optional `key` and `pin`. Empty = federation off. |
