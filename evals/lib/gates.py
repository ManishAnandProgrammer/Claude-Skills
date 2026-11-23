"""The eval harness's bookkeeping: what a headless run cost and did, the gates, the summary.

Called by scripts/eval.sh (python3 -I evals/lib/gates.py <command> ...). Never calls a model.
"""
import datetime
import fnmatch
import json
import pathlib
import re
import subprocess
import sys

SEVERITIES = ["Critical", "High", "Medium", "Low"]
BLOCKING = {"Critical", "High", "Medium"}
RULE_ID = re.compile(r"[A-Z]+-[0-9]+[a-z]?")


def load(path):
    path = pathlib.Path(path)
    return json.loads(path.read_text()) if path.is_file() else None


def save(path, data):
    pathlib.Path(path).write_text(json.dumps(data, indent=2) + "\n")


def run_record(transcript, out, exit_code):
    """One headless run, from its stream-json transcript: cost, time, turns, tools, the friction of running unattended."""
    result, tools, asks, denied_tools, texts, stamps = None, {}, 0, [], [], []
    lines = pathlib.Path(transcript).read_text(errors="replace").splitlines() if pathlib.Path(transcript).is_file() else []
    structured = None
    for line in lines:
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        if message.get("timestamp"):
            stamps.append(message["timestamp"])
        if message.get("type") == "result":
            # A run with background passes ends on a short turn of its own (stopping its wake-up) whose result
            # carries no structured output: the findings stay those of the last result that had them.
            if structured is not None and message.get("structured_output") is None:
                message = dict(message, structured_output=structured)
            structured = message.get("structured_output")
            result = message
        if message.get("type") != "assistant":
            continue
        for block in message.get("message", {}).get("content", []):
            if block.get("type") == "tool_use":
                name = block.get("name", "?")
                tools[name] = tools.get(name, 0) + 1
                if name == "AskUserQuestion":
                    asks += 1
            elif block.get("type") == "text":
                texts.append(block.get("text", ""))
    record = {"exit": int(exit_code), "tool_uses": tools, "ask_user_question_calls": asks, "wall_ms": wall(stamps)}
    if result is None:
        record.update({"finished": False, "subtype": "no result (killed by the timeout, or crashed)",
                       "total_cost_usd": None, "duration_ms": None, "num_turns": None})
    else:
        denials = result.get("permission_denials") or []
        for denial in denials:
            tool_input = denial.get("tool_input", {})
            denied_tools.append(denial.get("tool_name", "?") + ": " + str(tool_input.get("command", tool_input.get("file_path", "")))[:200])
        final = result.get("result") or ""
        record.update({
            "finished": True,
            "subtype": result.get("subtype"),
            "is_error": result.get("is_error"),
            "total_cost_usd": result.get("total_cost_usd"),
            "duration_ms": result.get("duration_ms"),
            "duration_api_ms": result.get("duration_api_ms"),
            "num_turns": result.get("num_turns"),
            "permission_denials": denied_tools,
            "structured_output": result.get("structured_output"),
            "final_text": final[-4000:],
            # A run that ends on a question waits for an answer nobody gives (the skill asked instead of deciding).
            "ended_on_a_question": bool(re.search(r"\?\s*$", final.strip())) or bool(
                re.search(r"(?i)\b(shall I|should I|would you like|do you want|please confirm|let me know)\b", final[-1500:])),
        })
    save(out, record)


PROFILES = (".claude/crud-profile.md", ".claude/review-profile.md")


def replay_profiles(transcript, workdir, out):
    """Applies the profile edits the run asked for and Claude Code refused: a headless run may not write under
    .claude/ (no allow rule lifts that), where a developer would approve the skill's own profile edit. Only the
    two profiles, in the run's order; an edit whose text is no longer there (a retry of one already applied) is
    skipped and recorded."""
    workdir = pathlib.Path(workdir).resolve()
    targets = {str(workdir / name) for name in PROFILES}
    asked, refused = {}, []
    for line in pathlib.Path(transcript).read_text(errors="replace").splitlines():
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            continue
        body = message.get("message") if isinstance(message, dict) else None
        content = body.get("content") if isinstance(body, dict) else None
        if not isinstance(content, list):
            continue
        for block in content:
            if message.get("type") == "assistant" and block.get("type") == "tool_use" \
                    and block.get("name") in ("Edit", "Write") and block.get("input", {}).get("file_path") in targets:
                asked[block["id"]] = (block["name"], block["input"])
            elif message.get("type") == "user" and block.get("type") == "tool_result" \
                    and block.get("tool_use_id") in asked and block.get("is_error") \
                    and "Permission for this tool use was denied" in str(block.get("content")):
                refused.append(asked[block["tool_use_id"]])
    applied = []
    for name, edit in refused:
        path = pathlib.Path(edit["file_path"])
        text = path.read_text() if path.is_file() else ""
        if name == "Write":
            path.write_text(edit.get("content", ""))
        elif edit.get("old_string") and edit["old_string"] in text:
            new = edit.get("new_string", "")
            path.write_text(text.replace(edit["old_string"], new) if edit.get("replace_all")
                            else text.replace(edit["old_string"], new, 1))
        else:
            applied.append({"file": path.name, "tool": name, "applied": False, "why": "its text is not in the file"})
            continue
        applied.append({"file": path.name, "tool": name, "applied": True})
    save(out, {"refused": len(refused), "edits": applied})


