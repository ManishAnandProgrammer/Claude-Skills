# reference-app

The house reference application: real, building, tested code that the skills copy and point at.

- `/crud-gen bootstrap` copies its **pieces** into a project with `scripts/bootstrap-copy.sh`
  (`core`, `sign-in`), renaming the root package `com.example.app`.
- The `todo` module is the **golden crud-gen output**: what a generated entity looks like, with its
  tests. crud-gen generates like it; house-review cites it as compliant code.
- Skills find it at `~/.claude/skills-reference` (linked by the installers).

It is not an application anyone deploys. Its domain (todo lists and todos) only exists to show
every pattern working: offset paging (lists), cursor paging (todos, the dropdown), projections,
ownership, optimistic locking, state actions, a generated keyset sort column.

## Build

JDK 25 and Docker (Testcontainers):
```bash
./mvnw -B verify                    # or: sg docker -c "./mvnw -B verify"
```
`../scripts/verify.sh` builds this project **and** a fresh bootstrap copy of it; the pre-push hook
runs it, since the repository is private and has no CI.

## Changing it

Every change must keep `scripts/verify.sh` green. Behaviour the skills describe lives here first;
the skill text then points at the file instead of repeating code.

Configuration is split by concern under `src/main/resources/config/` (with profile variants such as
`db-prod.yml`); every tunable number is in `config/limits.yml` or `config/jobs.yml`.

## Container

`Dockerfile` packages the jar `./mvnw -B verify` built (`docker build -t reference-app .`). Give the
container a stop grace period longer than the 30 s drain (`spring.lifecycle.timeout-per-shutdown-phase`):
Docker's default of 10 s kills requests mid-drain. In compose, `stop_grace_period: 40s`; with
`docker run`, `--stop-timeout 40`. Mount `/diagnostics` (heap dumps, `hs_err`, GC logs, one directory
per start) on a private volume: heap dumps hold tokens and personal data.
