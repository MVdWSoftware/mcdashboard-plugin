# Client verification and background updates

## What can and cannot be verified

Every request carries a `User-Agent`, but anyone can copy it, so it identifies a client without proving anything. Software on a
device the user controls can never be proven unmodified by the software itself. What can be proven is what a party **you
trust** saw. Verification therefore works through one service, the MVdW attestation service, and one token format, whatever the
client is:

1. A client proves itself to the service. The proof depends on the kind of client:
   - an Android or iOS app: the platform's own attestation (Play Integrity, App Attest), which says that this is an unmodified build
     signed by MVdW, on a genuine device. Level `device`.
   - a server-side client (a website or another service): a secret that the site registered with MVdW. Level `registered`. This proves who
     registered, not that the code is unmodified; for code that runs on a machine its operator controls that is all a secret can prove.
2. The service answers with a **client assertion**: an ES256 token that names the kind of client, its version and the host of your
   server, and lives for one hour.
3. The client shows the assertion to your plugin (header `X-MCDashboard-Attestation`; in the live feed's `auth` message, because a
   browser WebSocket cannot set headers). The plugin checks the signature (and, for an assertion with a `lid` claim, that it is for this server's `license.id`) with the service's public key, offline. It never contacts
   the service, and the service never sees your players.

A modified app cannot get an assertion, because the platform no longer vouches for it, so it cannot talk to a server that requires
one. Because the token format is the same for every client, any other client is protected by the same rule
without new code in the plugin.

### Assertions for one licensed server (`lid`)

The service issues assertions to server-side clients (a website or another service) only for hosts of licensed servers, and then adds a `lid`
claim: the licence id of that server. With `mode: required` the plugin accepts an assertion that carries `lid` only when it equals this
server's own `license.id`; a server without `license.id` accepts none of them. Assertions without `lid`, such as those issued to the
official app, stay valid as before. The comparison is offline, nothing is sent anywhere. It stops a client that is vouched for on one
licensed server from being shown to another server of the same host.

What it does not do: an assertion can be replayed by whoever steals it until it expires (an hour), and only for your host. It is not
bound to a key held by the client. It does not make a client harmless: the per-device rate limits still apply to verified clients.

## Turning it on

```yaml
security:
  client-verification:
    mode: required        # off (default) or required
    min-level: registered # device: platform-attested apps only
    kinds: []             # e.g. [android, ios] to refuse other kinds
    hosts: []             # empty: the host the client connected to
    keys: []              # extra trusted public keys (JWK)
```

With `required`, every request except `GET /v1/info` (discovery) needs a valid assertion, including pairing and the live feed.
`/v1/info` lists `client-verification` in `features`, so a client knows to bring one. Clients that cannot get an assertion are refused
with `403 client verification required`; apps that do not know the feature are refused the same way, so turn it on once most players
run a current app (`security.min-client-version` helps). If no public key is trusted, every client is refused and the log says so.

Behind a proxy that rewrites the `Host` header, set `hosts` to the name players connect to.

## Background updates

The app's notifications and home-screen widgets refresh while the app is closed or in the background, which asks more of your
server. Those requests carry `X-MCDashboard-Purpose: background`.

```yaml
security:
  background:
    enabled: true             # false: refused with 403, and the app hides the feature for this server
    requests-per-minute: 30   # per paired device, on top of requests-per-minute
```

`/v1/info` lists `background` in `features` while it is on, and the app reads that before offering alerts for the server. The header is
declared by the client: the official app always sets it, and `client-verification: required` keeps clients that do not out.
The per-device limit (`requests-per-minute`) applies to everything regardless of the header.
