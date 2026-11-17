# Security policy

This plugin exposes server data over the network, so reports are welcome and taken seriously.

## Reporting a vulnerability

Do **not** open a public issue. Email **security@mvdw-software.com** with a description, the plugin
version, your server software and version, and steps to reproduce. You get an acknowledgement within
3 working days. Fixes are released as a patch version with a note in the [changelog](CHANGELOG.md);
reporters are credited unless they prefer otherwise.

## Supported versions

The latest minor release receives security fixes.

## Scope

In scope: reading a placeholder that is not listed in `placeholders.yml` or that needs a permission or a
paired player; evaluating a client-supplied template; bypasses of pairing (one-time codes), token
validation (only HS256 is accepted), device revocation, rate limits or the client check; leaking a
per-player value in a `public` cache response; path traversal through image sources or dashboard
uploads; credentials (the database password, the tunnel key, the events token) appearing in logs or
responses; denial of service by a single client that the documented limits should have stopped.

Out of scope: volumetric network floods (put the API behind a reverse proxy, see
[docs/reverse-proxy.md](docs/reverse-proxy.md)), misconfiguration such as exposing the API without TLS,
and vulnerabilities in Spigot, PlaceholderAPI or an expansion (report those upstream).

The [security model](docs/security.md) describes what the plugin does and does not protect.
