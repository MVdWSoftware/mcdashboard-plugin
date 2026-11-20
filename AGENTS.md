# AGENTS.md

- **No runtime dependencies.** spigot-api, PlaceholderAPI, velocity-api and bungeecord-api are `provided`; JDK only otherwise (the one exception: `velocity/` shades SnakeYAML relocated to `org/mcdashboard/libs/`, because Velocity has no YAML library for plugins). CI fails if the
  jar contains anything but `org/mcdashboard`. JDBC drivers are never shaded (`history/JdbcConnector`).
- **`core/` knows no server platform.** It must not import `org.bukkit` or `me.clip`; everything it needs from the
  server (logger, placeholder engine, permissions, main thread, player count) comes through `platform/Platform`,
  and YAML through `config/YamlCodec`. Platform code goes in `spigot/`, `nukkit/`, `velocity/` or `bungee/` (the proxy ones share `proxy-common/`, which has no proxy API) and is wired up
  with `new MCDashboardCore(platform, yaml, dataDir)`. `spigot/` shades `core/` into `MCDashboard.jar`; the proxy jars also shade `proxy-common/`.
- **Contract first.** Change `openapi.yaml` together with `web/HttpApi` (and its `*Routes` classes) / `web/SenMl`
  and add a test. The companion app (closed source) and MCDashboard-proxy follow this file.
- **Values are SenML**, tokens are JWT HS256 (reject `alg` other than HS256), caching headers are set in
  `PlaceholderRoutes.cacheable`. Anything per-player must stay `private`.
- Only `placeholders.yml` entries are ever resolved; never evaluate client-supplied templates.
- **History schema** (`history/SosaSchema`): every table, column and foreign key names the SOSA/SSN term it maps
  to (`COMMENT ON` on PostgreSQL, inline `COMMENT` on MySQL). Sensors share the `ssn:System` supertype table.
- **Proxies** have no PlaceholderAPI: `proxy-common/ProxyValueSource` answers the built-in `%proxy_*%` placeholders from cached or in-memory data and must never block or ping on the calling thread. Federation (`network.backends`) only contacts the configured URLs: no redirects, timeouts, size cap, PUBLIC placeholders only.
- Commands: `mvn -B verify` (all modules); `mvn -B -pl core verify` for the platform independent part only. `/mcdashboard` text lives in `core/commands/DashboardCommands`; platforms add only what is specific to them (clickable text, QR maps).
- Skills: the owner-facing agent skills live in `.agents/skills/` at the repository root. `core/pom.xml` packages them as
  `skills/` in the jar and `DataFolder` writes them to `plugins/MCDashboard/.agents/skills/` on every start (a file whose
  content changed is replaced; extra files are kept). Change a skill together with the `docs/` page it summarises.
- Docs: no ASCII repo trees; keep README and `docs/` current in the same change.
- **The marketing site is part of the change.** A new or changed placeholder type, command,
  permission, config key, dashboard feature, history behaviour, licence limit or network
  feature must update `docs/` **and** the public site
  (`../mcdashboard-server/docs/website`): the documentation pages under `src/en/docs/` and
  `src/nl/docs/` (both languages) and, when the change is visible in the product story, the
  marketing copy in `src/_data/i18n.json`. Change an `.agents/skills/` entry together with its
  `docs/` page. The site describes what the plugin can do; stale copy is a bug.
