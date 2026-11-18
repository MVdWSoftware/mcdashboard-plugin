# The `%mcdashboard_...%` placeholders (PlaceholderAPI expansion)

On Spigot and Paper the plugin registers its own PlaceholderAPI expansion with the identifier `mcdashboard`; on PowerNukkitX it registers
the same names (`mcdashboard_<name>`) with the PowerNukkitX PlaceholderAPI. This happens whenever
PlaceholderAPI is installed and whether or not the web server is enabled. Use them in scoreboards, holograms, chat or any other plugin
that reads PlaceholderAPI. The expansion stays registered over `/papi reload` and is unregistered when the plugin is disabled.

Every value is a count or a setting kept in memory by the plugin. Reading one never does I/O or a database query, so it is safe on the
server's main thread. Nothing here exposes a player, an address, a token or a device identifier. A name that is not in the table
returns nothing (PlaceholderAPI shows the placeholder text unchanged).

| Placeholder | Meaning | Type | Notes |
| --- | --- | --- | --- |
| `%mcdashboard_devices%` | Paired devices | integer | All players together. |
| `%mcdashboard_devices_online%` | Devices that made a request in the last 5 minutes | integer | The last-seen time is kept in memory and resets on a restart, so it starts at 0. |
| `%mcdashboard_devices_server%` | Devices that say they use this server's licence | integer | Self-reported by the app, informational only ([security.md](security.md#device-entitlement)). Devices that never reported are in none of the three. |
| `%mcdashboard_devices_app%` | Devices that say they use the App unlock | integer | Self-reported, as above. |
| `%mcdashboard_devices_free%` | Devices that say they run the free app | integer | Self-reported, as above. |
| `%mcdashboard_licensed%` | Whether `license.id` is configured | `true` / `false` | Says nothing about whether the portal accepts the id. |
| `%mcdashboard_license_state%` | `licensed` when `license.id` is configured, else `free` | text | Config based and never asks the MVdW service. The per-device counts above are the refinement: they are self-reported, so they do not change this value. |
| `%mcdashboard_api_requests%` | API requests since the plugin started | integer | Every `/v1` request, also the refused ones, also those through the tunnel. |
| `%mcdashboard_api_requests_per_minute%` | API requests in the last 60 seconds | integer | Rolling window. |
| `%mcdashboard_rate_limited%` | Requests refused by a rate limit since start | integer | Device, address, background and event-stream limits; refused pairing and refresh attempts are counted too. |
| `%mcdashboard_live_clients%` | Open live-feed (WebSocket) connections | integer | 0 when the live feed is off. |
| `%mcdashboard_history_samples%` | History samples written to the database since start | integer | 0 with `history.storage.type: memory`. |
| `%mcdashboard_history_storage%` | `sqlite`, `mysql`, `mariadb`, `postgresql` or `off` | text | `off` is memory only. |
| `%mcdashboard_placeholders%` | Placeholders offered to the app | integer | After `stats.offer-to-app` is applied. |
| `%mcdashboard_dashboards%` | Dashboards defined | integer | Refreshed every few seconds. |
| `%mcdashboard_uptime%` | Seconds since the plugin started | integer | |
| `%mcdashboard_uptime_formatted%` | The same as text, such as `3d 4h 5m 6s` | text | |
| `%mcdashboard_version%` | Plugin version | text | |

While the web server is disabled or failed to start, the counters are 0, `history_storage` is `off` and the version is empty.

## Offering them to the app

`placeholders.yml` ships these as entries of the `MCDashboard` group (ids `mcdashboard-devices`, `mcdashboard-api-requests-per-minute`, ...),
so the app can show them. They are `PUBLIC`, which means any client that may read the server's PUBLIC placeholders can read these counts
and, for `devices_online` and `api_requests_per_minute`, a chart. That is why they are off by default: set

```yaml
stats:
  offer-to-app: true
```

in `config.yml` and restart the server to offer them. With it off, the entries are ignored; the PlaceholderAPI
placeholders above work either way. Delete the entries you do not want, or change `access` / `permission` to restrict them. No dashboard
uses them: the free limit is 2 dashboards, which the `player` and `server` presets already fill.

## Other platforms

The values come from `org.mcdashboard.stats.PluginStats` in `core/` and are named by `StatsPlaceholders`, so the proxy plugins can answer
the same names. The Nukkit plugin does not register these yet.
