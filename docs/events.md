# Device events

When a device is revoked (`/mcdashboard revoke <id>`, or the app unpairing itself with `DELETE /v1/devices/self`) the
plugin itself refuses its tokens at once. A reverse proxy that caches per-player answers (such as
[MCDashboard-proxy](https://github.com/MVdWSoftware/MCDashboard-proxy)) has to be told, or it would keep serving
that device from its cache until the entry expires. The plugin tells it in two ways; use either or both. Both are off
until configured, and neither carries anything but ids.

```yaml
events:
  token: "16-or-more-random-characters"      # enables the stream below
  webhooks:                                  # optional, one entry per receiver
    - url: "https://proxy.example.com:9191/hooks/mcdashboard"
      secret: "another-16-or-more-random-characters"
```

## The event

```json
{ "type": "device.revoked", "epoch": "a1b2c3d4", "seq": 7, "time": 1700000000, "deviceId": "9f2c...", "player": "uuid" }
```

`deviceId` is the `sub` of the device's tokens. `epoch` identifies one run of the plugin and `seq` counts within it, so
`epoch:seq` is the event id. The schema is `DeviceRevokedEvent` in [`openapi.yaml`](../openapi.yaml).

## Stream (recommended)

`GET /v1/events` with `Authorization: Bearer <events.token>` returns `text/event-stream`. The endpoint does not exist while
`events.token` is empty. Each event is sent as `id: <epoch>:<seq>`, `event: device.revoked`, `data: <json>`, and a `: ping`
comment every 15 seconds keeps the connection alive. At most 16 streams are open at once.

Unlike a webhook the stream **resumes**: after a reconnect send the last id as `Last-Event-ID` and everything missed is replayed
(the last 256 events are kept). When that is not possible (the plugin restarted, or the history moved on) the stream starts
with `event: reset`, and the consumer must assume it missed revocations (a cache drops what it holds per player).

The token opens only this stream; it is not a device token and grants no API access. Keep the port reachable only by the proxy.

## Webhooks

Every event is sent as `POST <url>` with `Content-Type: application/json` and these headers:

| Header | Value |
| --- | --- |
| `X-MCDashboard-Event` | `device.revoked` |
| `X-MCDashboard-Delivery` | the event id, `<epoch>:<seq>` |
| `X-MCDashboard-Timestamp` | unix seconds |
| `X-MCDashboard-Signature` | `sha256=` + hex HMAC-SHA256 of `<timestamp>.<raw body>`, keyed with the webhook's `secret` |

Answer `2xx` once the event is stored. Anything else (or no answer within 5 seconds) is retried after 1 s, 5 s, 30 s,
2 min and 10 min, then dropped with a warning in the console, so a receiver must be idempotent and the delivery is not
guaranteed: prefer the stream, or use both. Verify the signature in constant time and reject timestamps more than a few
minutes old. Redirects are not followed. Use HTTPS unless the receiver is on a private network (the plugin warns otherwise).

Test vector: secret `0123456789abcdef-secret`, timestamp `1700000000` and the body
`{"type":"device.revoked","epoch":"a1b2c3d4","seq":1,"time":1700000000,"deviceId":"d1","player":"p1"}` give
`sha256=3c3de4487ff7c14502e2546a8887a60bf3c93e72e75887eb89aff022e1eb4a11`.

## Not covered

Open live-feed (WebSocket) connections of a revoked device are not closed by these events.
