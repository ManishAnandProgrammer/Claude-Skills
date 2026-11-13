# Guide: share the skills as a zip (someone without your GitHub account)

## Overview

**Goal:** give someone (a tester, a teammate) the skills so their Claude Code sees and uses them
**exactly as yours does**, without access to your GitHub repository.

**What's different from your own machines:**

| | Your machines (`install.sh`) | A zip copy (`install-standalone.sh`) |
|---|---|---|
| Source | git clone of the repository | unzipped folder, no `.git` |
| Skills in Claude Code | symlinks into the clone | symlinks into the unzipped folder: the same single source of truth |
| `~/.claude/CLAUDE.md` block | "edit here, commit and push" | "standalone copy; updates arrive as a new zip" |
| Daily sync | yes (fast-forward pull) | **no**: you send a new zip, they re-run the installer |
| Changing a skill | edit, commit, push | local edits work but are lost on the next copy; they send them to you |

Two scripts are involved:
- `scripts/package.sh` (**you**): builds the zip from the last commit, without `.git` and
  without uncommitted edits;
- `scripts/install-standalone.sh` (**them**): links the skills and writes the `CLAUDE.md` block.
  It schedules nothing and needs no git or GitHub login.

`install.sh` refuses to run on a zip copy and points to `install-standalone.sh`, so nobody can
pick the wrong one.

---

## Details

### What the receiver's Claude sees
After `install-standalone.sh` and a new Claude Code session:
- `/house-review` and `/crud-gen` are listed like any other skill, and work the same way in their
  projects (profiles are built per project on first run);
- crud-gen finds house-review's references through `~/.claude/skills/house-review/…`, which the
  installer linked. That is why **both skills must be installed together**, which the zip does;
- the `CLAUDE.md` block tells their Claude where the skills live, not to copy them, and how
  updates arrive.

### Where to unzip
Anywhere stable, for example `~/Documents/claude-skills`. **Don't** unzip into Downloads or a temp
folder and delete it later: the skills are links into that folder. If it's moved, run the
installer again from the new place.

### Updating a receiver
1. You run `scripts/package.sh` again (the zip name contains the commit id).
2. They replace their folder with the new one (same path), and run
   `scripts/install-standalone.sh` again.
3. Their Claude sees the new version in the next session.

### Feedback from testers
Edits they make locally take effect at once, but disappear with the next copy. Ask them to send
you their notes or a diff (`diff -ru old/ new/`). You apply the changes in your repository,
commit and push, and the next zip carries them.

### Requirements on the receiver's machine
- Claude Code, installed and logged in (their own account).
- `bash`, `python3` and `unzip` (standard on Linux and macOS).
- To actually use the skills on projects: JDK 25 and Docker (see the project guides).

---

## Playbook

### You: make the zip
1. Commit (and push) any skill changes first: the zip contains the **last commit** only.
   `package.sh` warns about uncommitted edits.
2. Build it:
   ```bash
   ~/Documents/claude-skills/scripts/package.sh            # → ~/Downloads/claude-skills-<commit>.zip
   ~/Documents/claude-skills/scripts/package.sh /path/to/out.zip   # or choose the file
   ```
3. Send the zip, and point them to this guide (it's inside the zip, in `guides/`).

*(GitHub's "Download ZIP" also works, since the repository is public, but `package.sh` is
the controlled way: the exact commit, and nothing uncommitted.)*

### Them: install (do it yourself)
1. Unzip into a stable place:
   ```bash
   unzip claude-skills-<commit>.zip -d ~/Documents
   ```
   You now have `~/Documents/claude-skills/`.
2. Install:
   ```bash
   ~/Documents/claude-skills/scripts/install-standalone.sh
   ```
3. Verify:
   ```bash
   ls -l ~/.claude/skills                       # house-review -> …/claude-skills/house-review, crud-gen -> …
   grep -A3 'claude-skills:begin' ~/.claude/CLAUDE.md
   ```
4. Start a **new** Claude Code session; `/house-review` and `/crud-gen` are available. Then
   follow `guides/new-project.md` or `guides/existing-project.md`.

### Them: or let Claude do it
Unzip it first (step 1), then paste into Claude Code:

> Install the Claude Code skills from the standalone copy in `~/Documents/claude-skills`, keeping
> that folder as the single source of truth. Run `scripts/install-standalone.sh` (not
> `install.sh`; this copy has no git) and show me the output. Then verify the symlinks in
> `~/.claude/skills` and the skills block in `~/.claude/CLAUDE.md`. Follow
> `guides/share-as-zip.md` in that folder.

### Them: update to a newer zip
```bash
rm -rf ~/Documents/claude-skills && unzip claude-skills-<new-commit>.zip -d ~/Documents
~/Documents/claude-skills/scripts/install-standalone.sh
```
Start a new Claude Code session.

### Them: remove
```bash
~/Documents/claude-skills/scripts/uninstall.sh     # links + CLAUDE.md block of this copy only
rm -rf ~/Documents/claude-skills
```

### Later: they get GitHub access
Remove the standalone copy (above), then follow `guides/setup-new-machine.md`. Its `install.sh`
replaces the `CLAUDE.md` block with the git version and starts the daily sync.

---

## Troubleshooting

| Symptom | Fix |
|---|---|
| `install.sh` says "not a git clone" | That's expected for a zip: run `install-standalone.sh` |
| Skills don't show in Claude Code | Start a new session; check that `ls -l ~/.claude/skills` shows links pointing at an existing folder |
| Links broken after moving the folder | Run `scripts/install-standalone.sh` again from the new location |
| `crud-gen` can't find house-review's references | Both skills must be installed: re-run the installer (it links both) |
| `install-standalone.sh: python3: not found` | Install Python 3 (it updates the `CLAUDE.md` block) |
