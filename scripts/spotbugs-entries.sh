#!/usr/bin/env bash
# Checks, without a build, that every class SpotBugs will report has its entry in the exclude file of each copy that
# holds it (house-review CFG-11), so a forgotten one fails here and not in a copy's SpotBugs run late in verify.sh:
#   - a @RestController class: a SPRING_ENDPOINT entry (FindSecBugs marks every request handler; exact);
#   - a class with a logger (@Slf4j or LoggerFactory) whose log call passes a method's or catch block's own
#     non-primitive parameter (as an argument, its toString() or getMessage(), or concatenated into the message):
#     a CRLF_INJECTION_LOGS entry. FindSecBugs' rule is taint analysis (a parameter of any object type is unknown
#     taint, a primitive and some library results are safe); this matches only the calls it surely reports, so a
#     class logging another value (a field's, a lookup's result) is left to the copies' SpotBugs runs.
# Which file: the reference's for its classes and the roles piece's (whose entries sit in roles blocks there); for
# the classes of a tenancy layer, the file of each tenancy mode that holds it (row mode uses overlays/tenancy's,
# schema per tenant overlays/tenancy-schema's), the roles piece's classes included (one that imports a tenant
# class, a tenancy copy's only, is checked there alone).
#
# Usage: spotbugs-entries.sh   (from verify.sh's stage 0; exit 1 naming each class and file)
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 -I - "$REPO" <<'PYTHON'
import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree

repo = pathlib.Path(sys.argv[1])
REFERENCE = "reference-app/spotbugs-exclude.xml"
ROW = "overlays/tenancy/spotbugs-exclude.xml"
SCHEMA = "overlays/tenancy-schema/spotbugs-exclude.xml"
EXCLUDE_FILES = {
    "reference-app": [REFERENCE],
    "overlays/roles": [REFERENCE, ROW, SCHEMA],
    "overlays/tenancy": [ROW, SCHEMA],
    "overlays/tenancy-schema": [SCHEMA],
    "overlays/tenancy-row": [ROW],
}
PRIMITIVES = {"byte", "short", "int", "long", "float", "double", "boolean", "char"}
LOG_CALL = re.compile(r"\b(\w+)\s*\.\s*(trace|debug|info|warn|error)\s*\(")
TYPE_DECLARATION = re.compile(r"\b(class|record|enum|interface)\s+(\w+)[^;{]*\{")
METHOD_OR_CATCH = re.compile(r"(?:\bcatch\s*|\b\w+\s*)\(([^()]*(?:\([^()]*\)[^()]*)*)\)\s*(?:throws\s+[\w.,\s]+)?\{")


def without_comments_and_strings(text):
    """Comments blanked and string contents emptied, offsets kept, so braces and parentheses count right."""
    out = []
    i = 0
    while i < len(text):
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = len(text) if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2) + 2
            out.append(re.sub(r"[^\n]", " ", text[i:end]))
            i = end
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3) + 3
            out.append('"' + re.sub(r"[^\n]", " ", text[i + 1:end - 1]) + '"')
            i = end
        elif text[i] in "\"'":
            quote, j = text[i], i + 1
            while text[j] != quote:
                j += 2 if text[j] == "\\" else 1
            out.append(quote + " " * (j - i - 1) + quote)
            i = j + 1
        else:
            out.append(text[i])
            i += 1
    return "".join(out)


