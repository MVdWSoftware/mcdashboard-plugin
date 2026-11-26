# Contributing

Thanks for helping! Bug reports, fixes, documentation and ideas are all welcome.

## Ground rules

- **No runtime dependencies.** `spigot-api` and PlaceholderAPI are `provided`; the plugin uses the JDK only
  (HTTP server, WebSocket, JSON, JDBC). CI fails if the jar contains anything but `org/mcdashboard`. JDBC
  drivers are never shaded: the server's own SQLite and MySQL drivers are used, anything else is loaded from
  `plugins/MCDashboard/drivers/`.
- **The contract comes first.** The API is described in [`openapi.yaml`](openapi.yaml). Change it together with
  the code (`web/HttpApi` and its `*Routes` classes, `web/SenMl`) and add a test. Companion clients, such as the
  [MCDashboard-proxy](https://github.com/MVdWSoftware/mcdashboard-proxy), follow this file.
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

Apart from Maven Central, nothing is downloaded: the PowerNukkitX API that `nukkit/` compiles against is vendored in
`third-party/maven-repo`, because PowerNukkitX's own Maven repository has been offline since 2026-11-25 and nothing
else serves those artifacts. [third-party/maven-repo/README.md](third-party/maven-repo/README.md) says where the jars
come from, and `sha256sum -c SHA256SUMS.txt` in that directory checks them.

JaCoCo runs with the tests (`<module>/target/site/jacoco/index.html`); `mvn -B verify` fails when a module drops below the limits in its `pom.xml`. Raise a limit when you raise coverage, never lower it.

`mvn -B verify` also runs Javadoc with doclint's `all` checks and fails on any warning: every public and protected type,
field, constructor and method carries a doc comment, `@param` names match the signature (record components included),
anything that returns has `@return`, and `{@link}`/`{@code}` are valid. Write tags as `@param name Description.` (no
` - ` separator) and escape a literal `<` as `{@code &lt;}`.

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

## Releasing

A release is a `vX.Y.Z` tag; pushing it runs [.github/workflows/release.yml](.github/workflows/release.yml), which builds the
four jars exactly as CI does and publishes them as assets of a GitHub Release, with that version's changelog section as the
notes. The version comes from the poms, so the tag never sets it on its own: the workflow reads
`mvn help:evaluate -Dexpression=project.version` and stops when it differs from the tag, because `plugin.yml`,
`nukkit/plugin.yml`, `velocity-plugin.json` and `bungee.yml` filter that same value into the jar a server runs.

1. Make sure `mvn -B verify` passes on `main` and that everything you want to ship is under *Unreleased* in the changelog.
2. Set the version in `pom.xml` **and in the `<parent>` block of every module pom** (`core`, `spigot`, `nukkit`,
   `proxy-common`, `velocity`, `bungee`) to `X.Y.Z`, without `-SNAPSHOT`.
3. In `CHANGELOG.md`, turn `## [Unreleased]` into `## [X.Y.Z] - YYYY-MM-DD`, start a new empty *Unreleased* section, and add
   the comparison and release links at the bottom.
4. `git commit` as `chore: release vX.Y.Z`, then `git tag -a vX.Y.Z -m "MCDashboard X.Y.Z"` and push both the branch and the
   tag. A tag with a pre-release part (`v1.1.0-rc1`) publishes a pre-release.
5. Check the [Actions run](https://github.com/MVdWSoftware/mcdashboard-plugin/actions) and the
   [release page](https://github.com/MVdWSoftware/mcdashboard-plugin/releases): four jars named `MCDashboard-<name>-X.Y.Z.jar`
   and a `SHA256SUMS.txt`.

Only a maintainer with write access may push a tag, and nothing else publishes a release: do not add another way to attach
files to a release, and do not move a tag once it is released - cut a new version instead.
