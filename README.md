# Claude Code skills: house-review + crud-gen

Two skills that share one set of house engineering rules:

- **house-review**: reviews Java / Spring Boot code against the house standards (REST,
  code smells, Effective Java, persistence and transaction pitfalls, the eight lenses incl. the
  required tests, architecture/ArchUnit, error contract, config baseline). Builds and tests,
  verifies findings, handles accepted deviations, reports, offers fixes.
- **crud-gen**: generates full layered CRUD for a JPA entity (API interface with OpenAPI docs,
  controller, service, mapper, repository + Criteria impl, record DTOs, ErrorCodes, dropdown,
  integration tests) so that house-review finds nothing. `/crud-gen bootstrap` sets up the shared
  house infrastructure once per project.

**Install both together.** crud-gen reads house-review's `references/` as its source of truth.

## Install (once per machine)
```bash
git clone git@github.com:ManishAnandProgrammer/Claude-Skills.git ~/Documents/claude-skills
~/Documents/claude-skills/scripts/install.sh
```
This repository is the **single source of truth**:
- `install.sh` symlinks every skill into `~/.claude/skills/`, backing up any existing copy, so
  Claude Code loads the skills straight from this repository;
- it schedules `scripts/sync.sh` once a day: a systemd user timer on Linux (catches up after
  the machine was off), cron elsewhere.

The sync is a fast-forward-only `git pull`. It never merges or overwrites, and skips (logged) when
there are uncommitted local edits. Log: `~/.local/state/claude-skills/sync.log`.

Start a new Claude Code session afterwards; the skills show up as `/house-review` and `/crud-gen`.

## Changing a skill
Edit the files here (or through `~/.claude/skills/<name>`, the same files), then commit and push.
The other machines pick the change up within a day, or right away with
`~/Documents/claude-skills/scripts/sync.sh`.

## Use
- `/house-review`: asks for the scope (branch diff, uncommitted, a path, the whole codebase).
  Or pass it: `/house-review diff`, `/house-review uncommitted`, `/house-review all`,
  `/house-review src/main/java/com/example/orders`.
- `/crud-gen bootstrap`: once per project (asks before adding any dependency).
- `/crud-gen path/to/Entity.java`: generate CRUD for one entity.

The first run in a project builds `.claude/review-profile.md` / `.claude/crud-profile.md` from
the codebase and asks you to confirm once. Project-specific conventions and accepted deviations
live there, not in the skills.

## Where things are
- `house-review/SKILL.md`: the review process.
- `house-review/references/`: the rules: rest-api, code-smells, java, persistence, lenses,
  architecture, error-contract, config-baseline.
- `house-review/DESIGN.md`: every decision behind the rules, and why.
- `crud-gen/SKILL.md`: the generation steps; `crud-gen/references/`: bootstrap, tests,
  dropdown, media; `crud-gen/examples/`: a sample profile.

## Open item
- `metricsAuth` (how the Prometheus scrape is secured) is still `pending` in the profile
  template; health endpoints stay unauthenticated for the load balancer.
