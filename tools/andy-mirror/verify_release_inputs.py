#!/usr/bin/env python3
"""Verify the immutable input set for an Andy desktop release.

The native mirror is a product binary, not a runtime dependency.  A release must
therefore identify the exact pinned scrcpy source it was built from and every
platform executable it bundles.  This deliberately performs no build: build
workers produce the binaries, calculate their digests, verify the appropriate
platform signature, and write the checked-in-workspace staging manifest before
desktop packaging begins.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


REQUIRED_TARGETS = {
    "macos-arm64": "macos-arm64/andy-mirror",
    "macos-x86_64": "macos-x86_64/andy-mirror",
    "windows-x86_64": "windows-x86_64/andy-mirror.exe",
    "linux-x86_64": "linux-x86_64/andy-mirror",
    "linux-arm64": "linux-arm64/andy-mirror",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_json(path: Path, label: str) -> object:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ValueError(f"Missing {label}: {path}") from None
    except json.JSONDecodeError as error:
        raise ValueError(f"Invalid {label}: {path}: {error}") from error


def verify(dist: Path, source_pin_path: Path) -> list[str]:
    source_pin = load_json(source_pin_path, "source pin")
    manifest_path = dist / "manifest.json"
    manifest = load_json(manifest_path, "release manifest")
    if not isinstance(source_pin, dict) or not isinstance(manifest, dict):
        raise ValueError("Source pin and release manifest must be JSON objects")
    if manifest.get("schema") != 1:
        raise ValueError("Release manifest must declare schema 1")

    source = manifest.get("source")
    if not isinstance(source, dict):
        raise ValueError("Release manifest must include a source object")
    for field in ("name", "version", "commit", "license"):
        if source.get(field) != source_pin.get(field):
            raise ValueError(
                f"Release manifest source.{field} does not match {source_pin_path.name}"
            )

    artifacts = manifest.get("artifacts")
    if not isinstance(artifacts, list):
        raise ValueError("Release manifest must include an artifacts array")
    by_target: dict[str, dict[str, object]] = {}
    for item in artifacts:
        if not isinstance(item, dict) or not isinstance(item.get("target"), str):
            raise ValueError("Every release artifact must declare a string target")
        target = item["target"]
        if target in by_target:
            raise ValueError(f"Release manifest declares {target} more than once")
        by_target[target] = item

    errors: list[str] = []
    for target, expected_path in REQUIRED_TARGETS.items():
        artifact = by_target.get(target)
        if artifact is None:
            errors.append(f"missing manifest entry for {target}")
            continue
        if artifact.get("path") != expected_path:
            errors.append(f"{target} must be staged as {expected_path}")
            continue
        path = dist / expected_path
        if not path.is_file() or path.stat().st_size == 0:
            errors.append(f"missing staged executable {expected_path}")
            continue
        actual = sha256(path)
        expected_digest = artifact.get("sha256")
        if not isinstance(expected_digest, str) or expected_digest.lower() != actual:
            errors.append(f"SHA-256 mismatch for {expected_path}")
        signature = artifact.get("signature")
        if not isinstance(signature, dict) or signature.get("verified") is not True:
            errors.append(f"{target} has no verified platform-signature attestation")
        elif not isinstance(signature.get("kind"), str) or not signature["kind"].strip():
            errors.append(f"{target} has no platform signature kind")
        elif not isinstance(signature.get("identity"), str) or not signature["identity"].strip():
            errors.append(f"{target} has no signing identity")

    unexpected = sorted(set(by_target) - set(REQUIRED_TARGETS))
    if unexpected:
        errors.append(f"unsupported release targets in manifest: {', '.join(unexpected)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dist", type=Path, default=Path("native/andy-mirror/dist"))
    parser.add_argument(
        "--source-pin", type=Path, default=Path("native/andy-mirror/SOURCE_PIN.json")
    )
    args = parser.parse_args()
    try:
        errors = verify(args.dist, args.source_pin)
    except ValueError as error:
        print(f"andy-mirror release input verification failed: {error}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(f"andy-mirror release input verification failed: {error}", file=sys.stderr)
        return 1
    print("Verified signed, pinned andy-mirror release inputs for all desktop targets.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
