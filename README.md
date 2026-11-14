# Claude Code skills: house-review + crud-gen

Two skills that share one set of house engineering rules:

- **house-review**: reviews Java / Spring Boot code against the house standards (REST,
  code smells, Effective Java, persistence and transaction pitfalls, the eight lenses incl. the
  required tests, architecture/ArchUnit, error contract, config baseline). Builds and tests,
  verifies findings, handles accepted deviations, reports, offers fixes.
- **crud-gen**: generates full layered CRUD for a JPA entity (API interface with OpenAPI docs,
  controller, service, mapper, repository with Criteria projections and cursor or offset paging,
  request/response records, ErrorCodes, configurable limits, dropdown, integration tests) so that
  house-review finds nothing. `/crud-gen bootstrap` copies the shared house infrastructure into a
  project once.
- **reference-app/**: the house reference application, real code that builds and passes its
  tests. Bootstrap copies its pieces (`scripts/bootstrap-copy.sh`); its `todo` module is crud-gen's
  golden output; house-review cites it as compliant code. Installed as `~/.claude/skills-reference`.

**Install everything together.** crud-gen reads house-review's `references/` as its rules and
`reference-app/` as its examples.

## Guides
- [New project](guides/new-project.md): fresh from Spring Initializr, house standards from the
  first commit.
- [Existing project](guides/existing-project.md): an old codebase: baseline audit, fix by risk,
  all new code via crud-gen.
- [Set up on another computer](guides/setup-new-machine.md): SSH key, clone, install, verify;
  also how to let Claude do it.
- [Share as a zip](guides/share-as-zip.md): for someone without access to this repository:
  `scripts/package.sh` makes the zip, `scripts/install-standalone.sh` installs it (no sync).

## Verifying a change (before every push)
The repository is private, so there is no CI: `scripts/verify.sh` builds and tests
`reference-app/`, copies its pieces into fresh projects under other packages and builds those, and
starts the production image against PostgreSQL and Redis. It needs Docker
(`sg docker -c scripts/verify.sh` if your shell isn't in the `docker` group) and takes over ten
minutes. On success it records a fingerprint of the code it tested (`reference-app/`, `scripts/`,
`.githooks/`). `install.sh` enables a pre-push hook (`.githooks/pre-push`) that refuses a push whose
code differs from that record; skill text and guides alone never need a new run. So: change, run
`verify.sh`, commit, push. Skip once with `git push --no-verify`.

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
- it writes a marked block into `~/.claude/CLAUDE.md`, so Claude Code in every project knows the
  skills live here and that changes are committed and pushed here.

The sync is a fast-forward-only `git pull`. It never merges or overwrites, and skips (logged) when
there are uncommitted local edits. Log: `~/.local/state/claude-skills/sync.log`.

Start a new Claude Code session afterwards; the skills show up as `/house-review` and `/crud-gen`.

Got a **zip** instead of a clone (no `.git`)? Run `scripts/install-standalone.sh`: same links
and `CLAUDE.md` block, no daily sync. See [guides/share-as-zip.md](guides/share-as-zip.md).

## How the installers behave
- **Idempotent:** running one twice changes nothing (links refreshed, the `CLAUDE.md` block
  replaced and never duplicated, timer and cron lines not duplicated).
- **Safe:**
  - existing skill folders are moved to `~/.claude/skills-backup/` (outside the skills folder,
    so a backup is never loaded as a skill);
  - `CLAUDE.md` is written atomically, your own content in it is kept, a symlinked
    (dotfiles) `CLAUDE.md` stays a symlink, and damaged markers stop the run with the file
    untouched.
- **Refuses** to run as root (`sudo`), from a path with spaces, without `python3`, while
  another install is running, or (`install.sh`) on a copy without git.
- **A failed first pull** (offline) is a warning, not a failed install.
- **`--help`** on every installer. `scripts/uninstall.sh` undoes everything, but only for its
  own copy: it never removes another copy's links, block or timer.

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
- `reference-app/`: the house reference application, plain (no tenancy); `/crud-gen bootstrap` copies
  its pieces. `overlays/tenancy/` and `overlays/tenancy-schema/`: the tenancy mode, as layers that
  mirror the reference's tree, new files and replacements of reference files
  (`overlays/replaced.sha256` pins what each replacement was written against;
  `scripts/overlay-replacements.sh`). A row-based mode would be `overlays/tenancy-row/` over the same
  shared layer. `crud-gen/references/bootstrap.md` explains the layout.
- `house-review/SKILL.md`: the review process.
- `house-review/references/`: the rules: rest-api, code-smells, java, persistence, lenses,
  architecture, error-contract, config-baseline, metadata, media, tenancy.
- `house-review/DESIGN.md`: every decision behind the rules, and why.
- `crud-gen/SKILL.md`: the generation steps; `crud-gen/references/`: bootstrap, tests,
  dropdown, media.

## Open item
- `metricsAuth` (how the Prometheus scrape is secured) is still `pending` in the profile
  template; health endpoints stay unauthenticated for the load balancer.
