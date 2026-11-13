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

# Tell Claude Code, in every project on this machine, where the skills live and how to change them.
# Written between markers so a re-run replaces the block instead of duplicating it.
memory="$HOME/.claude/CLAUDE.md"
begin="<!-- claude-skills:begin -->"
end="<!-- claude-skills:end -->"
block="$begin
## Skills: single source of truth
The skills in \`~/.claude/skills/\` (house-review, crud-gen, ...) are symlinks into the git
repository \`$REPO\` (GitHub: $(git -C "$REPO" remote get-url origin)).
- To change a skill, edit the files in that repository, then commit and push there. Never copy a
  skill elsewhere, and never edit a copy.
- A daily job pulls the repository (fast-forward only) and skips while there are uncommitted
  changes, so push skill edits promptly.
- To pull now: \`$REPO/scripts/sync.sh\` (log: \`~/.local/state/claude-skills/sync.log\`).
- Guides: \`$REPO/guides/\`.
$end"
touch "$memory"
if grep -qF "$begin" "$memory"; then
    python3 - "$memory" "$begin" "$end" "$block" <<'PY'
import sys
path, begin, end, block = sys.argv[1:5]
text = open(path).read()
start = text.index(begin)
stop = text.index(end) + len(end)
open(path, "w").write(text[:start] + block + text[stop:])
PY
    echo "updated the skills block in $memory"
else
    [ -s "$memory" ] && printf '\n' >> "$memory"
    printf '%s\n' "$block" >> "$memory"
    echo "added the skills block to $memory"
fi

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
