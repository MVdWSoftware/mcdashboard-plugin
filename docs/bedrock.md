# Bedrock servers (PowerNukkitX)

`MCDashboard-Nukkit.jar` runs the plugin on [PowerNukkitX](https://powernukkitx.org) 3 (Java 21). Everything else
works as on Spigot: the same API, pairing, dashboards, history, events and tunnel, and the same app.

## Install

1. Put the [PlaceholderAPI for PowerNukkitX](https://powernukkitx.org/plugins/PowerNukkitX-Bundle.PlaceholderAPI)
   (`pnx install PowerNukkitX-Bundle.PlaceholderAPI`) and `MCDashboard-Nukkit.jar` (the release asset is
   `MCDashboard-Nukkit-<version>.jar`) in `plugins/`.
2. Start the server once. Edit `plugins/MCDashboard/config.yml` and `placeholders.yml`, set `server.enabled: true`.
3. Follow [installation.md](installation.md) for the reverse proxy and TLS.

The plugin is named `MCDashboard` and the command is `/mcdashboard` (aliases `/mcd` and `/dashboard`), as on Spigot.

## Differences from Spigot

- **Placeholder names.** The PowerNukkitX PlaceholderAPI has its own names, written `%name%`, with parameters after a
  semicolon (`%player_pos;x%`). The default `placeholders.yml` uses `%server_tps%` and `%player%`; the Spigot defaults
  `%server_tps_1%` and `%player_name%` do not exist there. Its built-ins include `server_online`, `server_max_players`,
  `server_motd`, `server_tps`, `player`, `player_health`, `player_ping`, `player_level` and `time`; other plugins add
  more.
- **`%mcdashboard_...%`.** The plugin registers its own counters (`mcdashboard_devices`, `mcdashboard_devices_server`, ...) with the
  PowerNukkitX PlaceholderAPI under the same names as on Spigot ([placeholders-api.md](placeholders-api.md)); the `MCDashboard` group
  of the default `placeholders.yml` offers them to the app with `stats.offer-to-app: true`.
- **No `catalog:`.** The PlaceholderAPI for PowerNukkitX cannot list its placeholders, so the plugin cannot expand an
  expansion into entries. List each placeholder under `placeholders:`; a `catalog:` section is ignored with a warning.
- **Player placeholders need the player online.** They read live player state, so a `PLAYER` placeholder has no value
  while that player is offline.
- **No QR map.** `/mcdashboard link` (and `qr`) prints the pairing code and, with `server.public-url` set, the
  `mcdashboard://` link to open on the phone.
- **Linking another player.** `/mcdashboard link <player>` (permission `mcdashboard.admin`, or the console) works for
  online players only.
- **No server icon** in the discovery response.
- **Permissions** are registered the same way, so `mcdashboard.dashboard.<id>` works with any PowerNukkitX
  permission plugin.
