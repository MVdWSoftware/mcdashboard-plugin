# Map pictures (`map:` images)

An `IMAGE` placeholder can show a piece of your web map: the plugin downloads the tiles of
[dynmap](https://github.com/webbukkit/dynmap), [squaremap](https://github.com/jpenilla/squaremap),
[Pl3xMap](https://github.com/granny/Pl3xMap) or [BlueMap](https://github.com/BlueMap-Minecraft/BlueMap) from the map's
own web server, puts them together into one square picture centred on a block and sends that to the app like any other
image. The map plugin does not have to run on the same server, and MCDashboard has no dependency on it: it only reads
tiles over HTTP, the way a browser does.

```yaml
placeholders:
  - id: spawn-map
    name: Spawn
    type: IMAGE
    source: map:squaremap          # dynmap | squaremap | pl3xmap | bluemap
    map:
      url: http://localhost:8080   # the map's web address (fixed: no placeholders)
      world: minecraft_overworld   # the world as the map names it (see below)
      x: 0                         # block X to centre on
      z: 0                         # block Z
      zoom: 0                      # 0 = the most detailed level; 1, 2, ... show more area
      size: 256                    # picture size in pixels, 64-512
  - id: my-map
    name: Around you
    type: IMAGE
    access: PLAYER
    source: map:dynmap
    map:
      url: https://map.example.com
      world: world
      map: flat
      x: '%player_x%'              # placeholders are expanded for the paired player
      z: '%player_z%'
      size: 256
```

## Keys

| Key | Default | Meaning |
| --- | --- | --- |
| `url` | required | The map's web root, `http://` or `https://`, a fixed host with an optional port and path. No placeholders, query or user name. |
| `world` | required (BlueMap: see `map`) | The world's folder in the map's tile URLs. |
| `map` | required for dynmap, Pl3xMap and BlueMap | dynmap: the tile prefix of the map. Pl3xMap: the renderer. BlueMap: the map id. Ignored by squaremap. |
| `x`, `z` | required | The block to centre on: a number, or a PlaceholderAPI template such as `'%player_x%'`. |
| `zoom` | `0` | `0` is the most detailed level the map renders; every step out shows a larger area (see the table below). |
| `size` | `256` | Width and height of the picture in pixels, 64 to 512. |
| `max-zoom` | per provider | The highest `zoom` you may use. squaremap needs it to build the URL: set it to `map.zoom.maximum` if you changed that (default 3). |
| `format` | `png` | dynmap: `png` or `jpg` (its `image-format`). Pl3xMap: `png`, `jpg`, `jpeg` or `gif` (its `tile-format`). |
| `perspective` | `iso_S_90_lowres` | dynmap only: the perspective of the map (see below). |
| `y` | `64` | dynmap only: the height an isometric picture is centred on. |

An invalid block is reported in the console and the entry is skipped, like any other mistake in `placeholders.yml`.

## Providers

| Provider | Tile URL | Tile | `zoom: 0` | Each step out |
| --- | --- | --- | --- | --- |
| squaremap | `tiles/<world>/<level>/<x>_<z>.png` | 512 px | 1 pixel per block | 2 times more blocks per pixel |
| Pl3xMap | `tiles/<world>/<zoom>/<renderer>/<x>_<z>.<format>` | 512 px | 1 pixel per block | 2 times |
| BlueMap | `maps/<map>/tiles/<lod>/x…/z….png` (lowres) | 500 px | 1 pixel per block (lod 1) | 5 times (lod 2, 3) |
| dynmap | `tiles/<world>/<prefix>/<x/32>_<y/32>/<z…_><x>_<y>.png` | 128 px | the perspective's resolution, e.g. 4 pixels per block for `lowres` | 2 times |

North is up and east is right on every top-down map. A `size: 256` picture at `zoom: 0` therefore shows 256 x 256
blocks on squaremap, Pl3xMap and BlueMap, and 64 x 64 blocks on a dynmap `lowres` flat map.

**squaremap.** `world` is the dimension key with `:` replaced by `_`: `minecraft_overworld`, `minecraft_the_nether`,
`minecraft_the_end`. When unsure, open the map in a browser and look at a tile URL in the developer tools.

**Pl3xMap.** `world` is the world's folder under `tiles/` (the world name; `:` becomes `-`), `map` the renderer key from
the world's `render.renderers` (`vintage_story` by default, or `basic`, `biomes`, `night`, ...).

**BlueMap.** Only the low-resolution tiles are PNG pictures (the detailed ones are 3D models), so the picture is BlueMap's
flat top view without its lighting and shading. `map` is the map id from BlueMap's `maps/` config folder (`world`,
`world_nether`, ...); `world` is used when `map` is missing. The map's default `lowres` settings are assumed
(`tile-size: 500`, `lod-factor: 5`); `zoom` 0 to 2 match the default three levels of detail.

**dynmap.** Every HD map works (dynmap 3 has no other kind): `map` is the map's **prefix**, which is not always its name.
In the default templates `flat` has prefix `flat`, `surface` has `t` and `cave` has `ct`. `perspective` must be the one the
map uses in `worlds.txt` or its template, because it decides where a block is on the tiles:

| Template (`deftemplatesuffix`) | `flat` | `surface` and `cave` |
| --- | --- | --- |
| `vlowres` | `iso_S_90_vlowres` | `iso_SE_60_vlowres` |
| `lowres` | `iso_S_90_lowres` | `iso_SE_60_lowres` |
| `hires` (the default) | `iso_S_90_lowres` | surface `iso_SE_30_hires`, cave `iso_SE_60_lowres` |

Any standard perspective name works: `iso_<N|NE|E|SE|S|SW|W|NW>_<inclination 20-90>_<vlowres|lowres|medres|hires>`, and
`default`. Custom perspectives (`custom-perspectives.txt`) and maps with a `tilescale` other than 0 are not supported. An
isometric map (inclination below 90, such as `surface`) works too: the picture is centred on the point of the block at
height `y` (default 64), so it is accurate for terrain around that height. Night and day maps: use the `_day` suffix in
`map` (e.g. `t_day`) for the day tiles. dynmap must serve its tiles under `<url>/tiles/`: its internal web server does
(any storage), as does a web server in front of the file tree storage. SQL storage served through the standalone PHP
files is not supported.

## Fetching, caching and failures

- Tiles are fetched with the same rules as `url:` images: http(s) only, the host is the fixed one in `url`, a 5 second
  timeout per tile, off the server's main thread, all tiles of one picture in parallel (at most about 10 seconds). A tile
  can be up to 1 MB (map tiles are larger than what the app gets); the finished picture is a PNG, or a JPEG when the PNG
  would be over 256 KB.
- A missing tile (404, not rendered yet, or a format Java cannot read, such as WebP) is left transparent. Only when
  no tile at all can be read is the picture unavailable; then the last good picture keeps being served.
- Pictures and tiles are cached for at least 5 minutes (longer with a larger `min-interval`). With placeholders in `x`
  or `z` each position gets its own picture, so players standing in the same block share one; with `access: PLAYER`
  each player's last good picture is kept separately.
- The load on the map server is bounded by the cache: one picture costs at most 25 tile requests per 5 minutes (512 px
  from 128 px dynmap tiles), or 4 with the 512 px tiles of squaremap and Pl3xMap.

## How the coordinates were verified

Each provider's URL scheme and block-to-tile mathematics was taken from the map plugin's own source code (links are in
the comments of `core/src/main/java/org/mcdashboard/image/map/`): squaremap's `LayerControl.js`, `TileImages` and
`RegionImage`; Pl3xMap's `ReversedZoomTileLayer.ts` and `TileImage`; BlueMap's `LowresTileLoader.js`, `pathFromCoords`
and `LowresTile`; dynmap's `hdmap.js`, `IsoHDPerspective` (its `world_to_map` matrix is ported as is) and
`FileTreeMapStorage`. The tests stitch generated tiles from a local web server. Not yet checked against a live map
server; please report a picture that is shifted or mirrored.
