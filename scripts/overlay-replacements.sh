#!/usr/bin/env bash
# Keeps the overlays' replacement files honest. A file in overlays/<layer>/ with the same path as a
# file of the reference (or of an earlier layer) replaces that file in a bootstrap copy; it was written
# as a copy of that file plus the layer's changes. overlays/replaced.sha256 pins the version of each
# replaced file it was written against, so a change to the reference can't silently miss the overlay.
#
# Usage: overlay-replacements.sh --check   fails, naming each replaced file that changed (verify.sh)
#        overlay-replacements.sh --update  rewrites the pins, after porting the change into the overlay
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
PINS="overlays/replaced.sha256"
# The shared layers, in order, then the tenancy modes: each mode lies over the shared layers only (a
# copy has one mode), so a mode's file never replaces another mode's file of the same path.
SHARED_LAYERS="reference-app overlays/tenancy"
MODE_LAYERS="overlays/tenancy-schema overlays/tenancy-row"

# Every replaced file, the nearest earlier layer's, one per line, relative to the repository.
replaced_files() {
    local layer earlier relative candidate found
    # The roles layer replaces reference files only (sign-in's RolePermissions), never a tenancy layer's.
    for layer in ${SHARED_LAYERS#reference-app } $MODE_LAYERS overlays/roles; do
        while IFS= read -r file; do
            relative="${file#$REPO/$layer/}"
            found=""
            for earlier in $SHARED_LAYERS; do
                [ "$earlier" = "$layer" ] && break
                candidate="$earlier/$relative"
                if [ -f "$REPO/$candidate" ]; then
                    found="$candidate"
                fi
            done
            if [ -n "$found" ]; then
                echo "$found"
            fi
        done < <(find "$REPO/$layer" -type f | sort)
    done | sort -u
}

cd "$REPO"
case "${1:-}" in
    --update)
        replaced_files | xargs sha256sum > "$PINS"
        echo "pinned $(wc -l < "$PINS") replaced file(s) in $PINS"
        ;;
    --check)
        expected="$(replaced_files)"
        pinned="$(awk '{print $2}' "$PINS" | sort)"
        if [ "$expected" != "$pinned" ]; then
            echo "FAILED: $PINS doesn't list exactly the files the overlays replace:" >&2
            diff <(echo "$pinned") <(echo "$expected") >&2 || true
            echo "run scripts/overlay-replacements.sh --update once the overlays are right" >&2
            exit 1
        fi
        if ! changed="$(sha256sum --check --quiet "$PINS" 2>&1)"; then
            echo "FAILED: the reference changed under an overlay's replacement; port the change into the overlay's copy:" >&2
            echo "$changed" >&2
            echo "then run scripts/overlay-replacements.sh --update" >&2
            exit 1
        fi
        ;;
    *) echo "usage: $0 --check | --update" >&2; exit 2 ;;
esac
