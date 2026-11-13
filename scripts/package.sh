#!/usr/bin/env bash
# Makes a shareable zip of the committed skills (no .git, no uncommitted edits), for someone
# without access to the GitHub repository. The receiver unzips it and runs scripts/install.sh,
# which detects the standalone copy (no daily sync).
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$HOME/Downloads/claude-skills-$(git -C "$REPO" rev-parse --short HEAD).zip}"

if [ -n "$(git -C "$REPO" status --porcelain)" ]; then
    echo "note: uncommitted changes are NOT included; commit them first if they should be"
fi
git -C "$REPO" archive --format=zip --prefix=claude-skills/ -o "$OUT" HEAD
echo "created $OUT"
