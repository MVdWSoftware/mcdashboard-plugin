---
name: MCDashboard configuration
description: Change the MCDashboard plugin settings in config.yml (server, security, history, tunnel, events, developer, diagnostics, stats, messages, license) and pick ports, TLS, rate limits and refresh intervals. Use when a server owner asks to change a setting, tighten security, limit traffic, keep history in a database, turn on opt-in error reports or grant permissions for MCDashboard.
license: AGPL-3.0-or-later
---

# Changing the configuration

One file: `plugins/MCDashboard/config.yml` (`plugins/mcdashboard/` on Velocity).

- **Restart after a change.** Only `/mcdashboard developer on|off` (and `/mcdashboard reload` on Velocity and
  BungeeCord) applies at runtime. `placeholders.yml` and `dashboards/*.yml` are re-read, `config.yml` is not, and a new
  dashboard file registers its permission only on the next start.
- Unknown keys are ignored with a warning, and an invalid value falls back to its default instead of breaking the
  plugin. Read the console after a restart; every fallback is logged with the key name.
- Missing keys keep their documented default, so an incomplete file works. Never delete the file to "reset" it: the
  plugin only writes defaults when the file is missing, and a missing file loses every owner comment.

## The sections

| Section | Covers | Defaults worth knowing |
| --- | --- | --- |
| `server` | master switch, name, `public-url`, bind `host`, `port` 1553, live feed 1554, TLS, polling/websocket, `endpoints`, `discovery-only` | `enabled: false`, `tls.mode: self-signed` |
| `security` | pairing, unverified player names (`allow-player-names`), tokens, device cap, refresh floor, client checks, rate limits, trusted proxies | `require-authentication: true`, `allow-player-names: false`, `max-devices: 5`, `otp-timeout: 300`, `min-interval: 1`, `requests-per-minute: 120`, `anonymous-requests-per-minute: 60` |
| `history` | chart storage (`memory`, `sqlite`, `mysql`, `mariadb`, `postgresql`), sampling, retention, `include`/`exclude` | memory, two hours, `sampling.interval: 30` |
| `tunnel` | dial out to MCDashboard-proxy over one WebSocket | off; needs `url` and a 32+ character `key` |
| `events` | device revocation stream (`token`) and signed `webhooks` | off; the tunnel carries the events without either |
| `developer` | allow dashboard uploads from the app (also `/mcdashboard developer`, which writes `developer.yml` and wins) | `false` |
| `diagnostics` | opt-in error reports: an **unexpected** plugin error goes to MVdW's own error tracker, never a placeholder value or player data | `error-reports: false` |
| bStats | anonymous usage metrics of the Spigot, BungeeCord and Velocity jars (opt-out in `plugins/BStats/config.yml`, `config.txt` on Velocity), never a server name, address, licence id or player value | on |
| `stats` | also offer the plugin's own `%mcdashboard_*%` counters to the app | `offer-to-app: false` |
| `messages` | `messages.prefix` (chat prefix, `&` codes) | MCDashboard prefix |
| `license` | `license.id` (`mcl_...`) registered for this server's address in the portal | empty = free limits |
| `network` | Velocity/BungeeCord only: backend federation and ping interval | written by the proxy plugins |

`security.require-authentication` and `server.tls` interact: with pairing required, every response is `private` and no
shared cache may store it. That is intended, not a bug. `security.allow-player-names: true` is an exception to pairing,
not authentication: anyone who can reach the API can type a player's name and read that player's data and dashboards.

## Recipes

**Fresh public server with a TLS-terminating reverse proxy**

```yaml
server:
  enabled: true
  name: "Survival"
  public-url: "https://mc.example.com"
  host: "127.0.0.1"
  tls:
    mode: off
security:
  trusted-proxies: ["127.0.0.1"]
```

**Fewer PlaceholderAPI resolutions on a busy server** - raise the floor and let staff see fresher values:

```yaml
security:
  min-interval: 15
  min-interval-permissions:
    mcdashboard.admin: 5
```

The effective interval is the larger of the player's global interval and the placeholder's `min-interval`, and it is
what the `Cache-Control` header advertises to a caching proxy. It never changes which placeholders someone may read.

**Keep charts across restarts** - SQLite needs nothing; MySQL/MariaDB drivers ship with Paper, PostgreSQL needs
`plugins/MCDashboard/drivers/postgresql-*.jar` (the plugin ships no JDBC driver):

```yaml
history:
  storage:
    type: sqlite
  retention-days: 30
  include: ["tps", "online", "ram-*"]
```

**Paired devices but no extra widgets** - the free limits are enforced by the app; a server licence removes the
dashboard, tile and history limits for everyone on this server. `license.id` is never published (only its SHA-256 is) and only works for the address
registered in the portal.

## Commands and permissions

`/mcdashboard` (aliases `/mcd`, `/dashboard`): `link`, `qr` (Spigot), `devices`, `revoke <id>`, `developer on|off`,
`tunnel`, `about` (what the plugin is, store links for the app, how to link; `messages.prefix` in `config.yml` changes the chat prefix); on Velocity/BungeeCord also `reload`, and `link <player>` for the console or an admin.

| Node | Default | Grants |
| --- | --- | --- |
| `mcdashboard.player` | everyone | link and revoke own devices |
| `mcdashboard.admin` | operators | admin commands, includes `mcdashboard.player` |
| `mcdashboard.developer` | nobody | upload a dashboard from the app while developer mode is on |
| `mcdashboard.dashboard.player` | everyone | the bundled Player dashboard |
| `mcdashboard.dashboard.server` | operators | the bundled Server dashboard |
| `mcdashboard.dashboard.<name>` | nobody | `dashboards/<name>.yml` |

Grant with the permissions plugin, e.g. `/lp group staff permission set mcdashboard.dashboard.server true`. A
dashboard permission only decides what is offered; the data itself is protected by `permission:` on the entry in
`placeholders.yml`. A placeholder permission refuses the value on every route.

## Before you call it done

1. Restart and read the console: no `WARN` about a key you changed.
2. `/mcdashboard about` and `/mcdashboard devices` still work.
3. `/papi parse me %server_online%` (or the value you added) answers.
4. Security did not get weaker: `require-authentication` still `true`, `trusted-proxies` only the proxy,
   `secret.key` not readable by other users.

## Read more

- Every setting, command and permission: https://mcdashboard.org/en/docs/server/configuration/
- The security model behind the settings: https://mcdashboard.org/en/docs/server/security/
- History and the database schema: https://mcdashboard.org/en/docs/server/dashboards/
- Tunnel, endpoints and events: https://mcdashboard.org/en/docs/proxy/
