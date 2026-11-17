# Reverse proxy, TLS and caching

The plugin serves plain HTTP on `server.port` (1553) and the live feed on `server.websocket.port`
(1554). Put a reverse proxy in front for TLS and, optionally, a cache. The API is designed for
this, using only standard HTTP:

| Mechanism | Standard | What it gives a proxy |
| --- | --- | --- |
| Sensor data as SenML | RFC 8428, `application/senml+json` | Any SenML-aware tool can read the values |
| `Cache-Control: public, max-age=N` | RFC 9111 | N is the placeholder's `min-interval`, so a cached copy is never older than allowed |
| `ETag` + `If-None-Match` | RFC 9110 | Cheap revalidation (`304`) |
| `Vary: Authorization` | RFC 9110 | Per-player answers are `private` and never shared between players |
| JWT bearer tokens | RFC 7519 / 6750 | Access tokens are stateless and can be checked by anything holding the secret |
| `X-Forwarded-For` | de-facto | Rate limiting sees the real client when the proxy is in `security.trusted-proxies` |

The plugin only accepts the official app's `User-Agent` ([security.md](security.md)): a proxy must pass `User-Agent` through
(nginx and Apache do by default) and must not replace it. A response served from a shared cache never reaches the plugin, so the
check is not applied to it; `security.require-authentication: true` makes every response `private` and keeps cached data away
from other clients. The live feed and `/v1/info` follow the same rules (`/v1/info` stays open).

Only responses for PUBLIC placeholders without any `Authorization` header are `public`. When
`security.require-authentication` is on, nothing is shared-cacheable.

## nginx (TLS + cache)

```nginx
proxy_cache_path /var/cache/nginx/mcd levels=1:2 keys_zone=mcd:5m max_size=50m inactive=10m;

server {
    listen 443 ssl http2;
    server_name mc.example.com;
    ssl_certificate     /etc/letsencrypt/live/mc.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/mc.example.com/privkey.pem;

    location /v1/live {                       # WebSocket live feed
        proxy_pass http://127.0.0.1:1554;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 120s;
    }

    location /v1/ {                           # REST, honours the plugin's Cache-Control
        proxy_pass http://127.0.0.1:1553;
        proxy_set_header X-Forwarded-For $remote_addr;   # overwrite, never append client-supplied values
        proxy_cache mcd;
        proxy_cache_key "$scheme$host$request_uri$http_authorization";
        proxy_cache_revalidate on;
        proxy_cache_use_stale error timeout updating;
        proxy_cache_lock on;                  # one request refreshes, the rest wait
    }
}
```

Set `server.host: "127.0.0.1"` so only the proxy can reach the plugin, and keep
`security.trusted-proxies` limited to the proxy's address.

## MCDashboard-proxy

For public servers there is a purpose-built, open source (MIT) proxy: [MCDashboard-proxy](https://github.com/MVdWSoftware/MCDashboard-proxy).
It speaks the same API and adds a shared Redis cache, request coalescing, stale-if-error, rate limiting, request
validation and optional token pre-checks. It follows device revocations through the [device events](events.md), so a revoked device is
refused at once even when its answers are cached. The proxy can also be reached through a [reverse tunnel](tunnel.md): the plugin dials out over one WebSocket, so the server needs no inbound port for the API. Put it behind your TLS terminator instead of a plain nginx cache.

## Apache

See [`examples/apache.vhost`](examples/apache.vhost); enable `mod_proxy`, `mod_proxy_wstunnel` and `mod_cache`.

## Why a cache helps

A busy server with many app users would otherwise resolve each placeholder per request. With the
cache in front, PlaceholderAPI is hit at most once per `min-interval` per placeholder, however many
clients poll.
