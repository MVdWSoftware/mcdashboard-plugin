---
'mcdashboard-plugin': minor
---

Push notifications for licensed servers (docs/push.md). A paired device can ask the server to watch threshold alerts (`PUT /v1/devices/self/push`); when one fires the plugin sends it, end-to-end encrypted, through the portal to the phone, also while the app is closed. The `push` feature in `/v1/info` is advertised only for a licensed server, so older apps and unlicensed servers see no change. New option `push.enabled` (default `true`); bindings live in `push.json`, `devices.json` keeps its format.
