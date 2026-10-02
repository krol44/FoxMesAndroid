#!/usr/bin/env python3
"""Checks that the release set is complete and writes version.json and SHA256SUMS.

    python3 foxmes/make-release-manifest.py release

version.json is what the in-app updater (FoxMesUpdater) reads from the latest
release: the APK URL and its SHA-256, computed here so they cannot be copied
wrong. The admin only ever types a version number in the dashboard.
"""
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
PROPERTIES_PATH = ROOT / "gradle.properties"
REPOSITORY = "https://github.com/krol44/FoxMesAndroid"


def gradle_property(name: str) -> str:
    for line in PROPERTIES_PATH.read_text(encoding="utf-8").splitlines():
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    raise SystemExit(f"No {name} in {PROPERTIES_PATH.name}")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    release_dir = Path(sys.argv[1]).resolve()
    version = gradle_property("APP_VERSION_NAME")
    version_code = int(gradle_property("APP_VERSION_CODE"))
    apk = f"FoxMes-{version}-android.apk"

    names = {path.name for path in release_dir.iterdir() if path.is_file()}
    if apk not in names:
        raise SystemExit(f"Missing release artifact: {apk}")
    unexpected = names - {apk, "version.json", "SHA256SUMS"}
    if unexpected:
        raise SystemExit(f"Unexpected files in the release set: {sorted(unexpected)}")

    manifest = {
        "version_code": version_code,
        "version": version,
        "android": {
            "url": f"{REPOSITORY}/releases/download/v{version}/{apk}",
            "sha256": sha256(release_dir / apk),
        },
    }
    (release_dir / "version.json").write_text(
        json.dumps(manifest, separators=(",", ":")),
        encoding="utf-8",
    )

    checksums = [
        f"{sha256(release_dir / name)}  {name}" for name in sorted([apk, "version.json"])
    ]
    (release_dir / "SHA256SUMS").write_text(
        "\n".join(checksums) + "\n",
        encoding="ascii",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
