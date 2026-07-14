from __future__ import annotations

from importlib.machinery import SourceFileLoader
from importlib.util import module_from_spec, spec_from_loader
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("andy-tracebox")
LOADER = SourceFileLoader("andy_tracebox", str(SCRIPT))
SPEC = spec_from_loader("andy_tracebox", LOADER)
assert SPEC is not None and SPEC.loader is not None
andy_tracebox = module_from_spec(SPEC)
SPEC.loader.exec_module(andy_tracebox)


class AndyTraceboxTest(unittest.TestCase):
    def test_release_asset_names(self) -> None:
        self.assertEqual(
            "andy-tracebox-bin-macos-arm64",
            andy_tracebox.release_asset_name("darwin", "aarch64"),
        )
        self.assertEqual(
            "andy-tracebox-bin-linux-x86_64",
            andy_tracebox.release_asset_name("linux", "amd64"),
        )

    def test_rejects_unsupported_platform(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "macOS and Linux"):
            andy_tracebox.release_asset_name("win32", "amd64")

    def test_parses_only_the_requested_valid_checksum(self) -> None:
        digest = "a" * 64
        checksums = f"{'b' * 64}  other\n{digest}  *wanted\n"
        self.assertEqual(digest, andy_tracebox.parse_checksum(checksums, "wanted"))
        with self.assertRaisesRegex(RuntimeError, "missing"):
            andy_tracebox.parse_checksum(checksums, "absent")

    def test_bridge_command_always_adds_exact_production_origin(self) -> None:
        command = andy_tracebox.bridge_command(Path("/tmp/tracebox"), ["--help"])
        self.assertEqual("websocket_bridge", command[1])
        self.assertEqual("--help", command[2])
        self.assertEqual("--http-additional-cors-origins", command[-2])
        self.assertEqual("https://andy.joetr.com", command[-1])


if __name__ == "__main__":
    unittest.main()
