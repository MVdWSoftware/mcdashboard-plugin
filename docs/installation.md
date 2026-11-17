# Installation

1. Install [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) and any
   expansions you want to expose, then copy `MCDashboard.jar` to `plugins/`.
2. Start the server once, edit `plugins/MCDashboard/config.yml` and set `server.enabled: true`.
3. List the placeholders players may see in `placeholders.yml` (one `id` each) and arrange them in
   `dashboards/player.yml` or a dashboard file of your own. The file name becomes its permission, so
   `dashboards/admin.yml` needs `mcdashboard.dashboard.admin`; the bundled `player.yml` is public and `server.yml`
   is for operators.
4. Put a reverse proxy with TLS in front ([guide](reverse-proxy.md)).
5. Restart. In game run `/mcdashboard link`, then enter the code and your server address in the
   MinecraftDashboard app.

Players who do not want to pair can still see PUBLIC placeholders, unless you enable
`security.require-authentication`.
