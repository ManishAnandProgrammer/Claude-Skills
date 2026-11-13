#!/usr/bin/env bash
# Installer for a STANDALONE copy (a zip from scripts/package.sh, or GitHub's "Download ZIP"),
# for someone without access to the owner's GitHub repository. No git, no automatic updates.
# - links every skill into ~/.claude/skills (this folder is the single source of truth);
# - tells Claude Code, in ~/.claude/CLAUDE.md, where the skills live and how updates arrive.
# Safe to run again; run it again after replacing this folder with a newer copy.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
source "$REPO/scripts/lib/common.sh"

case "${1:-}" in
    -h|--help) sed -n '2,7p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    "") ;;
    *) die "unknown option: $1 (see --help)" ;;
esac

preflight "$REPO"
acquire_lock

link_skills "$REPO"

update_claude_block write "## Skills: single source of truth (standalone copy)
The skills in \`~/.claude/skills/\` (house-review, crud-gen, ...) are symlinks into the folder
\`$REPO\`, a standalone copy (not a git clone, no automatic updates).
- Use the skills from there; never copy a skill elsewhere. Local edits to that folder take effect
  at once, but are lost when the copy is replaced, so note any change you want kept and send it
  to the skills' owner.
- To update: replace this folder with the new copy at the same path, then run
  \`$REPO/scripts/install-standalone.sh\` again.
- Guides: \`$REPO/guides/\`."

echo "done: standalone copy installed (no automatic updates). Start a new Claude Code session."
