# Reverse proxy, TLS and caching

By default the plugin serves HTTPS itself on `server.port` (1553) and the live feed on `server.websocket.port` (1554),
with a self-signed or your own certificate (`server.tls`). A reverse proxy adds a trusted certificate (Let's Encrypt), port
443 and, optionally, a cache. When the proxy terminates TLS and talks to the plugin on the same machine, set
`server.tls.mode: off` and `server.host: 127.0.0.1`, so plain HTTP never leaves the machine. The API is designed for this,
using only standard HTTP:

| Mechanism | Standard | What it gives a proxy |
| --- | --- | --- |
| Sensor data as SenML | RFC 8428, `application/senml+json` | Readable with standard HTTP tooling |
| `Cache-Control: public, max-age=N` | RFC 9111 | N is the caller's effective `min-interval` (including permission overrides), so a cached copy is never older than allowed |
| `ETag` + `If-None-Match` | RFC 9110 | Cheap revalidation (`304`) |
| `Vary: Authorization` | RFC 9110 | Per-player answers are `private` and never shared between players |
| JWT bearer tokens | RFC 7519 / 6750 | Access tokens are stateless |
| `X-Forwarded-For` | de-facto | Rate limiting sees the real client when the proxy is in `security.trusted-proxies` |

The plugin only accepts the official app's `User-Agent` ([security.md](security.md)): a proxy must pass `User-Agent` through
(nginx and Apache do by default) and must not replace it. A response served from a shared cache never reaches the plugin, so the
check is not applied to it. The live feed and the discovery endpoint follow the same rules (discovery stays open).

Only responses for PUBLIC placeholders without an `Authorization` header are `public`, so with
`security.require-authentication` on (the default) nothing is shared-cacheable. Where it is, PlaceholderAPI is hit at most
once per `min-interval` however many clients poll.

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

For public servers there is a purpose-built, open source (AGPL) proxy: [MCDashboard-proxy](https://github.com/MVdWSoftware/mcdashboard-proxy).
It speaks the same API and adds a shared Redis cache, request coalescing, stale-if-error, rate limiting, request
validation and optional token pre-checks. It follows device revocations through the [device events](events.md), and can be reached through a
[reverse tunnel](tunnel.md) so the server needs no inbound port for the API. Put it behind your TLS terminator instead of a plain nginx cache.

## Apache

See [`examples/apache.vhost`](examples/apache.vhost); enable `mod_proxy`, `mod_proxy_wstunnel` and `mod_cache`.
