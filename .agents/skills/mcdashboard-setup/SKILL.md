---
name: MCDashboard setup
description: Install, enable, expose and pair the MCDashboard plugin on Spigot/Paper, PowerNukkitX (Bedrock), Velocity or BungeeCord/Waterfall, including the TLS and port choice. Use when someone asks to install, enable, pair, reopen, upgrade or expose MCDashboard, or says the MCDashboard app cannot find or connect to the server.
license: AGPL-3.0-or-later
---

# MCDashboard setup

MCDashboard publishes an allowlist of PlaceholderAPI values over a small HTTP(S) API plus a live WebSocket feed; the
MCDashboard app (Android/iOS) draws them as dashboards. The app is a separate, closed product: this repository
is only the server side. Nothing is served until `server.enabled: true`.

The plugin writes its data folder on the first start:

| Path (inside `plugins/MCDashboard/`) | What it is |
| --- | --- |
| `config.yml` | every setting; **only this file needs a restart** |
| `placeholders.yml` | the allowlist of values the app may read, re-read at runtime |
| `dashboards/*.yml` | preset dashboards, re-read; a new file registers its permission on the next start |
| `secret.key`, `devices.json`, `tls/` | token signing key, paired devices, generated certificate; never share these |
| `.agents/skills/` | these instructions, refreshed on every start (a changed file is replaced) |

On Velocity the folder is `plugins/mcdashboard/` (the plugin id is lowercase). On BungeeCord and PowerNukkitX it is
`plugins/MCDashboard/`, as on Spigot.

## Install and enable

1. Spigot/Paper 1.18+: install PlaceholderAPI and the expansions whose values should be shown. PowerNukkitX: install
   the PowerNukkitX PlaceholderAPI bundle. Velocity/BungeeCord: no PlaceholderAPI exists there, see the networks skill.
2. Put the jar for the platform in `plugins/`: `MCDashboard.jar`, `MCDashboard-Nukkit.jar`,
   `MCDashboard-Velocity.jar` or `MCDashboard-Bungee.jar`.
3. Start once and stop. The first start writes `config.yml`, `placeholders.yml`, `dashboards/` and this folder.
4. Decide what may be shown first (`placeholders.yml`, see the placeholders skill), then edit at least:

   ```yaml
   server:
     enabled: true
     name: "Survival"
     public-url: "https://mc.example.com"   # what players type or scan
   ```

5. Choose a transport (below), open the ports in the firewall (API `1553`, live feed `1554` by default) or put a
   reverse proxy or tunnel in front, and restart.
6. In game, as the player the device belongs to: `/mcdashboard link`. Enter the code (`ABCD-2345`, valid five minutes,
   one use) and the address in the app. With `server.public-url` set the command also prints a `mcdashboard://` link,
   and `/mcdashboard qr` hands out a QR map (Spigot only, the map works for its owner only).

Every player pairs; `security.require-authentication` is on by default and should stay that way. Do not enable
`security.allow-player-names` on an internet-reachable server: it replaces pairing with a typed name, which nobody
verifies, so anyone could read any player's data.

## Transport: pick one

| Situation | Setting |
| --- | --- |
| Public address, no reverse proxy | `server.tls.mode: self-signed` (the default). The app pins the SHA-256 fingerprint the pairing link carries (`pin=`); adding a name to `tls.names` regenerates the certificate, so devices pair again. |
| Own certificate, e.g. Let's Encrypt | `tls.mode: custom` with `tls.certificate` + `tls.key` (PEM chain and an unencrypted PKCS#8 key) or `tls.keystore` + password. |
| Reverse proxy on the same machine terminates TLS | `tls.mode: off`, `server.host: 127.0.0.1`, and the proxy in `security.trusted-proxies`. Plain HTTP must not leave the machine. |
| MCDashboard-proxy or no inbound port | `server.endpoints` plus the tunnel; see the networks skill. |

TLS never silently falls back to plain HTTP: when the certificate cannot be loaded the API stays down, deliberately.

## Verify

From outside the network:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://mc.example.com:1553/v1/info
```

Any HTTP answer means DNS, firewall and certificate are fine; a timeout is the network, not the app. In game:
`/mcdashboard about` shows the version and the addresses the app should use, `/mcdashboard devices` shows whether the
phone paired. The discovery endpoint `/v1/info` answers without a token, so a browser or uptime check works too.

## Do not

- Do not set `security.require-authentication: false` on an internet-reachable server to fix pairing: it lets anyone
  who can reach the port read every PUBLIC placeholder. Fix the code or the clock instead.
- Do not copy `config.yml`, `secret.key` or `devices.json` between servers. A new `secret.key` invalidates every device,
  which is also the recovery from a leaked one.
- Do not delete the data folder to upgrade. Defaults are only written when missing, so an upgrade keeps the owner's
  files (and refreshes this folder).

## Read more

- Install, TLS and reverse proxy: https://mcdashboard.org/en/docs/server/installation/
- Security, pairing and revocation: https://mcdashboard.org/en/docs/server/security/
- Every setting, command and permission: https://mcdashboard.org/en/docs/server/configuration/
- Networks (Velocity, BungeeCord): https://mcdashboard.org/en/docs/server/networks/
- Bedrock: the same pages, with PowerNukkitX names and no QR map
- Why it does not work: https://mcdashboard.org/en/docs/troubleshooting/
