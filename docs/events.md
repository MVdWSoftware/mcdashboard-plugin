# Device events

When a device is revoked (in game or from the app) the plugin itself refuses its tokens at once. A reverse proxy
that caches per-player answers (such as [MCDashboard-proxy](https://github.com/MVdWSoftware/MCDashboard-proxy)) has to be
told, or it would keep serving that device from its cache until the entry expires. The plugin tells it in two ways;
use either or both. Both are off until configured, and neither carries anything but ids.

```yaml
events:
  token: "16-or-more-random-characters"      # enables the stream below
  webhooks:                                  # optional, one entry per receiver
    - url: "https://proxy.example.com:9191/hooks/mcdashboard"
      secret: "another-16-or-more-random-characters"
```

## The event

Each event identifies the revoked device and carries an id made of the plugin run and a sequence number within it,
so a receiver can order events and detect gaps.

## Stream (recommended)

With `events.token` set, the plugin serves a server-sent event stream that a proxy can subscribe to. It replays
events missed while disconnected (a bounded history is kept), and when replay is impossible it signals a reset, so
the consumer assumes it missed revocations and drops what it holds per player.

The token opens only this stream; it is not a device token and grants no API access. Keep the port reachable only
by the proxy.

## Webhooks

Every event is also POSTed to each configured receiver as JSON, signed with the receiver's `secret` so it can be
verified. Answer `2xx` once the event is stored. Anything else (or no answer within 5 seconds) is retried with
backoff, then dropped with a warning in the console, so a receiver must be idempotent and the delivery is not
guaranteed: prefer the stream, or use both. Redirects are not followed. Use HTTPS unless the receiver is on a
private network (the plugin warns otherwise).

## Live connections

The plugin closes the live-feed (WebSocket) connection of a revoked device itself, within a second; through the
tunnel that ends the proxy's channel as well. The events are only needed for what a proxy has cached.
