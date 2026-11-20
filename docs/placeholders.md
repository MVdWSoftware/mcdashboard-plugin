# Placeholders: groups, catalogs, images and lists (for server owners)

`plugins/MCDashboard/placeholders.yml` is the allowlist of what the app may read. Players never send a `%placeholder%`
template; they pick from this list, so nothing outside it is ever evaluated.

The plugin also provides placeholders of its own, `%mcdashboard_...%` (paired devices, API requests, uptime, ...), listed in
[placeholders-api.md](placeholders-api.md).

## Keys

| Key | Meaning |
| --- | --- |
| `id` | What the app asks for: letters, digits, `_`, `.` and `-`, at most 64. |
| `name`, `description`, `group` | Shown in the app; `group` defaults to `General`. |
| `value` | One PlaceholderAPI placeholder, e.g. `'%server_online%'`. |
| `type` | `STRING`, `INTEGER`, `DECIMAL`, `BOOLEAN`, `PERCENTAGE`, `AUTO` (decided per value), `IMAGE` or `LIST` (see below). |
| `access` | `PUBLIC`: anyone who can reach the server. `PLAYER`: a paired device, or a typed name when `security.allow-player-names` is on, resolved for that player. |
| `permission` | Optional node the paired player must hold for any request. |
| `min-interval` | Seconds; the value is resolved at most this often (never below the caller's `security.min-interval`; `min-interval-permissions` can change it). |
| `min-interval-permissions` | Optional map of permission node to seconds for this placeholder. A paired player holding a node uses that interval instead of `min-interval`; the fastest held node wins. |
| `min-value`, `max-value`, `unit` | Scale and unit for gauges and charts. |
| `max-from` | Another placeholder whose value is this one's maximum, e.g. `max-players` for `online`. |
| `history` | Minutes of samples kept for charts (PUBLIC only; up to a year with a database, see [history.md](history.md)). |
| `history-interval` | Seconds between two samples of this placeholder (default `history.sampling.interval`). |

## Groups, search and previews

Give every entry a `group` (default `General`) and an optional `description`. The app shows the groups as filter chips,
searches id, name, description and group, loads 30 at a time, and shows each placeholder's current value next to it
before it is picked. This keeps a list of thousands usable.

## Offering a whole PlaceholderAPI expansion (catalog)

```yaml
catalog:
  - expansion: server            # the PlaceholderAPI expansion identifier (must be installed)
    group: Server                # default: the identifier, capitalised
    access: PUBLIC               # PUBLIC | PLAYER
    permission: some.node        # optional: only players holding it can read these
    deny: ['*foo*']              # optional, added to the always-on deny list
    max: 2000                    # optional cap per expansion
    params: [online, max_players, tps]   # optional: list parameters yourself
```

- Every placeholder the expansion declares becomes an entry `<expansion>_<param>` of type `AUTO`: the app takes the type
  from the value it returns (number, true/false, text) and offers fitting widgets.
- **Discovery.** The plugin asks the installed expansion (`getPlaceholders()`); many expansions return nothing there, so
  it then uses PlaceholderAPI's cloud data (needs the server to reach the eCloud once). If an expansion yields nothing,
  the log says so; list the parameters with `params:`. `params` are added to whatever discovery finds.
- **Always denied** (even if listed): parameters matching `*ip*`, `*password*`, `*token*`, `*secret*`, `*address*`.
  Placeholders with parameters (`%x_<player>%`) are skipped. Explicit entries win over catalog entries of the same id.
- Expansions register with PlaceholderAPI a moment after the plugins start, so the catalog is filled a few seconds after
  startup (the plugin retries for up to 30 seconds).

## Images

```yaml
placeholders:
  - id: logo
    name: Server logo
    type: IMAGE
    source: file:logo.png                     # plugins/MCDashboard/images/logo.png
    group: Branding
  - id: head
    name: Your head
    type: IMAGE
    access: PLAYER
    source: url:https://mc-heads.net/avatar/%player_uuid%/64   # PlaceholderAPI is expanded for the paired player
  - id: skin
    name: Your skin
    type: IMAGE
    access: PLAYER
    source: url:https://mc-heads.net/body/%player_uuid%/128
    encode: false                             # the app loads the URL itself; the server fetches nothing
  - id: spawn-map
    name: Spawn
    type: IMAGE
    source: map:squaremap                     # tiles of a web map around a block, see maps.md
    map: { url: 'http://localhost:8080', world: minecraft_overworld, x: 0, z: 0, size: 256 }
```

- Sent to the app and shown by the `image` widget.
- Only PNG, JPEG, GIF and WebP are accepted, decided by the file's bytes, never by its name or Content-Type; at most
  256 KB. `file:` paths cannot leave the `images/` folder. `url:` must be http(s) with a fixed host (a placeholder in the
  host is refused), is fetched off the main thread with a 5 second timeout, cached for at least 5 minutes, and the last
  good copy keeps being served if the origin is down.
- `encode: false` (only for `url:`, default `true`): the server does not download the picture. It expands the
  placeholders (for the paired player with `access: PLAYER`) and sends the resulting URL as the value (SenML `vs`
  instead of the picture in `vd`); the app loads it directly. The URL must be `https://` (an `http://` one is refused when
  the file is loaded, with a warning in the console) and stays on its fixed host. Use it for pictures the server should not
  proxy, such as large ones; the phone then contacts that host itself, so only use hosts you trust with your players'
  IP addresses.
- `map:dynmap`, `map:squaremap`, `map:pl3xmap` and `map:bluemap` stitch tiles of your web map into one picture centred on
  a block (a fixed one or `'%player_x%'` / `'%player_z%'` for the paired player). Always sent as a picture (`encode: false`
  is refused). Keys, providers and limits: [Map pictures](maps.md).

## Lists and leaderboards

A `LIST` placeholder is a small table: several PlaceholderAPI placeholders, one per column, repeated for each row. `{n}` in a
column is replaced by the row number (1, 2, 3, ...) before PlaceholderAPI expands it. The `leaderboard`, `heads` and
`ticker` widgets of the app read it.

```yaml
placeholders:
  - id: top-kills
    name: Top kills
    type: LIST
    rows: 10                                   # 1-50, default 10
    columns:                                   # 1-6 columns; names a-z, 0-9, _ (start with a letter, max 24); the order is kept
      name: '%ajlb_lb_kills_{n}_alltime_name%'
      value: '%ajlb_lb_kills_{n}_alltime_value%'
    empty: ['---', 'N/A']                      # optional: cells equal to one of these (colour codes stripped, trimmed) are empty
    access: PUBLIC
    min-interval: 60
```

- This example uses the [ajLeaderboards](https://www.spigotmc.org/resources/ajleaderboards.85548/) expansion. Use the
  `_value` placeholder, not `_value_formatted`: formatted values such as `10k` are text, not numbers, so the app cannot
  format, compare or colour them.
- Each column is one `%placeholder%`, like `value:` of a normal entry; other text around it is refused.
- A row whose cells are all empty is dropped only when it comes after the last filled row, so the order of the rest is kept.
- A cell that is fully a number (colour codes and thousands separators such as `1,234` ignored) is sent as a number, any
  other cell as text with its `§`/`&` colour codes kept, cut to 256 characters.
- `access`, `permission`, `min-interval` and `min-interval-permissions` work as for any other placeholder; every cell is
  resolved on the server thread each refresh, so keep `rows` and `min-interval` modest. `history` is ignored.
- An app that does not know `LIST` shows the table as JSON text.

## Permissions

A placeholder with `permission:` is refused (403) for everyone without it on every route. A dashboard's permission is
derived from its file name (`admin.yml` -> `mcdashboard.dashboard.admin`) and only offered to players who hold it; see
[dashboards.md](dashboards.md).

`min-interval-permissions` never grants access by itself: the player must also be able to read the placeholder. It only
changes how often their requests may force a fresh value. The effective interval is the larger of the player's global
interval (`security.min-interval-permissions`, falling back to `security.min-interval`) and this placeholder's
interval. Values are cached per placeholder, so a fast caller can keep the value fresh for everyone; a slower caller
never forces a resolution more often than their own interval.

```yaml
# config.yml: everyone refreshes every 15 minutes, admins every 15 seconds
security:
  min-interval: 900
  min-interval-permissions:
    mcdashboard.admin: 15

# placeholders.yml: this expensive value stays at 30 minutes for everyone else, admins may read it every 15 seconds
placeholders:
  - id: player-health
    value: '%player_health%'
    min-interval: 1800
    min-interval-permissions:
      mcdashboard.admin: 15
```
