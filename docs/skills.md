# Configuration skills (AI agents)

The plugin ships six skills that teach an AI coding agent (Claude Code, OpenCode, Codex, ...) how to set this server up:
which file to edit, which setting does what, and what to check before saying it is done. They are plain Markdown, so a
person can read them too.

| Skill | Use it for |
| --- | --- |
| `mcdashboard-setup` | install, enable, choose TLS, open ports, pair a device, upgrade |
| `mcdashboard-configure` | every `config.yml` section, commands and permissions, recipes |
| `mcdashboard-placeholders` | the allowlist: entries, groups, `catalog`, images, refresh intervals |
| `mcdashboard-dashboards` | `dashboards/*.yml`: grid, widgets, tile options, translations, backgrounds |
| `mcdashboard-networks` | Velocity/BungeeCord, MCDashboard-proxy, the tunnel, federation |
| `mcdashboard-troubleshoot` | symptom by symptom, from the console to the app |

Where they live: one folder per skill, `plugins/MCDashboard/.agents/skills/<skill>/SKILL.md` (on Velocity
`plugins/mcdashboard/.agents/skills/`, because the data folder is lowercase there).

## Letting an agent use them

`.agents/skills/` is the standard folder agents search for skills, so the simplest way is to start the agent **inside
the data folder**; it then discovers all six and loads the one that matches the question. If the agent runs in the
server root instead, point it at the folder once:

```jsonc
// opencode.json
{
  "$schema": "https://opencode.ai/config.json",
  "skills": ["./plugins/MCDashboard/.agents/skills"]
}
```

Claude Code reads project skills from `.claude/skills/`, so link or copy `plugins/MCDashboard/.agents/skills` to
`.claude/skills` in the directory it runs in. An agent without skills support can simply be asked to read
`plugins/MCDashboard/.agents/skills/mcdashboard-configure/SKILL.md`.

The skills only read and write files in the data folder; they contain no scripts and no credentials.

## They are replaced on update

The skills are reference material that belongs to a plugin version, not configuration: every start rewrites a skill
file whose content changed in the new release. A skill folder the plugin does not ship (your own notes) is left alone,
but keep changes to a bundled skill in a copy, or they are overwritten.

The same files are the source used by the agents working on this repository: `.agents/skills/` in the plugin
repository is packaged into the jar as `skills/`. Edit them there, together with `docs/`, and both the repository and
every server get the change.

## The documentation they point at

Each skill ends with the matching pages on [mcdashboard.org](https://mcdashboard.org/en/docs/): installation,
configuration, placeholders, dashboards, security, networks, the caching proxy and troubleshooting. The skills carry the
facts an agent needs to act; the site has the long version, screenshots and the app guide.
