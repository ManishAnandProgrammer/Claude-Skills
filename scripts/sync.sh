#!/usr/bin/env bash
# Pulls the latest skills from GitHub. Fast-forward only: it never merges, rebases or overwrites
# local work. Uncommitted local edits make it skip (logged), so nothing is lost.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
LOG="${XDG_STATE_HOME:-$HOME/.local/state}/claude-skills/sync.log"
mkdir -p "$(dirname "$LOG")"

{
    echo "== $(date '+%Y-%m-%dT%H:%M:%S%z')"   # portable: BSD/macOS date has no -I
    if [ -n "$(git -C "$REPO" status --porcelain)" ]; then
        echo "skipped: uncommitted changes in $REPO; commit and push them, then run this again"
        exit 0
    fi
    # BatchMode: fail instead of waiting for a prompt nobody will answer.
    GIT_SSH_COMMAND="ssh -o BatchMode=yes" git -C "$REPO" pull --ff-only
} >>"$LOG" 2>&1