def wall(stamps):
    """The run's wall time, from its first to its last message: the result's duration_ms covers only the last turn
    when the run ends after background subagents (house-review's passes)."""
    times = []
    for stamp in stamps:
        try:
            times.append(datetime.datetime.fromisoformat(stamp.replace("Z", "+00:00")))
        except ValueError:
            continue
    return int((max(times) - min(times)).total_seconds() * 1000) if len(times) > 1 else None


def elapsed(run):
    """A run's time: its wall time where the transcript has one, else the result's duration."""
    run = run or {}
    return run.get("wall_ms") or run.get("duration_ms")


def gate2(workdir, baseline, allowed_file, package_path, out):
    """Gate 2: the change since the baseline touches only the case's allowed paths."""
    subprocess.run(["git", "-C", workdir, "add", "-A"], check=True)
    changed = subprocess.run(["git", "-C", workdir, "diff", "--cached", "--name-only", baseline],
                             check=True, capture_output=True, text=True).stdout.split()
    patterns = []
    for line in pathlib.Path(allowed_file).read_text().splitlines():
        line = line.split("#", 1)[0].strip()
        if line:
            patterns.append(line.replace("@PKG@", package_path))
    outside = [f for f in changed if not any(fnmatch.fnmatchcase(f, p) for p in patterns)]
    save(out, {"pass": bool(changed) and not outside, "changed": changed, "outside_allowed": outside,
               "note": "" if changed else "nothing changed since the baseline"})


def findings_of(run):
    output = (run or {}).get("structured_output") or {}
    return output.get("findings") if isinstance(output, dict) else None


def rule_of(finding):
    match = RULE_ID.search(finding.get("rule", ""))
    return match.group(0) if match else finding.get("rule", "")


def counts(findings):
    return {s: sum(1 for f in findings if f.get("severity") == s) for s in SEVERITIES}


def gate3(review_run, out):
    """Gate 3: house-review on the generated change reports nothing at Medium or above; Low ones are recorded."""
    run = load(review_run)
    findings = findings_of(run)
    if findings is None:
        save(out, {"pass": False, "note": "no structured findings (the review did not finish)"})
        return
    blocking = [f for f in findings if f.get("severity") in BLOCKING]
    save(out, {"pass": not blocking, "counts": counts(findings), "blocking": blocking,
               "low": [f for f in findings if f.get("severity") == "Low"]})


def score_review(review_run, expected_file, out):
    """A defect case passes when every expected rule is reported on its file; a control when nothing is Medium or above."""
    run, expected = load(review_run), load(expected_file)
    findings = findings_of(run)
    if findings is None:
        save(out, {"pass": False, "note": "no structured findings (the review did not finish)"})
        return
    matched, missing, used = [], [], set()
    for want in expected.get("expect", []):
        files = want["file"] if isinstance(want["file"], list) else [want["file"]]
        hits = [i for i, f in enumerate(findings)
                if rule_of(f) in want["rules"] and any(f.get("file", "").endswith(name) for name in files)]
        (matched if hits else missing).append(want)
        used.update(hits)
    noise = [f for i, f in enumerate(findings) if i not in used]
    if expected.get("control"):
        blocking = [f for f in findings if f.get("severity") in BLOCKING]
        verdict = not blocking
    else:
        verdict = not missing
    save(out, {"pass": verdict, "counts": counts(findings), "matched": matched, "missing": missing,
               "noise": noise, "control": bool(expected.get("control"))})


def money(value):
    return "-" if value is None else "$%.2f" % value


def minutes(ms):
    return "-" if ms is None else "%.1f min" % (ms / 60000)


def mark(gate):
    if gate is None:
        return "-"
    return "pass" if gate.get("pass") else "FAIL"


