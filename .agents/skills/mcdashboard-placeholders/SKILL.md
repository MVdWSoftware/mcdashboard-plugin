---
name: MCDashboard placeholders
description: Add, group, restrict or hide values in the MCDashboard plugin allowlist (placeholders.yml), including whole PlaceholderAPI expansions via catalog, per-player values, images and refresh intervals. Use when someone asks why a value is missing or empty, how to expose a new placeholder or expansion, or how to keep a value private or slow to refresh.
license: AGPL-3.0-or-later
---

# The allowlist: `placeholders.yml`

`plugins/MCDashboard/placeholders.yml` is the only source of values. The app asks for an `id` from this list; it never
sends a `%placeholder%` template, so nothing else is ever evaluated. Adding a `%something%` to a dashboard without an
entry here does nothing.

The file is re-read at runtime (no restart). Invalid entries are skipped with a warning in the console; check the
indentation and quote every `value: '%...%'`.

## An entry

```yaml
placeholders:
  - id: online                 # what the app asks for: letters, digits, _ . - (max 64)
    name: Players online
    description: Right now
    group: Players             # default General
    value: '%server_online%'   # exactly one PlaceholderAPI placeholder, quoted
    type: INTEGER              # STRING INTEGER DECIMAL BOOLEAN PERCENTAGE AUTO IMAGE LIST
    access: PUBLIC             # PUBLIC | PLAYER (resolved for the paired player)
    permission: some.node      # optional: refused (403) on every route without it
    min-interval: 30           # optional: seconds between resolutions for this value
    min-value: 0               # optional scale and unit for gauges and charts
    max-value: 100
    max-from: max-players      # optional: another id supplies the maximum
    unit: players
    history: 1440              # optional: minutes of samples kept for charts (PUBLIC only)
    history-interval: 60       # optional: seconds between samples
```

Rules that matter:

- `access: PLAYER` resolves for the paired player only and never has history. Use it for health, balance, playtime.
- `min-interval` can only make a value slower: the effective interval is the larger of it and the caller's
  `security.min-interval` (or their `min-interval-permissions` override). A typo here does not force a value faster.
- `permission:` refuses the value for everyone without the node on every route. It never affects which dashboard is
  offered; that is the dashboard file's permission.
- `AUTO` takes the type from the value at read time (number, true/false, text) and is what catalog entries use.

## Offering a whole expansion (catalog)

```yaml
catalog:
  - expansion: server           # the installed PlaceholderAPI expansion identifier
    group: Server               # default: the identifier, capitalised
    access: PUBLIC
    permission: some.node       # optional
    deny: ['*motd*']            # optional globs, on top of the always-denied names
    max: 2000                   # optional cap per expansion
    params: [online, max_players, tps]   # optional: list the parameters yourself
```

- Every declared placeholder becomes `<expansion>_<param>`; explicit `placeholders:` entries win over catalog ones.
- Names matching `*ip*`, `*password*`, `*token*`, `*secret*` or `*address*` are always denied, and parameterised
  placeholders (`%x_<player>%`) are skipped.
- Many expansions declare nothing to PlaceholderAPI; the plugin then uses PlaceholderAPI's cloud data (the server must
  reach the eCloud once). If both are empty the console says so; use `params:` then. The catalog fills a few seconds
  after startup (it retries for 30 seconds), so a missing entry right after a restart is not yet a problem.
- PowerNukkitX cannot list placeholders: `catalog:` is ignored there, list entries explicitly.

## Images

```yaml
  - id: logo
    name: Server logo
    type: IMAGE
    source: file:logo.png        # plugins/MCDashboard/images/logo.png
  - id: head
    name: Your head
    type: IMAGE
    access: PLAYER
    source: url:https://mc-heads.net/avatar/%player_uuid%/64
```

PNG, JPEG, GIF or WebP, at most 256 KB, decided by the bytes. `file:` cannot leave `images/`; `url:` must be http(s)
with a fixed host (PlaceholderAPI is expanded in the path for the paired player), fetched off the main thread with a
5 second timeout and cached for at least 5 minutes, with the last good copy kept when the origin is down.

- `encode: false` on a `url:` image: the server fetches nothing and sends the expanded URL (SenML `vs`); the app loads it
  itself. Needs `https://` (an `http://` entry is skipped with a console warning).
- `source: map:<dynmap|squaremap|pl3xmap|bluemap>` with a `map:` block (`url` of the map's web server, `world`, `map`,
  `x`, `z`, `zoom`, `size` 64-512) stitches the web map's tiles around a block into one picture; `x`/`z` may be
  `'%player_x%'`/`'%player_z%'` with `access: PLAYER`. dynmap needs the map's tile prefix in `map` (`flat`, `t` for
  surface, `ct` for cave) and its `perspective` when it is not `iso_S_90_lowres`. Missing tiles are transparent; a
  picture that does not line up usually means a wrong `world`, `map` or `perspective`: compare with a tile URL in the
  browser's developer tools.
  Read more: https://mcdashboard.org/en/docs/server/placeholders/ (Images) and `docs/maps.md` in the plugin repository.

## Lists and leaderboards

```yaml
  - id: top-kills
    name: Top kills
    type: LIST
    rows: 10                   # 1-50, default 10
    columns:                   # 1-6, names [a-z][a-z0-9_]{0,23}; {n} is the 1-based row number
      name: '%ajlb_lb_kills_{n}_alltime_name%'
      value: '%ajlb_lb_kills_{n}_alltime_value%'
    empty: ['---', 'N/A']      # optional: cells equal to these (colour codes stripped) count as empty
```

Every column is exactly one `%placeholder%`. Trailing all-empty rows are dropped, a fully numeric cell (thousands
separators ignored) is sent as a number, anything else as text with colour codes kept (max 256 characters). Use the
`_value` placeholder, not `_value_formatted` (`10k` is text). `history` is ignored. Shown by the `leaderboard`, `heads`
and `ticker` widgets. Docs: https://mcdashboard.org/en/docs/server/placeholders/

## Recipes

**One value, private to staff:**

```yaml
  - id: economy-total
    name: Economy
    value: '%vault_total%'
    permission: mcdashboard.staff
```

**A whole expansion, no manual list:** the `catalog:` block above. Then place the ids on a dashboard.

**Faster refresh for staff only:** `security.min-interval-permissions` in `config.yml`, plus a per-placeholder
`min-interval-permissions` when one value is expensive. Both only raise the refresh rate; neither grants access.

**The plugin's own counters** (`mcdashboard_devices`, `mcdashboard_api_requests_per_minute`, ...) are in the
`MCDashboard` group but ignored until `stats.offer-to-app: true` in `config.yml`. They are PUBLIC counts, never player
data.

## Verify

1. `/papi parse me %server_online%` - if PlaceholderAPI returns the raw text, the expansion is missing or outdated; the
   plugin cannot invent a value.
2. The id in `placeholders.yml` is what the app asks for (`online`, not `%server_online%`).
3. The console has no warning about the entry; a warning names the file line or the offending key.
4. In the app the value appears under its `group`, with a preview of the current value.

## Read more

- Allowlist, groups, catalogs, images: https://mcdashboard.org/en/docs/server/placeholders/
- Refresh rates and permission overrides:
  https://mcdashboard.org/en/docs/server/placeholders/#refresh-rate-and-permissions
- The plugin's own `%mcdashboard_*%` placeholders: https://mcdashboard.org/en/docs/server/configuration/
- Charts need history: https://mcdashboard.org/en/docs/server/dashboards/#history-and-charts
