---
name: MCDashboard dashboards
description: Create or edit MCDashboard preset dashboards in dashboards/*.yml (grid, widgets, tile options, icons, translations, backgrounds, history charts) and make them visible to the right players. Use when someone asks to add a dashboard or tab, arrange tiles, add a chart or gauge, translate a dashboard, or why a dashboard or tile does not appear in the app.
license: AGPL-3.0-or-later
---

# Dashboards (`dashboards/*.yml`)

One YAML file per dashboard in `plugins/MCDashboard/dashboards/`. The file name is the id **and** the permission:
`admin.yml` needs `mcdashboard.dashboard.admin`. Files are re-read, but a new or renamed file registers its permission
only on the next start (on Velocity/BungeeCord: `/mcdashboard reload`), so restart after adding one.

The bundled `player.yml` is granted to everyone and is the one dashboard a caller may open before pairing;
`server.yml` is for operators. Every dashboard you add is off until the permission is granted.
Grant with the permissions plugin: `/lp group staff permission set mcdashboard.dashboard.admin true`.

## Shape

```yaml
name: Server
description: Performance and players
order: 20                         # optional, lower first
background: stone                 # optional pattern: grid dirt stone planks checker cobble creeper
items:
  - { placeholder: online, widget: gauge, title: Players, column: 0, row: 0, width: 2, height: 2 }
  - { placeholder: tps, widget: gauge, title: TPS, column: 2, row: 0, width: 2, height: 2 }
  - placeholder: tps
    widget: area
    title: TPS history
    column: 0
    row: 2
    width: 4
    height: 3
```

- The grid is **4 columns** wide; `column`/`row` place the tile, `width`/`height` size it (a gauge is typically 2x2).
  A tile that overlaps another or reaches past the fourth column is reported in the console when the dashboard loads;
  fix the layout in the file. An app upload with such a layout is refused with `400`.
- `placeholder` must be an `id` from `placeholders.yml`. `stacked` takes several: `placeholders: [a, b, c]`.
- Tiles whose placeholder the player may not read are dropped; a dashboard with nothing left is not offered at all.
  That is why a preset can be missing for one player and present for another.
- Gauges and rings read their maximum from `max-from` in `placeholders.yml` (e.g. `online` -> `max-players`), falling
  back to `max-value`.

## Widgets

`text`, `ticker`, `image`, `number`, `countdown`, `stat` (value and trend), `progress`, `list`, `heads`,
`leaderboard`, `status`, `gauge` (half circle), `ring`, `line`, `area`, `stacked` (several series summed), `heatmap`
(day by hour), `bar`, `pie`, `sky`. Every widget, style, accent and background is free; the limits are on counts,
history and player features, not on visualisations.

`stat`, `line`, `area`, `stacked` and `heatmap` need history for the placeholder: `history: <minutes>` in
`placeholders.yml` or the id in `history.include`. No history means an empty chart, never a broken dashboard.

## Tile options

```yaml
  - placeholder: tps
    widget: number
    title: TPS
    icon: fa6-solid:gauge-high      # pack:name, optional
    options:
      decimals: 1
      thresholds:                   # first match colours the value
        - { when: below, value: 15, color: red }
        - { when: below, value: 18, color: orange }
```

- `icon` packs bundled in the app: `fa6-solid`, `fa6-regular`, `fa6-brands`, `game-icons`, `pixelarticons`, `ion`,
  `mdi`. Browse names at https://icon-sets.iconify.design/. An unknown icon is simply not drawn.
- `options.style` for `progress`: `bar` (default), `segments`, `battery`, `bullet`, `hearts`, `hunger`, `armor`, `xp`;
  `count` 1-20; `hideValue: true` drops the number (hearts without “14”).
- `options.color` and `thresholds` colors: `accent`, `green`, `yellow`, `orange`, `red`, `purple`, `cyan`, `muted`.
  There are no hex colours: they follow the player's theme.
- Number format: `decimals` 0-4, `compact`, `unit`, `prefix`. Layout: `hideTitle`, `align` (`start`/`center`/`end`),
  `size` (`s`/`m`/`l`), `card` (`default`/`accent`/`plain`). `invertTrend: true` makes a falling value green (lag, ping).
- Images: `fit` (`contain`, `cover`, `fill`, `none`), `position` (`center`, `top`, `bottom`), `smoothing` (`auto`,
  `pixelated`, `smooth`); `padding` (`none`, `s`, `m`); `markerIcon` (`pack:name`) on `gauge`, `ring` and `progress`;
  `speed` (`slow`, `normal`, `fast`) for `ticker`.
- `options.background`: per-tile `{ color, gradient, url (https), dim 0-0.9, blur 0-20, fit cover|contain|tile }`.
- Extra widgets: `sky` (world time ticks or `HH:mm`, optional weather, optional biome as a third placeholder or the
  `biome` option (rain, snow or nothing in a storm), `style` overworld/nether/end), `ticker`, `countdown`
  (`style: seconds` for remaining seconds), `heads` (name list or `LIST` with `name`), `leaderboard` (`LIST`; `value`
  column, `count`, `decimals`, `compact`, `unit`, `prefix`). `LIST` placeholders are in the placeholders skill.
- The plugin drops unknown keys and bad values; a typo never reaches the app as an error.

## Languages

Texts may be a language map instead of a string; supported: `en`, `nl`, `fr`, `de`, `es` (regions such as `nl-BE` are
ignored):

```yaml
name:
  en: Server
  nl: Server
  fr: Serveur
items:
  - { placeholder: online, widget: gauge, title: { en: Players, nl: Spelers }, column: 0, row: 0, width: 2, height: 2 }
```

Resolution: the app's `Accept-Language`, then `server.default-language`, then the first language the dashboard has. A
plain string wins over those and is never translated. Leave a text out of one language to get the fallback.

## Background

`background:` is a generated pattern (no download). `background-image:` puts a picture behind the dashboard and wins
over it: `url` (https only), `dim` 0-0.9 (default 0.4), `blur` 0-20, `fit` `cover`/`contain`/`tile`. The app loads the
picture directly, so that host sees every player's address. Players can override the background for themselves.

## Building in the app (developer mode)

Instead of hand-written YAML, an owner can let players build a dashboard in the app: `developer.enabled: true` in
`config.yml` (or `/mcdashboard developer on`, which writes `developer.yml` and wins) plus `mcdashboard.developer`.
An upload writes `dashboards/dev-<player>.yml` with the derived permission, automatically granted to
`mcdashboard.developer`: it is a draft only developers see. Rename it and grant the new permission to publish it.
Uploading needs a licensed server or the app unlock, like home-screen widgets.

## Verify

1. The console has no `Could not read dashboards/<file>.yml` warning (a YAML error names the problem).
2. Restart after adding or renaming the file, then `GET /v1/dashboards` with the app's User-Agent and a token of that
   player shows the dashboard in the list; without the permission it is not listed.
3. In the app the dashboard is a tab next to "My dashboard"; tiles with an unavailable placeholder are absent, not
   greyed out.

## Read more

- Grid, every widget and option, translations: https://mcdashboard.org/en/docs/server/dashboards/
- History and charts (including the database schema):
  https://mcdashboard.org/en/docs/server/dashboards/#history-and-charts
- Where the placeholder ids come from: https://mcdashboard.org/en/docs/server/placeholders/
