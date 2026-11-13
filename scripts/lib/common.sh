#!/usr/bin/env bash
# Steps shared by install.sh (git clone) and install-standalone.sh (zip copy). Sourced, not run.

# Links every skill folder (one with a SKILL.md) of $1 into ~/.claude/skills. An existing
# non-link entry is moved to a dated backup first.
link_skills() {
    local repo="$1" skills_dir="$HOME/.claude/skills" skill name target backup
    mkdir -p "$skills_dir"
    for skill in "$repo"/*/; do
        skill="${skill%/}"
        name="$(basename "$skill")"
        [ -f "$skill/SKILL.md" ] || continue
        target="$skills_dir/$name"
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
}

# Writes $1 (the block body, without markers) into ~/.claude/CLAUDE.md between the
# claude-skills markers: replaced on re-runs, never duplicated; the rest of the file is kept.
write_claude_block() {
    local body="$1" memory="$HOME/.claude/CLAUDE.md"
    local begin="<!-- claude-skills:begin -->" end="<!-- claude-skills:end -->"
    local block="$begin
$body
$end"
    mkdir -p "$(dirname "$memory")"
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
}
