#!/usr/bin/env bash
# Installer for the OWNER's machines: this folder is a git clone of the skills repository.
# - links every skill into ~/.claude/skills (this repository is the single source of truth);
# - tells Claude Code, in ~/.claude/CLAUDE.md, where the skills live and that changes are
#   committed and pushed here;
# - schedules scripts/sync.sh once a day (fast-forward-only pull).
# Safe to run again. For a zip copy without git, use install-standalone.sh instead.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
source "$REPO/scripts/lib/common.sh"

if [ ! -d "$REPO/.git" ]; then
    echo "error: $REPO is not a git clone (no .git)."
    echo "For a zip copy, run: $REPO/scripts/install-standalone.sh"
    exit 1
fi

link_skills "$REPO"

origin="$(git -C "$REPO" remote get-url origin 2>/dev/null || echo "no remote")"
write_claude_block "## Skills: single source of truth
The skills in \`~/.claude/skills/\` (house-review, crud-gen, ...) are symlinks into the git
repository \`$REPO\` (GitHub: $origin).
- To change a skill, edit the files in that repository, then commit and push there. Never copy a
  skill elsewhere, and never edit a copy.
- A daily job pulls the repository (fast-forward only) and skips while there are uncommitted
  changes, so push skill edits promptly.
- To pull now: \`$REPO/scripts/sync.sh\` (log: \`~/.local/state/claude-skills/sync.log\`).
- Guides: \`$REPO/guides/\`."

chmod +x "$REPO/scripts/sync.sh"
if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
    units="$HOME/.config/systemd/user"
    mkdir -p "$units"
    cat > "$units/claude-skills-sync.service" <<UNIT
[Unit]
Description=Pull the Claude Code skills from GitHub

[Service]
Type=oneshot
ExecStart=$REPO/scripts/sync.sh
UNIT
    cat > "$units/claude-skills-sync.timer" <<UNIT
[Unit]
Description=Pull the Claude Code skills from GitHub once a day

[Timer]
OnCalendar=daily
# Runs at the next start-up when the machine was off or asleep at the scheduled time.
Persistent=true
RandomizedDelaySec=15min

[Install]
WantedBy=timers.target
UNIT
    systemctl --user daemon-reload
    systemctl --user enable --now claude-skills-sync.timer
    echo "scheduled: systemd user timer claude-skills-sync.timer (daily)"
else
    ( crontab -l 2>/dev/null | grep -vF "$REPO/scripts/sync.sh"; echo "@daily $REPO/scripts/sync.sh"; echo "@reboot $REPO/scripts/sync.sh" ) | crontab -
    echo "scheduled: cron (@daily and @reboot)"
fi

"$REPO/scripts/sync.sh"
echo "first sync done; log: ${XDG_STATE_HOME:-$HOME/.local/state}/claude-skills/sync.log"
