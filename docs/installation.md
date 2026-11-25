# Installation

On a Velocity or BungeeCord proxy there is no PlaceholderAPI: follow [proxy-platforms.md](proxy-platforms.md) instead of step 1.

1. Install [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) and any
   expansions you want to expose, then copy the plugin jar to `plugins/`: `MCDashboard-<version>.jar` from the
   [latest release](https://github.com/MVdWSoftware/mcdashboard-plugin/releases/latest), or `spigot/target/MCDashboard.jar`
   if you built it yourself. The name is yours to choose.
2. Start the server once, edit `plugins/MCDashboard/config.yml` and set `server.enabled: true`.
3. List the placeholders players may see in `placeholders.yml` (one `id` each) and arrange them in
   `dashboards/player.yml` or a dashboard file of your own. The file name becomes its permission, so
   `dashboards/admin.yml` needs `mcdashboard.dashboard.admin`; the bundled `player.yml` is public and `server.yml`
   is for operators.
4. The plugin serves HTTPS itself with a self-signed certificate it generates (`server.tls`), or with your own
   certificate (`server.tls.mode: custom`). Behind a reverse proxy that terminates TLS, set `server.tls.mode: off`
   ([guide](reverse-proxy.md)).
5. Restart. In game run `/mcdashboard link` (or the short `/mcd link`; `/mcd about` shows players where to get the app), then enter the code and your server address in the
   MCDashboard app.

Every player pairs by default (`security.require-authentication: true`). To let players see PUBLIC placeholders without
pairing, set it to `false`. Separately, `security.allow-player-names: true` lets an unpaired app type a player name to
read that player's data; **it is not authentication** and exposes player-specific data to anyone who can reach the API.

With the default self-signed certificate, `/mcdashboard link` and the QR map (which need `server.public-url`) carry the
certificate's fingerprint as `pin=`, and `/v1/info` publishes it as `tlsFingerprint`: a client pins that fingerprint
instead of trusting a certificate authority. Read it from the startup log too, to compare it by hand.
