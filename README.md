<p align="center"><img src="docs/assets/logo.svg" alt="MCDashboard" width="420"></p>

# MCDashboard plugin

[![Release](https://img.shields.io/github/v/release/MVdWSoftware/mcdashboard-plugin)](https://github.com/MVdWSoftware/mcdashboard-plugin/releases/latest)
[![License: AGPL-3.0](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)

A Spigot/Paper plugin that publishes an allowlist of [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/)
values over HTTPS with a live feed, so the
MCDashboard mobile app can show live server stats.
Only placeholders you list in `placeholders.yml` are ever resolved, and nothing is exposed until you turn it on.

- Java 17, Spigot or Paper 1.18+, PlaceholderAPI on the server (Bedrock: see below). No other runtime dependencies: the HTTP server,
  WebSocket and JSON are the JDK's own.
- HTTPS built in (self-signed or your own certificate), pairing with one-time codes and QR maps, signed tokens, per-device revocation, rate limits.
- Optional history for charts, in memory or in SQLite, MySQL/MariaDB or PostgreSQL with a SOSA/SSN schema ([docs/history.md](docs/history.md)).
- Optional reverse tunnel and caching proxy support ([MCDashboard-proxy](https://github.com/MVdWSoftware/mcdashboard-proxy)).

## Install

1. Install PlaceholderAPI and the expansions you need.
2. Download the plugin from the [latest release](https://github.com/MVdWSoftware/mcdashboard-plugin/releases/latest)
   (`MCDashboard-<version>.jar`) and put it in `plugins/`, or build it yourself (below). The server does not care what the
   jar is called.
3. Start the server once, review `plugins/MCDashboard/config.yml` ([reference](docs/configuration.md)) and
   `placeholders.yml`, set `server.enabled: true`.
4. The plugin serves HTTPS itself (a self-signed certificate it generates, or your own); behind a TLS-terminating reverse proxy set `server.tls.mode: off`. Full guide: [docs/installation.md](docs/installation.md).

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

`mvn -B verify` also runs JaCoCo (`<module>/target/site/jacoco/index.html`) and fails below the per-module limits in each `pom.xml`; the limits only go up. Building from source needs nothing but Maven Central: the PowerNukkitX API the Bedrock plugin compiles against is vendored in `third-party/maven-repo/`, since PowerNukkitX's own Maven repository has been offline since 2026-11-25.

## End-to-end smoke tests

Beyond the unit tests, `scripts/` runs each jar on a real server in Docker and reads its API the way the app does:
`scripts/proxy-smoke-test.sh` (a Paper backend plus a Velocity and a BungeeCord proxy, including federation of the
backend's placeholders) and `scripts/nukkit-smoke-test.sh` (PowerNukkitX with its PlaceholderAPI). They need docker,
curl and python3 plus built jars (`mvn -B package`), and CI runs them on every change to the scripts and nightly
([.github/workflows/e2e.yml](.github/workflows/e2e.yml)).

The [releases](https://github.com/MVdWSoftware/mcdashboard-plugin/releases) are these four jars, built by
[.github/workflows/release.yml](.github/workflows/release.yml) from a `vX.Y.Z` tag, each named with its version
(`MCDashboard-1.0.0.jar`) and listed with its SHA-256 in `SHA256SUMS.txt`.

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

Two things leave the server, and neither carries server names, addresses, player names or placeholder values: opt-in error
reports (`diagnostics.error-reports`, default `false`) and anonymous [bStats](https://bstats.org) usage metrics (Spigot, Bungee and
Velocity jars; turn off in `plugins/BStats/config.yml`). Details: [docs/configuration.md](docs/configuration.md#diagnostics).

## Concepts

- **Placeholders**: only entries of `placeholders.yml` are resolved; a `catalog:` offers a whole PlaceholderAPI expansion, `IMAGE`
  and `LIST` types carry pictures and tables ([docs/placeholders.md](docs/placeholders.md)).
- **Dashboards**: one YAML file each in `dashboards/`, permission derived from the file name; developers can upload from the
  app ([docs/dashboards.md](docs/dashboards.md)).
- **Update method and limits**: polling, live feed, refresh intervals, allowed clients and request limits
  ([docs/configuration.md](docs/configuration.md), [docs/security.md](docs/security.md)).
- **Device events** tell a caching proxy about revocations ([docs/events.md](docs/events.md)).

