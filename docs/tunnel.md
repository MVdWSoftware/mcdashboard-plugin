# Tunnel and endpoint discovery

Two features make a proxy easy to use without the player ever entering the proxy's address or the server opening ports.

## Endpoint discovery

Players enter (or scan) the **server's** address. The app asks the server where to really connect:

```yaml
server:
  endpoints: ["https://proxy.example.com"]   # best first
```

The app switches to the first endpoint that answers as an MCDashboard server and remembers the address it started from, so it
finds the server again if the endpoint changes (the proxy moved). Endpoints follow the same rules as typed addresses: plain HTTP
only on a local network, other schemes are ignored, at most four are tried. If none answers, the original address stays in use.

`server.discovery-only: true` makes the plugin's own port answer **only** the discovery request to anyone but a trusted peer
(`security.trusted-proxies`), so the full API is reachable only through the proxy or the tunnel.

## Reverse tunnel to MCDashboard-proxy

With `tunnel.enabled` the plugin dials out to the proxy over **one WebSocket**. REST requests, live-feed streams and device
events all use that connection; the proxy never connects to the server and device events need no `events.token` or webhook.

```yaml
tunnel:
  enabled: true
  url: "wss://proxy.example.com/tunnel"
  key: "<32+ random characters, the proxy's TUNNEL_TOKEN>"
```

Security: the connection is outbound only and authenticated by the key (`Authorization: Bearer`, never in the URL); the proxy
can additionally restrict who may connect by address (`TUNNEL_ALLOWED_IPS`) and rate limits attempts. The plugin refuses
a key shorter than 32 characters and `ws://` to anything but `localhost` or a private IP address (unless
`tunnel.allow-insecure`; a host name never counts as private), never logs the key, and over the
tunnel answers only the API routes it is meant to (never the events stream) and its own live port. The tunnel's address is
added to `security.trusted-proxies` automatically (loopback) so the client address the proxy forwards is used for rate limiting.

Health: the plugin pings the proxy every 20 s, drops a connection that has been silent for 60 s or that never answers `ready`,
and reconnects with exponential backoff (1 s to 60 s, forever). The backoff resets only after a connection has stayed up
30 s, so a flapping proxy cannot cause a reconnect storm. An outage is logged once, not on every attempt. `/mcdashboard tunnel`
(permission `mcdashboard.admin`) shows whether it is connected, the last error, the attempt number and the ping.

Protocol and the proxy's settings: [MCDashboard-proxy `docs/tunnel.md`](https://git.mvdw-software.com/mcdashboard/mcdashboard-proxy).

## With TLS

When `server.tls` is on and the tunnel is enabled, the plugin also opens plain listeners on `127.0.0.1` on free ports for the tunnel
client only; the proxy terminates TLS towards the app. Nothing outside the machine can reach them.
