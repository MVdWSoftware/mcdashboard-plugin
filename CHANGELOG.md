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
- `security.client-verification`: require a client assertion from the MVdW attestation service (ES256, verified offline, bound to the
  host) so only genuine clients connect; the same for the app and other clients ([docs/client-verification.md](docs/client-verification.md)).
- `security.background`: owners can switch off background updates (notifications, home-screen widgets) and limit them per device;
  clients mark such requests with `X-MCDashboard-Purpose: background`, and `features` lists `background` while they are allowed.
- `/v1/info` reports `features`, and the protocol and client versioning rules are in [docs/versioning.md](docs/versioning.md).
  `ClientPolicy.BUILT_IN_MIN_CLIENT` lets a release require a newer app.

### Fixed
- The previous refresh token works for 30 seconds after a rotation, so a response lost on the way no longer unpairs the device.
- Concurrent requests for one placeholder share a single main-thread resolution, a stuck server thread opens a circuit breaker, and
  unresolvable values stop being served after a minute instead of looking live forever.
- The API has 16 worker threads and 30 second request/response timeouts; rate limiters no longer grow without bound.

### Changed
- Relicensed from the MIT licence to the GNU AGPL v3 licence ([LICENSE](LICENSE)).
- The project is a Maven multi-module build: `core/` (the API, authentication, history, tunnel and dashboards, JDK only,
  no server platform) and `spigot/` (the Bukkit plugin, which shades `core/`). The jar is now
  `spigot/target/MCDashboard.jar`; its content and behaviour are unchanged. The text of `/mcdashboard` is shared in `core/`.
- Building needs JDK 21 (CI uses 21); the Spigot plugin and `core/` still target Java 17.

### Added
- A PowerNukkitX plugin for Bedrock servers (`nukkit/`, `MCDashboard-Nukkit.jar`) on the PlaceholderAPI for PowerNukkitX; see
  [docs/bedrock.md](docs/bedrock.md).
- Dashboard items take an `icon` (`pack:name`, e.g. `fa6-solid:memory`) and `options` (progress styles such as hearts,
  colour rules, number format, layout); dashboards take a `background-image` (https URL, dim, blur, fit). Uploads accept
  them and the `stat`, `stacked` and `heatmap` widgets ([docs/dashboards.md](docs/dashboards.md)).
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
