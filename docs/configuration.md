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
| `polling.enabled` | `true` | Plain requests for values. Turn it off to serve values over the live feed only; at least one of polling and the live feed stays on. Charts and dashboards work either way. |
| `websocket.enabled` | `true` | The live feed, which pushes values when they change. |
| `websocket.port` | `1554` | Live feed port. |
| `websocket.max-connections` | `200` | Open live connections at most. |

## `security`

| Setting | Default | Meaning |
| --- | --- | --- |
| `require-authentication` | `false` | Only paired players can read anything, PUBLIC placeholders included. Live connections must authenticate within 10 seconds. |
| `max-devices` | `5` | Paired devices per player. |
| `otp-timeout` | `300` | Seconds a `/mcdashboard link` code stays valid. It works once. |
| `access-token-timeout` | `900` | Seconds an access token is valid. |
| `refresh-token-timeout` | `31536000` | Seconds a refresh token is valid (one year). |
| `auth-attempts-per-minute` | `10` | Pairing and refresh attempts per minute from one address. |
| `min-interval` | `1` | Seconds; no placeholder is resolved more often than this, whatever the app asks. `min-interval` in `placeholders.yml` can make one slower. |
| `allowed-clients` | `[]` | Regular expressions (matched against the whole `User-Agent`) for clients besides the official app, e.g. a monitoring tool. The User-Agent identifies a client; it is not a password ([security.md](security.md)). |
| `min-client-version` | empty | Official apps older than this (`x.y.z`) get `426` so players update. |
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
