# MCDashboard plugin

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

A Spigot/Paper plugin that publishes an allowlist of [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
values as [SenML](https://www.rfc-editor.org/rfc/rfc8428) over REST and a WebSocket live feed, so the
MinecraftDashboard mobile app (and anything else that speaks the [API](openapi.yaml)) can show live server stats.
Only placeholders you list in `placeholders.yml` are ever resolved, and nothing is exposed until you turn it on.

- Java 17, Spigot or Paper 1.18+, PlaceholderAPI on the server. No other runtime dependencies: the HTTP server,
  WebSocket and JSON are the JDK's own.
- Pairing with one-time codes and QR maps, JWT (HS256) tokens, per-device revocation, rate limits.
- Optional history for charts, in memory or in SQLite, MySQL/MariaDB or PostgreSQL with a SOSA/SSN schema ([docs/history.md](docs/history.md)).
- Optional reverse tunnel and caching proxy support ([MCDashboard-proxy](https://github.com/MVdWSoftware/MCDashboard-proxy)).

## Install

1. Install PlaceholderAPI and the expansions you need.
2. Download `MCDashboard.jar` and put it in `plugins/`, or build it yourself (below).
3. Start the server once, review `plugins/MCDashboard/config.yml` and `placeholders.yml`, set `server.enabled: true`.
4. Put a reverse proxy with TLS in front. Full guide: [docs/installation.md](docs/installation.md).

## Build

```sh
mvn -B verify    # target/MCDashboard.jar
```

## Documentation

[docs/](docs/README.md) covers installation, the security model, placeholders, dashboards, history, events and the
tunnel. The API is described in [openapi.yaml](openapi.yaml). To contribute, read [CONTRIBUTING.md](CONTRIBUTING.md);
report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## License

[MIT](LICENSE)

## Placeholder catalog

`placeholders.yml` lists what the app may read. Entries take an optional `group` (default `General`) and `description`,
and the types `AUTO` (number, boolean or text decided per value) and `IMAGE`. A top-level `catalog:` list adds every
placeholder of a PlaceholderAPI expansion as an AUTO entry (`<expansion>_<name>`, parameterised ones skipped, names with
`ip`, `password`, `token`, `secret` or `address` always denied, optional `params: [a, b]` to name placeholders explicitly (many
expansions declare none; discovery then falls back to PlaceholderAPI's cloud data, and a warning tells you to use `params` when both are empty), `deny` globs and `max` per entry). It is read on the first
server tick after startup; explicit entries win. IMAGE entries use `source: file:<path>` (inside `plugins/MCDashboard/images/`)
or `source: url:<template>` (http/https, fetched off the main thread, 5 s timeout, 256 KB, cached 5 minutes at least);
only PNG, JPEG, GIF and WebP are served, as the SenML `vd` field (base64url without padding).

`GET /v1/placeholders` accepts `q`, `group`, `limit` (1-100, default 50), `offset` and `ids` (exact lookup) and answers
`{ placeholders, total, groups }`; see [`openapi.yaml`](openapi.yaml).

## Dashboards

One YAML file per dashboard in `dashboards/`; the permission is derived from the file name (`admin.yml` needs
`mcdashboard.dashboard.admin`). `GET /v1/dashboards` returns what the caller may open and `canUpload`. In developer
mode (`developer.enabled`, `/mcdashboard developer on`) a player with `mcdashboard.developer` can `POST /v1/dashboards`;
the plugin saves or replaces `dashboards/dev-<player>.yml`, visible only to developers. See
[docs/dashboards.md](docs/dashboards.md).

## Device events

`events.token` opens `GET /v1/events` (server-sent events with `Last-Event-ID` replay) and `events.webhooks` POSTs HMAC-signed
events to receivers, so a caching proxy learns of revocations immediately. Both are off by default; code in
`org.mcdashboard.events` (`EventBus`, `EventStream`, `WebhookDispatcher`), emitted from `DeviceStore.onRemoved`. See
[docs/events.md](docs/events.md).

## Languages

Dashboard YAML may translate `name`, `description` and item `title` with a language map (`en`, `nl`, `fr`, `de`, `es`);
plain strings stay the same in every language. `GET /v1/dashboards`, `/v1/dashboards/{id}` and `/v1/dashboard` resolve
those texts against the caller's `Accept-Language`, then `server.default-language`, then the first language the
dashboard provides, and set `Vary: Authorization, Accept-Language`. `/v1/info` publishes `defaultLanguage` and the
`languages` in use. The server configuration stays in `config.yml`; `plugin.yml` only declares the command and
permissions.

## Update method and frequency

- `server.polling.enabled` and `server.websocket.enabled` choose how values reach the app: either, or both (the app
  polls only while the live feed is down). With polling off `GET /v1/values` answers 403 and `/v1/info` reports
  `polling: false`; if both are off, polling stays on.
- `security.require-authentication` rejects unauthenticated requests, and live-feed connections that do not
  authenticate within a few seconds.
- `security.min-interval` is the global floor for every placeholder; `min-interval` in `placeholders.yml` sets it per
  placeholder (the larger of the two wins, for polling, the live feed and caching headers).

## Clients and limits

Only the official app may call the API: it sends `User-Agent: MCDashboard/<x.y.z> (android|ios)` and everything else gets `403`
(`web/ClientPolicy`; `GET /v1/info` stays open so a browser or an uptime check can still probe it, and `/v1/events` has its own
token). `security.allowed-clients` allows further clients by regular expression, `security.min-client-version` answers `426` to
older apps and is published as `minClientVersion` in `/v1/info`. The live feed applies the same check to its handshake. Each
device records the client it paired with (`/mcdashboard devices`), and requests are limited per paired device
(`security.requests-per-minute`) or, when not paired, per address (`security.anonymous-requests-per-minute`). A User-Agent is
identification, not authentication: see [docs/security.md](docs/security.md) for what it does and does not give you. Tests:
`ApiTest` and `LiveServerTest`.
