# Versioning and compatibility

The app and the plugin are released separately, so every change must work across versions. The rules:

- **`protocol`** in `/v1/info` is the API's major version. It is bumped only for a change that would break an older app. An app that
  does not know a higher value asks its player to update. A missing value counts as 1.
- **`features`** in `/v1/info` lists the optional capabilities of this plugin (`presets`, `history-points`, `upload`, and when on, `tls`, `background`, `client-verification`, `player-names`). An
  addition is a new feature, not a new protocol version. The app gates what needs the plugin on this list and keeps working without
  it, so a new app works with an old plugin. An old app ignores features it does not know, so an old app works with a new plugin.
- **The client's version** is in every request's `User-Agent` (`MCDashboard/x.y.z (android|ios)`). The plugin refuses apps below its
  minimum with `426` and publishes the minimum as `minClientVersion`; the app also reads it from `/v1/info` and asks for an update
  before connecting.
- **Forcing an update.** A plugin release that needs a newer app sets `ClientPolicy.BUILT_IN_MIN_CLIENT` to that app version. Owners
  can raise it further with `security.min-client-version`, never lower it. The plugin never asks for a plugin update: an older plugin
  just lacks features, and the app degrades.

New response fields are additive and optional; a field is never removed or given a new meaning within a `protocol`.
Code: `ApiContext.PROTOCOL` and `FEATURES`, `ClientPolicy`, and in the app `checkCompatibility`, `hasFeature`
(`packages/protocol`).
