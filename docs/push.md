# Push notifications

Licensed servers can push notifications to the paired devices of the MCDashboard app, also while the app is closed. The
server watches alert rules the app asked for and the notification travels through the portal to Google (Android) or Apple
(iOS). Since 1.1.0.

- **Opt-in per device and per server.** The app turns it on for one server at a time; nothing is sent to a device that did
  not ask for it.
- **Only for licensed servers.** The plugin advertises the `push` feature in `/v1/info` only while the last licence
  heartbeat said the server is licensed. Otherwise the app shows push as unavailable and keeps using its local alerts.
- **End-to-end encrypted.** The app creates a key pair per server and the plugin encrypts every notification for that
  key. The portal and the push services only carry ciphertext; the portal also never learns the device's push token
  from the plugin and the plugin never learns it from the portal.
- **Protected against overload.** The portal limits notifications per device, per phone and per server, and the plugin
  never fires a rule more than once per episode (the value has to recover first) or more often than its cooldown (at
  least 60 seconds).

Configuration: [`push.enabled`](configuration.md#push). Without a licence, or with `push.enabled: false`, the plugin
creates nothing and sends nothing. The data is kept in `plugins/MCDashboard/push.json`, a separate file, so
`devices.json` keeps its 1.0 format and a server can move between plugin versions.

## How it works

1. The app registers once with the portal and gets an installation. For each server it asks the portal for a **route**
   (`licenseHash` from `/v1/info`); one installation therefore serves any number of servers.
2. The app calls `PUT /v1/devices/self/push` with the route token, the public key it made for this server and its rules.
3. Every 5 seconds the plugin's sampler evaluates the rules. A rule is `placeholder op threshold` that has to hold for
   `forSeconds`. It can only watch a placeholder the device's player may read.
4. When a rule fires, the plugin renders the app's `title` and `body` templates (`{label}`, `{value}`, `{server}`),
   encrypts them and posts them to `POST {license.api-url}/v1/push/send` with the licence id as bearer credential.
5. The portal answers per route: `ok`, `throttled`, `muted`, `suspended`, `gone` or `unknown_route`. The plugin backs
   off on `throttled` and `muted`, stops offering push for a while on `suspended` or when the portal refuses the
   licence, and forgets the binding on `gone`. Revoking a device (or `DELETE /v1/devices/self/push`) revokes its route.

## Envelope (version 1)

`{ "v": 1, "epk": "...", "iv": "...", "ct": "..." }`, all base64url without padding.

- Keys: P-256. A public key is the uncompressed X9.62 point (`0x04 || X || Y`, 65 bytes).
- `epk` is a fresh ephemeral public key per message. The shared secret is the ECDH result (32 bytes).
- `prk = HMAC-SHA256(key = "mcdashboard-push-v1", data = secret)`.
- `key = HMAC-SHA256(key = prk, data = epk || recipientPublicKey || 0x01)` (32 bytes, base64url-decoded inputs).
- `ct` = AES-256-GCM (12 byte `iv`, 16 byte tag appended) of the JSON payload, with the additional authenticated data
  `routeToken + "|" + licenseHash` (UTF-8; an empty licence hash when the server has none).
- Payload: `{ "id", "ts", "title", "body", "data": { "server", "rule" } }`. The app dedupes on `id` and ignores stale `ts`.

`core/src/test/resources/push-vectors.json` holds known-answer vectors that every implementation (Java, TypeScript,
Kotlin, Swift) has to open.
