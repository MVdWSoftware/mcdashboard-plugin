# AGENTS.md

- **No runtime dependencies.** spigot-api and PlaceholderAPI are `provided`; JDK only otherwise. CI fails if the
  jar contains anything but `org/mcdashboard`. JDBC drivers are never shaded (`history/JdbcConnector`).
- **Contract first.** Change `openapi.yaml` together with `web/HttpApi` / `web/SenMl` and add a test. The
  companion app (closed source) and MCDashboard-proxy follow this file.
- **Values are SenML**, tokens are JWT HS256 (reject `alg` other than HS256), caching headers are set in
  `HttpApi.cacheable`. Anything per-player must stay `private`.
- Only `placeholders.yml` entries are ever resolved; never evaluate client-supplied templates.
- **History schema** (`history/SosaSchema`): every table, column and foreign key names the SOSA/SSN term it maps
  to (`COMMENT ON` on PostgreSQL, inline `COMMENT` on MySQL). Sensors share the `ssn:System` supertype table.
- Commands: `mvn -B verify`.
- Docs: no ASCII repo trees; keep README and `docs/` current in the same change.
