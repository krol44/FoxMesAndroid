#!/usr/bin/env python3
"""Builds the body of a GitHub release.

The body is the static preamble in release-notes.md - what the APK is, how to
verify it - followed by the signing certificate and the changelog entries of the
version being released. Those entries live in changelog.txt and nowhere else, and
set-version.sh already refuses to bump without one.

Run without arguments to print what the next release would publish:

    python3 foxmes/make-release-notes.py
"""
import argparse
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
NOTES_PATH = ROOT / "foxmes" / "release-notes.md"
CHANGELOG_PATH = ROOT / "changelog.txt"
PROPERTIES_PATH = ROOT / "gradle.properties"

HEADING = "## Changes"
ENTRY = re.compile(r"^(\d+\.\d+\.\d+)\s+\(([^)]*)\)\s*$")


def current_version() -> str:
    for line in PROPERTIES_PATH.read_text(encoding="utf-8").splitlines():
        if line.startswith("APP_VERSION_NAME="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"No APP_VERSION_NAME in {PROPERTIES_PATH.name}")


def changes_for(version: str) -> list[str]:
    changes = None
    for line in CHANGELOG_PATH.read_text(encoding="utf-8").splitlines():
        entry = ENTRY.match(line)
        if entry:
            if changes is not None:
                break
            if entry.group(1) == version:
                changes = []
        elif changes is not None and line.startswith("- "):
            changes.append(line[2:].strip())
    if changes is None:
        raise SystemExit(
            f"No changelog entry for {version} in {CHANGELOG_PATH.name}"
            " - add one before releasing")
    if not changes:
        raise SystemExit(
            f"Changelog entry for {version} lists no changes -"
            f" add them to {CHANGELOG_PATH.name}")
    return changes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "-o",
        "--output",
        type=Path,
        help="write the notes here instead of stdout")
    parser.add_argument(
        "--signing-cert",
        help="SHA-256 digest of the APK signing certificate")
    args = parser.parse_args()

    preamble = NOTES_PATH.read_text(encoding="utf-8").rstrip("\n")
    changes = changes_for(current_version())
    lines = [preamble]
    if args.signing_cert:
        lines += ["", f"Signing certificate SHA-256: `{args.signing_cert}`"]
    lines += ["", HEADING, ""]
    lines.extend(f"- {change}" for change in changes)
    result = "\n".join(lines) + "\n"

    if args.output:
        args.output.write_text(result, encoding="utf-8")
    else:
        print(result, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
