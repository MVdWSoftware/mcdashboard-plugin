# History: charts, persistence and the SOSA/SSN schema

The line and area widgets (and the home-screen graph) draw samples the plugin recorded. By default the last
two hours are kept in memory and are gone after a restart. Point the plugin at a database and the history
survives restarts, can span weeks, and can be queried by anything that speaks SQL.

Only PUBLIC placeholders are recorded (never per-player values, never images). Everything is configured in the
`history` section of `config.yml`.

## What is recorded

A placeholder is recorded when it has `history: <minutes>` in `placeholders.yml`, or its id matches
`history.include`. `history.exclude` always wins, also over an explicit `history`.

```yaml
history:
  include: ["tps", "ram-*"]     # * matches anything; catalog placeholders count too
  exclude: ["ram-secret"]
```

`history: <minutes>` on a placeholder is also how long it is kept (up to a year with a database). Without it,
`history.retention-days` (default 30, `0` = forever) applies; in memory the window is two hours.

## Sampling

| Setting | Default | Meaning |
| --- | --- | --- |
| `history.sampling.interval` | 30 | seconds between two samples, 10 to 86400 |
| `history-interval` (per placeholder) | | overrides the interval for that placeholder |
| `history.sampling.flush-interval` | 10 | seconds between two batched writes to the database |

A placeholder is never sampled faster than it refreshes (`min-interval`, `security.min-interval`) or faster
than every 10 seconds. At the default interval a placeholder costs about 2,900 rows a day.

## Storage

```yaml
history:
  storage:
    type: postgresql      # memory | sqlite | mysql | mariadb | postgresql
    host: db.internal
    database: mcdashboard
    user: mcdashboard
    password: "..."
    server-id: survival   # names this server in the data; servers sharing a database need different ids
```

- **sqlite** needs nothing: one file, `plugins/MCDashboard/history.db` (`storage.file`).
- **mysql / mariadb / postgresql** connect to a server. Create the database and a user with the right to create
  tables; the plugin creates and keeps its own tables (all start with `mcd_`).
- `storage.url` replaces host, port and database with a complete JDBC URL (without the password).

**Drivers.** The plugin ships none. Spigot and Paper already include the SQLite and MySQL/MariaDB drivers. For
PostgreSQL (or any other driver) put the driver jar, such as `postgresql-42.7.4.jar`, in
`plugins/MCDashboard/drivers/`; the plugin loads it from there. Without it the plugin logs which driver is
missing and keeps history in memory.

**Failure behaviour.** Samples are written in batches on their own thread, never on the server thread. When the
database is unreachable the plugin keeps recording in memory (at most 50,000 samples), serves charts from memory,
warns at most every five minutes and writes what it kept once the database is back.

**Pruning.** Samples older than their retention are deleted every hour. Sensors you stop recording keep their
rows until you delete them.

## The data model

The schema follows the W3C [SOSA/SSN](https://www.w3.org/TR/vocab-ssn/) ontology: the server is a
`sosa:Platform`, every recorded placeholder is a `sosa:Sensor` hosted by it that observes one
`sosa:ObservableProperty` of the server (`sosa:FeatureOfInterest`) through a `sosa:Procedure` (the sampling
settings), and every sample is a `sosa:Observation`. Sensors are `ssn:System`s and share a supertype table that
carries their identity, so other kinds of system can join later.

| Table | Ontology term | Holds |
| --- | --- | --- |
| `mcd_platform` | `sosa:Platform` | the Minecraft server (`uri`, `label`) |
| `mcd_feature_of_interest` | `sosa:FeatureOfInterest` | what is observed: the same server |
| `mcd_observable_property` | `sosa:ObservableProperty` | the placeholder's property: `label`, `unit` (`qudt:unit`), `feature_of_interest_id` (`ssn:isPropertyOf`) |
| `mcd_procedure` | `sosa:Procedure` | the sampling: `sampling_interval_s` (`mcd:samplingInterval`) |
| `mcd_system` | `ssn:System` | identity (`uri`, `label`) shared by all systems |
| `mcd_sensor` | `sosa:Sensor` | `system_id` (the system it is), `platform_id` (`sosa:isHostedBy`), `observed_property_id` (`sosa:observes`), `procedure_id` (`ssn:implements`) |
| `mcd_observation` | `sosa:Observation` | `sensor_id` (`sosa:madeBySensor`), `result_time` (`sosa:resultTime`), `result_numeric` / `result_text` (`sosa:hasSimpleResult`) |
| `mcd_schema` | | the schema version |

Every table, column and foreign key carries the ontology term it maps to: `COMMENT ON` in PostgreSQL, a column
and table `COMMENT` in MySQL and MariaDB. SQLite has no comments; this table is the reference.

- An observation does not repeat its observed property, feature of interest or procedure: they follow from its
  sensor. Its `sosa:phenomenonTime` equals its `sosa:resultTime`, because a sample is instantaneous.
- `result_time` is unix time in milliseconds. A value that is a number (after dropping colour codes, a leading `*`
  or a trailing `%`) is stored in `result_numeric`, anything else (`true`, a name) in `result_text`.
- Resource identifiers are minted under `history.storage.base-uri` (default `urn:mcdashboard:<server-id>`):
  the sensor of `tps` is `urn:mcdashboard:survival:sensor:tps`, its property `...:property:tps`. Set the base to
  an IRI you control to publish the data as linked data.

Example: the last hour of one sensor.

```sql
SELECT o.result_time, o.result_numeric
FROM mcd_observation o
JOIN mcd_sensor se ON se.system_id = o.sensor_id
JOIN mcd_system sy ON sy.id = se.system_id
WHERE sy.uri = 'urn:mcdashboard:survival:sensor:tps'
  AND o.result_time >= (EXTRACT(EPOCH FROM now()) - 3600) * 1000   -- PostgreSQL
ORDER BY o.result_time;
```

## What the app gets

`GET /v1/history/{id}?since=<unix seconds>&points=<n>` ([openapi.yaml](../openapi.yaml)). With a database the
range can be long, so the server reduces it: it splits the range into at most `points` (2 to 2000, default 2000)
equal time buckets and returns the average of the numbers in each (for text, the last value), stamped with the
time of the bucket's last sample. The app asks for 150 points.
