# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Security
- **Breaking default:** `security.require-authentication` is now `true`; anonymous reading of PUBLIC placeholders is opt-in.
- **Breaking default:** the API and the live feed serve HTTPS (`server.tls.mode: self-signed`). Behind a reverse proxy that terminates TLS, set
  `server.tls.mode: off`. Your own certificate: `custom` with PEM files or a keystore. The pairing link carries the certificate's
  fingerprint (`pin=`) and `/v1/info` publishes it (`tlsFingerprint`, `websocketTls`).

### Added
- Dashboards are checked for a layout the app can draw: two tiles on the same cells or a tile past the fourth column are
  reported in the console when the dashboard loads (once, not on every request), and a developer upload with such a layout
  is refused with `400`. See [docs/dashboards.md](docs/dashboards.md).
- Chat that looks like a plugin: every `/mcdashboard` message starts with a configurable prefix (`messages.prefix`, default `[MCDashboard]`), grey text, green for good news, red for problems; follow-up lines are indented. Players without the permission get a clear "You don't have permission to do that (needs mcdashboard.admin)", and a wrong sub-command says what to try instead of a bare usage line.
- `/mcd` as a short alias of `/mcdashboard` on Spigot, PowerNukkitX, Velocity and BungeeCord.
- A richer `/mcdashboard about`: what MCDashboard is, store links for the Android and iOS apps (fixed in the plugin) and how to link a device. Clickable buttons on Spigot, plain links elsewhere. The pairing code message points to it.
- `sky` tile: the biome decides what falls in a storm. A third placeholder (e.g. `%player_biome%` of PlaceholderAPI's Player expansion) or the new `biome` tile option (`snowy_plains`, `desert`, ...) gives rain, snow or nothing; the weather itself stays per world.
- JaCoCo in `mvn -B verify`: per-module coverage report and a check with limits (core about 98% lines / 95% branches); CI publishes the reports and the coverage figure. More tests for the tunnel channels, loopback requests, catalog loader, JSON and JDBC connector.
- `map:` IMAGE sources (`map:dynmap`, `map:squaremap`, `map:pl3xmap`, `map:bluemap`): the tiles of a web map around a block
  (fixed, or `%player_x%`/`%player_z%` for the paired player) stitched into one 64-512 px picture, fetched from the map's own
  web server like `url:` images (no dependency on the map plugin); missing tiles stay transparent, PNG with a JPEG fallback
  over 256 KB. See [docs/maps.md](docs/maps.md).
- `encode: false` for `url:` IMAGE sources: the server fetches nothing and sends the expanded https URL in the SenML `vs`
  value for the app to load directly (an `http://` URL is refused). See [docs/placeholders.md](docs/placeholders.md#images).
- `LIST` placeholders (`rows`, `columns` with `{n}`, `empty`): a table such as a leaderboard or a player list, sent as JSON in the
  SenML `vs` value; `GET /v1/placeholders` shows `columns` and `rows`. No history. See [docs/placeholders.md](docs/placeholders.md#lists-and-leaderboards).
- New tile options `fit`, `position`, `padding`, `smoothing`, `markerIcon`, `hideValue`, `speed` and a per-tile `background` (colour, gradient,
  https picture), validated like the existing ones; documented with the `sky`, `ticker`, `countdown`, `heads` and `leaderboard`
  widgets in [docs/dashboards.md](docs/dashboards.md#icons-and-tile-options). `hideValue` drops the number of a progress bar,
  so hearts show the health without “20”.
- `limits.max-dashboards` and `limits.max-tiles` (default `0` = no cap): the owner can cap dashboards per server and tiles per
  dashboard to protect performance, whatever the app unlock or licence. Published in `GET /v1/info` as `limits`; developer
  uploads above them are rejected with `422` (`tiles-limit`, `dashboards-limit`). See [docs/configuration.md](docs/configuration.md#limits).
- Network variant: plugins for Velocity (`velocity/`, `MCDashboard-Velocity.jar`) and BungeeCord/Waterfall (`bungee/`,
  `MCDashboard-Bungee.jar`) around a shared `proxy-common/` module. Built-in network placeholders replace PlaceholderAPI
  (`proxy_online`, `proxy_max`, `proxy_server_<name>_online`/`_up`, `proxy_uptime`, `proxy_version`, and for the paired player
  `proxy_player_ping`), a default Network dashboard, permissions from the proxy's own check (LuckPerms backs it), and
  `/mcdashboard link|devices|revoke|reload|developer|tunnel|about`. Optional, off by default federation (`network.backends`)
  exposes the PUBLIC placeholders of backend servers as `backend_<name>_<id>`. See [docs/proxy-platforms.md](docs/proxy-platforms.md).
- `Platform.defaultDashboards()` and a platform's `config-append.yml` let a platform choose its preset dashboards and add its own
  settings to a new `config.yml`.
- Configuration skills for AI agents: six Markdown skills (setup, configuration, placeholders, dashboards, networks,
  troubleshooting) teach an agent such as Claude Code or OpenCode how to configure this server, each pointing at the matching
  page on mcdashboard.org. They live in `.agents/skills/` in the repository, ship in the jar as `skills/`, and are written to
  `plugins/MCDashboard/.agents/skills/` on every start (a changed file is replaced, an owner's own skill is kept). See
  [docs/skills.md](docs/skills.md).
- `security.client-verification`: require a client assertion from the MVdW attestation service (ES256, verified offline, bound to the
  host) so only genuine clients connect; the same for the app and other clients ([docs/client-verification.md](docs/client-verification.md)).
- `security.background`: owners can switch off background updates (notifications, home-screen widgets) and limit them per device;
  clients mark such requests with `X-MCDashboard-Purpose: background`, and `features` lists `background` while they are allowed.
- `/v1/info` reports `features`, and the protocol and client versioning rules are in [docs/versioning.md](docs/versioning.md).
  `ClientPolicy.BUILT_IN_MIN_CLIENT` lets a release require a newer app.
- The reverse tunnel's handshake is versioned: `hello` carries the plugin's tunnel protocol and `ready` the proxy's, plus
  the API features the proxy can carry; a proxy that forwards over plain HTTP sends the same list in
  `X-MCDashboard-Proxy-Features`. `/v1/info` publishes only the intersection, so an older proxy hides a feature instead of
  advertising a route, header or query parameter it would drop ([docs/versioning.md](docs/versioning.md),
  [docs/tunnel.md](docs/tunnel.md)). The loopback API now forwards `X-MCDashboard-Attestation`, `-Player`, `-Purpose` and
  `-Entitlement` from the proxy, so client verification, player names and background limits work through the tunnel.
- Spigot: the plugin registers its own PlaceholderAPI expansion `%mcdashboard_...%` (paired and online devices, licence state, API
  requests, rate limited requests, live clients, history samples and storage, placeholders, dashboards, uptime, version), fed by the
  new thread safe `PluginStats` in core (memory reads only, never blocks the main thread). The shipped `placeholders.yml` lists them in
  the `MCDashboard` group, offered to the app only with `stats.offer-to-app: true` (default `false`). See
  [docs/placeholders-api.md](docs/placeholders-api.md).

- `security.client-verification`: an assertion with a `lid` claim (issued to a server-side client of one licensed server) is accepted only
  when `lid` equals this server's `license.id`; without `license.id` none is accepted. Assertions without `lid` are unchanged.
- Devices may report which licence they use (`X-MCDashboard-Entitlement: server | app | free`, self-reported, informational only). It is
  stored on the device record, shown by `/mcdashboard devices` with a summary line, and counted by `%mcdashboard_devices_server%`,
  `%mcdashboard_devices_app%` and `%mcdashboard_devices_free%` (also offered in the `MCDashboard` group). See
  [docs/security.md](docs/security.md#device-entitlement).
- PowerNukkitX: the `%mcdashboard_...%` placeholders are registered with the PowerNukkitX PlaceholderAPI too, and the `MCDashboard`
  group is in its default `placeholders.yml`.

### Fixed
- An expired or revoked access token is answered with `401` (`WWW-Authenticate: Bearer error="invalid_token"`) instead of
  the anonymous answer: the app kept showing the public view without ever refreshing, about 15 minutes after pairing.
- The tunnel client applied live-feed frames (`open`, data, `x`) on parallel threads, so the first data frame of a stream could arrive before its `open` finished and be lost; channel frames are now applied in order.
- BungeeCord: `bungee.yml` was invalid YAML (unquoted colon in the description), so the plugin was skipped; `%proxy_max%` is 0 instead of -1 when the proxy has no player limit; the federation warning no longer says `null`. Smoke-tested on real Velocity and BungeeCord (`scripts/proxy-smoke-test.sh`).
- Refused pairing and refresh attempts (`429`) now count in `%mcdashboard_rate_limited%`.
- The previous refresh token works for 30 seconds after a rotation, so a response lost on the way no longer unpairs the device.
- Concurrent requests for one placeholder share a single main-thread resolution, a stuck server thread opens a circuit breaker, and
  unresolvable values stop being served after a minute instead of looking live forever.
- The API has 16 worker threads and 30 second request/response timeouts; rate limiters no longer grow without bound.

### Changed
- The product is called MCDashboard everywhere users see it (docs, command output, plugin descriptions); technical ids are unchanged.
- Client verification docs speak of server-side clients in general instead of naming a specific one.
- The App unlock now also lifts the app's dashboard and tile limits (history stays 24 h unless the server is licensed).
- Relicensed from the MIT licence to the GNU AGPL v3 licence ([LICENSE](LICENSE)).
- The project is a Maven multi-module build: `core/` (the API, authentication, history, tunnel and dashboards, JDK only,
  no server platform) and `spigot/` (the Bukkit plugin, which shades `core/`). The jar is now
  `spigot/target/MCDashboard.jar`; its content and behaviour are unchanged. The text of `/mcdashboard` is shared in `core/`.
- Building needs JDK 21 (CI uses 21); the Spigot plugin and `core/` still target Java 17.
- Javadoc is part of the build: `mvn -B verify` runs doclint's `all` checks and fails on any warning (unknown tags such as
  `@returns`, a `@param` that does not match the signature, a missing `@return`, an undocumented public member).
  The whole public API is documented as a result.

### Added
- A PowerNukkitX plugin for Bedrock servers (`nukkit/`, `MCDashboard-Nukkit.jar`) on the PlaceholderAPI for PowerNukkitX; see
  [docs/bedrock.md](docs/bedrock.md).
- Dashboard items take an `icon` (`pack:name`, e.g. `fa6-solid:memory`) and `options` (progress styles such as hearts,
  colour rules, number format, layout); dashboards take a `background-image` (https URL, dim, blur, fit). Uploads accept
  them and the `stat`, `stacked` and `heatmap` widgets ([docs/dashboards.md](docs/dashboards.md)).
- Tile units can be styled: `unitSize` (`s` small by default, `m`, `l`) and `unitColor` (a theme colour) next to `unit`,
  so a value reads `567 MB` with the unit smaller or in another colour.
- The plugin is open source under the GNU AGPL v3 licence, with its API contract ([openapi.yaml](openapi.yaml)) and
  server-owner documentation in this repository.
- Persistent history: samples can be stored in SQLite, MySQL/MariaDB or PostgreSQL using a schema based on the
  W3C SOSA/SSN ontology (every table, column and foreign key documents its ontology term), with configurable
  sampling (`history.sampling`, `history-interval`), retention (`history.retention-days`) and include/exclude lists.
  Drivers are not shipped: the server's own SQLite and MySQL drivers are used, others load from `drivers/`.
  See [docs/history.md](docs/history.md).
- `GET /v1/history/{id}` takes `points` (2 to 2000) to reduce long ranges on the server.
- `placeholders.yml`: `history` can be up to a year (was 24 hours); `history-interval` sets the sampling per placeholder.

### Changed
- History is sampled every 30 s by default (was every 10 s); set `history.sampling.interval: 10` for the old rate.
- The default `security.otp-timeout` is 300 seconds everywhere (the code fell back to 600 when the key was missing).
- `config.yml` keeps one short comment per setting; the full reference is [docs/configuration.md](docs/configuration.md).

### Security
- `tunnel.url` with `ws://` is only accepted for `localhost` and private IP addresses: a host name such as
  `10.example.com` no longer counts as private (the key would have gone out in clear text). The same check decides
  the plain-HTTP webhook warning.
- The live feed closes the connection of a revoked device within a second instead of serving it until it disconnects.
- The live feed's handshake is read with a size (8 KiB) and time (10 s) limit, and with
  `security.require-authentication` a connection that does not authenticate is closed after 10 seconds.
- The tunnel checks the decoded request path, so `/v1/events` cannot be reached with percent-encoding; oversized
  tunnel messages drop the connection and a slow proxy slows the live feed instead of filling memory.
- Device names are stripped of colour codes and control characters before they are shown in chat.

### Fixed
- `GET /v1/history/{id}` answers 200 instead of 500 for an out-of-range `since`.
- `plugin.yml` lists the `qr` and `tunnel` sub-commands; the bundled `server.yml` no longer carries an unused key.
- `docs/examples/apache.vhost` is a working TLS, WebSocket and cache example.
