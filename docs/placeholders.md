# Placeholders: groups, catalogs and images (for server owners)

`plugins/MCDashboard/placeholders.yml` is the allowlist of what the app may read. Players never send a `%placeholder%`
template; they pick from this list, so nothing outside it is ever evaluated.

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
```

- Sent to the app as a SenML data value (`vd`, base64url without padding) and shown by the `image` widget.
- Only PNG, JPEG, GIF and WebP are accepted, decided by the file's bytes, never by its name or Content-Type; at most
  256 KB. `file:` paths cannot leave the `images/` folder. `url:` must be http(s) with a fixed host (a placeholder in the
  host is refused), is fetched off the main thread with a 5 second timeout, cached for at least 5 minutes, and the last
  good copy keeps being served if the origin is down.
- Head, map-tile and other image URL templates (mc-heads, crafatar, squaremap, Pl3xMap) and their limits:
  [demo-placeholders.md](demo-placeholders.md), section "Images and maps".

## Permissions

A placeholder with `permission:` is refused (403) for everyone without it on every route. A dashboard's permission is
derived from its file name (`admin.yml` -> `mcdashboard.dashboard.admin`) and only offered to players who hold it; see
[dashboards.md](dashboards.md).