def summary(results_dir):
    """summary.md: per case each gate, the cost and time of each model run, the findings."""
    root = pathlib.Path(results_dir).resolve()
    rows, details, total_cost, total_ms = [], [], 0.0, 0
    for case_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        meta = load(case_dir / "case.json") or {}
        gen, rev = load(case_dir / "crud-gen.run.json"), load(case_dir / "house-review.run.json")
        g1, g2, g3, score = (load(case_dir / n) for n in ("gate1.json", "gate2.json", "gate3.json", "score.json"))
        for run in (gen, rev):
            if run and run.get("total_cost_usd") is not None:
                total_cost += run["total_cost_usd"]
                total_ms += elapsed(run) or 0
        verdict = meta.get("verdict", "?")
        found = (g3 or score or {}).get("counts")
        found_text = ", ".join("%d %s" % (n, s) for s, n in found.items() if n) if found else "-"
        if meta.get("kind") == "generation":
            gates = "g1 %s · g2 %s · g3 %s" % (mark(g1), mark(g2), mark(g3))
        else:
            gates = "build %s · expected %s" % ("ok" if (g1 or {}).get("exit") == 0 else "red", mark(score))
        rows.append("| %s | %s | %s | %s / %s | %s / %s | %s | %s |" % (
            case_dir.name, verdict, gates,
            money((gen or {}).get("total_cost_usd")), minutes(elapsed(gen)),
            money((rev or {}).get("total_cost_usd")), minutes(elapsed(rev)),
            meta.get("wall", "-"), found_text))
        details.append(case_details(case_dir.name, meta, gen, rev, g1, g2, g3, score))
    text = ["# Eval run %s" % root.name, "",
            "Model runs: %s, %s of model time (wall time per case includes the harness's builds)." % (
                money(total_cost), minutes(total_ms)), "",
            "| Case | Verdict | Gates | crud-gen cost / time | house-review cost / time | Wall | Findings |",
            "|---|---|---|---|---|---|---|"] + rows + [""] + details
    (root / "summary.md").write_text("\n".join(text) + "\n")
    print("\n".join(text[:len(rows) + 6]))


def case_details(name, meta, gen, rev, g1, g2, g3, score):
    lines = ["## %s" % name, ""]
    for note in meta.get("notes", []):
        lines.append("- %s" % note)
    for label, run in (("crud-gen", gen), ("house-review", rev)):
        if not run:
            continue
        lines.append("- %s run: %s, %s turns, %s, %s; tools %s" % (
            label, run.get("subtype"), run.get("num_turns"), money(run.get("total_cost_usd")),
            minutes(elapsed(run)), json.dumps(run.get("tool_uses", {}))))
        if run.get("permission_denials"):
            lines.append("  - denied: %s" % "; ".join(run["permission_denials"][:10]))
        if run.get("ask_user_question_calls") or run.get("ended_on_a_question"):
            lines.append("  - asked instead of deciding: AskUserQuestion calls %d, ended on a question: %s" % (
                run.get("ask_user_question_calls", 0), run.get("ended_on_a_question")))
    if g1:
        lines.append("- gate 1 (`./mvnw -o -B verify` by the harness): exit %s, %s, %ss" % (
            g1.get("exit"), g1.get("tests", "no test line"), g1.get("seconds")))
    if g2:
        lines.append("- gate 2 (paths): %d changed, outside the allow-list: %s %s" % (
            len(g2.get("changed", [])), ", ".join(g2.get("outside_allowed", [])) or "none", g2.get("note", "")))
    for label, gate in (("gate 3 (house-review, Medium+ blocks)", g3), ("expected findings", score)):
        if not gate:
            continue
        if gate.get("note"):
            lines.append("- %s: %s" % (label, gate["note"]))
            continue
        lines.append("- %s: %s" % (label, mark(gate)))
        for want in gate.get("missing", []):
            lines.append("  - missing: %s on %s" % ("/".join(want["rules"]), want["file"]))
        for want in gate.get("matched", []):
            lines.append("  - found: %s on %s" % ("/".join(want["rules"]), want["file"]))
        for finding in gate.get("blocking", []) + gate.get("low", []) + gate.get("noise", []):
            lines.append("  - %s %s %s:%s %s" % (finding.get("severity"), finding.get("rule"), finding.get("file"),
                                                finding.get("line"), finding.get("summary", "")[:200]))
    lines.append("")
    return "\n".join(lines)


def verdict(case_dir, kind):
    """case.json's verdict: every gate of the case passed."""
    case_dir = pathlib.Path(case_dir)
    meta = load(case_dir / "case.json") or {}
    names = ["gate1.json", "gate2.json", "gate3.json"] if kind == "generation" else ["score.json"]
    gates = [load(case_dir / n) for n in names]
    meta["verdict"] = "pass" if all(g and g.get("pass") for g in gates) else "FAIL"
    save(case_dir / "case.json", meta)
    print(meta["verdict"])


if __name__ == "__main__":
    command, args = sys.argv[1], sys.argv[2:]
    {"run": run_record, "replay": replay_profiles, "gate2": gate2, "gate3": gate3, "score": score_review, "summary": summary,
     "verdict": verdict}[command](*args)
