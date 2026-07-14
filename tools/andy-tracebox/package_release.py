#!/usr/bin/env python3
"""Create checksum-verified Andy tracebox GitHub Release assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import stat
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional


ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "manifest.json"
LAUNCHER = ROOT / "andy-tracebox"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "Andy tracebox packager"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def verified_download(url: str, destination: Path, expected_sha256: str, expected_size: Optional[int] = None) -> None:
    download(url, destination)
    if expected_size is not None and destination.stat().st_size != expected_size:
        raise RuntimeError(
            f"Size mismatch for {destination.name}: expected {expected_size}, got {destination.stat().st_size}."
        )
    actual_sha256 = sha256(destination)
    if actual_sha256 != expected_sha256:
        raise RuntimeError(
            f"Checksum mismatch for {destination.name}: expected {expected_sha256}, got {actual_sha256}."
        )


def load_manifest() -> Dict:
    manifest = json.loads(MANIFEST.read_text())
    if not manifest.get("assets"):
        raise RuntimeError("The Andy tracebox manifest has no assets.")
    release_names = [asset["release_name"] for asset in manifest["assets"]]
    if len(release_names) != len(set(release_names)):
        raise RuntimeError("The Andy tracebox manifest contains duplicate release names.")
    return manifest


def package(output: Path) -> None:
    manifest = load_manifest()
    output.mkdir(parents=True, exist_ok=True)
    checksums: List[str] = []
    for asset in manifest["assets"]:
        destination = output / asset["release_name"]
        verified_download(asset["url"], destination, asset["sha256"], asset["size"])
        destination.chmod(destination.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        checksums.append(f"{asset['sha256']}  {asset['release_name']}")

    license_destination = output / "andy-tracebox-LICENSE.txt"
    upstream = manifest["upstream"]
    verified_download(upstream["license_url"], license_destination, upstream["license_sha256"])
    checksums.append(f"{upstream['license_sha256']}  {license_destination.name}")

    launcher_destination = output / LAUNCHER.name
    shutil.copy2(LAUNCHER, launcher_destination)
    launcher_destination.chmod(launcher_destination.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    checksums.append(f"{sha256(launcher_destination)}  {launcher_destination.name}")
    (output / "andy-tracebox-SHA256SUMS").write_text("\n".join(checksums) + "\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path)
    parser.add_argument("--verify-manifest-only", action="store_true")
    arguments = parser.parse_args()
    load_manifest()
    if arguments.verify_manifest_only:
        return
    if arguments.output is None:
        parser.error("--output is required unless --verify-manifest-only is used")
    package(arguments.output)


if __name__ == "__main__":
    main()
