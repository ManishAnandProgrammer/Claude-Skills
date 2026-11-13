#!/usr/bin/env bash
# One-time setup on a machine: links every skill in this repo into ~/.claude/skills (the repo is
# the single source of truth) and schedules scripts/sync.sh once a day. Safe to run again.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
SKILLS_DIR="$HOME/.claude/skills"
mkdir -p "$SKILLS_DIR"

for skill in "$REPO"/*/; do
    skill="${skill%/}"
    name="$(basename "$skill")"
    [ -f "$skill/SKILL.md" ] || continue
    target="$SKILLS_DIR/$name"
    if [ -L "$target" ]; then
        ln -sfn "$skill" "$target"
    elif [ -e "$target" ]; then
        backup="$target.backup-$(date +%Y%m%d%H%M%S)"
        mv "$target" "$backup"
        echo "existing $name moved to $backup"
        ln -s "$skill" "$target"
    else
        ln -s "$skill" "$target"
    fi
    echo "linked $target -> $skill"
done

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
    line="@daily $REPO/scripts/sync.sh"
    ( crontab -l 2>/dev/null | grep -vF "$REPO/scripts/sync.sh"; echo "$line"; echo "@reboot $REPO/scripts/sync.sh" ) | crontab -
    echo "scheduled: cron (@daily and @reboot)"
fi

"$REPO/scripts/sync.sh"
echo "first sync done; log: ${XDG_STATE_HOME:-$HOME/.local/state}/claude-skills/sync.log"
