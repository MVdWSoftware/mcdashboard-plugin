# Preset dashboards (for server owners)

Besides the placeholders you allow (`placeholders.yml`), you can offer ready-made dashboards: one YAML file per
dashboard in `plugins/MCDashboard/dashboards/`. Players pick between the presets you offer them and can also build their
own dashboard in the app.

```yaml
# plugins/MCDashboard/dashboards/server.yml
name: Server
description: Performance and players
order: 20                                   # optional; lower comes first
background: stone                           # optional; a subtle pattern behind the dashboard (see below)
items:
  # widget: gauge is the half circle; its maximum comes from `max-from` in placeholders.yml
  - { placeholder: online, widget: gauge, title: Players, column: 0, row: 0, width: 2, height: 2 }
  - { placeholder: tps, widget: gauge, title: TPS, column: 2, row: 0, width: 2, height: 2 }
  - { placeholder: tps, widget: area, title: TPS history, column: 0, row: 2, width: 4, height: 3 }
```

- **Who sees a preset.** The permission is derived from the file name: `admin.yml` needs
  `mcdashboard.dashboard.admin`, `server.yml` needs `mcdashboard.dashboard.server`. Grant it with your permissions
  plugin, for example `/lp group staff permission set mcdashboard.dashboard.admin true`. The bundled `player.yml`
  is granted to everyone (it is also the one dashboard a caller can open before pairing) and `server.yml` to operators;
  any dashboard you add is off until you grant its permission.
  Items a player may not read are left out, and a preset with nothing left to show is not offered at all.
- **The permission is not only cosmetic.** A placeholder can carry its own `permission:` in `placeholders.yml`; the API then
  refuses it (403) for everyone without it, on every route (value, bulk values, history, live feed). A dashboard
  permission only decides what is offered; use placeholder permissions for data that must stay private.
- **Permissions refresh** on join, on quit and every 5 seconds for online players. An offline player keeps what they had
  when they left, until they join again.
- **Widgets** are the app's widget ids: `text`, `image`, `number`, `progress` (free), `status`, `gauge` (half circle), `ring`,
  `line`, `area`, `bar`, `pie` (full version, or a subscribed server). `line` and `area` need `history:` on the
  placeholder. The grid has 4 columns.
- **Maximum from another placeholder.** `max-from: max-players` on `online` makes gauges and rings show players online out
  of the real maximum, even when it changes.
- Presets are created from the defaults only on the first start; delete a file to remove a preset.

The app shows the presets as tabs, plus "My dashboard". Editing a preset first copies it into "My dashboard"; the preset
itself never changes.

## Developer mode (build a dashboard in the app)

Creating YAML by hand is not for everyone. A server owner can turn on developer mode for the server; players who also
hold `mcdashboard.developer` then get an upload button on "My dashboard" in the app. They build the dashboard with the
normal tile editor and upload it; the server writes the YAML for them.

1. Turn developer mode on, either in `config.yml` (restart) or at runtime (the choice is stored in
   `plugins/MCDashboard/developer.yml`):

   ```yaml
   developer:
     enabled: true
   ```

   ```
   /mcdashboard developer on
   ```

   The command writes `plugins/MCDashboard/developer.yml`, which overrides the `config.yml` value until you delete it.

2. Give the player the permission. It is off by default; operators do not get it automatically:

   ```
   /lp user Steve permission set mcdashboard.developer true
   ```

   Everyone with that permission may upload while developer mode is on; there is no per-player allow list.

3. In the app, open the server, choose "My dashboard", add tiles, then press the upload button and give the dashboard
   a name. The app shows the button only when the server allows uploads and the player has the permission. The upload
   itself also needs a licensed server or the full app, the same rule as home-screen widgets.

The server writes `plugins/MCDashboard/dashboards/dev-<player>.yml` (the file name is `dev-` plus a short prefix of the
player's UUID); uploading again replaces that file, so every developer has one dashboard. The file gets the normal
derived permission, `mcdashboard.dashboard.dev-<player>`, which is automatically granted to `mcdashboard.developer`.
That is why only developers see it: an uploaded dashboard is a draft, not a public preset. To offer it to someone
else, grant that derived permission, or rename the file and grant the new one (for example `admin.yml` ->
`mcdashboard.dashboard.admin`). Turning developer mode off stops new uploads; the dashboard stays available to the
players who hold its permission.

## Languages

The app is available in English, Dutch, French, German and Spanish and sends its UI language to the plugin as
`Accept-Language`. Translate a dashboard by replacing a text with a language map; leave it a plain string to show the
same text everywhere:

```yaml
# English, Dutch, French, German and Spanish are supported; regions such as nl-BE are ignored.
name:
  en: Server
  nl: Server
  fr: Serveur
  de: Server
  es: Servidor
description:
  en: Performance and players
  nl: Prestaties en spelers
items:
  - { placeholder: online, widget: gauge, title: { en: Players, nl: Spelers, fr: Joueurs, de: Spieler, es: Jugadores }, column: 0, row: 0, width: 2, height: 2 }
```

Set the language used when the app asks for one this dashboard does not provide in `config.yml` (it is also what older
apps and non-localised dashboards get):

```yaml
server:
  default-language: en   # en, nl, fr, de or es
```

Resolution order for every `name`, `description` and item `title`: the app's language, then `server.default-language`,
then the first language the dashboard provides. A text that is a plain string wins over the last step and is not
translated. `/v1/info` reports `defaultLanguage` and every `languages` the server publishes, so the app can tell what is
available. Nothing needs to change in `plugin.yml`: the languages live with the dashboard content, not in the plugin
descriptor.

## Background

`background:` sets a subtle pattern behind the dashboard: `grid`, `dirt`, `stone`, `planks`, `checker`, `cobble` or
`creeper`. The patterns are generated in the app (nothing is downloaded), drawn in mid grey at low opacity so they
read on every style and accent, and only appear behind the dashboard, never in the rest of the app. An unknown
name shows no background, so a server can use a pattern an older app does not have yet.

The player can override it under Settings > Dashboard background: *Server default* (the default), *None*, or any
pattern of their own.
