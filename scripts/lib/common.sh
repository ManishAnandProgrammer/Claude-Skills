#!/usr/bin/env bash
# Steps shared by install.sh (git clone), install-standalone.sh (zip copy) and uninstall.sh.
# Sourced, not run. Every step is idempotent: running an installer twice changes nothing.

SKILLS_DIR="$HOME/.claude/skills"
BACKUP_DIR="$HOME/.claude/skills-backup"      # outside SKILLS_DIR, so backups are never loaded as skills
MEMORY_FILE="$HOME/.claude/CLAUDE.md"
BLOCK_BEGIN="<!-- claude-skills:begin -->"
BLOCK_END="<!-- claude-skills:end -->"
LOCK_DIR="$HOME/.claude/.claude-skills-install.lock"

die() {
    echo "error: $*" >&2
    exit 1
}

# Refuses the situations that would install into the wrong place or half-way.
preflight() {
    local repo="$1"
    [ "$(id -u)" -ne 0 ] || die "don't run this as root (sudo): it installs for the current user, into \$HOME/.claude"
    case "$repo" in
        *[[:space:]]*) die "the folder path contains spaces ($repo); move it to a path without spaces and run again" ;;
    esac
    command -v python3 >/dev/null 2>&1 || die "python3 is required (it updates ~/.claude/CLAUDE.md); install it and run again"
    [ -n "$(find "$repo" -mindepth 2 -maxdepth 2 -name SKILL.md -print -quit)" ] || die "no skills found in $repo"
}

# One installer at a time. A lock left by a killed run is reported, not silently broken.
acquire_lock() {
    mkdir -p "$HOME/.claude"
    if ! mkdir "$LOCK_DIR" 2>/dev/null; then
        die "another install seems to be running (lock: $LOCK_DIR); if not, remove that folder and run again"
    fi
    trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT
}

# Links every skill folder (one with a SKILL.md) of $1 into ~/.claude/skills. An existing
# entry that isn't a link is moved to ~/.claude/skills-backup/<name>-<timestamp> first.
link_skills() {
    local repo="$1" skill name target backup
    mkdir -p "$SKILLS_DIR"
    for skill in "$repo"/*/; do
        skill="${skill%/}"
        name="$(basename "$skill")"
        [ -f "$skill/SKILL.md" ] || continue
        target="$SKILLS_DIR/$name"
        if [ -e "$target" ] && [ ! -L "$target" ]; then
            mkdir -p "$BACKUP_DIR"
            backup="$BACKUP_DIR/$name-$(date +%Y%m%d%H%M%S)"
            mv "$target" "$backup"
            echo "existing $name moved to $backup"
        fi
        ln -sfn "$skill" "$target"
        echo "linked $target -> $skill"
    done
}

# Removes the links in ~/.claude/skills that point into $1 (and only those).
unlink_skills() {
    local repo="$1" link
    for link in "$SKILLS_DIR"/*; do
        [ -L "$link" ] || continue
        case "$(readlink "$link")" in
            "$repo"/*) rm "$link" && echo "removed link $link" ;;
        esac
    done
}

# Writes (mode "write", with the body in $2) or removes (mode "remove", only when the block names
# the folder in $2, so uninstalling one copy never removes another copy's block) the block
# between the claude-skills markers in ~/.claude/CLAUDE.md. The rest of the file is kept; the write is atomic
# (temp file + rename); a symlinked CLAUDE.md is updated at its target; damaged markers stop with
# a message and leave the file untouched.
update_claude_block() {
    local mode="$1" body="${2:-}"
    mkdir -p "$(dirname "$MEMORY_FILE")"
    python3 - "$MEMORY_FILE" "$BLOCK_BEGIN" "$BLOCK_END" "$mode" "$body" <<'PY'
import os, sys, tempfile

path, begin, end, mode, body = sys.argv[1:6]
real = os.path.realpath(path)
text = open(real, encoding="utf-8").read() if os.path.exists(real) else ""

starts, ends = text.count(begin), text.count(end)
if (starts, ends) not in ((0, 0), (1, 1)) or (starts == 1 and text.index(begin) > text.index(end)):
    sys.exit(f"error: the claude-skills markers in {path} are damaged (found {starts} begin, {ends} end); "
             "fix or delete that block by hand, then run again")

block = f"{begin}\n{body}\n{end}"
if starts == 1:
    head, rest = text.split(begin, 1)
    tail = rest.split(end, 1)[1]
    if mode == "remove" and body not in rest.split(end, 1)[0]:
        print(f"the skills block in {path} belongs to another copy; left as is")
        sys.exit(0)
    if mode == "remove":
        new = (head.rstrip("\n") + "\n" + tail.lstrip("\n")).lstrip("\n") if head.strip() else tail.lstrip("\n")
        action = "removed the skills block from"
    else:
        new = head + block + tail
        action = "updated the skills block in"
elif mode == "remove":
    print(f"no skills block in {path}")
    sys.exit(0)
else:
    sep = "" if not text else ("\n" if text.endswith("\n") else "\n\n")
    new = text + sep + block + "\n"
    action = "added the skills block to"

if new == text:
    print(f"skills block in {path} already up to date")
    sys.exit(0)

directory = os.path.dirname(real) or "."
fd, tmp = tempfile.mkstemp(dir=directory, prefix=".CLAUDE.md.")
try:
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(new)
    if os.path.exists(real):
        os.chmod(tmp, os.stat(real).st_mode & 0o7777)
    os.replace(tmp, real)
except BaseException:
    os.unlink(tmp)
    raise
print(f"{action} {path}")
PY
}
