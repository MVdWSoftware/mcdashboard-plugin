<p align="center"><img src="docs/assets/logo.svg" alt="MCDashboard" width="420"></p>

# MCDashboard plugin

[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

A Spigot/Paper plugin that publishes an allowlist of [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
values over HTTPS with a live feed, so the
MCDashboard mobile app can show live server stats.
Only placeholders you list in `placeholders.yml` are ever resolved, and nothing is exposed until you turn it on.

- Java 17, Spigot or Paper 1.18+, PlaceholderAPI on the server (Bedrock: see below). No other runtime dependencies: the HTTP server,
  WebSocket and JSON are the JDK's own.
- HTTPS built in (self-signed or your own certificate), pairing with one-time codes and QR maps, signed tokens, per-device revocation, rate limits.
- Optional history for charts, in memory or in SQLite, MySQL/MariaDB or PostgreSQL with a SOSA/SSN schema ([docs/history.md](docs/history.md)).
- Optional reverse tunnel and caching proxy support ([MCDashboard-proxy](https://git.mvdw-software.com/mcdashboard/mcdashboard-proxy)).

## Install

1. Install PlaceholderAPI and the expansions you need.
2. Download `MCDashboard.jar` and put it in `plugins/`, or build it yourself (below).
3. Start the server once, review `plugins/MCDashboard/config.yml` ([reference](docs/configuration.md)) and
   `placeholders.yml`, set `server.enabled: true`.
4. The plugin serves HTTPS itself (a self-signed certificate it generates, or your own); behind a TLS-terminating reverse proxy set `server.tls.mode: off`. Full guide: [docs/installation.md](docs/installation.md).

## Pictures, lists and tiles

Placeholders of type `IMAGE` can show a web-map picture (`map:dynmap`, `map:squaremap`, `map:pl3xmap`, `map:bluemap`, see [docs/maps.md](docs/maps.md)) or an `https` URL the app loads itself (`encode: false`), `LIST` placeholders send a table such as a leaderboard, and tiles take look options (`fit`, `position`, `padding`, `smoothing`, `markerIcon`, `speed`, `background`): see [docs/placeholders.md](docs/placeholders.md) and [docs/dashboards.md](docs/dashboards.md).

## Configuration skills (AI agents)

The plugin writes six skills to `plugins/MCDashboard/.agents/skills/` (setup, configuration, placeholders, dashboards,
networks, troubleshooting): Markdown instructions an AI agent (Claude Code, OpenCode, ...) loads when it helps with this
server, each ending with the matching pages on [mcdashboard.org](https://mcdashboard.org/en/docs/). They are refreshed on
update; see [docs/skills.md](docs/skills.md).

## Bedrock (PowerNukkitX)

`MCDashboard-Nukkit.jar` is the same plugin for [PowerNukkitX](https://powernukkitx.org) 3 (Java 21), using the
[PlaceholderAPI for PowerNukkitX](https://powernukkitx.org/plugins/PowerNukkitX-Bundle.PlaceholderAPI). Install both jars,
then follow the steps above. Differences: [docs/bedrock.md](docs/bedrock.md).

## Network (Velocity / BungeeCord)

`MCDashboard-Velocity.jar` and `MCDashboard-Bungee.jar` put one dashboard on the whole network. Proxies have no
PlaceholderAPI, so they publish built-in network placeholders (players online, per server online and up/down, uptime, version, the
paired player's ping) and can optionally read the values of backend servers that run MCDashboard. Details and limits:
[docs/proxy-platforms.md](docs/proxy-platforms.md).

## Build

```sh
mvn -B verify    # spigot/target/MCDashboard.jar, nukkit/target/MCDashboard-Nukkit.jar,
                 # velocity/target/MCDashboard-Velocity.jar, bungee/target/MCDashboard-Bungee.jar
```

`mvn -B verify` also runs JaCoCo: the report is in `<module>/target/site/jacoco/index.html` and the build fails below the limits set per module (`jacoco.minimum.line` / `jacoco.minimum.branch` in each `pom.xml`; the limits only go up). `core/` is at about 98% lines / 95% branches, `proxy-common/` at about 83% / 73%; the platform modules need a running server and are covered only for their pure helpers.

`core/` holds everything that does not depend on a server platform (the API, authentication, history, tunnel and
dashboards; JDK only). `spigot/` and `nukkit/` are the Spigot/Paper and PowerNukkitX plugins around it; `velocity/` and `bungee/` are the proxy plugins, sharing `proxy-common/`. Each shades `core/` into its jar.

## Documentation

[docs/](docs/README.md) covers installation, configuration, the security model, placeholders, dashboards, history,
events and the tunnel. The API is described in [openapi.yaml](openapi.yaml). To contribute, read [CONTRIBUTING.md](CONTRIBUTING.md);
report vulnerabilities as described in [SECURITY.md](SECURITY.md).

## Terms and privacy

The plugin's source code is licensed under the GNU AGPL v3 ([LICENSE](LICENSE)). Using the MCDashboard app and the MCDashboard
licence service is governed by the [terms of service](https://mcdashboard.org/en/terms-of-service/) and the
[privacy statement](https://mcdashboard.org/en/privacy-statement/).

Error reports are **opt-in** (`diagnostics.error-reports`, default `false`). When enabled, only an unexpected plugin
error (an uncaught exception or a failed start) is sent to MVdW's own error tracker, with the exception and its stack;
placeholder values, player names and server addresses are never included, and a placeholder that does not resolve is
not reported.

## Placeholder catalog

`placeholders.yml` lists what the app may read. Entries take an optional `group` (default `General`) and `description`,
and the types `AUTO` (number, boolean or text decided per value) and `IMAGE`. A top-level `catalog:` list adds every
placeholder of a PlaceholderAPI expansion as an AUTO entry (`<expansion>_<name>`, parameterised ones skipped, names with
`ip`, `password`, `token`, `secret` or `address` always denied, optional `params: [a, b]` to name placeholders explicitly (many
expansions declare none; discovery then falls back to PlaceholderAPI's cloud data, and a warning tells you to use `params` when both are empty), `deny` globs and `max` per entry). It is read on the first
server tick after startup; explicit entries win. IMAGE entries use `source: file:<path>` (inside `plugins/MCDashboard/images/`)
or `source: url:<template>` (http/https, fetched off the main thread, 5 s timeout, 256 KB, cached 5 minutes at least);
only PNG, JPEG, GIF and WebP are served.

The placeholder list is searchable and paginated; see [`openapi.yaml`](openapi.yaml).

## Dashboards

One YAML file per dashboard in `dashboards/`; the permission is derived from the file name (`admin.yml` needs
`mcdashboard.dashboard.admin`). The plugin reports what the caller may open and whether they can upload. In developer
mode (`developer.enabled`, `/mcdashboard developer on`) a player with `mcdashboard.developer` can upload a dashboard;
the plugin saves or replaces `dashboards/dev-<player>.yml`, visible only to developers. See
[docs/dashboards.md](docs/dashboards.md).

## Device events

`events.token` opens the event stream and `events.webhooks` POSTs signed
events to receivers, so a caching proxy learns of revocations immediately. Both are off by default; code in
`org.mcdashboard.events` (`EventBus`, `EventStream`, `WebhookDispatcher`), emitted from `DeviceStore.onRemoved`. See
[docs/events.md](docs/events.md).

## Languages

Dashboard YAML may translate `name`, `description` and item `title` with a language map (`en`, `nl`, `fr`, `de`, `es`);
plain strings stay the same in every language. The plugin resolves
those texts against the caller's `Accept-Language`, then `server.default-language`, then the first language the
dashboard provides, and set `Vary: Authorization, Accept-Language`. The server publishes `defaultLanguage` and the
`languages` in use. The server configuration stays in `config.yml`; `plugin.yml` only declares the command and
permissions.

## Update method and frequency

- `server.polling.enabled` and `server.websocket.enabled` choose how values reach the app: either, or both (the app
  polls only while the live feed is down). With polling off, value requests are refused and the server reports
  `polling: false`; if both are off, polling stays on.
- `security.require-authentication` (on by default) rejects unauthenticated requests, and live-feed connections that do not
  authenticate within 10 seconds. A live connection whose device is revoked is closed within a second.
- `security.allow-player-names` (off by default) is a deliberate exception: an app may type a player name instead of
  pairing. It is not authentication, so anyone who can reach the API could read that player's data and dashboards.
- `security.min-interval` is the global floor for every placeholder; `min-interval` in `placeholders.yml` sets it per
  placeholder (the larger of the two wins, for polling, the live feed and caching headers).
  `security.min-interval-permissions` (and `min-interval-permissions` per placeholder) lets players with a permission
  node refresh faster; the fastest node they hold wins.

## Clients and limits

Only the official app may call the API: it sends `User-Agent: MCDashboard/<x.y.z> (android|ios)` and everything else gets `403`
(the discovery endpoint stays open so a browser or an uptime check can still probe it, and the event stream has its own
token). `security.allowed-clients` allows further clients by regular expression, `security.min-client-version` answers `426` to
older apps and is published as `minClientVersion`. The live feed applies the same check to its handshake. Each
device records the client it paired with (`/mcdashboard devices`), and requests are limited per paired device
(`security.requests-per-minute`) or, when not paired, per address (`security.anonymous-requests-per-minute`). A User-Agent is
identification, not authentication: see [docs/security.md](docs/security.md) for what it does and does not give you.
