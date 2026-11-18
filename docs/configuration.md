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
| `client-verification.min-level` | `registered` | `device`: only platform-attested apps; `registered` also accepts registered clients such as a WordPress site. |
| `client-verification.kinds` | `[]` | Accepted client kinds (`android`, `ios`, `wordpress`, ...); empty accepts all. |
| `client-verification.hosts` | `[]` | Host names an assertion may be for; empty means the host the client connected to. Set it behind a proxy that rewrites `Host`. |
| `client-verification.keys` | `[]` | Extra trusted public keys as JWKs (`kty: EC`, `crv: P-256`, `x`, `y`), besides the ones in this release. |
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

## `license`

`id` is the licence id from the MVdW portal (https://mvdw-software.com), where the owner registers this server's
address. Apps ask the portal whether the id is valid for the address they connected to; if so, players get every
widget on this server and it does not count toward their free server limit. The id is public
and only works for the registered address.

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
