#!/usr/bin/env bash
# Undoes install.sh / install-standalone.sh for this folder: removes the ~/.claude/skills links
# that point here (only those), the skills block in ~/.claude/CLAUDE.md, and the daily sync
# (systemd timer or cron lines). Leaves this folder and any backups in ~/.claude/skills-backup.
# Safe to run more than once.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
source "$REPO/scripts/lib/common.sh"

case "${1:-}" in
    -h|--help) sed -n '2,5p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    "") ;;
    *) die "unknown option: $1 (see --help)" ;;
esac

[ "$(id -u)" -ne 0 ] || die "don't run this as root (sudo)"
command -v python3 >/dev/null 2>&1 || die "python3 is required"
acquire_lock

unlink_skills "$REPO"
if [ "$(git -C "$REPO" config --get core.hooksPath 2>/dev/null)" = ".githooks" ]; then
    git -C "$REPO" config --unset core.hooksPath && echo "pre-push verification disabled"
fi
update_claude_block remove "$REPO"

# The timer has one name per user: remove it only when it runs THIS folder's sync.sh, so
# uninstalling another copy (e.g. a standalone one) never stops the owner's sync.
service="$HOME/.config/systemd/user/claude-skills-sync.service"
if [ -f "$service" ] && grep -qF "ExecStart=$REPO/scripts/sync.sh" "$service"; then
    if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
        systemctl --user disable --now claude-skills-sync.timer >/dev/null 2>&1 || true
    fi
    rm -f "$HOME/.config/systemd/user/claude-skills-sync.timer" "$service"
    if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
        systemctl --user daemon-reload
    fi
    echo "removed the daily sync timer"
fi
if command -v crontab >/dev/null 2>&1 && crontab -l 2>/dev/null | grep -qF "$REPO/scripts/sync.sh"; then
    { crontab -l 2>/dev/null | grep -vF "$REPO/scripts/sync.sh" || true; } | crontab -
    echo "removed the daily sync cron lines"
fi

echo "uninstalled; this folder ($REPO) is untouched. Start a new Claude Code session."
