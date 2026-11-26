# Vendored Maven repository

The two PowerNukkitX artifacts `nukkit/` compiles against. `nukkit/pom.xml` adds this directory as a
`file://` repository, so building the Bedrock jar needs nothing from the PowerNukkitX Maven repository.

**Why.** `https://repo.powernukkitx.org/releases` has been offline since 2026-11-25 (the host answers nothing:
ports filtered, ICMP lost), and it was the only place those two artifacts were published. Checked at the time:
Maven Central and the public mirrors (TUNA, Huawei, Tencent, NetEase, Aliyun, CodeMC, EngineHub, Minebench,
OpenCollab, helpch) do not carry `org/powernukkitx`; JitPack's builds of PowerNukkitX 3.x all fail because that
build itself resolves from the offline repository; the Wayback Machine never crawled it; the older
`maven.powernukkitx.cn` Nexus serves a shutdown notice. PowerNukkitX's own CI fails with the same connect
timeout, so nothing upstream resolves either. The jars in this directory are the copies from a build that
still predates the outage: they may be the only copies left, which is why they are in version control.

## What is here

| Coordinates | Bytes | SHA-256 | Where the bytes come from |
|---|---:|---|---|
| `org.powernukkitx:server:3.0.6` | 9 788 290 | `07c74021ec87a5aa9eb81b3affbde6cca3d31947e741cdd3723ab36c0e57de59` | the timestamped snapshot `3.0.6-20261027.165713-1` that upstream published for tag `3.0.6` (commit `c57499365737654eca32df008751ef6bca5af142`, built 2026-10-27T16:57:13Z) |
| `org.powernukkitx.placeholderapi:PlaceholderAPI:1.2.4` | 11 020 | `7f2d96c6970c77346dd28e7c1d4be891d9190819d4ef030eb65b03781b97bf2f` | byte-identical to the `PlaceholderAPI.jar` asset of [PowerNukkitX-Bundle/PlaceholderAPI](https://github.com/PowerNukkitX-Bundle/PlaceholderAPI) tag `v1.2.4`, which is still downloadable |

Check them with `sha256sum -c SHA256SUMS.txt` from this directory. `PlaceholderAPI-1.2.4.jar` can be re-obtained
from that GitHub release and compared against the hash above; the `server` jar cannot be re-obtained in this
shape - the `3.0.6` GitHub release only carries the 93 MB shadow jar (`powernukkitx.jar`, SHA-256
`10822d818c1f2c7d423092a235f3f04bd0eb915b2298b204ed1697d7a0dd9539`), which contains the same classes plus every
library the server bundles.

Both are `provided`: nothing here is shaded into a MCDashboard jar, and CI fails if a published jar contains
anything under another package than `org/mcdashboard`.

## Notes on the metadata

- `server-3.0.6.pom` is the pom upstream published, with one change: its version is `3.0.6` instead of
  `3.0.6-SNAPSHOT`, because a release version does not make Maven re-check the offline repository once a day.
  The dependency list is the original one and still names snapshot artifacts from that offline repository, which
  is exactly why `nukkit/pom.xml` excludes `*:*` from it: the server provides its own libraries at runtime.
- Only Maven metadata is mirrored: there is no Gradle module (`.module`) file, so Gradle consumers should prefer
  the GitHub release assets.

## Licence

`LICENSE-powernukkitx.txt` is LGPL-3.0, the licence in the LICENSE file of
[PowerNukkitX/PowerNukkitX](https://github.com/PowerNukkitX/PowerNukkitX); the published pom claims MIT, so this
copy is kept under the more restrictive of the two. Both artifacts are redistributed unmodified, only as a
compile-time API reference, with their origin recorded here and in the pom.

The PlaceholderAPI repository states no licence at all (no LICENSE file, none in its pom); its jar is kept here
because the plugin cannot compile against the PNX placeholder API without it. If upstream ever restores the
repository or states a licence, switch `nukkit/pom.xml` back to it and delete this directory.
