"""Check commit authors and Co-authored-by trailers for public history."""
from __future__ import annotations

import os
import re
import subprocess
import sys

EXPECTED = "qiyinxi <qiyinxi@foxmail.com>"
NOREPLY = re.compile(r"^(?:[0-9]+\+[A-Za-z0-9-]+(?:\[bot\])?|[A-Za-z0-9-]+)@users\.noreply\.github\.com$")
TRAILER = re.compile(r"^\s*Co-authored-by:\s*(.+?)\s*<([^>]+)>\s*$", re.I | re.M)


def identity_allowed(name: str, email: str) -> bool:
    identity = f"{name} <{email}>"
    if identity == EXPECTED:
        return True
    blocked_ai_emails = {"noreply@" + "anthropic.com", "noreply@" + "openai.com"}
    if email.lower() in blocked_ai_emails:
        return False
    return bool(NOREPLY.fullmatch(email))


def commits_in_range(base: str, head: str):
    raw = subprocess.check_output(
        ["git", "log", "--no-merges", "--format=%H%x00%an%x00%ae%x00%B%x1e", f"{base}..{head}"],
        text=True,
        encoding="utf-8",
    )
    commits = []
    for record in raw.split("\x1e"):
        record = record.strip("\n")
        if not record:
            continue
        sha, name, email, message = record.split("\x00", 3)
        commits.append((sha, name, email, message))
    return commits


def check_commits(commits):
    failures = []
    for sha, name, email, message in commits:
        if not identity_allowed(name, email):
            failures.append(f"{sha}: author {name} <{email}> is not allowed")
        for trailer_name, trailer_email in TRAILER.findall(message):
            if not identity_allowed(trailer_name, trailer_email):
                failures.append(f"{sha}: Co-authored-by {trailer_name} <{trailer_email}> is not allowed")
    return failures


def main() -> int:
    base = os.environ.get("CHECK_BASE_SHA")
    head = os.environ.get("CHECK_HEAD_SHA") or subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if os.environ.get("CHECK_MANUAL") == "1" or not base:
        base = "origin/main"
    try:
        commits = commits_in_range(base, head)
    except subprocess.CalledProcessError as exc:
        print(f"无法读取提交范围 {base}..{head}: {exc}", file=sys.stderr)
        return 2
    failures = check_commits(commits)
    print(f"检查 {len(commits)} 个非合并提交")
    for failure in failures:
        print(f"FAIL: {failure}")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