def closing_brace(code, opening):
    depth = 0
    for i in range(opening, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                return i
    return len(code)


def arguments(code, start):
    """The top-level arguments of the call whose opening parenthesis ends at start."""
    depth, current, found = 0, [], []
    for ch in code[start:]:
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            if depth == 0:
                found.append("".join(current).strip())
                return found
            depth -= 1
        if ch == "," and depth == 0:
            found.append("".join(current).strip())
            current = []
        else:
            current.append(ch)
    return found


def parameters(declaration):
    names = {}
    for parameter in re.split(r",(?![^<]*>)", declaration):
        words = re.sub(r"@\w+(\([^)]*\))?|\bfinal\b", " ", parameter).split()
        if len(words) >= 2 and re.fullmatch(r"\w+", words[-1]):
            names[words[-1]] = " ".join(words[:-1])
    return names


def surely_reported(argument, in_scope):
    def tainted(name):
        return name in in_scope and in_scope[name] not in PRIMITIVES
    bare = re.fullmatch(r"(\w+)(?:\s*\.\s*(?:toString|getMessage|getLocalizedMessage)\s*\(\s*\))?", argument)
    return bare is not None and tainted(bare.group(1))


def reported_patterns(path):
    code = without_comments_and_strings(path.read_text())
    package = re.search(r"^\s*package\s+([\w.]+)\s*;", code, re.M).group(1)
    types = [(m.start(), closing_brace(code, m.end() - 1), m.group(2)) for m in TYPE_DECLARATION.finditer(code)]
    scopes = [(m.end() - 1, closing_brace(code, m.end() - 1), parameters(m.group(1))) for m in METHOD_OR_CATCH.finditer(code)]

    def class_at(position):
        enclosing = [name for start, end, name in types if start <= position <= end]
        return package + "." + "$".join(enclosing)

    found = set()
    for m in re.finditer(r"@RestController\b", code):
        following = [t for t in types if t[0] > m.start()]
        if following:
            found.add((class_at(following[0][0]), "SPRING_ENDPOINT"))
    if re.search(r"@Slf4j\b|\bLoggerFactory\s*\.\s*getLogger\b", code):
        loggers = {"log"} | set(re.findall(r"\bLogger\s+(\w+)\s*=", code))
        for call in LOG_CALL.finditer(code):
            if call.group(1) not in loggers:
                continue
            in_scope = {}
            for start, end, names in scopes:
                if start <= call.start() <= end:
                    in_scope.update(names)
            given = arguments(code, call.end())
            message_parts = [part.strip() for part in given[0].split("+")] if given else []
            if any(surely_reported(argument, in_scope) for argument in given[1:] + message_parts[1:]):
                found.add((class_at(call.start()), "CRLF_INJECTION_LOGS"))
    return found


def entries(exclude):
    tree = ElementTree.parse(repo / exclude)
    namespace = {"f": "https://github.com/spotbugs/filter/3.0.0"}
    listed = []
    for match in tree.getroot().findall("f:Match", namespace):
        classes = [c.get("name") for c in match.findall("f:Class", namespace)]
        bugs = [b.get("pattern") or "" for b in match.findall("f:Bug", namespace)]
        patterns = {p.strip() for bug in bugs for p in bug.split(",")}
        listed.extend((name, patterns) for name in classes)
    return listed


def covered(listed, class_name, pattern):
    for name, patterns in listed:
        if pattern not in patterns:
            continue
        if name == class_name or (name.startswith("~") and re.fullmatch(name[1:], class_name)):
            return True
    return False


listed_by_file = {exclude: entries(exclude) for files in EXCLUDE_FILES.values() for exclude in files}
missing = []
for layer, files in EXCLUDE_FILES.items():
    for path in sorted((repo / layer / "src/main/java").rglob("*.java")):
        holders = files
        if layer == "overlays/roles" and re.search(r"^import\s+com\.example\.app\.tenant\.", path.read_text(), re.M):
            holders = [ROW, SCHEMA]
        for class_name, pattern in sorted(reported_patterns(path)):
            for exclude in holders:
                if not covered(listed_by_file[exclude], class_name, pattern):
                    missing.append(f"{class_name} ({layer}): no {pattern} entry in {exclude}")
if missing:
    print("FAILED: classes SpotBugs reports without their exclude entry (house-review CFG-11):", file=sys.stderr)
    for line in missing:
        print("  " + line, file=sys.stderr)
    sys.exit(1)
PYTHON
