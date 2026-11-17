# Contributing

Thanks for helping! Bug reports, fixes, documentation and ideas are all welcome.

## Ground rules

- **No runtime dependencies.** `spigot-api` and PlaceholderAPI are `provided`; the plugin uses the JDK only
  (HTTP server, WebSocket, JSON, JDBC). CI fails if the jar contains anything but `org/mcdashboard`. JDBC
  drivers are never shaded: the server's own SQLite and MySQL drivers are used, anything else is loaded from
  `plugins/MCDashboard/drivers/`.
- **The contract comes first.** The API is described in [`openapi.yaml`](openapi.yaml). Change it together with
  the code (`web/HttpApi` and its `*Routes` classes, `web/SenMl`) and add a test. Companion clients, such as the
  [MCDashboard-proxy](https://git.mvdw-software.com/mcdashboard/mcdashboard-proxy), follow this file.
- **Values are SenML**, tokens are JWT HS256 (reject any other `alg`). Caching headers are set in
  `PlaceholderRoutes.cacheable`; anything per player must stay `private`.
- **Only `placeholders.yml` entries are ever resolved.** Never evaluate a client-supplied template.
- **Secrets stay out of logs and responses**: database password, tunnel key, events token.
- Keep the docs current in the same change (README, `docs/`, `config.yml` comments, changelog).

## Development

You need JDK 21 and Maven (the Spigot plugin and `core/` are compiled for Java 17, the PowerNukkitX plugin for Java 21).

```sh
mvn -B verify          # compile, unit tests, jars in spigot/target/ and nukkit/target/
```

The tests start the HTTP API and the live feed on random local ports with a fake placeholder source, so no
Minecraft server is needed. The history tests run against SQLite. To run them against PostgreSQL and MySQL as
well, start the databases and pass their JDBC URLs:

```sh
docker run -d --name mcd-pg -e POSTGRES_PASSWORD=test -p 5432:5432 postgres:16
docker run -d --name mcd-my -e MYSQL_ROOT_PASSWORD=test -e MYSQL_DATABASE=mcd -p 3306:3306 mysql:8
MCD_TEST_POSTGRES_URL='jdbc:postgresql://localhost:5432/postgres?user=postgres&password=test' \
MCD_TEST_MYSQL_URL='jdbc:mysql://localhost:3306/mcd?user=root&password=test&allowPublicKeyRetrieval=true&useSSL=false' \
mvn -B verify
```

The tests drop and recreate the `mcd_*` tables in those databases: use throwaway ones.

## Pull requests

1. Open an issue first for larger changes.
2. Add or update tests; `mvn -B verify` must pass.
3. Describe the behaviour change and add an entry under *Unreleased* in the changelog.
4. By contributing you agree your work is released under the [GNU AGPL v3 licence](LICENSE).
