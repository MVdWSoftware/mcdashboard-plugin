# Changelog

All notable changes are documented here. The format follows [Keep a Changelog](https://keepachangelog.com/)
and the project uses [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- The plugin is open source under the MIT licence, with its API contract ([openapi.yaml](openapi.yaml)) and
  server-owner documentation in this repository.
- Persistent history: samples can be stored in SQLite, MySQL/MariaDB or PostgreSQL using a schema based on the
  W3C SOSA/SSN ontology (every table, column and foreign key documents its ontology term), with configurable
  sampling (`history.sampling`, `history-interval`), retention (`history.retention-days`) and include/exclude lists.
  Drivers are not shipped: the server's own SQLite and MySQL drivers are used, others load from `drivers/`.
  See [docs/history.md](docs/history.md).
- `GET /v1/history/{id}` takes `points` (2 to 2000) to reduce long ranges on the server.
- `placeholders.yml`: `history` can be up to a year (was 24 hours); `history-interval` sets the sampling per placeholder.

### Changed
- History is sampled every 30 s by default (was every 10 s); set `history.sampling.interval: 10` for the old rate.
