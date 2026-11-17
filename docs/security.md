# Security model

- **Off by default.** `server.enabled: false` until the owner has reviewed `placeholders.yml`.
- **Allowlist.** Only placeholders listed in `placeholders.yml` can be read; the app never sends
  a `%placeholder%` template, only an `id`, so nothing arbitrary is ever evaluated.
- **Who is the player.** Placeholders can be specific to a player, so the app has to prove it acts for that player.
  The only way in is a code the player creates themselves in game (`/mcdashboard link`, or the `[Get QR map]` button).
  The app never sends a player name: redeeming the code decides which player the device is linked to, so only someone
  who controls that account in game at that moment can pair for them. The code is shown as `ABCD-2345`, works once,
  expires after 5 minutes (`security.otp-timeout`), and a player has at most one live code. Ways to hand it to the app:
  type it, click `[Copy code]` in chat, scan the QR map (a map item that only its owner sees; everyone else sees a
  blank map), or open the `mcdashboard://connect…` link when `server.public-url` is set. Because the map contains a
  pairing secret it is deliberately ephemeral: it self-destructs after at most five minutes, as soon as the code is
  redeemed, or as soon as it leaves its owner's inventory (dropped, stored in a chest or container, moved by a
  hopper, placed in an item frame, lost on death, or left behind on logout).
- **In-game notice.** When a device is linked the player gets a chat message with the device name and
  `/mcdashboard revoke <id>`, so a code that was shared or shoulder-surfed does not stay unnoticed.
- **Tokens.** Redeeming a code creates a device with a JWT access token (15 min) and a rotating refresh token. Reusing
  an old refresh token fails, which reveals a stolen token on next use.
- **Revocation.** `/mcdashboard revoke <id>` (or from the app); access tokens of a
  removed device are refused immediately and its open live-feed connection is closed within a second. A caching proxy learns of it through the [device events](events.md)
  (stream or signed webhook), both off until `events.*` is configured.
- **PLAYER placeholders** resolve for the paired player only, never for a player named by the client.
- **Brute force.** Pairing and refresh are rate limited per client address (10 per minute).
- **Tunnel.** The optional [reverse tunnel](tunnel.md) is outbound only and needs a 32+ character key; the plugin refuses
  `ws://` unless the host is `localhost` or a private IP address (a host name never counts as private) and answers only
  the API routes it is meant to (not the events stream) over it.
- **Device names** are shown in chat, so colour codes and control characters are removed from them when a device pairs.
- **Device list.** `/mcdashboard devices` shows a player their devices (app, when linked, last seen) with a `[Revoke]` button.
- **Official client.** The app identifies itself with `User-Agent: MCDashboard/<x.y.z> (android|ios)`. Every route except
  the discovery endpoint (and the proxy's event stream, which has its own token) and the live feed refuse any other User-Agent with
  `403`; a nonmatching client the owner trusts is allowed with `security.allowed-clients` (regular expressions matched
  against the whole header). `security.min-client-version` answers `426` to older apps (and is published as `minClientVersion`
  ), so a release with a security fix can be required. The plugin records the client each device paired with;
  `/mcdashboard devices` shows it. **A User-Agent is identification, not authentication**: a custom client can send the same
  string. It tells you who is calling and lets you retire old versions, and it keeps casual third-party use out; it does not
  stop a determined one.
- **Request limits.** `security.requests-per-minute` (default 120) applies per paired device and
  `security.anonymous-requests-per-minute` (default 60) per address for everything that is not paired, answered with `429` and
  `Retry-After`. They key on the device, not on the User-Agent or address, so a client that spoofs or rotates those gets no extra
  budget; getting more devices needs a pairing code from a player in game, bounded by `security.max-devices`. The discovery endpoint is not
  counted.
- **Transport.** Use TLS via a reverse proxy ([reverse-proxy.md](reverse-proxy.md)). Tokens are
  bearer tokens: without TLS they can be stolen.
- **Secret.** `plugins/MCDashboard/secret.key` signs tokens (HS256). Deleting it invalidates all
  tokens; do not share or commit it.
- **No dependencies.** The jar contains only the plugin's own classes.
- **Offline-mode servers.** On a server with `online-mode=false` anyone can join under any name, so the in-game proof is
  only as strong as the server's own login. Run the server in online mode (or behind an authenticating proxy) if
  player data matters.
