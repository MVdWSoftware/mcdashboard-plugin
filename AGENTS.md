# AGENTS.md

- **No runtime dependencies.** spigot-api and PlaceholderAPI are `provided`; JDK only otherwise. CI fails if the
  jar contains anything but `org/mcdashboard`. JDBC drivers are never shaded (`history/JdbcConnector`).
- **`core/` knows no server platform.** It must not import `org.bukkit` or `me.clip`; everything it needs from the
  server (logger, placeholder engine, permissions, main thread, player count) comes through `platform/Platform`,
  and YAML through `config/YamlCodec`. Platform code goes in `spigot/` or `nukkit/` (or another platform module) and is wired up
  with `new MCDashboardCore(platform, yaml, dataDir)`. `spigot/` shades `core/` into `MCDashboard.jar`.
- **Contract first.** Change `openapi.yaml` together with `web/HttpApi` (and its `*Routes` classes) / `web/SenMl`
  and add a test. The companion app (closed source) and MCDashboard-proxy follow this file.
- **Values are SenML**, tokens are JWT HS256 (reject `alg` other than HS256), caching headers are set in
  `PlaceholderRoutes.cacheable`. Anything per-player must stay `private`.
- Only `placeholders.yml` entries are ever resolved; never evaluate client-supplied templates.
- **History schema** (`history/SosaSchema`): every table, column and foreign key names the SOSA/SSN term it maps
  to (`COMMENT ON` on PostgreSQL, inline `COMMENT` on MySQL). Sensors share the `ssn:System` supertype table.
- Commands: `mvn -B verify` (core and spigot); `mvn -B -pl core verify` for the platform independent part only. `/mcdashboard` text lives in `core/commands/DashboardCommands`; platforms add only what is specific to them (clickable text, QR maps).
- Docs: no ASCII repo trees; keep README and `docs/` current in the same change.
