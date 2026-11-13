# Guide: set up the skills on another computer

## Overview

**Goal:** on a new laptop, Claude Code uses the skills straight from this git repository (the
**single source of truth**), keeps them up to date every day, and knows that skill changes are
made, committed and pushed here.

**What `scripts/install.sh` sets up:**

| Piece | Where | Purpose |
|---|---|---|
| Symlinks | `~/.claude/skills/<skill>` → `~/Documents/claude-skills/<skill>` | Claude Code loads the skills from the repository; there is no copy to drift |
| Daily sync | systemd user timer `claude-skills-sync.timer` (Linux), or cron (macOS, other) | Fast-forward-only `git pull`; skips while there are uncommitted edits |
| Instructions for Claude | a marked block in `~/.claude/CLAUDE.md` | Every Claude Code session, in every project, knows where the skills live and that changes are committed and pushed here |

`install.sh` (git clone only; a zip copy uses `install-standalone.sh`, see `share-as-zip.md`) is
safe to run again: links are refreshed, an existing non-link skill folder is
backed up first, and the `CLAUDE.md` block is replaced, not duplicated.

---

## Details

### Why `~/.claude/CLAUDE.md`
Claude Code reads the user-level `~/.claude/CLAUDE.md` in **every** session on the machine, in
every project. The installer writes this block (between `<!-- claude-skills:begin -->` and
`<!-- claude-skills:end -->`; keep the markers):
- the skills are symlinks into this repository;
- edit them only here, then commit and push;
- how to pull right away, and where the guides are.

You don't need to repeat any of this to Claude in each project. Anything else you put in
`~/.claude/CLAUDE.md` outside the markers stays untouched.

### How the sync behaves
- It runs once a day. On Linux, if the laptop was off or asleep, it runs at the next start-up.
  With cron (macOS), it runs `@daily` and at every reboot.
- It is **fast-forward only**: it never merges, rebases or overwrites.
- **Uncommitted edits make it skip** (the log says so). Push your skill edits; until then, this
  laptop won't receive changes from the others.
- Log: `~/.local/state/claude-skills/sync.log`.

### SSH vs HTTPS
- Pushing needs your GitHub login: an **SSH key per machine** (steps below).
- The repository is **private**: cloning, pulling and pushing all need your GitHub login. SSH
  with a key per machine is the simplest; the daily sync uses it unattended.

---

## Playbook

### 1. Prerequisites on the new laptop
- `git`, and Claude Code installed and logged in.
- For using the skills on projects: JDK 25 and Docker.

### 2. An SSH key for this laptop (once per machine)
```bash
ssh-keygen -t ed25519 -C "manishanandprogrammer@gmail.com"     # Enter for the default path
eval "$(ssh-agent -s)" && ssh-add ~/.ssh/id_ed25519
cat ~/.ssh/id_ed25519.pub                                    # copy the whole line
```
On GitHub: **Settings → SSH and GPG keys → New SSH key**. Use a title like "Mac laptop", type
*Authentication Key*, paste the line and save. Then test:
```bash
ssh -T git@github.com      # "Hi ManishAnandProgrammer! You've successfully authenticated…"
```
A key **without** a passphrase lets the daily sync run unattended over SSH. A key with a
passphrase works only while it is loaded in `ssh-agent`; otherwise the sync logs a failure and
retries the next day. (HTTPS is no way around it: the repository is private, so HTTPS pulls need
credentials too.)

### 3. Clone and install (do it yourself)
```bash
git clone git@github.com:ManishAnandProgrammer/Claude-Skills.git ~/Documents/claude-skills
git -C ~/Documents/claude-skills config user.name  "Manish Anand"
git -C ~/Documents/claude-skills config user.email "manishanandprogrammer@gmail.com"
~/Documents/claude-skills/scripts/install.sh
```

### 3 (alternative). Or let Claude do it
Start Claude Code in any folder and paste:

> Set up my Claude Code skills on this machine from my skills repository, as its single source of
> truth. Clone `git@github.com:ManishAnandProgrammer/Claude-Skills.git` into
> `~/Documents/claude-skills` (if it isn't there yet), set the repo's git identity to
> "Manish Anand" / manishanandprogrammer@gmail.com, then run `scripts/install.sh` and show me its
> output. Afterwards verify: the symlinks in `~/.claude/skills`, the daily sync schedule, the
> skills block in `~/.claude/CLAUDE.md`, and the sync log. Follow
> `guides/setup-new-machine.md` in that repository.

(The SSH key in step 2 has to exist first. Claude can create it, but adding it to GitHub needs
you in the browser.)

### 4. Verify
```bash
ls -l ~/.claude/skills                                  # house-review -> ~/Documents/claude-skills/house-review, crud-gen -> …
systemctl --user list-timers claude-skills-sync.timer   # Linux: next run listed
crontab -l | grep claude-skills                         # macOS / no systemd: @daily and @reboot lines
grep -A3 'claude-skills:begin' ~/.claude/CLAUDE.md      # the skills block
cat ~/.local/state/claude-skills/sync.log               # last entry: "Already up to date."
```
Then **start a new Claude Code session**. `/house-review` and `/crud-gen` appear in the skill
list. Ask "where do my skills live and how do I change them?": the answer should come from
the `CLAUDE.md` block.

### 5. Daily use on this laptop
- **Using the skills on a project:** see `guides/new-project.md` or
  `guides/existing-project.md`. Nothing else to configure; each project gets its own
  `.claude/*-profile.md` on first run.
- **Changing a skill** (yourself, or by asking Claude): edit in `~/Documents/claude-skills`
  (`~/.claude/skills/<skill>` is the same files), then
  `git -C ~/Documents/claude-skills commit -am "<what and why>" && git -C ~/Documents/claude-skills push`.
  Other machines receive it within a day.
- **Getting changes right now:** `~/Documents/claude-skills/scripts/sync.sh`.

### 6. Adding a new skill to the repository later
Create `~/Documents/claude-skills/<new-skill>/SKILL.md`, commit and push, then run
`scripts/install.sh` once on each machine (it links every folder that has a `SKILL.md`).

### Removing it from a machine
```bash
~/Documents/claude-skills/scripts/uninstall.sh
```
This removes the links pointing into this folder, its `CLAUDE.md` block and its daily sync
(timer or cron lines), and only those that belong to this folder. The folder itself and any
backups in `~/.claude/skills-backup/` are kept.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `git clone` / `push`: "Permission denied (publickey)" | The SSH key isn't on GitHub, or not loaded: redo step 2 and `ssh -T git@github.com` |
| The log says "skipped: uncommitted changes" | Commit and push (or discard) the edits in `~/Documents/claude-skills`; the next sync pulls |
| The log shows "Not possible to fast-forward" | This laptop has local commits the remote doesn't: `git -C ~/Documents/claude-skills pull --rebase && git push` |
| Skills don't show up in Claude Code | Start a **new** session; check the links with `ls -l ~/.claude/skills` |
| `install.sh`: "existing <skill> moved to …backup-…" | There was an old copy; it's kept as the backup. Delete it once the linked version works |
