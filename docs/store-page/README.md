# Store page (SpigotMC)

The listing for the MCDashboard resource: [`listing.bbcode`](listing.bbcode) is the whole description, ready to
paste into the SpigotMC resource editor's BBCode mode. The images it references live in [`assets/`](assets) and are
served from <https://mcdashboard.org/assets/store/>.

| Image | Used for |
| --- | --- |
| `logo.png` | The transparent banner at the top (copied from the site's `docs/spigotmc/logo.png`). |
| `sec-*.png` | Section headers: Features, The app, Free vs paid, Getting started, About. |
| `ic-*.png` | The 40px feature tiles (live, shield, lock, chart, widgets, map, layout, network), inline before each feature line. |
| `plan-*.png` | The Free, App unlock and Server licence cards above their comparison lists. |
| `btn-*.png` | The linked buttons, three per row, FeatherBoard style. |
| `app.png` | Three app screens: dashboard, Minecraft style, edit mode. |
| `widgets.png` | Android and iOS home-screen widgets. |
| `map.png` | A live map tile from the marketing site. |
| `add-server.png` | The app's add-server sheet with a pairing code. |

## Publishing

The site is the image host: `generate.py --publish` copies `assets/*.png` into
`mcdashboard-server/docs/website/src/assets/store/`, which Eleventy serves at `/assets/store/` after the next deploy.
The paths in `listing.bbcode` assume that deploy. To host somewhere else, change the
`https://mcdashboard.org/assets/store/` prefix in the listing (one find-and-replace) and the filenames stay the same.

The SpigotMC resource icon and banner for the resource page itself (not the description) are
`mcdashboard-server/docs/spigotmc/icon-512.png`, `icon-128.png` and `logo.png`.

Cloudflare caches `/assets/store/` for four hours. When an image is replaced under the same file name, bump the
`?v=` query on its URL in the listing (the feature icons use `?v=3`) or rename the file; otherwise visitors keep the
old one. A URL that returned 404 before the deploy was live is cached too, which is why the docs button carries
`?v=1`.

## BBCode on SpigotMC

`[TABLE]` is not rendered, and `[LIST=1]` came out as a single numbered item followed by plain bullets, so the plan
rows are plain lines with `✔`/`—` markers and the install steps are plain lines with their own numbers. Each of
those lines carries its own `[SIZE]` tag, so there is no multi-line block the parser can group. Do not reintroduce
`[LIST]` or `[TABLE]` without checking the rendered resource page.

## Regenerating the images

```sh
python3 docs/store-page/generate.py            # writes assets/, reads fonts and screenshots from ../mcdashboard-server
python3 docs/store-page/generate.py --publish  # also copies to the site's assets/store/
```

It needs Pillow and a checkout of `mcdashboard-server` next to `mcdashboard-plugin`, with `pnpm install` run in
`mcdashboard-server/docs/website` (for the Tektur and Inter fonts). The app screenshots come from
`mcdashboard-server/docs/website/src/assets/media/`, the same marketing images the site uses; refresh those there
first if the app changed.

## Keeping it true

The listing describes the free and paid limits, the prices and the supported platforms, so it has to change with
them. Sources: `README.md` and `docs/` in this repository for the plugin, `mcdashboard-server/docs/website` (the
pricing page and `src/_data/site.json` for the app unlock price) and the portal's public plan list for the licence
prices. The old `mcdashboard-server/docs/spigotmc/listing.bbcode` predates the app rewrite and this page supersedes
it.

An app change is the trigger for the screenshots: recapture the site's marketing images in
`mcdashboard-server/docs/website/src/assets/media/` (see the site README), then rerun
`generate.py --publish`, so the listing never shows an older app.

The copy follows the product's own rules: themes and visualisations are never limited, nothing is deleted when a
licence lapses, and the free tier is stated plainly so it can be trusted.
